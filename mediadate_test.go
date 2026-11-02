package main

import (
	"bytes"
	"encoding/binary"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"
)

// tiffBlock builds a minimal EXIF TIFF block: IFD0 with DateTime and a pointer
// to an EXIF IFD holding DateTimeOriginal and (optionally) OffsetTimeOriginal.
func tiffBlock(bo binary.ByteOrder, original, offset string) []byte {
	var b bytes.Buffer
	if bo == binary.LittleEndian {
		b.WriteString("II")
	} else {
		b.WriteString("MM")
	}
	w := func(v any) { binary.Write(&b, bo, v) }
	w(uint16(42))
	w(uint32(8)) // IFD0 at 8

	// IFD0: 2 entries (DateTime, ExifIFD pointer) = 2 + 2*12 + 4 = 30 bytes -> ends at 38.
	// Data area: DateTime string (20 bytes) at 38, EXIF IFD at 58.
	exifEntries := 1
	if offset != "" {
		exifEntries = 2
	}
	exifIFD := uint32(58)
	exifData := exifIFD + 2 + uint32(exifEntries)*12 + 4
	w(uint16(2))
	w(uint16(0x0132))
	w(uint16(2))
	w(uint32(20))
	w(uint32(38))
	w(uint16(0x8769))
	w(uint16(4))
	w(uint32(1))
	w(exifIFD)
	w(uint32(0))
	b.WriteString("2001:01:01 00:00:00\x00") // IFD0 DateTime (should lose to the original)

	w(uint16(exifEntries))
	w(uint16(0x9003))
	w(uint16(2))
	w(uint32(20))
	w(exifData)
	if offset != "" {
		w(uint16(0x9011))
		w(uint16(2))
		w(uint32(7))
		w(exifData + 20)
	}
	w(uint32(0))
	b.WriteString(original + "\x00")
	if offset != "" {
		b.WriteString(offset + "\x00")
	}
	return b.Bytes()
}

func jpegWithExif(tiff []byte) []byte {
	var b bytes.Buffer
	b.Write([]byte{0xFF, 0xD8})
	b.Write([]byte{0xFF, 0xE0, 0x00, 0x10}) // APP0 (JFIF), 14 bytes of payload
	b.WriteString("JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00")
	app1 := append([]byte("Exif\x00\x00"), tiff...)
	b.Write([]byte{0xFF, 0xE1})
	binary.Write(&b, binary.BigEndian, uint16(len(app1)+2))
	b.Write(app1)
	b.Write([]byte{0xFF, 0xDA, 0x00, 0x02}) // start of scan
	b.WriteString("image data")
	b.Write([]byte{0xFF, 0xD9})
	return b.Bytes()
}

func mkbox(typ string, payload ...[]byte) []byte {
	body := bytes.Join(payload, nil)
	out := binary.BigEndian.AppendUint32(nil, uint32(8+len(body)))
	return append(append(out, typ...), body...)
}

func u16(v uint16) []byte { return binary.BigEndian.AppendUint16(nil, v) }
func u32(v uint32) []byte { return binary.BigEndian.AppendUint32(nil, v) }

// heicWithExif builds ftyp + meta(iinf, iloc) + mdat, with the Exif item in mdat.
func heicWithExif(tiff []byte) []byte {
	ftyp := mkbox("ftyp", []byte("heic"), u32(0), []byte("mif1heic"))
	item := append(append(u32(6), "Exif\x00\x00"...), tiff...)
	infe := func(id uint16, typ string) []byte {
		return mkbox("infe", []byte{2, 0, 0, 0}, u16(id), u16(0), []byte(typ), []byte{0})
	}
	iinf := mkbox("iinf", []byte{0, 0, 0, 0}, u16(2), infe(1, "hvc1"), infe(2, "Exif"))
	ilocFor := func(exifOff uint32) []byte {
		// version 1, offset_size 4, length_size 4, base_offset_size 0, index_size 0
		return mkbox("iloc", []byte{1, 0, 0, 0}, []byte{0x44, 0x00}, u16(2),
			u16(1), u16(0), u16(0), u16(1), u32(0), u32(4), // item 1: dummy image extent
			u16(2), u16(0), u16(0), u16(1), u32(exifOff), u32(uint32(len(item)))) // item 2: Exif
	}
	meta := func(off uint32) []byte {
		return mkbox("meta", []byte{0, 0, 0, 0}, mkbox("hdlr", make([]byte, 24)), iinf, ilocFor(off))
	}
	// mdat payload starts after ftyp + meta + mdat header; meta's size doesn't depend on the offset.
	off := uint32(len(ftyp) + len(meta(0)) + 8)
	return bytes.Join([][]byte{ftyp, meta(off), mkbox("mdat", item)}, nil)
}

