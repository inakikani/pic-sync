package main

import (
	"fmt"
	"io"
	"sync"
	"sync/atomic"
	"time"
)

// activity records what the server is doing, for the menu bar status.
type activity struct {
	mu         sync.Mutex
	now        func() time.Time
	inflight   map[*transfer]struct{}
	day        string // local date the "today" counters belong to
	savedToday int
	dupToday   int
	lastUpload time.Time
	lastName   string
	lastErr    string
	lastErrAt  time.Time
}

// transfer is one in-progress upload request.
type transfer struct {
	mu    sync.Mutex
	name  string
	bytes atomic.Int64
	total int64 // request Content-Length, or -1 if unknown (chunked)
}

func newActivity() *activity {
	return &activity{now: time.Now, inflight: map[*transfer]struct{}{}}
}

func (a *activity) begin(total int64) *transfer {
	t := &transfer{total: total}
	a.mu.Lock()
	a.inflight[t] = struct{}{}
	a.mu.Unlock()
	return t
}

func (t *transfer) setName(name string) {
	t.mu.Lock()
	t.name = name
	t.mu.Unlock()
}

// reader wraps r so bytes read count towards the transfer's progress.
func (t *transfer) reader(r io.Reader) io.Reader { return countingReader{r, &t.bytes} }

type countingReader struct {
	r io.Reader
	n *atomic.Int64
}

func (c countingReader) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	c.n.Add(int64(n))
	return n, err
}

func (a *activity) end(t *transfer) {
	a.mu.Lock()
	delete(a.inflight, t)
	a.mu.Unlock()
}

// rollDay resets the daily counters at local midnight. Must hold a.mu.
func (a *activity) rollDay() {
	if d := a.now().Format("2006-01-02"); d != a.day {
		a.day, a.savedToday, a.dupToday = d, 0, 0
	}
}

func (a *activity) recordSaved(res savedFile) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.rollDay()
	if res.Duplicate {
		a.dupToday++
	} else {
		a.savedToday++
	}
	a.lastUpload, a.lastName = a.now(), res.Name
}

func (a *activity) recordError(name string, err error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	msg := err.Error()
	if name != "" {
		msg = name + ": " + msg
	}
	a.lastErr, a.lastErrAt = msg, a.now()
}

type transferInfo struct {
	Name         string
	Bytes, Total int64
}

type statusSnapshot struct {
	Transfers            []transferInfo
	SavedToday, DupToday int
	LastUpload           time.Time
	LastName             string
	LastErr              string
	LastErrAt            time.Time
}

func (a *activity) snapshot() statusSnapshot {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.rollDay()
	s := statusSnapshot{
		SavedToday: a.savedToday, DupToday: a.dupToday,
		LastUpload: a.lastUpload, LastName: a.lastName,
		LastErr: a.lastErr, LastErrAt: a.lastErrAt,
	}
	for t := range a.inflight {
		t.mu.Lock()
		s.Transfers = append(s.Transfers, transferInfo{t.name, t.bytes.Load(), t.total})
		t.mu.Unlock()
	}
	return s
}

// ---- turning a snapshot into menu bar text (pure, unit-tested) ----

type trayState int

const (
	stateIdle trayState = iota
	stateReceiving
	stateError
)

type trayStatus struct {
	State   trayState
	Title   string // short text next to the menu bar icon ("" when idle)
	Status  string // first menu line
	Today   string // second menu line
	LastErr string // "" if there has been no error
}

// errorShownFor is how long a new error keeps the menu bar in the error state
// (unless the menu is opened, or an upload succeeds, first).
const errorShownFor = 2 * time.Minute

// describe builds the menu bar text. errSeenAt is when the user last opened the
// menu; errors older than that no longer flag the icon.
func describe(s statusSnapshot, now, errSeenAt time.Time) trayStatus {
	var st trayStatus
	switch n := len(s.Transfers); {
	case n == 1:
		t := s.Transfers[0]
		st.State = stateReceiving
		if t.Total > 0 {
			pct := int(t.Bytes * 100 / t.Total)
			st.Title = fmt.Sprintf("↑ %d%%", pct)
			st.Status = fmt.Sprintf("Receiving %s · %d%%", orUnnamed(t.Name), pct)
		} else {
			st.Title = "↑ " + formatBytes(t.Bytes)
			st.Status = fmt.Sprintf("Receiving %s · %s", orUnnamed(t.Name), formatBytes(t.Bytes))
		}
	case n > 1:
		st.State = stateReceiving
		st.Title = fmt.Sprintf("↑ %d files", n)
		st.Status = fmt.Sprintf("Receiving %d files", n)
	default:
		freshErr := s.LastErr != "" && s.LastErrAt.After(s.LastUpload) &&
			s.LastErrAt.After(errSeenAt) && now.Sub(s.LastErrAt) < errorShownFor
		if freshErr {
			st.State = stateError
			st.Title = "!"
			st.Status = "Error: " + s.LastErr
		} else {
			st.Status = "Running · waiting for uploads"
		}
	}

	switch {
	case s.SavedToday == 0 && s.DupToday == 0:
		st.Today = "No uploads yet today"
	default:
		st.Today = fmt.Sprintf("Today: %d received", s.SavedToday)
		if s.DupToday > 0 {
			st.Today += fmt.Sprintf(", %d already on Mac", s.DupToday)
		}
		st.Today += " · last " + ago(now, s.LastUpload)
	}
	if s.LastErr != "" {
		st.LastErr = fmt.Sprintf("Last error (%s): %s", ago(now, s.LastErrAt), s.LastErr)
	}
	return st
}

func orUnnamed(name string) string {
	if name == "" {
		return "file"
	}
	return name
}

func ago(now, t time.Time) string {
	d := now.Sub(t)
	switch {
	case d < time.Minute:
		return "just now"
	case d < time.Hour:
		return fmt.Sprintf("%d min ago", int(d.Minutes()))
	case d < 24*time.Hour:
		return fmt.Sprintf("%d h ago", int(d.Hours()))
	default:
		return t.Format("2 Jan 15:04")
	}
}

// formatBytes uses decimal units, like Finder.
func formatBytes(n int64) string {
	switch {
	case n >= 1e9:
		return fmt.Sprintf("%.1f GB", float64(n)/1e9)
	case n >= 1e6:
		return fmt.Sprintf("%.1f MB", float64(n)/1e6)
	case n >= 1e3:
		return fmt.Sprintf("%d KB", n/1e3)
	}
	return fmt.Sprintf("%d B", n)
}
