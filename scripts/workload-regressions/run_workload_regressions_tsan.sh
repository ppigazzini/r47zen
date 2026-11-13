#!/bin/bash
# ThreadSanitizer host workload lane. Runs the imported .p47 workload corpus through
# the same staged-core-plus-bridge host build as run_workload_regressions.sh, under
# ThreadSanitizer. The harness's worker runs each program as the calculator thread
# while the main thread watches it and stops it with keys, the way the app's threads
# meet, so a data race between them, in the glue or in the harness, fails the fixture
# and the run. The watcher reads only the state the worker publishes through
# r47_keys_set_poll_hook, so the harness itself is race-free by construction.
#
# SPIRALk's dense plot is slow under ThreadSanitizer, as under AddressSanitizer in
# run_workload_regressions_sanitized.sh, so the outer timeout bounds it and records
# degraded coverage for that fixture alone; a race, a wrong result, or a hang in any
# other fixture still fails the lane.
set -Eeuo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
export CFLAGS="${CFLAGS:+$CFLAGS }-fsanitize=thread -g -O1 -fno-omit-frame-pointer"
export LDFLAGS="${LDFLAGS:+$LDFLAGS }-fsanitize=thread"
export TSAN_OPTIONS="${TSAN_OPTIONS:-halt_on_error=1:exitcode=66:report_signal_unsafe=0}"
export HOST_WORKLOAD_TOLERATE_TIMEOUT_FIXTURES="${HOST_WORKLOAD_TOLERATE_TIMEOUT_FIXTURES:-SPIRALk.p47}"
export HOST_WORKLOAD_PROGRAM_TIMEOUT_SCALE="${HOST_WORKLOAD_PROGRAM_TIMEOUT_SCALE:-20}"
export HOST_WORKLOAD_FIXTURE_TIMEOUT="${HOST_WORKLOAD_FIXTURE_TIMEOUT:-90s}"
export HOST_WORKLOAD_FIXTURE_KILL_AFTER="${HOST_WORKLOAD_FIXTURE_KILL_AFTER:-15s}"
export HOST_WORKLOAD_BUILD_DIR="${HOST_WORKLOAD_BUILD_DIR:-$PROJECT_ROOT/android/build/workload-regressions-host-tsan}"
export HOST_WORKLOAD_OUTPUT_NAME="${HOST_WORKLOAD_OUTPUT_NAME:-r47-workload-regression-tsan}"
# ThreadSanitizer maps its shadow at fixed addresses, which the address-space
# randomization of recent kernels can take, as build_bridge_tsan_harness.sh notes.
if command -v setarch >/dev/null 2>&1 && setarch -R true >/dev/null 2>&1; then
    exec setarch -R bash "$SCRIPT_DIR/run_workload_regressions.sh" "$@"
fi
exec bash "$SCRIPT_DIR/run_workload_regressions.sh" "$@"