func mp4WithTime(t time.Time, moovLast bool) []byte {
	secs := uint32(t.Unix() + 2082844800)
	mvhd := mkbox("mvhd", []byte{0, 0, 0, 0}, u32(secs), u32(secs), make([]byte, 88))
	moov := mkbox("moov", mvhd)
	ftyp := mkbox("ftyp", []byte("isom"), u32(0), []byte("isommp41"))
	mdat := mkbox("mdat", bytes.Repeat([]byte{7}, 1000))
	if moovLast {
		return bytes.Join([][]byte{ftyp, mdat, moov}, nil)
	}
	return bytes.Join([][]byte{ftyp, moov, mdat}, nil)
}

func takenOf(t *testing.T, data []byte) (time.Time, bool) {
	t.Helper()
	return takenTime(bytes.NewReader(data), int64(len(data)))
}

func TestTakenTimeJPEG(t *testing.T) {
	want := time.Date(2024, 7, 14, 18, 30, 5, 0, time.FixedZone("", 2*3600))
	for _, bo := range []binary.ByteOrder{binary.BigEndian, binary.LittleEndian} {
		got, ok := takenOf(t, jpegWithExif(tiffBlock(bo, "2024:07:14 18:30:05", "+02:00")))
		if !ok || !got.Equal(want) {
			t.Errorf("%v: got %v %v, want %v", bo, got, ok, want)
		}
	}
	// Without an offset tag the time is local.
	got, ok := takenOf(t, jpegWithExif(tiffBlock(binary.BigEndian, "2024:07:14 18:30:05", "")))
	if want := time.Date(2024, 7, 14, 18, 30, 5, 0, time.Local); !ok || !got.Equal(want) {
		t.Errorf("no offset: got %v %v, want %v", got, ok, want)
	}
	// Unset DateTimeOriginal: falls back to IFD0 DateTime instead of year 0.
	got, ok = takenOf(t, jpegWithExif(tiffBlock(binary.BigEndian, "0000:00:00 00:00:00", "")))
	if want := time.Date(2001, 1, 1, 0, 0, 0, 0, time.Local); !ok || !got.Equal(want) {
		t.Errorf("unset original: got %v %v, want IFD0 date %v", got, ok, want)
	}
}

func TestTakenTimeHEIC(t *testing.T) {
	want := time.Date(2025, 3, 2, 9, 15, 0, 0, time.FixedZone("", -5*3600))
	got, ok := takenOf(t, heicWithExif(tiffBlock(binary.BigEndian, "2025:03:02 09:15:00", "-05:00")))
	if !ok || !got.Equal(want) {
		t.Errorf("got %v %v, want %v", got, ok, want)
	}
}

func TestTakenTimeMovie(t *testing.T) {
	want := time.Date(2023, 12, 24, 20, 0, 0, 0, time.UTC)
	for _, last := range []bool{false, true} {
		got, ok := takenOf(t, mp4WithTime(want, last))
		if !ok || !got.Equal(want) {
			t.Errorf("moovLast=%v: got %v %v, want %v", last, got, ok, want)
		}
	}
}

func TestTakenTimeUnknownOrBroken(t *testing.T) {
	jpeg := jpegWithExif(tiffBlock(binary.BigEndian, "2024:07:14 18:30:05", "+02:00"))
	for name, data := range map[string][]byte{
		"png":       []byte("\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR"),
		"text":      []byte("hello, world"),
		"empty":     nil,
		"truncated": jpeg[:30],
		"bad box":   append(mkbox("ftyp", []byte("isom")), 0xFF, 0xFF, 0xFF, 0xFF, 'm', 'o', 'o', 'v'),
	} {
		if got, ok := takenOf(t, data); ok {
			t.Errorf("%s: got %v", name, got)
		}
	}
}

// uploadWithMtime posts one file, optionally preceded by an "mtime" field.
func uploadWithMtime(t *testing.T, srv *httptest.Server, name string, data []byte, mtime int64) {
	t.Helper()
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	if mtime != 0 {
		mw.WriteField("mtime", strconv.FormatInt(mtime, 10))
	}
	fw, _ := mw.CreateFormFile("file", name)
	fw.Write(data)
	mw.Close()
	resp, err := http.Post(srv.URL+"/upload", mw.FormDataContentType(), &body)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("upload %s: status %d", name, resp.StatusCode)
	}
}

