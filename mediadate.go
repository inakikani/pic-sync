package main

import (
	"bytes"
	"encoding/binary"
	"io"
	"os"
	"time"
)

// When a photo or video was taken, read from the file itself, so the saved
// copy can get that date as its modification time. Supported:
//   - JPEG: EXIF DateTimeOriginal (+ OffsetTimeOriginal when present)
//   - HEIC/HEIF/AVIF: the EXIF item, same tags
//   - MP4/MOV (and other ISO base media files): the movie header creation time
//
// Everything is bounded: only headers and small metadata blocks are read.

const maxMetaRead = 1 << 20 // never read more than 1 MiB of metadata

// takenTimeOfFile returns the capture time recorded in the file at path.
func takenTimeOfFile(path string) (time.Time, bool) {
	f, err := os.Open(path)
	if err != nil {
		return time.Time{}, false
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return time.Time{}, false
	}
	return takenTime(f, info.Size())
}

func takenTime(r io.ReaderAt, size int64) (time.Time, bool) {
	var head [12]byte
	if n, _ := r.ReadAt(head[:], 0); n < 12 {
		return time.Time{}, false
	}
	switch {
	case head[0] == 0xFF && head[1] == 0xD8:
		if tiff := jpegExif(r, size); tiff != nil {
			return exifTime(tiff)
		}
	case string(head[4:8]) == "ftyp":
		if tiff := heifExif(r, size); tiff != nil {
			if t, ok := exifTime(tiff); ok {
				return t, true
			}
		}
		return movieTime(r, size)
	}
	return time.Time{}, false
}

// ---- JPEG ----

// jpegExif returns the TIFF block of a JPEG's EXIF APP1 segment, or nil.
func jpegExif(r io.ReaderAt, size int64) []byte {
	off := int64(2)
	for off+4 <= size && off < maxMetaRead {
		var h [4]byte
		if _, err := r.ReadAt(h[:], off); err != nil {
			return nil
		}
		if h[0] != 0xFF {
			return nil
		}
		switch m := h[1]; {
		case m == 0xFF: // fill byte
			off++
			continue
		case m == 0x01 || (m >= 0xD0 && m <= 0xD8): // markers without a length
			off += 2
			continue
		case m == 0xDA || m == 0xD9: // start of scan / end: no more metadata
			return nil
		}
		l := int64(binary.BigEndian.Uint16(h[2:]))
		if l < 2 {
			return nil
		}
		if h[1] == 0xE1 && l > 8 {
			buf := make([]byte, l-2)
			if _, err := r.ReadAt(buf, off+4); err != nil {
				return nil
			}
			if bytes.HasPrefix(buf, []byte("Exif\x00\x00")) {
				return buf[6:]
			}
		}
		off += 2 + l
	}
	return nil
}

// ---- EXIF (TIFF structure) ----

type ifdEntry struct {
	typ   uint16
	count uint32
	raw   []byte // the 4-byte value/offset field
}

func readIFD(tiff []byte, bo binary.ByteOrder, off uint32) map[uint16]ifdEntry {
	if uint64(off)+2 > uint64(len(tiff)) {
		return nil
	}
	n := int(bo.Uint16(tiff[off:]))
	tags := make(map[uint16]ifdEntry, n)
	p := int(off) + 2
	for i := 0; i < n && p+12 <= len(tiff); i, p = i+1, p+12 {
		tags[bo.Uint16(tiff[p:])] = ifdEntry{bo.Uint16(tiff[p+2:]), bo.Uint32(tiff[p+4:]), tiff[p+8 : p+12]}
	}
	return tags
}

// ascii returns an ASCII tag's value without the trailing NUL.
func (e ifdEntry) ascii(tiff []byte, bo binary.ByteOrder) string {
	if e.typ != 2 || e.count == 0 {
		return ""
	}
	var b []byte
	if e.count <= 4 {
		b = e.raw[:e.count]
	} else {
		off := uint64(bo.Uint32(e.raw))
		if off+uint64(e.count) > uint64(len(tiff)) {
			return ""
		}
		b = tiff[off : off+uint64(e.count)]
	}
	return string(bytes.TrimRight(b, "\x00 "))
}

