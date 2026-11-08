package main

import (
	"log/slog"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"sync"
	"time"
)

// awakeIdle is how long the Mac is kept awake after the last upload ends, so the
// short gaps between files of a batch don't let it sleep.
const awakeIdle = 5 * time.Minute

// awake keeps the Mac from idle-sleeping while uploads are in progress and for
// [awake.idle] after the last one ends. It runs macOS's caffeinate with -i (no
// idle system sleep): the display can still turn off, and closing the lid still
// puts the Mac to sleep. A nil *awake does nothing.
type awake struct {
	idle time.Duration
	// Replaced in tests: hold starts preventing sleep; after runs f once d has passed
	// and returns a function that cancels it.
	hold  func() (release func())
	after func(d time.Duration, f func()) (cancel func())

	mu      sync.Mutex
	active  int    // uploads in progress
	release func() // non-nil while sleep is prevented
	cancel  func() // non-nil while the idle countdown runs
	gen     int    // invalidates a countdown that fired while begin was waiting for the lock
}

func newAwake(idle time.Duration) *awake {
	return &awake{idle: idle, hold: caffeinate, after: func(d time.Duration, f func()) func() {
		t := time.AfterFunc(d, f)
		return func() { t.Stop() }
	}}
}

// stopCountdown must be called with a.mu held.
func (a *awake) stopCountdown() {
	a.gen++
	if a.cancel != nil {
		a.cancel()
		a.cancel = nil
	}
}

// begin marks an upload as started.
func (a *awake) begin() {
	if a == nil {
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	a.active++
	a.stopCountdown()
	if a.release == nil {
		a.release = a.hold()
	}
}

// end marks an upload as finished; after idle with no other upload, sleep is allowed.
func (a *awake) end() {
	if a == nil {
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	a.active--
	if a.active > 0 {
		return
	}
	gen := a.gen
	a.cancel = a.after(a.idle, func() { a.expire(gen) })
}

func (a *awake) expire(gen int) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if gen != a.gen || a.active > 0 || a.release == nil {
		return
	}
	slog.Info("letting the Mac sleep: no uploads", "for", a.idle)
	a.release()
	a.release = nil
	a.cancel = nil
}

// close allows sleep right away (the server is stopping).
func (a *awake) close() {
	if a == nil {
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	a.stopCountdown()
	if a.release != nil {
		a.release()
		a.release = nil
	}
}

// caffeinate prevents idle sleep until the returned function is called. -w makes
// caffeinate exit with this process too, even if it crashes. Elsewhere than
// macOS it does nothing.
func caffeinate() (release func()) {
	if runtime.GOOS != "darwin" {
		return func() {}
	}
	cmd := exec.Command("/usr/bin/caffeinate", caffeinateArgs(os.Getpid())...)
	if err := cmd.Start(); err != nil {
		slog.Warn("can't keep the Mac awake; it may sleep during uploads", "err", err)
		return func() {}
	}
	slog.Info("keeping the Mac awake while uploading (closing the lid still makes it sleep)")
	return func() {
		cmd.Process.Kill()
		cmd.Wait()
	}
}

func caffeinateArgs(pid int) []string {
	return []string{"-i", "-w", strconv.Itoa(pid)}
}
