// Host ThreadSanitizer harness over the threads of the running app. The calculator
// runs upstream's main loop on a thread of its own (r47_calculator_main) and only it
// touches core state; every other thread pushes a key or a service request, or copies
// what the calculator thread published (jni_display.c, keypad_scene.c, hal/lcd.c).
// Both other sanitizer lanes (build_graph_crash_harness.sh,
// run_workload_regressions_sanitized.sh) drive the core on one thread, so this is
// the only lane where that rule meets a data-race adversary.
//
// Two phases. The startup phase races the UI entry points against the calculator's
// own init: MainActivity posts a keypad refresh after first layout, and onStart
// starts the host, while the thread is still initializing. The steady-state phase races a producer, pushing keys and
// requests as the UI does, against a consumer reading the way the display loop does.
// A race here is a hard failure, because no lock serializes the core any more.
#include "jni_bridge.h"
#include "host_services.h"
#include "keypad_fixture_bridge.h"
// The repo-owned HAL header, by a path relative to this file: the staged upstream
// tree also has a hal/lcd.h, and it comes first on the include path.
#include "../../android/app/src/main/cpp/r47zen/hal/lcd.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

bool r47_try_take_packed_display(uint8_t *dst);

static uint32_t g_consumer_sink = 0;

// Harness-owned handoff flags; atomic so the harness itself is race-free and
// only the bridge's synchronization is under test.
static atomic_bool g_producer_done = false;
static atomic_bool g_init_done = false;

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

// Producer: the UI's push side. Keys, then a request that runs at rest, so the
// calculator thread writes the packed display and publishes the keypad meanwhile.
static void *producer_main(void *arg) {
  long iterations = *(const long *)arg;
  for (long i = 0; i < iterations; i++) {
    int keyCode = 1 + (int)(i % 37);
    if (keyCode == 33 || keyCode == 36) {
      continue; // leave run mode alone
    }
    r47_key_push(keyCode);
    r47_key_push(0);
    if ((i % 16) == 0) {
      r47_key_push_pc('H', 0x1u, true); // shifted H, the HOME menu
      r47_key_push_pc('H', 0x1u, false);
      r47_service_graph_gesture(0.01f, 0.0f, 1.0f);
      r47_service_copy(R47_COPY_X);
      r47_service_save_and_wait(5000);
    }
  }
  atomic_store_explicit(&g_producer_done, true, memory_order_relaxed);
  return NULL;
}

// Consumer: the UI read side. Takes the packed display through the same native
// function getPackedDisplayBuffer calls, gate and trylock included; only the
// JNI array pinning around it is left out.
static void consumer_read_packed_display(void) {
  static uint8_t snapshot[SCREEN_HEIGHT * LCD_ROW_SIZE_BYTES];
  if (!r47_try_take_packed_display(snapshot)) {
    return;
  }
  uint8_t acc = 0;
  for (int row = 0; row < SCREEN_HEIGHT; row++) {
    acc = (uint8_t)(acc ^ snapshot[LCD_ROW_SIZE_BYTES * row + 2]);
  }
  g_consumer_sink ^= acc;
}

// Startup phase: the UI side is live before r47_init_runtime returns. onStart tells
// the calculator the host started, which wakes it through keyCond, and it can do so
// before the calculator thread has run a line.
static void *startup_reader_main(void *arg) {
  (void)arg;
  static int32_t meta[R47_KEYPAD_META_LENGTH];
  static char labels[R47_KEYPAD_KEY_COUNT * R47_KEYPAD_LABELS_PER_KEY]
                    [R47_KEYPAD_LABEL_CAPACITY];
  long spins = 0;
  long copies = 0;
  while (!atomic_load_explicit(&g_init_done, memory_order_relaxed)) {
    r47_keys_on_host_stopped(false);
    if (r47_try_copy_app_keypad_snapshot(meta, labels, 0)) {
      g_consumer_sink ^= (uint32_t)meta[R47_KEYPAD_META_CALC_MODE];
      copies++;
    }
    consumer_read_packed_display();
    spins++;
  }
  fprintf(stderr, "startup reader: %ld spins, %ld keypad copies during init\n",
          spins, copies);
  return NULL;
}

static void *consumer_main(void *arg) {
  (void)arg;
  static int32_t meta[R47_KEYPAD_META_LENGTH];
  static char labels[R47_KEYPAD_KEY_COUNT * R47_KEYPAD_LABELS_PER_KEY]
                    [R47_KEYPAD_LABEL_CAPACITY];
  long spins = 0;
  while (!atomic_load_explicit(&g_producer_done, memory_order_relaxed)) {
    extern _Atomic uint32_t packedDisplayGeneration;
    // Sample the generations the way the JNI getters do: no mutex held.
    uint32_t kg = r47_keypad_published_generation();
    uint32_t pg = atomic_load_explicit(&packedDisplayGeneration,
                                       memory_order_relaxed);
    g_consumer_sink ^= kg ^ pg;

    if (r47_try_copy_app_keypad_snapshot(meta, labels, (spins & 64) != 0)) {
      g_consumer_sink ^= (uint32_t)meta[R47_KEYPAD_META_CALC_MODE];
    }
    consumer_read_packed_display();
    spins++;
  }
  fprintf(stderr, "consumer: %ld spins\n", spins);
  return NULL;
}

static void *calculator_main(void *arg) {
  (void)arg;
  r47_calculator_main();
  return NULL;
}

int main(void) {
  const char *runtime_dir = "/tmp/r47-bridge-tsan-harness";
  mkdir(runtime_dir, 0700);
  r47_native_preinit_path(runtime_dir);

  pthread_t startup_reader;
  if (pthread_create(&startup_reader, NULL, startup_reader_main, NULL) != 0) {
    fprintf(stderr, "FATAL: cannot start startup reader thread\n");
    return 1;
  }
  pthread_t calculator;
  if (pthread_create(&calculator, NULL, calculator_main, NULL) != 0) {
    fprintf(stderr, "FATAL: cannot start calculator thread\n");
    return 1;
  }
  while (!r47_runtime_ready()) {
    usleep(1000);
  }
  atomic_store_explicit(&g_init_done, true, memory_order_relaxed);
  pthread_join(startup_reader, NULL);

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

  r47_calculator_stop(false);
  pthread_join(calculator, NULL);

  fprintf(stderr, "DONE: producer/consumer race completed (sink=%u)\n",
          g_consumer_sink);
  return 0;
}
