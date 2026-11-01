package main

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
)

// trayIcon draws the menu bar icon: a photo frame with mountains and a sun, as a
// 32x32 px macOS template image (black + alpha; macOS tints it for light/dark
// menu bars). systray displays it at 16x16 pt, i.e. 2x on Retina.
func trayIcon() []byte {
	const n = 32
	img := image.NewNRGBA(image.Rect(0, 0, n, n))
	ink := color.NRGBA{A: 0xFF}
	set := func(x, y int) { img.SetNRGBA(x, y, ink) }

	// Frame: rounded rectangle outline, 2px thick.
	x0, y0, x1, y1 := 3, 6, 28, 25
	for x := x0 + 1; x <= x1-1; x++ {
		for t := 0; t < 2; t++ {
			set(x, y0+t)
			set(x, y1-t)
		}
	}
	for y := y0 + 1; y <= y1-1; y++ {
		for t := 0; t < 2; t++ {
			set(x0+t, y)
			set(x1-t, y)
		}
	}

	// Mountains: two filled triangles resting on the bottom edge.
	fillTriangle(img, ink, [3][2]int{{7, 22}, {13, 12}, {19, 22}})
	fillTriangle(img, ink, [3][2]int{{15, 22}, {20, 16}, {25, 22}})

	// Sun: small filled circle.
	cx, cy, r := 21, 11, 2
	for y := cy - r; y <= cy+r; y++ {
		for x := cx - r; x <= cx+r; x++ {
			if (x-cx)*(x-cx)+(y-cy)*(y-cy) <= r*r {
				set(x, y)
			}
		}
	}

	var buf bytes.Buffer
	png.Encode(&buf, img)
	return buf.Bytes()
}

func fillTriangle(img *image.NRGBA, c color.NRGBA, p [3][2]int) {
	sign := func(ax, ay, bx, by, cx, cy int) int { return (ax-cx)*(by-cy) - (bx-cx)*(ay-cy) }
	b := img.Bounds()
	for y := b.Min.Y; y < b.Max.Y; y++ {
		for x := b.Min.X; x < b.Max.X; x++ {
			d1 := sign(x, y, p[0][0], p[0][1], p[1][0], p[1][1])
			d2 := sign(x, y, p[1][0], p[1][1], p[2][0], p[2][1])
			d3 := sign(x, y, p[2][0], p[2][1], p[0][0], p[0][1])
			hasNeg := d1 < 0 || d2 < 0 || d3 < 0
			hasPos := d1 > 0 || d2 > 0 || d3 > 0
			if !(hasNeg && hasPos) {
				img.SetNRGBA(x, y, c)
			}
		}
	}
}
