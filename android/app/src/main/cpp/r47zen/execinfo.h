#ifndef DUMMY_EXECINFO_H
#define DUMMY_EXECINFO_H

#include <stdlib.h>

// bionic's backtrace is not available at the app's minSdk, so there are no frames
// to name. upstream's freeList.c prints strs[1] whatever the frame count, then frees
// the array, so it has to be a real one with two entries: anything less turns its
// bad-free diagnostic into a crash.
static inline int backtrace(void **buffer, int size) {
  (void)buffer;
  (void)size;
  return 0;
}
static inline char **backtrace_symbols(void *const *buffer, int size) {
  (void)buffer;
  (void)size;
  return (char **)calloc(2, sizeof(char *));
}

#endif
