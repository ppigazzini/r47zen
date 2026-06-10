#ifndef R47_TIME_H
#define R47_TIME_H

// Wrap-safe millisecond deadline helpers for the Android-native scheduler.
//
// sys_current_ms() truncates CLOCK_MONOTONIC to uint32_t, so the scheduler
// clock wraps every ~49.7 days of device awake time (the clock is
// boot-relative, not app-relative). Raw relational comparisons between that
// clock and a deadline misread the wrap: a deadline set just before the wrap
// compares as ~49.7 days in the future the moment now wraps past it, stalling
// every timer and display refresh for the rest of the session. These helpers
// compare via the unsigned difference against the half range, which is exact
// as long as a deadline is never scheduled more than ~24.8 days out (the
// scheduler horizon is 5/100 ms).
//
// Sentinel: a deadline of 0 means "unset / due immediately" everywhere this
// clock is used, and the helpers preserve that. A genuinely wrapped clock
// value of 0 colliding with the sentinel fires one deadline early once per
// wrap cycle, which is harmless for refresh scheduling.
//
// Guarded by scripts/android/run_wrap_safe_time_contract.sh, which pins the
// across-the-wrap semantics and rejects raw deadline comparisons in the
// deadline-bearing sources.

#include <stdbool.h>
#include <stdint.h>

#define R47_MS_HALF_RANGE UINT32_C(0x80000000)

// True when now_ms has reached or passed deadline_ms (or the deadline is the
// 0 "due immediately" sentinel).
static inline bool r47_ms_deadline_reached(uint32_t now_ms,
                                           uint32_t deadline_ms) {
  if (deadline_ms == 0u) {
    return true;
  }
  return (uint32_t)(now_ms - deadline_ms) < R47_MS_HALF_RANGE;
}

// Milliseconds remaining until deadline_ms, 0 when the deadline is reached,
// already past, or the 0 sentinel.
static inline uint32_t r47_ms_until_deadline(uint32_t now_ms,
                                             uint32_t deadline_ms) {
  uint32_t remaining = deadline_ms - now_ms;
  if (deadline_ms == 0u || remaining == 0u ||
      remaining >= R47_MS_HALF_RANGE) {
    return 0u;
  }
  return remaining;
}

// True when deadline a_ms is strictly earlier than deadline b_ms, wrap-safe.
// The 0 sentinel is not special here; callers guard it explicitly when
// picking among optional deadlines.
static inline bool r47_ms_before(uint32_t a_ms, uint32_t b_ms) {
  return a_ms != b_ms && (uint32_t)(a_ms - b_ms) >= R47_MS_HALF_RANGE;
}

#endif // R47_TIME_H
