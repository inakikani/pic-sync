BIN  := bin
ARCH ?= arm64
APP  := $(BIN)/pic-sync.app
# The menu bar item needs cgo (macOS APIs), so it's only built on a Mac.
# Elsewhere the app is built without it and falls back to the "running" window.
CGO  ?= $(if $(filter Darwin,$(shell uname -s)),1,0)

.PHONY: build build-mac app sign test

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

test:
	go test ./...
	go vet -tags traycheck ./...   # compile-check the menu bar code on any OS
