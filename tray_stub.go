//go:build !((darwin && cgo) || (linux && traycheck))

package main

// Builds without cgo on macOS (e.g. cross-compiled from Linux) have no menu bar
// item; the app falls back to the "pic-sync is running" window.
const trayAvailable = false

func runTray(*trayApp) {}
