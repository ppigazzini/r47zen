// r47_format: snprintf that refuses a cut. The glue's file paths and names go
// through it, so a text that does not fit fails the operation instead of naming
// another file.
#ifndef R47_FORMAT_H
#define R47_FORMAT_H

#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdio.h>

// Formats into out, or returns false with out empty and the cut logged when the
// text does not fit: a cut path would name another file, so no caller goes on
// with one.
__attribute__((format(printf, 3, 4))) static inline bool
r47_format(char *out, size_t size, const char *format, ...) {
  if (size == 0) {
    return false;
  }
  va_list args;
  va_start(args, format);
  const int written = vsnprintf(out, size, format, args);
  va_end(args);
  if (written < 0 || (size_t)written >= size) {
    out[0] = '\0';
    LOGE("r47_format: \"%s\" does not fit %zu bytes", format, size);
    return false;
  }
  return true;
}

#endif // R47_FORMAT_H
