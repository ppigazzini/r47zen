#!/bin/bash

# Sanitized host workload regression lane. Runs the imported .p47 workload
# corpus through the same staged-core-plus-bridge host build as
# run_workload_regressions.sh, compiled under AddressSanitizer and
# UndefinedBehaviorSanitizer, so the richest behavioral corpus in the repo
# executes against a memory and undefined-behavior adversary instead of only the
# graph-solver crash path.
#
# AddressSanitizer is the hard gate: a memory error aborts the fixture process,
# which the inner lane reports as a fixture failure and the run propagates as a
# non-zero exit. UndefinedBehaviorSanitizer runs alongside in recoverable report
# mode (no -fno-sanitize-recover; halt_on_error=0) because the upstream-owned C47
# core trips portable-but-UB constructs this repo cannot fix in src/ (negative
# left shifts in screen.c, byte-offset casts to aligned structs in manage.c);
# UBSan surfaces every such finding for triage without failing on un-ownable
# upstream UB. The alignment check is dropped because the core's single RAM blob
# casts unaligned offsets to aligned structs on every program scan, which is
# pure host-layout noise.
#
# This lane is host-only (no emulator) and slower than the plain lane because
# the sanitized core builds without optimization, so it carries a wider
# per-fixture timeout and its own build directory and binary name to avoid
# clobbering the plain lane. It reuses the inner lane's CFLAGS, timeout, and
# output-path seams rather than duplicating the staged-core build.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

export CFLAGS="${CFLAGS:+$CFLAGS }-fsanitize=address,undefined -fno-sanitize=alignment -fno-omit-frame-pointer"

# AddressSanitizer halts on the first memory error; UndefinedBehaviorSanitizer
# only prints and continues so upstream-owned core UB does not fail the lane.
# Leak detection is off: the C47 core allocates long-lived globals (mini-gmp
# pools, decNumber contexts) it never frees, so an exit-time leak census would
# fail every fixture on intentional never-freed globals. This lane gates on hard
# memory-safety violations (use-after-free, overflow, bad free) and surfaces UB;
# the one leak that matters, the graph free-list overflow, is reproduced by the
# dedicated build_graph_crash_harness.sh.
export ASAN_OPTIONS="${ASAN_OPTIONS:-abort_on_error=1:halt_on_error=1:detect_leaks=0}"
export UBSAN_OPTIONS="${UBSAN_OPTIONS:-print_stacktrace=1:halt_on_error=0}"

# Sanitized fixtures run several times slower than the plain lane. Two timeouts
# bound each fixture and both must be widened so a healthy workload is not killed
# before it finishes:
#   - the harness-internal per-program deadline (the deterministic-completion
#     budget; a non-deterministic workload such as SPIRALk that exceeds it fails
#     the lane), scaled up through the harness's own
#     HOST_WORKLOAD_PROGRAM_TIMEOUT_SCALE knob to absorb instrumentation overhead;
#   - the outer wall-clock timeout that kills a genuinely hung process (kept
#     above the largest scaled internal budget so the internal deadline, not the
#     kill, governs a slow-but-live workload).
# A genuine hang still degrades to coverage through the outer timeout net, while
# an ASan abort fails the lane.
export HOST_WORKLOAD_PROGRAM_TIMEOUT_SCALE="${HOST_WORKLOAD_PROGRAM_TIMEOUT_SCALE:-6}"
export HOST_WORKLOAD_FIXTURE_TIMEOUT="${HOST_WORKLOAD_FIXTURE_TIMEOUT:-180s}"
export HOST_WORKLOAD_FIXTURE_KILL_AFTER="${HOST_WORKLOAD_FIXTURE_KILL_AFTER:-15s}"
export HOST_WORKLOAD_BUILD_DIR="${HOST_WORKLOAD_BUILD_DIR:-$PROJECT_ROOT/android/build/workload-regressions-host-sanitized}"
export HOST_WORKLOAD_OUTPUT_NAME="${HOST_WORKLOAD_OUTPUT_NAME:-r47-workload-regression-sanitized}"

exec bash "$SCRIPT_DIR/run_workload_regressions.sh" "$@"
