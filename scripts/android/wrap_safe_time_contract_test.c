// Host contract test for the wrap-safe millisecond deadline helpers in
// android/app/src/main/cpp/r47zen/r47_time.h.
//
// sys_current_ms() truncates CLOCK_MONOTONIC to uint32_t, so the native
// scheduler clock wraps every ~49.7 days of device awake time. Raw unsigned
// comparisons against that clock stall every pending deadline for the rest of
// the wrap period the moment "now" wraps past a not-yet-due deadline. The
// helpers under test encode the wrap-safe semantics (unsigned difference
// against the half range) plus the codebase-wide "deadline 0 means unset /
// due immediately" sentinel; this test pins both, including the exact
// across-the-wrap cases the raw comparisons get wrong.
//
// Run via scripts/android/run_wrap_safe_time_contract.sh (no SDK, no staged
// native tree required).

#include "r47_time.h"

#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>

static int failures = 0;

static void expect_bool(const char *label, bool actual, bool expected) {
  if (actual != expected) {
    fprintf(stderr, "FAIL: %s: got %s, want %s\n", label,
            actual ? "true" : "false", expected ? "true" : "false");
    failures++;
  }
}

static void expect_u32(const char *label, uint32_t actual, uint32_t expected) {
  if (actual != expected) {
    fprintf(stderr, "FAIL: %s: got %" PRIu32 ", want %" PRIu32 "\n", label,
            actual, expected);
    failures++;
  }
}

int main(void) {
  // r47_ms_deadline_reached: plain ordering.
  expect_bool("reached(now past deadline)",
              r47_ms_deadline_reached(100u, 50u), true);
  expect_bool("reached(deadline in future)",
              r47_ms_deadline_reached(50u, 100u), false);
  expect_bool("reached(now equals deadline)",
              r47_ms_deadline_reached(50u, 50u), true);

  // r47_ms_deadline_reached: across the uint32 wrap. A deadline set just
  // before the wrap must read as reached once now wraps past it (the raw
  // "deadline <= now" comparison returns false here and stalls ~49.7 days).
  expect_bool("reached(now wrapped, deadline pre-wrap)",
              r47_ms_deadline_reached(UINT32_C(0x00000010),
                                      UINT32_C(0xFFFFFFF0)),
              true);
  // ...and a deadline that lies just after the wrap must NOT read as reached
  // while now is still pre-wrap (the raw comparison fires it early).
  expect_bool("reached(now pre-wrap, deadline wrapped)",
              r47_ms_deadline_reached(UINT32_C(0xFFFFFFF0),
                                      UINT32_C(0x00000010)),
              false);

  // r47_ms_deadline_reached: the 0 sentinel means unset / due immediately,
  // regardless of where now sits in the wrap cycle.
  expect_bool("reached(sentinel, small now)",
              r47_ms_deadline_reached(1u, 0u), true);
  expect_bool("reached(sentinel, large now)",
              r47_ms_deadline_reached(UINT32_C(0xF0000000), 0u), true);

  // r47_ms_until_deadline: plain ordering.
  expect_u32("until(future deadline)", r47_ms_until_deadline(100u, 150u), 50u);
  expect_u32("until(past deadline)", r47_ms_until_deadline(150u, 100u), 0u);
  expect_u32("until(due now)", r47_ms_until_deadline(100u, 100u), 0u);

  // r47_ms_until_deadline: across the wrap. The remaining wait for a
  // deadline on the far side of the wrap is the short distance, not the
  // ~2^32 ms the raw subtraction reports.
  expect_u32("until(deadline across wrap)",
             r47_ms_until_deadline(UINT32_C(0xFFFFFFF0), UINT32_C(0x00000010)),
             0x20u);
  expect_u32("until(deadline already passed across wrap)",
             r47_ms_until_deadline(UINT32_C(0x00000010), UINT32_C(0xFFFFFFF0)),
             0u);

  // r47_ms_until_deadline: 0 sentinel is due immediately even when the raw
  // difference would be a small positive value.
  expect_u32("until(sentinel, large now)",
             r47_ms_until_deadline(UINT32_C(0xF0000000), 0u), 0u);
  expect_u32("until(sentinel, small now)", r47_ms_until_deadline(5u, 0u), 0u);

  // r47_ms_before: deadline ordering used to pick the earlier of two
  // deadlines, wrap-safe.
  expect_bool("before(plain earlier)", r47_ms_before(100u, 200u), true);
  expect_bool("before(plain later)", r47_ms_before(200u, 100u), false);
  expect_bool("before(equal)", r47_ms_before(100u, 100u), false);
  expect_bool("before(earlier across wrap)",
              r47_ms_before(UINT32_C(0xFFFFFFF0), UINT32_C(0x00000010)), true);
  expect_bool("before(later across wrap)",
              r47_ms_before(UINT32_C(0x00000010), UINT32_C(0xFFFFFFF0)),
              false);

  if (failures != 0) {
    fprintf(stderr, "FAIL: %d wrap-safe time case(s) failed\n", failures);
    return EXIT_FAILURE;
  }

  printf("OK: wrap-safe time helpers satisfy all %d contract cases.\n", 21);
  return EXIT_SUCCESS;
}
