package main

import (
	"net"
	"strings"

	"rsc.io/qr"
)

// renderQR draws text as a QR code for a terminal. Each character cell holds two
// vertical modules using half-block glyphs, with explicit ANSI colours so the code
// scans on both dark and light terminal themes.
func renderQR(text string) (string, error) {
	code, err := qr.Encode(text, qr.M)
	if err != nil {
		return "", err
	}
	const quiet = 2 // quiet-zone width in modules
	n := code.Size
	dark := func(x, y int) bool {
		x, y = x-quiet, y-quiet
		return x >= 0 && y >= 0 && x < n && y < n && code.Black(x, y)
	}
	const (
		reset = "\x1b[0m"
		upper = "▀"
	)
	fg := map[bool]string{true: "\x1b[30m", false: "\x1b[97m"}
	bg := map[bool]string{true: "\x1b[40m", false: "\x1b[107m"}

	var b strings.Builder
	total := n + 2*quiet
	for y := 0; y < total; y += 2 {
		for x := 0; x < total; x++ {
			b.WriteString(fg[dark(x, y)] + bg[dark(x, y+1)] + upper)
		}
		b.WriteString(reset + "\n")
	}
	return b.String(), nil
}

// primaryIP returns the local address used for outbound traffic (normally the
// Wi-Fi/Ethernet address). Dialing UDP sends no packets; it only picks a route.
func primaryIP() string {
	conn, err := net.Dial("udp4", "1.1.1.1:80")
	if err != nil {
		return ""
	}
	defer conn.Close()
	return conn.LocalAddr().(*net.UDPAddr).IP.String()
}
