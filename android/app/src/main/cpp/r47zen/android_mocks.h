#ifndef ANDROID_MOCKS_H
#define ANDROID_MOCKS_H

#if defined(ANDROID_BUILD) || defined(HOST_TOOL_BUILD)

#include <stdint.h>
#include <stdbool.h>
#include <time.h>
#include <stdlib.h>
#include <stdio.h>
#include <pthread.h>

// Every translation unit gets this header through -include, so this is the
// one native log tag.
#define LOG_TAG "R47Native"

#if defined(HOST_TOOL_BUILD)
#define LOGI(...)                                                              \
  do {                                                                         \
    fprintf(stderr, "I/%s: ", LOG_TAG);                                        \
    fprintf(stderr, __VA_ARGS__);                                              \
    fputc('\n', stderr);                                                       \
  } while (0)
#define LOGE(...)                                                              \
  do {                                                                         \
    fprintf(stderr, "E/%s: ", LOG_TAG);                                        \
    fprintf(stderr, __VA_ARGS__);                                              \
    fputc('\n', stderr);                                                       \
  } while (0)
#define LOGD(...)                                                              \
  do {                                                                         \
    fprintf(stderr, "D/%s: ", LOG_TAG);                                        \
    fprintf(stderr, __VA_ARGS__);                                              \
    fputc('\n', stderr);                                                       \
  } while (0)
#else
#include <android/log.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
// Release builds (CMake defines NDEBUG for RelWithDebInfo and Release) keep
// errors only: debug and info logging is per key press and names user data
// such as program labels. The if (0) keeps the arguments type-checked and used.
#if defined(NDEBUG)
#define LOGI(...)                                                              \
  do {                                                                         \
    if (0)                                                                     \
      __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__);             \
  } while (0)
#define LOGD(...)                                                              \
  do {                                                                         \
    if (0)                                                                     \
      __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__);            \
  } while (0)
#else
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#endif
#endif

// The GMP names the staged core uses that mini-gmp lacks; the suite on the app's
// configuration, run_upstream_suite_android_config.sh, includes the same header.
#include "gmp_names.h"

// GTK/GDK/Cairo Mocks
typedef void GtkWidget;
// hal/keys.h declares keyPushPcEvent for every non-DMCP build.
typedef struct {
  unsigned int keyval;
  unsigned int state;
} GdkEventKey;
typedef long long gint64;

#ifndef TRUE
#define TRUE 1
#endif
#ifndef FALSE
#define FALSE 0
#endif

extern uint32_t sys_current_ms(void);
extern gint64 g_get_monotonic_time(void);
extern gint64 g_get_real_time(void);
extern int requestAndroidFile(int isSave, const char *defaultName,
                              int fileType);

// Globals needed by core - MATCHING TYPES IN c47.h
extern int16_t debugWindow;
extern int16_t screenStride;
extern bool debugMemAllocation;
extern bool forceTamAlpha;
extern uint32_t deadKey;

#endif // ANDROID_BUILD || HOST_TOOL_BUILD

#endif // ANDROID_MOCKS_H