// exifTime reads DateTimeOriginal (falling back to DateTimeDigitized and then
// the IFD0 DateTime) with its UTC offset tag if present. Without an offset the
// time is taken as local time, which matches the camera when the phone and the
// Mac are in the same time zone.
func exifTime(tiff []byte) (time.Time, bool) {
	if len(tiff) < 8 {
		return time.Time{}, false
	}
	var bo binary.ByteOrder
	switch string(tiff[:2]) {
	case "II":
		bo = binary.LittleEndian
	case "MM":
		bo = binary.BigEndian
	default:
		return time.Time{}, false
	}
	ifd0 := readIFD(tiff, bo, bo.Uint32(tiff[4:]))
	var exif map[uint16]ifdEntry
	if p, ok := ifd0[0x8769]; ok {
		exif = readIFD(tiff, bo, bo.Uint32(p.raw))
	}
	for _, c := range []struct {
		tags         map[uint16]ifdEntry
		date, offset uint16
	}{
		{exif, 0x9003, 0x9011}, // DateTimeOriginal, OffsetTimeOriginal
		{exif, 0x9004, 0x9012}, // DateTimeDigitized, OffsetTimeDigitized
		{ifd0, 0x0132, 0x9010}, // DateTime, OffsetTime (which lives in the EXIF IFD)
	} {
		e, ok := c.tags[c.date]
		if !ok {
			continue
		}
		loc := time.Local
		if oe, ok := exif[c.offset]; ok {
			if z, err := time.Parse("-07:00", oe.ascii(tiff, bo)); err == nil {
				_, secs := z.Zone()
				loc = time.FixedZone("", secs)
			}
		}
		if t, err := time.ParseInLocation("2006:01:02 15:04:05", e.ascii(tiff, bo), loc); err == nil && plausible(t) {
			return t, true
		}
	}
	return time.Time{}, false
}

// plausible rejects unset (zero/epoch) and nonsense dates.
func plausible(t time.Time) bool {
	return t.Year() >= 1980 && t.Before(time.Now().Add(48*time.Hour))
}

// ---- ISO base media file format (MP4, MOV, HEIC) ----

type box struct {
	typ       string
	data, end int64 // payload start and end offsets in the file
}

// eachBox calls fn for each box in [start, end) until fn returns false.
func eachBox(r io.ReaderAt, start, end int64, fn func(box) bool) {
	for off := start; off+8 <= end; {
		var h [16]byte
		if _, err := r.ReadAt(h[:8], off); err != nil {
			return
		}
		size, hdr := int64(binary.BigEndian.Uint32(h[:4])), int64(8)
		switch size {
		case 1: // 64-bit size follows
			if _, err := r.ReadAt(h[8:16], off+8); err != nil {
				return
			}
			size, hdr = int64(binary.BigEndian.Uint64(h[8:16])), 16
		case 0: // box extends to the end
			size = end - off
		}
		if size < hdr || off+size > end {
			return
		}
		if !fn(box{string(h[4:8]), off + hdr, off + size}) {
			return
		}
		off += size
	}
}

func findBox(r io.ReaderAt, start, end int64, typ string) (box, bool) {
	var found box
	ok := false
	eachBox(r, start, end, func(b box) bool {
		if b.typ == typ {
			found, ok = b, true
			return false
		}
		return true
	})
	return found, ok
}

func readBox(r io.ReaderAt, b box) []byte {
	n := b.end - b.data
	if n <= 0 || n > maxMetaRead {
		return nil
	}
	buf := make([]byte, n)
	if _, err := r.ReadAt(buf, b.data); err != nil {
		return nil
	}
	return buf
}

// movieTime reads moov/mvhd creation_time (seconds since 1904, UTC). The moov
// box is often at the end of Android videos; boxes are skipped, not read.
func movieTime(r io.ReaderAt, size int64) (time.Time, bool) {
	moov, ok := findBox(r, 0, size, "moov")
	if !ok {
		return time.Time{}, false
	}
	mvhd, ok := findBox(r, moov.data, moov.end, "mvhd")
	if !ok {
		return time.Time{}, false
	}
	var h [12]byte
	if _, err := r.ReadAt(h[:], mvhd.data); err != nil {
		return time.Time{}, false
	}
	var secs uint64
	if h[0] == 1 { // version 1: 64-bit times
		secs = binary.BigEndian.Uint64(h[4:12])
	} else {
		secs = uint64(binary.BigEndian.Uint32(h[4:8]))
	}
	const from1904 = 2082844800 // seconds between 1904-01-01 and 1970-01-01
	if secs <= from1904 {
		return time.Time{}, false
	}
	t := time.Unix(int64(secs-from1904), 0)
	return t, plausible(t)
}

