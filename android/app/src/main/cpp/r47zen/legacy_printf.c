// sprintf and snprintf for Android 7.0 to 8.1, which cannot read the core's format
// strings.
//
// Android 7.0 to 8.1 (API 24 to 27) format with OpenBSD's vfprintf, which reads the
// format string as UTF-8 through mbrtowc and returns -1 at the first byte that is
// not. The core pastes its own glyph encoding into format strings across src/c47
// (STD_SPACE_EM is "\xa0\x03"), so on those versions each such call fails: a
// fortified sprintf aborts, since __vsprintf_chk casts the -1 to size_t
// ("FORTIFY: vsprintf: prevented write past end of buffer", in showStep at a
// program's first step on API 24), and any other returns -1. Android 9 scans the
// format for '%' byte by byte, as glibc does.
//
// CMakeLists.txt wraps each sprintf and snprintf form at link time. On API 28 and
// later, and for a format of ASCII alone, each wrapper calls the platform at once.
// Otherwise the call formats here: literal bytes are copied as they are, and each
// conversion, whose spec is ASCII, is handed alone to the platform's snprintf, which
// formats it exactly. The fortified entries keep bionic's checks against the size
// the compiler saw.
#include "legacy_printf.h"

#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <wchar.h>

#if defined(__ANDROID__)
#include <android/api-level.h>
#include <android/log.h>
#include <stdatomic.h>

int __real_snprintf(char *out, size_t size, const char *format, ...);
#define PLATFORM_SNPRINTF __real_snprintf
#else
#define PLATFORM_SNPRINTF snprintf
#endif

typedef enum {
  ARG_INT,
  ARG_LONG,
  ARG_LONG_LONG,
  ARG_INTMAX,
  ARG_SIZE,
  ARG_PTRDIFF,
  ARG_DOUBLE,
  ARG_LONG_DOUBLE,
  ARG_POINTER,
  ARG_WINT,
} argKind_t;

// The longest conversion spec this takes, terminator included.
#define SPEC_CAPACITY 32

bool r47_format_has_high_bytes(const char *format) {
  for (const unsigned char *cursor = (const unsigned char *)format;
       *cursor != '\0'; cursor++) {
    if (*cursor > 0x7f) {
      return true;
    }
  }
  return false;
}

// Copy literal bytes, keeping room for the terminator, and count them all.
static void appendBytes(char *out, size_t size, size_t *total,
                        const char *bytes, size_t length) {
  if (*total + 1 < size) {
    const size_t room = size - 1 - *total;
    memcpy(out + *total, bytes, length < room ? length : room);
  }
  *total += length;
}

// Which argument a conversion takes, or -1 for one this does not format.
static int argKindOf(char conversion, const char *length, argKind_t *kind) {
  const int wide = strcmp(length, "l") == 0;
  switch (conversion) {
  case 'd':
  case 'i':
  case 'o':
  case 'u':
  case 'x':
  case 'X':
    if (length[0] == '\0' || strcmp(length, "h") == 0 ||
        strcmp(length, "hh") == 0) {
      *kind = ARG_INT;
    } else if (wide) {
      *kind = ARG_LONG;
    } else if (strcmp(length, "ll") == 0) {
      *kind = ARG_LONG_LONG;
    } else if (strcmp(length, "j") == 0) {
      *kind = ARG_INTMAX;
    } else if (strcmp(length, "z") == 0) {
      *kind = ARG_SIZE;
    } else if (strcmp(length, "t") == 0) {
      *kind = ARG_PTRDIFF;
    } else {
      return -1;
    }
    return 0;
  case 'c':
    *kind = wide ? ARG_WINT : ARG_INT;
    return 0;
  case 's':
  case 'p':
    *kind = ARG_POINTER;
    return 0;
  case 'a':
  case 'A':
  case 'e':
  case 'E':
  case 'f':
  case 'F':
  case 'g':
  case 'G':
    if (strcmp(length, "L") == 0) {
      *kind = ARG_LONG_DOUBLE;
    } else if (length[0] == '\0' || wide) {
      *kind = ARG_DOUBLE;
    } else {
      return -1;
    }
    return 0;
  default:
    return -1;
  }
}

