package main

import (
	"bytes"
	"image/png"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

var t0 = time.Date(2026, 11, 1, 15, 0, 0, 0, time.Local)

func TestDescribeIdle(t *testing.T) {
	s := describe(statusSnapshot{}, t0, time.Time{})
	if s.State != stateIdle || s.Title != "" || s.Status != "Running · waiting for uploads" ||
		s.Today != "No uploads yet today" || s.LastErr != "" {
		t.Fatalf("%+v", s)
	}
}

func TestDescribeReceivingWithKnownSize(t *testing.T) {
	s := describe(statusSnapshot{Transfers: []transferInfo{{"IMG_1.HEIC", 450, 1000}}}, t0, time.Time{})
	if s.State != stateReceiving || s.Title != "↑ 45%" || s.Status != "Receiving IMG_1.HEIC · 45%" {
		t.Fatalf("%+v", s)
	}
}

func TestDescribeReceivingChunked(t *testing.T) {
	s := describe(statusSnapshot{Transfers: []transferInfo{{"VID.mp4", 12_300_000, -1}}}, t0, time.Time{})
	if s.Title != "↑ 12.3 MB" || s.Status != "Receiving VID.mp4 · 12.3 MB" {
		t.Fatalf("%+v", s)
	}
}

func TestDescribeSeveralTransfers(t *testing.T) {
	s := describe(statusSnapshot{Transfers: []transferInfo{{"a", 1, 2}, {"b", 1, 2}}}, t0, time.Time{})
	if s.Title != "↑ 2 files" || s.Status != "Receiving 2 files" {
		t.Fatalf("%+v", s)
	}
}

func TestDescribeTodayCounts(t *testing.T) {
	s := describe(statusSnapshot{SavedToday: 148, DupToday: 3, LastUpload: t0.Add(-2 * time.Minute)}, t0, time.Time{})
	if s.Today != "Today: 148 received, 3 already on Mac · last 2 min ago" {
		t.Fatalf("%q", s.Today)
	}
}

func TestDescribeErrorLifecycle(t *testing.T) {
	snap := statusSnapshot{LastErr: "IMG.jpg: no space left on device", LastErrAt: t0.Add(-10 * time.Second)}

	s := describe(snap, t0, time.Time{})
	if s.State != stateError || s.Title != "!" || s.Status != "Error: IMG.jpg: no space left on device" {
		t.Fatalf("fresh error: %+v", s)
	}
	if !strings.HasPrefix(s.LastErr, "Last error (just now): ") {
		t.Fatalf("LastErr = %q", s.LastErr)
	}
	// Opening the menu acknowledges it: icon back to normal, last error still listed.
	if s := describe(snap, t0, t0); s.State != stateIdle || s.LastErr == "" {
		t.Fatalf("after menu opened: %+v", s)
	}
	// A later successful upload clears the error state too.
	snap.LastUpload = t0.Add(-time.Second)
	if s := describe(snap, t0, time.Time{}); s.State != stateIdle {
		t.Fatalf("after success: %+v", s)
	}
	// And it times out on its own.
	snap.LastUpload = time.Time{}
	if s := describe(snap, t0.Add(errorShownFor), time.Time{}); s.State != stateIdle {
		t.Fatalf("after timeout: %+v", s)
	}
}

func TestFormatHelpers(t *testing.T) {
	for n, want := range map[int64]string{999: "999 B", 1500: "1 KB", 2_500_000: "2.5 MB", 3_200_000_000: "3.2 GB"} {
		if got := formatBytes(n); got != want {
			t.Errorf("formatBytes(%d) = %q, want %q", n, got, want)
		}
	}
	if got := ago(t0, t0.Add(-3*time.Hour)); got != "3 h ago" {
		t.Errorf("ago = %q", got)
	}
}

func TestActivityTracksUploads(t *testing.T) {
	dir := t.TempDir()
	st := newStore(dir)
	mux := newStoreMux(st)

	decodeSaved(t, uploadTo(t, mux, upFile{"a.jpg", "aaa"}))
	decodeSaved(t, uploadTo(t, mux, upFile{"b.jpg", "aaa"})) // duplicate
	upload := uploadTo(t, mux, upFile{".bad", "x"})          // rejected name
	if upload.Code != 400 {
		t.Fatalf("status %d", upload.Code)
	}

	s := st.act.snapshot()
	if s.SavedToday != 1 || s.DupToday != 1 || s.LastName != "a.jpg" || len(s.Transfers) != 0 {
		t.Fatalf("%+v", s)
	}
	if !strings.Contains(s.LastErr, ".bad") || s.LastErrAt.IsZero() {
		t.Fatalf("LastErr = %q", s.LastErr)
	}
}

func TestActivityProgressDuringTransfer(t *testing.T) {
	a := newActivity()
	tr := a.begin(1000)
	tr.setName("IMG.jpg")
	io.Copy(io.Discard, tr.reader(strings.NewReader(strings.Repeat("x", 400))))
	s := a.snapshot()
	if len(s.Transfers) != 1 || s.Transfers[0] != (transferInfo{"IMG.jpg", 400, 1000}) {
		t.Fatalf("%+v", s.Transfers)
	}
	a.end(tr)
	if len(a.snapshot().Transfers) != 0 {
		t.Fatal("transfer not removed")
	}
}

func TestActivityDailyCountersReset(t *testing.T) {
	a := newActivity()
	now := t0
	a.now = func() time.Time { return now }
	a.recordSaved(savedFile{Name: "a.jpg"})
	now = now.Add(24 * time.Hour)
	if s := a.snapshot(); s.SavedToday != 0 {
		t.Fatalf("counters not reset at midnight: %+v", s)
	}
}

func TestSetDirRedirectsNewUploads(t *testing.T) {
	first, second := t.TempDir(), t.TempDir()
	st := newStore(first)
	mux := newStoreMux(st)
	decodeSaved(t, uploadTo(t, mux, upFile{"one.jpg", "1"}))
	st.SetDir(second)
	decodeSaved(t, uploadTo(t, mux, upFile{"two.jpg", "2"}))
	if _, err := os.Stat(filepath.Join(first, "one.jpg")); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(second, "two.jpg")); err != nil {
		t.Fatal(err)
	}
	// Duplicate detection follows the folder too: "1" is not in the new folder.
	if got := decodeSaved(t, uploadTo(t, mux, upFile{"again.jpg", "1"})); got[0].Duplicate {
		t.Fatal("duplicate check looked in the old folder")
	}
}

