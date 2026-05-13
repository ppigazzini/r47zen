#ifndef R47_ANDROID_LOG_STUB_H
#define R47_ANDROID_LOG_STUB_H

#include <stdarg.h>
#include <stdio.h>

#define ANDROID_LOG_UNKNOWN 0
#define ANDROID_LOG_DEFAULT 1
#define ANDROID_LOG_VERBOSE 2
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_FATAL 7
#define ANDROID_LOG_SILENT 8

static inline int __android_log_print(int priority, const char *tag,
                                      const char *format, ...) {
  (void)priority;
  va_list args;
  va_start(args, format);
  if (tag != NULL && tag[0] != '\0') {
    fprintf(stderr, "%s: ", tag);
  }
  int written = vfprintf(stderr, format, args);
  fputc('\n', stderr);
  va_end(args);
  return written;
}

#endif
