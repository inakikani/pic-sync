BIN  := bin
ARCH ?= arm64
APP  := $(BIN)/pic-sync.app
# The menu bar item needs cgo (macOS APIs), so it's only built on a Mac.
# Elsewhere the app is built without it and falls back to the "running" window.
CGO  ?= $(if $(filter Darwin,$(shell uname -s)),1,0)

# Release version, e.g. `make release VERSION=1.2`. Defaults to the latest
# git tag without its leading "v" (tag v1.2 -> 1.2).
VERSION ?= $(patsubst v%,%,$(shell git describe --tags --abbrev=0 2>/dev/null))

.PHONY: build build-mac app sign universal release pkg test

build:
	go build -o $(BIN)/pic-sync .

build-mac:
	GOOS=darwin GOARCH=arm64 go build -o $(BIN)/pic-sync-darwin-arm64 .
	GOOS=darwin GOARCH=amd64 go build -o $(BIN)/pic-sync-darwin-amd64 .

# macOS app bundle. ARCH=arm64 (Apple Silicon, default) or ARCH=amd64 (Intel).
app:
	rm -rf $(APP)
	mkdir -p $(APP)/Contents/MacOS
	cp packaging/Info.plist $(APP)/Contents/Info.plist
	CGO_ENABLED=$(CGO) GOOS=darwin GOARCH=$(ARCH) go build -o $(APP)/Contents/MacOS/pic-sync .

# Ad-hoc sign the bundle (run on the Mac; needs Xcode command line tools).
sign: app
	codesign --force --deep --sign - $(APP)

# Universal (Apple Silicon + Intel) app bundle, ad-hoc signed, version from
# VERSION. Mac only: the menu bar item needs cgo and lipo/codesign/plutil.
universal:
	@test "$$(uname -s)" = Darwin || { echo "make universal/release/pkg must run on a Mac"; exit 1; }
	@test -n "$(VERSION)" || { echo "set VERSION, e.g. make release VERSION=1.0 (or tag a commit v1.0)"; exit 1; }
	rm -rf $(APP)
	mkdir -p $(APP)/Contents/MacOS
	cp packaging/Info.plist $(APP)/Contents/Info.plist
	plutil -replace CFBundleShortVersionString -string $(VERSION) $(APP)/Contents/Info.plist
	plutil -replace CFBundleVersion -string $(VERSION) $(APP)/Contents/Info.plist
	CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -o $(BIN)/pic-sync-darwin-arm64 .
	CGO_ENABLED=1 GOOS=darwin GOARCH=amd64 go build -o $(BIN)/pic-sync-darwin-amd64 .
	lipo -create -output $(APP)/Contents/MacOS/pic-sync $(BIN)/pic-sync-darwin-arm64 $(BIN)/pic-sync-darwin-amd64
	codesign --force --deep --sign - $(APP)

# Download for GitHub Releases: bin/pic-sync-<VERSION>-macos.zip.
# ditto keeps the bundle's signature and attributes intact (plain zip doesn't).
release: universal
	rm -f $(BIN)/pic-sync-$(VERSION)-macos.zip
	ditto -c -k --keepParent $(APP) $(BIN)/pic-sync-$(VERSION)-macos.zip
	@echo "built $(BIN)/pic-sync-$(VERSION)-macos.zip"

# Installer alternative: bin/pic-sync-<VERSION>.pkg, installs to /Applications.
pkg: universal
	pkgbuild --root $(APP) --install-location /Applications/pic-sync.app \
		--identifier com.inakikani.pic-sync --version $(VERSION) \
		$(BIN)/pic-sync-$(VERSION).pkg
	@echo "built $(BIN)/pic-sync-$(VERSION).pkg"

test:
	go test ./...
	go vet -tags traycheck ./...   # compile-check the menu bar code on any OS
