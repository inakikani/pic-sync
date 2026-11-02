package main

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"sort"
	"strings"
)

// pageEventMaxBytes bounds a page event; they're a few hundred bytes.
const pageEventMaxBytes = 4 << 10

// handlePageLog logs one event sent by the upload page (index.html), e.g.
//
//	page: picker returned files client=192.168.1.40 count=3 bytes="12.1 MB" types="image/heic×3"
//
// Events are small JSON objects: {"event": "...", ...simple fields}. Only short
// string, number and boolean fields are logged; the rest is ignored.
func handlePageLog(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(io.LimitReader(r.Body, pageEventMaxBytes+1))
	if err != nil || len(body) > pageEventMaxBytes {
		httpError(w, http.StatusBadRequest, "event too large")
		return
	}
	var ev map[string]any
	if err := json.Unmarshal(body, &ev); err != nil {
		httpError(w, http.StatusBadRequest, "invalid event")
		return
	}
	name, _ := ev["event"].(string)
	if name == "" {
		httpError(w, http.StatusBadRequest, "missing event")
		return
	}
	keys := make([]string, 0, len(ev))
	for k := range ev {
		if k != "event" {
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)
	args := []any{"client", clientIP(r)}
	for _, k := range keys {
		if len(args) >= 2+2*12 {
			break // at most 12 fields
		}
		switch v := ev[k].(type) {
		case string:
			args = append(args, truncate(k, 32), truncate(v, 200))
		case float64, bool:
			args = append(args, truncate(k, 32), v)
		}
	}
	level := slog.LevelInfo
	if strings.Contains(name, "error") || strings.Contains(name, "nothing") {
		level = slog.LevelWarn
	}
	slog.Log(r.Context(), level, "page: "+truncate(name, 60), args...)
	w.WriteHeader(http.StatusNoContent)
}
