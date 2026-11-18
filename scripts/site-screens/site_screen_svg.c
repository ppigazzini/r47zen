// The SVG writer for the site's calculator screens. It holds no core code, so
// run_site_screens_contract.sh compiles it alone, feeds it seeded bitmaps, and
// decodes what it writes.

#include "site_screen_svg.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// The site's LCD colors: black pixels on a light gray glass. They are the page's
// choice, not the app's palette, which LcdThemePolicy.resolve computes at run
// time from the user's settings.
#define SITE_SCREEN_PIXEL_COLOR "#000000"
#define SITE_SCREEN_GLASS_COLOR "#dcdedb"

typedef struct {
  uint16_t x;
  uint16_t width;
  uint16_t top;
  uint16_t height;
} run_t;

static void write_rect(FILE *out, const run_t *run) {
  fprintf(out, "M%u %uh%uv%uh-%uz", run->x, run->top, run->width, run->height,
          run->width);
}

// Each run of lit pixels in a row is a rectangle, and a run that repeats the
// one in the row above at the same x and width grows that rectangle downward
// instead of starting a new one.
bool site_screen_write_svg(const char *path, uint16_t width, uint16_t height,
                           bool (*pixel_on)(uint32_t x, uint32_t y)) {
  // Lit runs are separated by unlit pixels, so a row holds at most
  // (width + 1) / 2 of them.
  const size_t max_runs = (size_t)width / 2u + 1u;
  run_t *open = calloc(max_runs, sizeof(run_t));
  run_t *row = calloc(max_runs, sizeof(run_t));
  run_t *next = calloc(max_runs, sizeof(run_t));
  size_t open_count = 0;
  FILE *out = NULL;
  bool written = false;

  if (open == NULL || row == NULL || next == NULL) {
    fprintf(stderr, "ERROR: out of memory writing %s\n", path);
    goto done;
  }
  out = fopen(path, "w");
  if (out == NULL) {
    fprintf(stderr, "ERROR: cannot write %s\n", path);
    goto done;
  }
  fprintf(out,
          "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %u %u\" "
          "width=\"%u\" height=\"%u\" shape-rendering=\"crispEdges\">"
          "<rect width=\"%u\" height=\"%u\" fill=\"%s\"/>"
          "<path fill=\"%s\" d=\"",
          width, height, width, height, width, height, SITE_SCREEN_GLASS_COLOR,
          SITE_SCREEN_PIXEL_COLOR);

  // One pass past the last row closes every rectangle still open.
  for (uint32_t y = 0; y <= height; ++y) {
    size_t row_count = 0;
    for (uint32_t x = 0; y < height && x < width;) {
      if (!pixel_on(x, y)) {
        ++x;
        continue;
      }
      const uint32_t start = x;
      while (x < width && pixel_on(x, y)) {
        ++x;
      }
      row[row_count++] =
          (run_t){(uint16_t)start, (uint16_t)(x - start), (uint16_t)y, 1u};
    }
    size_t next_count = 0;
    for (size_t i = 0; i < open_count; ++i) {
      bool grown = false;
      for (size_t j = 0; j < row_count; ++j) {
        if (row[j].width != 0 && row[j].x == open[i].x &&
            row[j].width == open[i].width) {
          open[i].height++;
          row[j].width = 0;
          next[next_count++] = open[i];
          grown = true;
          break;
        }
      }
      if (!grown) {
        write_rect(out, &open[i]);
      }
    }
    for (size_t j = 0; j < row_count; ++j) {
      if (row[j].width != 0) {
        next[next_count++] = row[j];
      }
    }
    memcpy(open, next, next_count * sizeof(run_t));
    open_count = next_count;
  }

  fprintf(out, "\"/></svg>\n");
  written = true;

done:
  if (out != NULL && fclose(out) != 0) {
    fprintf(stderr, "ERROR: cannot finish %s\n", path);
    written = false;
  }
  free(open);
  free(row);
  free(next);
  return written;
}
