// Host contract for r47zen/legacy_printf.c: r47_bytes_vsnprintf formats every
// case as glibc's vsnprintf does, return value and truncation included, at each
// buffer size from none to ample. glibc scans a format byte by byte, as Android 9
// and later do, so it is the reference; the cases carry the core's glyph bytes in
// the format (STD_SPACE_EM "\xa0\x03", the degree sign "\x80\xb0") as showStep's
// "%04u:" STD_SPACE_EM does, and every conversion and length the core uses. It
// also refuses what it does not format: %n and a positional argument. And the
// gate: only a format with a byte over 0x7f is formatted here.
#include "legacy_printf.h"

#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static int failures = 0;

static int bytesFormat(char *out, size_t size, const char *format, ...) {
  va_list args;
  va_start(args, format);
  const int result = r47_bytes_vsnprintf(out, size, format, args);
  va_end(args);
  return result;
}

// Compare both formatters at every size from 0 to 80 for one format and argument
// list, given twice since a va_list is spent once read.
#define CHECK(format, ...)                                                     \
  do {                                                                         \
    for (size_t size = 0; size <= 80; size++) {                               \
      char expected[96];                                                       \
      char actual[96];                                                         \
      memset(expected, 'E', sizeof expected);                                  \
      memset(actual, 'E', sizeof actual);                                      \
      const int want = snprintf(size ? expected : NULL, size, format,          \
                                __VA_ARGS__);                                  \
      const int got = bytesFormat(size ? actual : NULL, size, format,         \
                                  __VA_ARGS__);                                \
      if (want != got || memcmp(expected, actual, sizeof expected) != 0) {     \
        fprintf(stderr, "FAIL: %s at size %zu: want %d, got %d\n", #format,     \
                size, want, got);                                              \
        failures++;                                                            \
        break;                                                                 \
      }                                                                        \
    }                                                                          \
  } while (0)

int main(void) {
  CHECK("%04u:\xa0\x03", 7u);
  CHECK("%04u:\xa0\x03", 65535u);
  CHECK("\xa0\x03%s\x80\xb0%d", "deg", -12);
  CHECK("%-8s|%8s|", "\x80\xb0x", "ab");
  CHECK("%+.3e %g %f %a", 1234.5678, 1e-300, -0.5, 1.0);
  CHECK("%*.*f|%-*d", 10, 3, 3.14159, 6, 42);
  CHECK("%lld %llu %ld %lu", -9000000000LL, 18000000000ULL, -7L, 7UL);
  CHECK("%zu %zd %td %jd %ju", (size_t)99, (ssize_t)-99, (ptrdiff_t)-3,
        (intmax_t)-4, (uintmax_t)4);
  CHECK("%hhd %hd %hu %#o %#x %X", 300, 70000, 70000u, 8u, 255u, 3054u);
  CHECK("%Lg %LG", 1.5L, -2.25L);
  CHECK("%c%c%c", 'a', 0xb0, '\x03');
  CHECK("100%% \xa0\x03 %s", "\xa0\x03");
  CHECK("%5.2s|%.0s|%s", "abcdef", "x", "");
  CHECK("%p", (void *)0x1234);
  CHECK("%s", "no conversion bytes \xff\xfe");
  CHECK("%d", 0);

  // Only a format with a byte over 0x7f is formatted here; the platform keeps
  // every other, so an ASCII format behaves on Android 7.0 to 8.1 as it did.
  if (!r47_format_has_high_bytes("%04u:\xa0\x03") ||
      !r47_format_has_high_bytes("\x80\xb0") ||
      !r47_format_has_high_bytes("ok \xff") ||
      r47_format_has_high_bytes("%04u: %s %d%%") ||
      r47_format_has_high_bytes("\x7f") || r47_format_has_high_bytes("")) {
    fprintf(stderr, "FAIL: r47_format_has_high_bytes\n");
    failures++;
  }

  char out[16];
  if (bytesFormat(out, sizeof out, "%n", (int *)NULL) != -1) {
    fprintf(stderr, "FAIL: %%n was formatted\n");
    failures++;
  }
  if (bytesFormat(out, sizeof out, "%1$d", 5) != -1) {
    fprintf(stderr, "FAIL: a positional argument was formatted\n");
    failures++;
  }
  if (bytesFormat(out, sizeof out, "") != 0 || out[0] != '\0') {
    fprintf(stderr, "FAIL: an empty format\n");
    failures++;
  }

  if (failures != 0) {
    return 1;
  }
  printf("legacy printf: every case matches glibc's vsnprintf at sizes 0 to 80\n");
  return 0;
}