// cursor reads big-endian fields from a box payload; reads past the end yield 0
// and set bad.
type cursor struct {
	b   []byte
	p   int
	bad bool
}

func (c *cursor) uint(n int) uint64 {
	if n == 0 {
		return 0
	}
	if c.p+n > len(c.b) {
		c.bad = true
		c.p = len(c.b)
		return 0
	}
	var v uint64
	for _, x := range c.b[c.p : c.p+n] {
		v = v<<8 | uint64(x)
	}
	c.p += n
	return v
}

func (c *cursor) skip(n int) {
	c.p += n
	if c.p > len(c.b) {
		c.bad, c.p = true, len(c.b)
	}
}

// heifExif returns the TIFF block of a HEIF file's "Exif" item, or nil.
func heifExif(r io.ReaderAt, size int64) []byte {
	meta, ok := findBox(r, 0, size, "meta")
	if !ok {
		return nil
	}
	start := meta.data + 4 // meta is a full box: version + flags
	iinf, ok1 := findBox(r, start, meta.end, "iinf")
	iloc, ok2 := findBox(r, start, meta.end, "iloc")
	if !ok1 || !ok2 {
		return nil
	}
	id, ok := exifItemID(r, iinf)
	if !ok {
		return nil
	}
	off, length, ok := itemExtent(readBox(r, iloc), id)
	if !ok || length < 8 || length > maxMetaRead || off+length > uint64(size) {
		return nil
	}
	data := make([]byte, length)
	if _, err := r.ReadAt(data, int64(off)); err != nil {
		return nil
	}
	// The item starts with a 4-byte offset to the TIFF header (usually past "Exif\0\0").
	skip := uint64(binary.BigEndian.Uint32(data))
	if 4+skip >= length {
		return nil
	}
	tiff := data[4+skip:]
	return bytes.TrimPrefix(tiff, []byte("Exif\x00\x00"))
}

func exifItemID(r io.ReaderAt, iinf box) (uint32, bool) {
	var h [1]byte
	if _, err := r.ReadAt(h[:], iinf.data); err != nil {
		return 0, false
	}
	start := iinf.data + 4 + 2 // version/flags + entry_count
	if h[0] != 0 {
		start += 2 // 32-bit entry_count
	}
	var id uint32
	found := false
	eachBox(r, start, iinf.end, func(b box) bool {
		if b.typ != "infe" {
			return true
		}
		c := cursor{b: readBox(r, b)}
		v := c.uint(1)
		c.skip(3)
		if v < 2 {
			return true
		}
		itemID := c.uint(2)
		if v >= 3 {
			itemID = itemID<<16 | c.uint(2)
		}
		c.skip(2) // protection index
		typ := c.uint(4)
		if !c.bad && typ == 0x45786966 { // "Exif"
			id, found = uint32(itemID), true
			return false
		}
		return true
	})
	return id, found
}

// itemExtent returns the file offset and length of the first extent of item id.
func itemExtent(iloc []byte, id uint32) (off, length uint64, ok bool) {
	c := cursor{b: iloc}
	v := c.uint(1)
	c.skip(3)
	sizes := c.uint(1)
	offSize, lenSize := int(sizes>>4), int(sizes&0xF)
	sizes = c.uint(1)
	baseSize, idxSize := int(sizes>>4), int(sizes&0xF)
	if v == 0 {
		idxSize = 0
	}
	idSize := 2
	if v == 2 {
		idSize = 4
	}
	count := c.uint(idSize)
	for i := uint64(0); i < count && !c.bad; i++ {
		itemID := c.uint(idSize)
		method := uint64(0)
		if v == 1 || v == 2 {
			method = c.uint(2) & 0xF
		}
		c.skip(2) // data_reference_index
		base := c.uint(baseSize)
		extents := c.uint(2)
		for e := uint64(0); e < extents && !c.bad; e++ {
			c.skip(idxSize)
			eo, el := c.uint(offSize), c.uint(lenSize)
			if uint32(itemID) == id && e == 0 && !c.bad {
				if method != 0 { // only plain file offsets are supported
					return 0, 0, false
				}
				return base + eo, el, true
			}
		}
	}
	return 0, 0, false
}
