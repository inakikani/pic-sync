package main

import (
	"bytes"
	"image"
	"image/png"
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

// qrPNG renders text as a PNG QR code with scale pixels per module and the
// standard 4-module quiet zone. (rsc.io/qr's own Code.Image ignores Scale and
// draws the code at 1px per module in the corner, so it can't be used.)
func qrPNG(text string, scale int) ([]byte, error) {
	code, err := qr.Encode(text, qr.M)
	if err != nil {
		return nil, err
	}
	const quiet = 4
	side := (code.Size + 2*quiet) * scale
	img := image.NewGray(image.Rect(0, 0, side, side))
	for i := range img.Pix {
		img.Pix[i] = 0xFF
	}
	for y := 0; y < code.Size; y++ {
		for x := 0; x < code.Size; x++ {
			if !code.Black(x, y) {
				continue
			}
			for dy := 0; dy < scale; dy++ {
				row := ((y+quiet)*scale + dy) * img.Stride
				for dx := 0; dx < scale; dx++ {
					img.Pix[row+(x+quiet)*scale+dx] = 0x00
				}
			}
		}
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}
