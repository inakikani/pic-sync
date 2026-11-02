package main

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// fixDates gives files already in dir (not subfolders) the capture date
// recorded inside them as their modification time, like new uploads get.
// With dryRun nothing is changed. Files without a capture date are listed
// and left alone. File contents are never touched.
func fixDates(dir string, dryRun bool, out io.Writer) error {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	var changed, correct int
	var noDate []string
	verb := "set"
	if dryRun {
		verb = "would set"
	}
	for _, e := range entries {
		if !e.Type().IsRegular() || strings.HasPrefix(e.Name(), ".") {
			continue // folders, links, .DS_Store, in-progress .upload-*.part files
		}
		path := filepath.Join(dir, e.Name())
		taken, ok := takenTimeOfFile(path)
		if !ok {
			noDate = append(noDate, e.Name())
			continue
		}
		info, err := e.Info()
		if err != nil {
			fmt.Fprintf(out, "skipped %s: %v\n", e.Name(), err)
			continue
		}
		if d := info.ModTime().Sub(taken); d > -time.Second && d < time.Second {
			correct++
			continue
		}
		if !dryRun {
			if err := os.Chtimes(path, taken, taken); err != nil {
				fmt.Fprintf(out, "failed %s: %v\n", e.Name(), err)
				continue
			}
		}
		changed++
		fmt.Fprintf(out, "%s %s: %s -> %s\n", verb, e.Name(),
			info.ModTime().Format("2006-01-02 15:04"), taken.Local().Format("2006-01-02 15:04"))
	}
	sort.Strings(noDate)
	if len(noDate) > 0 {
		fmt.Fprintf(out, "\nNo date inside these files, left as they are:\n  %s\n", strings.Join(noDate, "\n  "))
	}
	summary := "updated"
	if dryRun {
		summary = "would update"
	}
	fmt.Fprintf(out, "\n%s %d, already correct %d, no date in file %d\n", summary, changed, correct, len(noDate))
	if dryRun && changed > 0 {
		fmt.Fprintln(out, "Nothing was changed (dry run). Run again without -dry-run to apply.")
	}
	return nil
}
