package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"time"
)

// Timeouts. There is deliberately no overall read timeout: a large video over
// slow Wi-Fi can legitimately take many minutes. Instead an upload fails when no
// data has arrived for uploadIdleTimeout.
const (
	readHeaderTimeout = 10 * time.Second // request line + headers
	idleConnTimeout   = 2 * time.Minute  // keep-alive connections between requests
	shutdownGrace     = 5 * time.Second  // uploads in progress get this long to finish on quit
)

// uploadIdleTimeout is a variable so tests can shorten it.
var uploadIdleTimeout = 30 * time.Second

// newHandler wraps the routes with everything every request goes through:
// request logging, cross-site protection and the upload idle timeout.
func newHandler(routes http.Handler) http.Handler {
	return logRequests(crossOriginProtection().Handler(idleTimeout(routes)))
}

// crossOriginProtection rejects POSTs that a browser makes on behalf of another
// site, e.g. a web page open on the Mac uploading files with fetch(). Requests
// from the upload page itself (same origin) and from non-browser clients such
// as the Android app (no Sec-Fetch-Site/Origin headers) are allowed.
func crossOriginProtection() *http.CrossOriginProtection {
	p := http.NewCrossOriginProtection()
	p.SetDenyHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		slog.Warn("request rejected: sent by another website", "client", clientIP(r), "path", r.URL.Path,
			"origin", r.Header.Get("Origin"), "sec_fetch_site", r.Header.Get("Sec-Fetch-Site"))
		httpError(w, http.StatusForbidden, "cross-origin request rejected")
	}))
	return p
}

// idleTimeout makes a request body read fail when nothing arrives for
// uploadIdleTimeout, e.g. the phone went out of Wi-Fi range mid-upload. Without
// it the connection could hang until TCP keep-alive gives up (minutes), with the
// menu bar showing "Receiving…" all along.
func idleTimeout(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Body != nil && r.Body != http.NoBody {
			r.Body = &idleBody{ReadCloser: r.Body, rc: http.NewResponseController(w), timeout: uploadIdleTimeout}
		}
		next.ServeHTTP(w, r)
	})
}

type idleBody struct {
	io.ReadCloser
	rc      *http.ResponseController
	timeout time.Duration
}

func (b *idleBody) Read(p []byte) (int, error) {
	// Not supported (e.g. httptest.ResponseRecorder): just read without a deadline.
	_ = b.rc.SetReadDeadline(time.Now().Add(b.timeout))
	return b.ReadCloser.Read(p)
}

// serve runs the HTTP server until ctx is cancelled, then shuts it down:
// uploads in progress get shutdownGrace to finish, then their connections are
// closed. It returns nil after a shutdown, or the error that stopped the server.
func serve(ctx context.Context, ln net.Listener, h http.Handler) error {
	srv := &http.Server{
		Handler:           newHandler(h),
		ReadHeaderTimeout: readHeaderTimeout,
		IdleTimeout:       idleConnTimeout,
		ErrorLog:          slog.NewLogLogger(slog.Default().Handler(), slog.LevelWarn),
	}
	errc := make(chan error, 1)
	go func() { errc <- srv.Serve(ln) }()
	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
	}
	slog.Info("shutting down", "grace", shutdownGrace)
	sctx, cancel := context.WithTimeout(context.Background(), shutdownGrace)
	defer cancel()
	if err := srv.Shutdown(sctx); err != nil {
		slog.Warn("uploads still in progress were cut off", "err", err)
		srv.Close()
	}
	if err := <-errc; err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}
