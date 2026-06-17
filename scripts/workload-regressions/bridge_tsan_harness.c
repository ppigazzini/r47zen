// Host ThreadSanitizer harness over the JNI bridge's concurrent display
// protocol. The production bridge runs the upstream C47 core on a dedicated
// "R47CoreRuntime" thread (NativeCoreRuntime.kt) that mutates screen and packed
// -display state under screenMutex and packedDisplayMutex, while the Android UI
// thread reads that state behind matching pthread_mutex_trylock fast paths and
// volatile generation counters (jni_display.c). Both existing sanitizer lanes
// (build_graph_crash_harness.sh, run_workload_regressions_sanitized.sh) drive
// the core single-threaded, so that producer/consumer protocol has never run
// under a data-race adversary.
//
// This harness races a producer thread (the live sendKey input path plus
// r47_force_refresh, exactly as the core runtime drives the screen) against a
// consumer thread that reproduces the UI read path: it samples the volatile
// generation counters the way getKeypadSnapshotGeneration /
// getPackedDisplayGeneration do, reads the keypad snapshot under screenMutex via
// the real r47_get_keypad_meta entry point, and runs the packed-display
// trylock-copy-clear sequence the way getPackedDisplayBuffer does. Built under
// -fsanitize=thread by build_bridge_tsan_harness.sh, a missing acquire on a
// generation counter, a torn read across the trylock fast path, or a lock-order
// inversion between the two display mutexes fails the harness instead of passing
// every existing single-threaded check.
//
// R47_TSAN_HARNESS_ITERS bounds the run (default below) for a fast check.

#include "keypad_fixture_bridge.h"
#include "screen.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#define LCD_ROW_SIZE_BYTES 52u

extern void r47_initialize_native_bridge_state(void);
extern void r47_native_preinit_path(const char *path);
extern void r47_init_runtime(int slotId);

// The live fixed-width key input path. It ignores its JNIEnv/jobject arguments
// (jni_input.c), so the harness drives it with NULL the way the instrumentation
// fixtures in jni_program_load_test.c already do.
extern void Java_com_example_r47_MainActivity_sendKey(void *env, void *thiz,
                                                      int32_t keyCode);

// The lock-free signals the UI thread samples without holding a display mutex.
// Match hal/lcd.c's C11 atomic definitions so the consumer's relaxed loads are
// exactly the production access, not a stronger-typed stand-in.
extern _Atomic uint32_t packedDisplayGeneration;
extern _Atomic uint32_t keypadSnapshotGeneration;
extern _Atomic bool lcdBufferDirty;
extern uint8_t *packedDisplayBuffer;
extern pthread_mutex_t packedDisplayMutex;

// Sink to keep the consumer reads from being optimized away.
static volatile uint32_t g_consumer_sink = 0;
// Harness-owned handoff flag; atomic so the harness itself is race-free and only
// the bridge's synchronization is under test.
static atomic_bool g_producer_done = false;

static long harness_iterations(void) {
  long iterations = 4000;
  const char *iters_env = getenv("R47_TSAN_HARNESS_ITERS");
  if (iters_env != NULL) {
    long parsed = strtol(iters_env, NULL, 10);
    if (parsed > 0) {
      iterations = parsed;
    }
  }
  return iterations;
}

// Producer: the core runtime side. Each iteration presses and releases a live
// key and forces a screen refresh, so the core mutates screen state under
// screenMutex and LCD_write_line writes the packed buffer and bumps both
// generation counters under packedDisplayMutex.
static void *producer_main(void *arg) {
  long iterations = *(const long *)arg;
  for (long i = 0; i < iterations; i++) {
    int keyCode = 1 + (int)(i % 37);
    Java_com_example_r47_MainActivity_sendKey(NULL, NULL, keyCode);
    Java_com_example_r47_MainActivity_sendKey(NULL, NULL, 0);
    r47_force_refresh();
  }
  atomic_store_explicit(&g_producer_done, true, memory_order_relaxed);
  return NULL;
}

// Consumer: the UI read side. Mirrors the three production read paths exactly.
static void consumer_read_packed_display(void) {
  if (!packedDisplayBuffer) {
    return;
  }
  // Unsynchronized fast-path dirty check (jni_display.c getPackedDisplayBuffer):
  // a relaxed atomic load, no display mutex held.
  if (!atomic_load_explicit(&lcdBufferDirty, memory_order_relaxed)) {
    return;
  }
  if (pthread_mutex_trylock(&packedDisplayMutex) != 0) {
    return;
  }
  uint8_t acc = 0;
  for (int row = 0; row < SCREEN_HEIGHT; row++) {
    acc = (uint8_t)(acc ^ packedDisplayBuffer[LCD_ROW_SIZE_BYTES * row + 2]);
  }
  for (int row = 0; row < SCREEN_HEIGHT; row++) {
    packedDisplayBuffer[LCD_ROW_SIZE_BYTES * row] = 0u;
  }
  // Cleared under the lock, as the JNI reader does.
  atomic_store_explicit(&lcdBufferDirty, false, memory_order_relaxed);
  pthread_mutex_unlock(&packedDisplayMutex);
  g_consumer_sink ^= acc;
}

static void *consumer_main(void *arg) {
  (void)arg;
  int32_t meta[R47_KEYPAD_META_LENGTH];
  long spins = 0;
  while (!atomic_load_explicit(&g_producer_done, memory_order_relaxed)) {
    // Sample the generation counters the way the JNI getters do: a relaxed
    // atomic load with no display mutex held.
    uint32_t kg = atomic_load_explicit(&keypadSnapshotGeneration,
                                       memory_order_relaxed);
    uint32_t pg = atomic_load_explicit(&packedDisplayGeneration,
                                       memory_order_relaxed);
    g_consumer_sink ^= kg ^ pg;

    // Real keypad snapshot read; r47_get_keypad_meta locks screenMutex.
    r47_get_keypad_meta(meta, (spins & 1) != 0);
    g_consumer_sink ^= (uint32_t)meta[R47_KEYPAD_META_CALC_MODE];

    consumer_read_packed_display();
    spins++;
  }
  fprintf(stderr, "consumer: %ld read spins\n", spins);
  return NULL;
}

int main(void) {
  const char *runtime_dir = "/tmp/r47-bridge-tsan-harness";
  mkdir(runtime_dir, 0700);

  r47_initialize_native_bridge_state();
  r47_native_preinit_path(runtime_dir);
  r47_init_runtime(0);

  long iterations = harness_iterations();
  fprintf(stderr, "bridge TSan harness: %ld producer iterations\n", iterations);

  pthread_t producer;
  pthread_t consumer;
  if (pthread_create(&consumer, NULL, consumer_main, NULL) != 0) {
    fprintf(stderr, "FATAL: cannot start consumer thread\n");
    return 1;
  }
  if (pthread_create(&producer, NULL, producer_main, &iterations) != 0) {
    fprintf(stderr, "FATAL: cannot start producer thread\n");
    return 1;
  }

  pthread_join(producer, NULL);
  pthread_join(consumer, NULL);

  fprintf(stderr, "DONE: producer/consumer race completed (sink=%u)\n",
          g_consumer_sink);
  return 0;
}
