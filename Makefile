BIN  := bin
ARCH ?= arm64
APP  := $(BIN)/pic-sync.app

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
	GOOS=darwin GOARCH=$(ARCH) go build -o $(APP)/Contents/MacOS/pic-sync .

# Ad-hoc sign the bundle (run on the Mac; needs Xcode command line tools).
sign: app
	codesign --force --deep --sign - $(APP)

test:
	go test ./...
