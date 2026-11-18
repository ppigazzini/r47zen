#ifndef SITE_SCREEN_SVG_H
#define SITE_SCREEN_SVG_H

#include <stdbool.h>
#include <stdint.h>

// Write a width x height bitmap as an SVG of one background rect and one path of
// the lit pixels, in the shape scripts/site-screens/check_screens_artifact.sh
// accepts. pixel_on reports whether the pixel at x, y is lit. Return false, with
// a message on stderr, when the file cannot be written.
bool site_screen_write_svg(const char *path, uint16_t width, uint16_t height,
                           bool (*pixel_on)(uint32_t x, uint32_t y));

#endif // SITE_SCREEN_SVG_H
