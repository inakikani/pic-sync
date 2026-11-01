BIN := bin

.PHONY: build build-mac test

build:
	go build -o $(BIN)/pic-sync .

build-mac:
	GOOS=darwin GOARCH=arm64 go build -o $(BIN)/pic-sync-darwin-arm64 .
	GOOS=darwin GOARCH=amd64 go build -o $(BIN)/pic-sync-darwin-amd64 .

test:
	go test ./...
