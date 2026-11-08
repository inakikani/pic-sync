package main

import (
	"slices"
	"testing"
	"time"
)

func TestCaffeinateArgs(t *testing.T) {
	got := caffeinateArgs(1234)
	want := []string{"-i", "-w", "1234"}
	if !slices.Equal(got, want) {
		t.Fatalf("caffeinateArgs = %q, want %q", got, want)
	}
}

// fakeAwake replaces caffeinate and the timer: countdowns only fire when the test calls fire.
type fakeAwake struct {
	*awake
	holds, releases int
	pending         []func() // started countdowns, in order; nil once cancelled
}

func newFakeAwake() *fakeAwake {
	f := &fakeAwake{awake: newAwake(5 * time.Minute)}
	f.hold = func() func() {
		f.holds++
		return func() { f.releases++ }
	}
	f.after = func(d time.Duration, fn func()) func() {
		if d != 5*time.Minute {
			panic("unexpected idle time")
		}
		i := len(f.pending)
		f.pending = append(f.pending, fn)
		return func() { f.pending[i] = nil }
	}
	return f
}

// fire runs countdown i as if its 5 minutes had passed.
func (f *fakeAwake) fire(t *testing.T, i int) {
	t.Helper()
	if f.pending[i] == nil {
		t.Fatalf("countdown %d was cancelled", i)
	}
	f.pending[i]()
}

func (f *fakeAwake) check(t *testing.T, holds, releases int) {
	t.Helper()
	if f.holds != holds || f.releases != releases {
		t.Fatalf("holds/releases = %d/%d, want %d/%d", f.holds, f.releases, holds, releases)
	}
}

func TestAwakeHeldWhileUploading(t *testing.T) {
	f := newFakeAwake()
	f.check(t, 0, 0) // nothing before the first upload
	f.begin()
	f.begin() // two at once: one hold
	f.end()
	if len(f.pending) != 0 {
		t.Fatal("countdown started while an upload is still running")
	}
	f.end()
	f.check(t, 1, 0) // idle, but not for 5 minutes yet
	f.fire(t, 0)
	f.check(t, 1, 1)

	f.begin() // the next upload holds again
	f.check(t, 2, 1)
}

func TestAwakeNewUploadRestartsCountdown(t *testing.T) {
	f := newFakeAwake()
	f.begin()
	f.end()
	f.begin() // the next file of a batch, within 5 minutes
	if f.pending[0] != nil {
		t.Fatal("first countdown not cancelled")
	}
	f.end()
	f.check(t, 1, 0) // kept, not restarted
	f.fire(t, 1)
	f.check(t, 1, 1)
}

func TestAwakeLateTimerIgnored(t *testing.T) {
	f := newFakeAwake()
	f.begin()
	f.end()
	stale := f.pending[0]
	f.begin()
	f.end()
	stale() // fired just as the second upload began: must not release early
	f.check(t, 1, 0)
	f.begin()
	stale() // or during an upload
	f.check(t, 1, 0)
}

func TestAwakeCloseReleasesNow(t *testing.T) {
	f := newFakeAwake()
	f.begin()
	f.end()
	f.close()
	f.check(t, 1, 1)
	if f.pending[0] != nil {
		t.Fatal("countdown left running after close")
	}
	f.close() // twice is fine
	f.check(t, 1, 1)
}

func TestAwakeNilIsNoop(t *testing.T) {
	var a *awake
	a.begin()
	a.end()
	a.close()
}

func TestUploadKeepsMacAwake(t *testing.T) {
	st := newStore(t.TempDir())
	f := newFakeAwake()
	st.awake = f.awake
	held := false
	f.hold = func() func() { // check the hold happens while the upload is in progress
		f.holds++
		held = len(st.act.inflight) == 1
		return func() { f.releases++ }
	}
	if rec := uploadTo(t, newStoreMux(st), upFile{"IMG_001.jpg", "hello"}); rec.Code != 200 {
		t.Fatalf("status %d: %s", rec.Code, rec.Body)
	}
	if !held {
		t.Fatal("sleep wasn't prevented during the upload")
	}
	f.check(t, 1, 0)
	if len(f.pending) != 1 || f.pending[0] == nil {
		t.Fatal("no idle countdown after the upload")
	}
}
