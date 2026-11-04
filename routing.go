package main

import (
	"fmt"
	"mime"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// category is the kind of file an upload is, which decides its folder.
type category string

const (
	catPhotos    category = "photos"
	catVideos    category = "videos"
	catDocuments category = "documents"
	catOther     category = "other"
)

// categories in display order. Photos always use upload_dir; the others can have
// their own folder (config "folders") and fall back to the photos folder.
var categories = []category{catPhotos, catVideos, catDocuments, catOther}

func (c category) label() string {
	switch c {
	case catPhotos:
		return "Photos"
	case catVideos:
		return "Videos"
	case catDocuments:
		return "Documents"
	}
	return "Other"
}

// extCategory maps lowercase extensions (no dot) to their category.
var extCategory = func() map[string]category {
	m := map[string]category{}
	add := func(c category, exts string) {
		for _, e := range strings.Fields(exts) {
			m[e] = c
		}
	}
	add(catPhotos, "jpg jpeg jpe heic heif hif avif png gif webp bmp tif tiff dng raw cr2 cr3 nef arw orf rw2 raf srw pef")
	add(catVideos, "mp4 m4v mov 3gp 3g2 mkv webm avi mts m2ts wmv mpg mpeg")
	add(catDocuments, "pdf doc docx odt rtf txt md pages xls xlsx ods csv numbers ppt pptx odp key epub")
	return m
}()

// categorize decides a file's category from its extension, or, for unknown or
// missing extensions, from the Content-Type the client sent with it.
func categorize(name, contentType string) category {
	if c, ok := extCategory[fileExt(name)]; ok {
		return c
	}
	mt, _, _ := mime.ParseMediaType(contentType)
	switch {
	case strings.HasPrefix(mt, "image/"):
		return catPhotos
	case strings.HasPrefix(mt, "video/"):
		return catVideos
	case strings.HasPrefix(mt, "text/"), mt == "application/pdf", mt == "application/rtf", mt == "application/epub+zip",
		mt == "application/msword", strings.HasPrefix(mt, "application/vnd.openxmlformats-officedocument."),
		strings.HasPrefix(mt, "application/vnd.oasis.opendocument."), strings.HasPrefix(mt, "application/vnd.ms-"):
		return catDocuments
	}
	return catOther
}

// fileExt is name's extension, lowercased and without the dot ("" if none).
func fileExt(name string) string {
	return strings.ToLower(strings.TrimPrefix(filepath.Ext(name), "."))
}

// folders says where each upload goes. Treat it as immutable: changes build a new
// value with new maps (see with), so a copy can be read without locking.
type folders struct {
	Photos   string              // upload_dir: photos, and every category without its own folder
	Category map[category]string // videos, documents, other; missing means Photos
	Ext      map[string]string   // extension (lowercase, no dot) → folder; wins over categories
}

// forCategory is the folder for c, falling back to the photos folder.
func (f folders) forCategory(c category) string {
	if d := f.Category[c]; d != "" {
		return d
	}
	return f.Photos
}

// route returns the folder for an upload and its category. rule is the extension
// rule that decided the folder (e.g. "zip"), or "" if the category did.
func (f folders) route(name, contentType string) (dir string, c category, rule string) {
	c = categorize(name, contentType)
	if ext := fileExt(name); ext != "" {
		if d := f.Ext[ext]; d != "" {
			return d, c, ext
		}
	}
	return f.forCategory(c), c, ""
}

// with returns a copy where category c uses dir ("" = same as photos).
func (f folders) with(c category, dir string) folders {
	if c == catPhotos {
		f.Photos = dir
		return f
	}
	m := make(map[category]string, len(f.Category)+1)
	for k, v := range f.Category {
		m[k] = v
	}
	if dir == "" || dir == f.Photos {
		delete(m, c)
	} else {
		m[c] = dir
	}
	f.Category = m
	return f
}

// all lists every distinct folder uploads can go to.
func (f folders) all() []string {
	seen := map[string]bool{}
	var out []string
	add := func(d string) {
		if d != "" && !seen[d] {
			seen[d] = true
			out = append(out, d)
		}
	}
	add(f.Photos)
	for _, c := range categories {
		add(f.forCategory(c))
	}
	for _, e := range f.extList() {
		add(f.Ext[e])
	}
	return out
}

func (f folders) extList() []string {
	exts := make([]string, 0, len(f.Ext))
	for e := range f.Ext {
		exts = append(exts, e)
	}
	sort.Strings(exts)
	return exts
}

// describeFolder is how a category's folder is shown: its path, or "same as
// Photos" when it falls back.
func (f folders) describeFolder(c category) string {
	if c != catPhotos && f.Category[c] == "" {
		return "same as Photos"
	}
	return shortPath(f.forCategory(c))
}

// summary lists the folders for display, e.g. "Photos: ~/Pictures/Phone",
// "Documents: same as Photos", ".zip: ~/Downloads".
func (f folders) summary() []string {
	var out []string
	for _, c := range categories {
		out = append(out, c.label()+": "+f.describeFolder(c))
	}
	for _, e := range f.extList() {
		out = append(out, "."+e+": "+shortPath(f.Ext[e]))
	}
	return out
}

// folders builds the routing from the config. upload_dir must already be set;
// "~" is expanded in every path.
func (cfg Config) folders() (folders, error) {
	f := folders{Photos: cfg.UploadDir}
	if len(cfg.Folders) > 0 {
		f.Category = map[category]string{}
	}
	for k, dir := range cfg.Folders {
		c := category(strings.ToLower(k))
		if c != catVideos && c != catDocuments && c != catOther {
			if c == catPhotos {
				return folders{}, fmt.Errorf(`config "folders": photos use upload_dir; only videos, documents and other can be set here`)
			}
			return folders{}, fmt.Errorf(`config "folders": unknown category %q (want videos, documents or other)`, k)
		}
		if dir == "" {
			continue
		}
		d, err := expandHome(dir)
		if err != nil {
			return folders{}, err
		}
		f.Category[c] = filepath.Clean(d)
	}
	if len(cfg.Extensions) > 0 {
		f.Ext = map[string]string{}
	}
	for k, dir := range cfg.Extensions {
		ext := strings.ToLower(strings.TrimPrefix(strings.TrimSpace(k), "."))
		if ext == "" || strings.ContainsAny(ext, `./\ `) {
			return folders{}, fmt.Errorf(`config "extensions": %q is not a file extension (e.g. "zip")`, k)
		}
		if dir == "" {
			return folders{}, fmt.Errorf(`config "extensions": no folder for %q`, k)
		}
		d, err := expandHome(dir)
		if err != nil {
			return folders{}, err
		}
		f.Ext[ext] = filepath.Clean(d)
	}
	return f, nil
}

// setFolders stores f's category folders back into the config (extension rules
// are only edited in the file, so they're left as they are).
func (cfg *Config) setFolders(f folders) {
	cfg.UploadDir = f.Photos
	var m map[string]string
	for _, c := range categories[1:] {
		if d := f.Category[c]; d != "" {
			if m == nil {
				m = map[string]string{}
			}
			m[string(c)] = d
		}
	}
	cfg.Folders = m
}

// expandHome expands a leading "~" to the home folder.
func expandHome(p string) (string, error) {
	if p != "~" && !strings.HasPrefix(p, "~/") {
		return p, nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("expanding ~: %w", err)
	}
	return filepath.Join(home, strings.TrimPrefix(p, "~")), nil
}

// shortPath shows paths under the home folder as "~/…".
func shortPath(p string) string {
	if home, err := os.UserHomeDir(); err == nil && (p == home || strings.HasPrefix(p, home+string(filepath.Separator))) {
		return "~" + strings.TrimPrefix(p, home)
	}
	return p
}
