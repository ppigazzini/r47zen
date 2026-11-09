// sprintf and snprintf for Android 7.0 to 8.1, which cannot read the core's format
// strings: see legacy_printf.c.
#pragma once

#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>

// Whether a format holds a byte over 0x7f, which Android 7.0 to 8.1 may refuse: the
// only formats this formats; the platform keeps every other.
bool r47_format_has_high_bytes(const char *format);

// vsnprintf for a format whose literal text holds any bytes: they are copied as
// they are, and each conversion is formatted alone by the platform's snprintf. The
// result and the truncation are vsnprintf's; -1 for a conversion it does not take
// (%n, a positional %1$, an unknown letter, a spec over 31 bytes).
int r47_bytes_vsnprintf(char *out, size_t size, const char *format,
                        va_list args);
