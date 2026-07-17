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
# memory-safety violations (use-after-free, overflow, bad free) and surfaces UB.
# The dedicated build_graph_crash_harness.sh guards against the graph free-list
# overflow.
export ASAN_OPTIONS="${ASAN_OPTIONS:-abort_on_error=1:halt_on_error=1:detect_leaks=0}"
export UBSAN_OPTIONS="${UBSAN_OPTIONS:-print_stacktrace=1:halt_on_error=0}"

# Two timeouts bound each fixture, and the sanitized lane deliberately orders
# them so the OUTER wall-clock timeout fires first:
#   - the harness-internal per-program deadline maps a non-deterministic
#     overrun to a FATAL exit, so it is scaled well above the outer bound
#     (HOST_WORKLOAD_PROGRAM_TIMEOUT_SCALE) and never governs here;
#   - the outer wall-clock timeout (HOST_WORKLOAD_FIXTURE_TIMEOUT) kills an
#     overrunning process and the inner lane records it as DEGRADED coverage
#     (exit 124/137), not a failure.
# This matters because the imported workloads split into two classes under
# sanitizers: the arithmetic and stop-probe fixtures (BinetV4, GudrmPL, MANSLV2,
# NQueens) finish in seconds and must complete, while SPIRALk -- a dense,
# non-deterministic spiral plot -- is pathologically slow under AddressSanitizer's
# per-access shadow checks on its tight pixel loop (it did not finish in six
# minutes). Rather than block the lane, SPIRALk is bounded by the outer timeout
# and degrades to coverage; its rendering is still exercised under ASan by
# build_graph_crash_harness.sh and runs to completion in the plain workload lane,
# and the UBSan findings the corpus surfaces come from the fast fixtures too.
# An ASan memory error still aborts (exit 134) and fails the lane.
# Tolerate the outer wall-clock timeout (exit 124/137) as degraded coverage for
# SPIRALk ONLY -- its ASan slowness is expected -- so a genuine hang in any other
# sanitized fixture (BinetV4, GudrmPL, MANSLV2, NQueens) still fails the lane. A
# real sanitizer fault (ASan abort, exit 134), an oracle mismatch, or a
# stop-plumbing failure (exit 3) also still gates. Scoping the tolerance to the
# named fixture is what keeps this from being a blanket "ignore every timeout".
export HOST_WORKLOAD_TOLERATE_TIMEOUT_FIXTURES="${HOST_WORKLOAD_TOLERATE_TIMEOUT_FIXTURES:-SPIRALk.p47}"
export HOST_WORKLOAD_PROGRAM_TIMEOUT_SCALE="${HOST_WORKLOAD_PROGRAM_TIMEOUT_SCALE:-20}"
export HOST_WORKLOAD_FIXTURE_TIMEOUT="${HOST_WORKLOAD_FIXTURE_TIMEOUT:-90s}"
export HOST_WORKLOAD_FIXTURE_KILL_AFTER="${HOST_WORKLOAD_FIXTURE_KILL_AFTER:-15s}"
export HOST_WORKLOAD_BUILD_DIR="${HOST_WORKLOAD_BUILD_DIR:-$PROJECT_ROOT/android/build/workload-regressions-host-sanitized}"
export HOST_WORKLOAD_OUTPUT_NAME="${HOST_WORKLOAD_OUTPUT_NAME:-r47-workload-regression-sanitized}"

exec bash "$SCRIPT_DIR/run_workload_regressions.sh" "$@"
