package main

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

func TestCategorize(t *testing.T) {
	for _, tc := range []struct {
		name, contentType string
		want              category
	}{
		{"IMG_1.JPG", "", catPhotos},
		{"IMG_1.heic", "application/octet-stream", catPhotos},
		{"clip.MOV", "", catVideos},
		{"report.pdf", "", catDocuments},
		{"notes.docx", "", catDocuments},
		{"archive.zip", "application/zip", catOther},
		{"noext", "", catOther},
		// Unknown or missing extension: the type sent with the file decides.
		{"shared-file", "image/jpeg", catPhotos},
		{"shared-file", "video/mp4; codecs=avc1", catVideos},
		{"letter", "application/pdf", catDocuments},
		{"sheet", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", catDocuments},
		{"x.unknown", "text/plain", catDocuments},
		// A known extension wins over the type.
		{"photo.jpg", "application/pdf", catPhotos},
	} {
		if got := categorize(tc.name, tc.contentType); got != tc.want {
			t.Errorf("categorize(%q, %q) = %s, want %s", tc.name, tc.contentType, got, tc.want)
		}
	}
}

func TestRoute(t *testing.T) {
	f := folders{
		Photos:   "/p",
		Category: map[category]string{catDocuments: "/d"},
		Ext:      map[string]string{"zip": "/z", "pdf": "/pdf"},
	}
	for _, tc := range []struct {
		name, wantDir, wantRule string
		wantCat                 category
	}{
		{"a.jpg", "/p", "", catPhotos},
		{"a.mov", "/p", "", catVideos}, // no videos folder: same as photos
		{"a.docx", "/d", "", catDocuments},
		{"a.PDF", "/pdf", "pdf", catDocuments}, // extension rule wins over the category
		{"a.zip", "/z", "zip", catOther},
		{"a.rar", "/p", "", catOther},
	} {
		dir, c, rule := f.route(tc.name, "")
		if dir != tc.wantDir || c != tc.wantCat || rule != tc.wantRule {
			t.Errorf("route(%q) = %s, %s, %q; want %s, %s, %q", tc.name, dir, c, rule, tc.wantDir, tc.wantCat, tc.wantRule)
		}
	}
}

func TestFoldersWith(t *testing.T) {
	orig := folders{Photos: "/p", Category: map[category]string{catVideos: "/v"}}
	f := orig.with(catDocuments, "/d")
	if f.forCategory(catDocuments) != "/d" || f.forCategory(catVideos) != "/v" {
		t.Fatalf("with: %+v", f)
	}
	if _, ok := orig.Category[catDocuments]; ok {
		t.Fatal("with changed the original's map")
	}
	// "" or the photos folder itself means "same as Photos".
	if f = f.with(catVideos, ""); f.Category[catVideos] != "" || f.forCategory(catVideos) != "/p" {
		t.Fatalf("reset to photos: %+v", f)
	}
	if f = f.with(catDocuments, "/p"); len(f.Category) != 0 {
		t.Fatalf("photos folder kept as an explicit entry: %+v", f)
	}
	// Moving the photos folder moves every category that falls back to it.
	if f = f.with(catPhotos, "/p2"); f.forCategory(catOther) != "/p2" {
		t.Fatalf("fallback didn't follow the photos folder: %+v", f)
	}
}

func TestFoldersSummaryAndAll(t *testing.T) {
	f := folders{Photos: "/p", Category: map[category]string{catDocuments: "/d"}, Ext: map[string]string{"zip": "/z", "epub": "/d"}}
	want := []string{"Photos: /p", "Videos: same as Photos", "Documents: /d", "Other: same as Photos", ".epub: /d", ".zip: /z"}
	if got := f.summary(); !reflect.DeepEqual(got, want) {
		t.Errorf("summary = %q, want %q", got, want)
	}
	if got, want := f.all(), []string{"/p", "/d", "/z"}; !reflect.DeepEqual(got, want) {
		t.Errorf("all = %q, want %q", got, want)
	}
}

func TestConfigFolders(t *testing.T) {
	home, _ := os.UserHomeDir()
	cfg := Config{
		UploadDir:  "/p",
		Folders:    map[string]string{"Documents": "~/Docs/", "videos": ""},
		Extensions: map[string]string{".ZIP": "~/Downloads", "epub": "/books"},
	}
	f, err := cfg.folders()
	if err != nil {
		t.Fatal(err)
	}
	if got := f.Category[catDocuments]; got != filepath.Join(home, "Docs") {
		t.Errorf("documents = %q", got)
	}
	if _, ok := f.Category[catVideos]; ok {
		t.Error(`"videos": "" should mean same as photos`)
	}
	if f.Ext["zip"] != filepath.Join(home, "Downloads") || f.Ext["epub"] != "/books" {
		t.Errorf("extensions = %v", f.Ext)
	}

	for _, bad := range []Config{
		{UploadDir: "/p", Folders: map[string]string{"photos": "/x"}},
		{UploadDir: "/p", Folders: map[string]string{"music": "/x"}},
		{UploadDir: "/p", Extensions: map[string]string{"tar.gz": "/x"}},
		{UploadDir: "/p", Extensions: map[string]string{"zip": ""}},
	} {
		if _, err := bad.folders(); err == nil {
			t.Errorf("no error for %+v", bad)
		}
	}
}

func TestConfigSetFoldersKeepsExtensions(t *testing.T) {
	cfg := Config{UploadDir: "/p", Folders: map[string]string{"videos": "/v"}, Extensions: map[string]string{"zip": "~/Downloads"}}
	f, _ := cfg.folders()
	cfg.setFolders(f.with(catVideos, "").with(catOther, "/o"))
	if cfg.UploadDir != "/p" || !reflect.DeepEqual(cfg.Folders, map[string]string{"other": "/o"}) {
		t.Errorf("after setFolders: %+v", cfg)
	}
	if cfg.Extensions["zip"] != "~/Downloads" {
		t.Error("extension rules were rewritten")
	}
}

func TestLoadConfigRejectsBadFolders(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	os.WriteFile(path, []byte(`{"upload_dir": "/p", "folders": {"music": "/m"}}`), 0o644)
	if _, err := loadConfig(path); err == nil || !strings.Contains(err.Error(), "music") {
		t.Fatalf("err = %v", err)
	}
}

func TestUploadsGoToTheirFolder(t *testing.T) {
	root := t.TempDir()
	photos, docs, zips := filepath.Join(root, "p"), filepath.Join(root, "d"), filepath.Join(root, "z")
	f := folders{Photos: photos, Category: map[category]string{catDocuments: docs}, Ext: map[string]string{"zip": zips}}
	if err := createFolders(f); err != nil {
		t.Fatal(err)
	}
	st := newStore(photos)
	st.SetFolders(f)
	got := decodeSaved(t, uploadTo(t, newStoreMux(st),
		upFile{"a.jpg", "photo"}, upFile{"b.pdf", "doc"}, upFile{"c.zip", "zip"}, upFile{"d.rar", "rar"}))
	want := map[string]string{"a.jpg": photos, "b.pdf": docs, "c.zip": zips, "d.rar": photos}
	for _, s := range got {
		if s.Folder != shortPath(want[s.Name]) {
			t.Errorf("%s: folder %q, want %q", s.Name, s.Folder, want[s.Name])
		}
		if readFile(t, filepath.Join(want[s.Name], s.Name)) == "" {
			t.Errorf("%s not in %s", s.Name, want[s.Name])
		}
	}
	if got[1].Category != "documents" || got[3].Category != "other" {
		t.Errorf("categories: %+v", got)
	}
}

func TestDuplicatesAreCheckedPerFolder(t *testing.T) {
	root := t.TempDir()
	photos, docs := filepath.Join(root, "p"), filepath.Join(root, "d")
	f := folders{Photos: photos, Category: map[category]string{catDocuments: docs}}
	createFolders(f)
	st := newStore(photos)
	st.SetFolders(f)
	mux := newStoreMux(st)
	decodeSaved(t, uploadTo(t, mux, upFile{"a.jpg", "same bytes"}))
	// Same content as a PDF goes to the documents folder, which doesn't have it yet.
	if got := decodeSaved(t, uploadTo(t, mux, upFile{"a.pdf", "same bytes"})); got[0].Duplicate {
		t.Error("duplicate check looked in another category's folder")
	}
	if got := decodeSaved(t, uploadTo(t, mux, upFile{"b.pdf", "same bytes"})); !got[0].Duplicate {
		t.Error("duplicate within the documents folder not detected")
	}
}

func TestMissingCategoryFolderIsNotRecreated(t *testing.T) {
	root := t.TempDir()
	docs := filepath.Join(root, "ejected", "docs")
	st := newStore(root)
	st.SetFolders(folders{Photos: root, Category: map[category]string{catDocuments: docs}})
	if _, err := st.save("a.pdf", "", strings.NewReader("x"), time.Time{}); err == nil {
		t.Fatal("saved into a folder that doesn't exist")
	}
	if _, err := os.Stat(filepath.Join(root, "ejected")); err == nil {
		t.Fatal("the missing folder was created")
	}
}

func TestSetFoldersDropsUnusedCache(t *testing.T) {
	a, b := t.TempDir(), t.TempDir()
	st := newStore(a)
	st.SetFolders(folders{Photos: a, Category: map[category]string{catDocuments: b}})
	st.save("x.jpg", "", strings.NewReader("1"), time.Time{})
	st.save("y.pdf", "", strings.NewReader("2"), time.Time{})
	if len(st.cache) != 2 {
		t.Fatalf("cache has %d entries, want 2", len(st.cache))
	}
	st.SetFolders(folders{Photos: a})
	if len(st.cache) != 1 {
		t.Fatalf("cache has %d entries after dropping the documents folder, want 1", len(st.cache))
	}
}