func TestTrayIcon(t *testing.T) {
	img, err := png.Decode(bytes.NewReader(trayIcon()))
	if err != nil {
		t.Fatal(err)
	}
	if b := img.Bounds(); b.Dx() != 32 || b.Dy() != 32 {
		t.Fatalf("bounds %v", b)
	}
	opaque, other := 0, 0
	for y := 0; y < 32; y++ {
		for x := 0; x < 32; x++ {
			r, g, bl, a := img.At(x, y).RGBA()
			switch {
			case a == 0:
			case a == 0xFFFF && r == 0 && g == 0 && bl == 0:
				opaque++
			default:
				other++
			}
		}
	}
	// Template images must be black + alpha only, and actually draw something.
	if other != 0 || opaque < 100 {
		t.Fatalf("opaque=%d other=%d", opaque, other)
	}
}

func TestHashCacheDroppedOnFolderChange(t *testing.T) {
	a, b := t.TempDir(), t.TempDir()
	st := newStore(a)
	if _, err := st.save("one.jpg", "", strings.NewReader("first"), time.Time{}); err != nil {
		t.Fatal(err)
	}
	if len(st.cache) != 1 {
		t.Fatalf("cache has %d entries, want 1", len(st.cache))
	}
	st.SetDir(b)
	if _, err := st.save("two.jpg", "", strings.NewReader("second"), time.Time{}); err != nil {
		t.Fatal(err)
	}
	if len(st.cache) != 1 {
		t.Fatalf("cache has %d entries after folder change, want 1", len(st.cache))
	}
	for p := range st.cache {
		if filepath.Dir(p) != b {
			t.Errorf("cache entry %s is not in the new folder %s", p, b)
		}
	}
}
