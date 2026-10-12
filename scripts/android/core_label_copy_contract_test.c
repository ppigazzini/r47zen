// Host contract test for r47_copy_core_label in
// android/app/src/main/cpp/r47zen/core_label.h: a core-encoded label copied
// into a fixed buffer is cut only between glyphs, so the copy never ends in a
// lead byte whose trail is the terminator.
//
// Run via scripts/android/run_core_label_copy_contract.sh (no SDK, no staged
// native tree required).

#include "core_label.h"

#include <stdbool.h>
#include <stdio.h>
#include <string.h>

static int cases = 0;
static int failures = 0;

static void expect_copy(const char *label, const char *src, size_t cap,
                        const char *want) {
  char dst[80];
  memset(dst, 'Z', sizeof(dst));
  r47_copy_core_label(dst, cap, src);
  cases++;
  if (cap == 0) {
    if (dst[0] != 'Z') {
      fprintf(stderr, "FAIL: %s: wrote into a zero-byte buffer\n", label);
      failures++;
    }
    return;
  }
  if (strcmp(dst, want) != 0) {
    fprintf(stderr, "FAIL: %s: got \"%s\", want \"%s\"\n", label, dst, want);
    failures++;
  }
}

// True when s ends inside a two-byte glyph, read from the start.
static bool ends_in_lead_byte(const char *s) {
  size_t at = 0;
  while (s[at] != '\0') {
    if (((unsigned char)s[at] & 0x80u) != 0u) {
      if (s[at + 1] == '\0') {
        return true;
      }
      at += 2;
    } else {
      at += 1;
    }
  }
  return false;
}

int main(void) {
  expect_copy("ASCII that fits", "ABC", 8, "ABC");
  expect_copy("ASCII cut to the buffer", "ABCDEFGH", 4, "ABC");
  expect_copy("a glyph that fits exactly", "\x80\x41"
                                           "B",
              4, "\x80\x41"
                 "B");
  expect_copy("a cut that would split a glyph", "AB\x80\x41", 4, "AB");
  expect_copy("a lead byte with no trail", "A\x80", 8, "A");
  expect_copy("a one-byte buffer", "ABC", 1, "");
  expect_copy("a zero-byte buffer", "ABC", 0, "");
  expect_copy("no source", NULL, 8, "");

  // Every cap over a label that mixes one- and two-byte glyphs: the copy is a
  // prefix of the source, never ends inside a glyph, and is as long as the
  // buffer allows, so the next glyph would not fit.
  const char *mixed = "A\x81\x22"
                      "BC\x9f\x7f\x80\x01"
                      "D\xa0\x10"
                      "E";
  const size_t mixed_len = strlen(mixed);
  for (size_t cap = 1; cap <= mixed_len + 2; cap++) {
    char dst[80];
    r47_copy_core_label(dst, cap, mixed);
    const size_t got = strlen(dst);
    cases++;
    if (got > cap - 1 || strncmp(dst, mixed, got) != 0 ||
        ends_in_lead_byte(dst)) {
      fprintf(stderr, "FAIL: cap %zu: \"%s\" is not a whole-glyph prefix\n",
              cap, dst);
      failures++;
      continue;
    }
    if (got < mixed_len) {
      const size_t next = ((unsigned char)mixed[got] & 0x80u) ? 2u : 1u;
      if (got + next <= cap - 1) {
        fprintf(stderr, "FAIL: cap %zu: stopped at %zu with room for more\n",
                cap, got);
        failures++;
      }
    }
  }

  if (failures != 0) {
    fprintf(stderr, "core label copy: %d of %d cases failed\n", failures,
            cases);
    return 1;
  }
  printf("core label copy: %d cases passed\n", cases);
  return 0;
}