func modTime(t *testing.T, path string) time.Time {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	return info.ModTime()
}

func TestUploadKeepsOriginalDate(t *testing.T) {
	dir := t.TempDir()
	srv := httptest.NewServer(newMux(dir))
	defer srv.Close()

	exifDate := time.Date(2024, 7, 14, 18, 30, 5, 0, time.FixedZone("", 2*3600))
	client := time.Date(2022, 1, 2, 3, 4, 5, 0, time.UTC)

	// The date in the file wins over the client's (iOS sends the pick time).
	uploadWithMtime(t, srv, "a.jpg", jpegWithExif(tiffBlock(binary.BigEndian, "2024:07:14 18:30:05", "+02:00")), client.UnixMilli())
	if got := modTime(t, filepath.Join(dir, "a.jpg")); !got.Equal(exifDate) {
		t.Errorf("a.jpg: mtime %v, want EXIF date %v", got, exifDate)
	}
	// No date in the file: the client's date is used.
	uploadWithMtime(t, srv, "b.png", []byte("\x89PNG not really"), client.UnixMilli())
	if got := modTime(t, filepath.Join(dir, "b.png")); !got.Equal(client) {
		t.Errorf("b.png: mtime %v, want client date %v", got, client)
	}
	// Neither: the upload time is kept. The client date applies to one file only.
	before := time.Now().Add(-time.Minute)
	uploadWithMtime(t, srv, "c.txt", []byte("plain"), 0)
	if got := modTime(t, filepath.Join(dir, "c.txt")); got.Before(before) {
		t.Errorf("c.txt: mtime %v, want about now", got)
	}
	// A nonsense client date is ignored.
	uploadWithMtime(t, srv, "d.txt", []byte("plain 2"), time.Now().Add(365*24*time.Hour).UnixMilli())
	if got := modTime(t, filepath.Join(dir, "d.txt")); got.After(time.Now().Add(time.Minute)) {
		t.Errorf("d.txt: future mtime %v was applied", got)
	}
}

func TestFixDates(t *testing.T) {
	dir := t.TempDir()
	write := func(name string, data []byte) string {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, data, 0o644); err != nil {
			t.Fatal(err)
		}
		return p
	}
	exif := time.Date(2024, 7, 14, 18, 30, 5, 0, time.FixedZone("", 2*3600))
	jpg := write("a.jpg", jpegWithExif(tiffBlock(binary.BigEndian, "2024:07:14 18:30:05", "+02:00")))
	movTime := time.Date(2023, 12, 24, 20, 0, 0, 0, time.UTC)
	mov := write("b.mov", mp4WithTime(movTime, true))
	png := write("c.png", []byte("\x89PNG no date"))
	hidden := write(".upload-1.part", jpegWithExif(tiffBlock(binary.BigEndian, "2024:07:14 18:30:05", "")))
	before := map[string]time.Time{}
	for _, p := range []string{jpg, mov, png, hidden} {
		before[p] = modTime(t, p)
	}

	var out bytes.Buffer
	if err := fixDates(dir, true, &out); err != nil {
		t.Fatal(err)
	}
	for p, m := range before {
		if !modTime(t, p).Equal(m) {
			t.Errorf("dry run changed %s", p)
		}
	}
	if !bytes.Contains(out.Bytes(), []byte("would update 2, already correct 0, no date in file 1")) {
		t.Errorf("dry run output:\n%s", out.String())
	}

	out.Reset()
	if err := fixDates(dir, false, &out); err != nil {
		t.Fatal(err)
	}
	if got := modTime(t, jpg); !got.Equal(exif) {
		t.Errorf("a.jpg: %v, want %v", got, exif)
	}
	if got := modTime(t, mov); !got.Equal(movTime) {
		t.Errorf("b.mov: %v, want %v", got, movTime)
	}
	if !modTime(t, png).Equal(before[png]) || !modTime(t, hidden).Equal(before[hidden]) {
		t.Error("files without a date / hidden files were changed")
	}
	if !bytes.Contains(out.Bytes(), []byte("updated 2, already correct 0, no date in file 1")) {
		t.Errorf("output:\n%s", out.String())
	}

	out.Reset()
	fixDates(dir, false, &out)
	if !bytes.Contains(out.Bytes(), []byte("updated 0, already correct 2, no date in file 1")) {
		t.Errorf("second run output:\n%s", out.String())
	}
}