#define FORMAT_ONE(value)                                                      \
  (stars == 0   ? PLATFORM_SNPRINTF(out, size, spec, value)                    \
   : stars == 1 ? PLATFORM_SNPRINTF(out, size, spec, star[0], value)           \
                : PLATFORM_SNPRINTF(out, size, spec, star[0], star[1], value))

// Format one conversion, taking its argument from args.
static int formatOne(char *out, size_t size, const char *spec, int stars,
                     const int *star, argKind_t kind, va_list *args) {
  switch (kind) {
  case ARG_INT: {
    const int value = va_arg(*args, int);
    return FORMAT_ONE(value);
  }
  case ARG_LONG: {
    const long value = va_arg(*args, long);
    return FORMAT_ONE(value);
  }
  case ARG_LONG_LONG: {
    const long long value = va_arg(*args, long long);
    return FORMAT_ONE(value);
  }
  case ARG_INTMAX: {
    const intmax_t value = va_arg(*args, intmax_t);
    return FORMAT_ONE(value);
  }
  case ARG_SIZE: {
    const size_t value = va_arg(*args, size_t);
    return FORMAT_ONE(value);
  }
  case ARG_PTRDIFF: {
    const ptrdiff_t value = va_arg(*args, ptrdiff_t);
    return FORMAT_ONE(value);
  }
  case ARG_DOUBLE: {
    const double value = va_arg(*args, double);
    return FORMAT_ONE(value);
  }
  case ARG_LONG_DOUBLE: {
    const long double value = va_arg(*args, long double);
    return FORMAT_ONE(value);
  }
  case ARG_POINTER: {
    const void *value = va_arg(*args, const void *);
    return FORMAT_ONE(value);
  }
  case ARG_WINT: {
    const wint_t value = va_arg(*args, wint_t);
    return FORMAT_ONE(value);
  }
  }
  return -1;
}

int r47_bytes_vsnprintf(char *out, size_t size, const char *format,
                        va_list args) {
  va_list remaining;
  va_copy(remaining, args);
  size_t total = 0;
  const char *cursor = format;
  int result = 0;
  // The platform takes no size over INT_MAX: an unbounded sprintf passes INT_MAX.
  if (size > INT_MAX) {
    size = INT_MAX;
  }
  while (*cursor != '\0') {
    if (*cursor != '%') {
      const char *literal = cursor;
      while (*cursor != '\0' && *cursor != '%') {
        cursor++;
      }
      appendBytes(out, size, &total, literal, (size_t)(cursor - literal));
      continue;
    }
    const char *specStart = cursor++;
    if (*cursor == '%') {
      appendBytes(out, size, &total, "%", 1);
      cursor++;
      continue;
    }
    int star[2] = {0, 0};
    int stars = 0;
    while (*cursor != '\0' && strchr("-+ #0'", *cursor) != NULL) {
      cursor++;
    }
    if (*cursor == '*') {
      star[stars++] = va_arg(remaining, int);
      cursor++;
    }
    while (*cursor >= '0' && *cursor <= '9') {
      cursor++;
    }
    if (*cursor == '$') {
      result = -1; // a positional argument
      break;
    }
    if (*cursor == '.') {
      cursor++;
      if (*cursor == '*') {
        star[stars++] = va_arg(remaining, int);
        cursor++;
      }
      while (*cursor >= '0' && *cursor <= '9') {
        cursor++;
      }
    }
    char length[3] = {0, 0, 0};
    for (size_t used = 0;
         used < 2 && *cursor != '\0' && strchr("hljztL", *cursor) != NULL;
         used++) {
      length[used] = *cursor++;
    }
    const char conversion = *cursor;
    argKind_t kind;
    const size_t specLength = (size_t)(cursor + 1 - specStart);
    if (conversion == '\0' || specLength >= SPEC_CAPACITY ||
        argKindOf(conversion, length, &kind) != 0) {
      result = -1;
      break;
    }
    cursor++;
    char spec[SPEC_CAPACITY];
    memcpy(spec, specStart, specLength);
    spec[specLength] = '\0';
    char *const piece = total < size ? out + total : NULL;
    const size_t room = total < size ? size - total : 0;
    const int written =
        formatOne(piece, room, spec, stars, star, kind, &remaining);
    if (written < 0) {
      result = -1;
      break;
    }
    total += (size_t)written;
  }
  va_end(remaining);
  if (size > 0) {
    out[total < size ? total : size - 1] = '\0';
  }
  if (result < 0 || total > INT_MAX) {
    return -1;
  }
  return (int)total;
}

