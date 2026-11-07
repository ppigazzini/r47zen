// Requests the host makes of the calculator that are not keys: paste, a shell
// menu, a graph gesture, a copy, a save, the keypad label mode.
//
// They live apart from the key buffer because a long operation wipes that buffer
// (exitKeyWaiting drops every key but EXIT and R/S). Any thread queues one, and
// the calculator thread runs it from sys_sleep at rest, so none of them can run
// inside a computation or be lost to one. Each answers through an up-call.
#ifndef HOST_SERVICES_H
#define HOST_SERVICES_H

#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

// Formats into out, or returns false with out empty and the cut logged when the text
// does not fit: a cut path would name another file, so no caller goes on with one.
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

enum {
  R47_COPY_X = 0,
  R47_COPY_STACK = 1,
  R47_COPY_ALL = 2,
};

// The app's one threading rule is that only the calculator thread runs core code. A debug
// build checks it where the core calls the HAL and where a request starts, so a path that
// breaks the rule aborts at its first call, not as a race much later. A release build
// compiles this out. jni_core_reach.py holds the JNI entries to the same rule.
#if defined(NDEBUG)
#define R47_CALCULATOR_THREAD_ONLY() ((void)0)
#else
void r47_assert_calculator_thread(const char *caller);
#define R47_CALCULATOR_THREAD_ONLY() r47_assert_calculator_thread(__func__)
#endif
#if defined(HOST_TOOL_BUILD)
// A host tool that hands the core to another thread, across a thread start or a join,
// names the thread that owns it from here on.
void r47_keys_claim_calculator_thread(void);
#endif

// Any thread. A false return means the queue is full and the request was refused.
bool r47_service_paste(const char *tokens);
bool r47_service_shell_menu(int shellMenu);
bool r47_service_graph_reset(void);
bool r47_service_copy(int what);
bool r47_service_describe_display(void);
bool r47_service_keypad_mode(int mode);

// Any thread. Gestures that arrive while the calculator is busy merge into one, so
// a slow graph re-solve never falls behind a drag.
void r47_service_graph_gesture(float dxNorm, float dyNorm, float scale);

// Any thread but the calculator's. Queues a save and, if the calculator is at rest,
// waits up to timeoutMs for it. A false return means it is still pending: the
// calculator is busy or parked at a picker, where no wait could end, and the save
// runs when it next reaches rest or a pause.
bool r47_service_save_and_wait(int timeoutMs);

// Any thread: whether idle saves may run, the user's autosave setting.
void r47_service_set_autosave(bool enabled);

// The calculator thread, at rest. Returns whether any request ran. A request runs only
// behind the keys pushed before it: the main loop may have looked at an empty buffer just
// before they arrived, so each request is checked against the keys once it is seen.
bool r47_services_run(void);
bool r47_services_pending(void);
bool r47_services_save_waiting(void);
bool r47_services_autosave_enabled(void);

// The calculator thread, in a program's pause: run a save that is waiting. A pause
// is a step boundary, so the program's state is whole, unlike a poll in a computation.
void r47_services_save_in_pause(void);

// The calculator thread: how long until an idle checkpoint is due, or -1 for none.
int r47_services_checkpoint_due_ms(bool hostStopped);
// The calculator thread: a key arrived, so there is work to keep.
void r47_services_note_input(void);
void r47_services_note_saved(void);
// The calculator thread at rest: save if a checkpoint is due.
bool r47_services_checkpoint(bool hostStopped);

// The calculator thread: write the state file. saveCalc is upstream's shutdown
// routine, so it is called only at rest, in a pause, and from the picker's park, and
// r47_save_settled undoes what it changes in the running calculator.
void r47_save_settled(void);
// The calculator thread, before a picker: save unless the user turned autosave off.
void r47_save_before_picker(void);

// The calculator thread, as the keypad labels may have changed.
void r47_keypad_publish_at_rest(void);
void r47_keypad_publish_if_due(void);
void r47_keypad_mark_dirty(void);
void r47_keypad_set_mode(int mode);

#if defined(HOST_TOOL_BUILD) || defined(R47_TEST_BRIDGE)
// A harness reads and sets core state the only way the app may: on the calculator
// thread, at rest. The first form waits for it, the second does not.
void r47_run_at_rest(void (*fn)(void *), void *arg);
void r47_run_at_rest_async(void (*fn)(void *), void *arg);
void r47_services_set_checkpoint_timing(uint32_t intervalMs, uint32_t quietMs);
#endif // HOST_TOOL_BUILD || R47_TEST_BRIDGE

// hal/keys.c: any thread.
void r47_keys_init(void);
void r47_keys_from_host(bool fromHost);
void r47_keys_wake(void);
void r47_keys_on_host_stopped(bool stopped);
void r47_key_push(int code);
void r47_key_cancel(void);
void r47_key_release_if_down(void);
void r47_key_push_quit(void);
bool r47_keys_calculator_idle(void);
bool r47_keys_pending(void);

#endif // HOST_SERVICES_H