#if defined(__ANDROID__)
int __real_vsnprintf(char *out, size_t size, const char *format, va_list args);
int __real_vsprintf(char *out, const char *format, va_list args);
int __real___vsnprintf_chk(char *out, size_t size, int flags, size_t outLength,
                           const char *format, va_list args);
int __real___vsprintf_chk(char *out, int flags, size_t outLength,
                          const char *format, va_list args);

// Whether the platform's printf reads a format as UTF-8: below API 28.
static bool platformReadsUtf8(void) {
  static atomic_int apiLevel;
  int level = atomic_load_explicit(&apiLevel, memory_order_relaxed);
  if (level == 0) {
    level = android_get_device_api_level();
    atomic_store_explicit(&apiLevel, level, memory_order_relaxed);
  }
  return level < 28;
}

// Whether this call is formatted here rather than by the platform.
static bool formatsHere(const char *format) {
  return platformReadsUtf8() && r47_format_has_high_bytes(format);
}

static void fortifyFail(const char *what) {
  __android_log_assert(NULL, "r47zen",
                       "FORTIFY: %s: prevented write past end of buffer", what);
}

int __wrap_vsnprintf(char *out, size_t size, const char *format, va_list args) {
  return formatsHere(format) ? r47_bytes_vsnprintf(out, size, format, args)
                             : __real_vsnprintf(out, size, format, args);
}

int __wrap_vsprintf(char *out, const char *format, va_list args) {
  return formatsHere(format) ? r47_bytes_vsnprintf(out, INT_MAX, format, args)
                             : __real_vsprintf(out, format, args);
}

int __wrap_snprintf(char *out, size_t size, const char *format, ...) {
  va_list args;
  va_start(args, format);
  const int result = __wrap_vsnprintf(out, size, format, args);
  va_end(args);
  return result;
}

int __wrap_sprintf(char *out, const char *format, ...) {
  va_list args;
  va_start(args, format);
  const int result = __wrap_vsprintf(out, format, args);
  va_end(args);
  return result;
}

int __wrap___vsnprintf_chk(char *out, size_t size, int flags, size_t outLength,
                           const char *format, va_list args) {
  if (!formatsHere(format)) {
    return __real___vsnprintf_chk(out, size, flags, outLength, format, args);
  }
  if (size > outLength) {
    fortifyFail("vsnprintf");
  }
  return r47_bytes_vsnprintf(out, size, format, args);
}

int __wrap___vsprintf_chk(char *out, int flags, size_t outLength,
                          const char *format, va_list args) {
  if (!formatsHere(format)) {
    return __real___vsprintf_chk(out, flags, outLength, format, args);
  }
  const int result = r47_bytes_vsnprintf(out, outLength, format, args);
  if (result < 0 || (size_t)result >= outLength) {
    fortifyFail("vsprintf");
  }
  return result;
}

int __wrap___snprintf_chk(char *out, size_t size, int flags, size_t outLength,
                          const char *format, ...) {
  va_list args;
  va_start(args, format);
  const int result =
      __wrap___vsnprintf_chk(out, size, flags, outLength, format, args);
  va_end(args);
  return result;
}

int __wrap___sprintf_chk(char *out, int flags, size_t outLength,
                         const char *format, ...) {
  va_list args;
  va_start(args, format);
  const int result = __wrap___vsprintf_chk(out, flags, outLength, format, args);
  va_end(args);
  return result;
}
#endif // __ANDROID__
