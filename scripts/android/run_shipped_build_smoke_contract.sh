#!/bin/bash
# Contract: the release lane and android-ci's dev lane each build the library
# they ship with the PGO profile they collect, run their device tests on that
# PGO-compiled native code, and launch a build in the shipped shape.
#
# run_shipped_build_smoke.sh is run against a fake adb that answers as an
# emulator does: a launch that stays up passes, and so does a crash buffer
# naming only another package; a Java exception or a native tombstone naming
# the package, a process that died or restarted, a refused install, and a
# launch that does not report Status: ok each fail.
#
# Then each lane's wiring, build-production-release-bundle and
# verify-production-release in android-release.yml, android-build-test-package
# and android-tests in android-ci.yml: the build job builds through
# build_android.sh --collect-host-pgo, which builds with the profile it
# collected, and no step of it names a profile of its own; it outputs the
# profile's artifact id; the test job needs the build job, downloads that
# profile by id, and points R47_PGO_PROFILE_PATH at it for the whole job, so the
# instrumented build and the connected tests compile with it; it builds the
# smoke APK with no R8 or test-bridge override, and its emulator step runs the
# smoke. Each wiring check also fails a seeded copy of each lane. Pure host test.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
SMOKE="$SCRIPT_DIR/run_shipped_build_smoke.sh"
PACKAGE='io.github.ppigazzini.r47zen'

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin"
: >"$work/app.apk"

# The fake adb reads FAKE_ADB_MODE; the pid it reports moves to 4242 on the
# second pidof only when the mode says the app restarted.
cat >"$work/bin/adb" <<'FAKE'
#!/bin/bash
mode="${FAKE_ADB_MODE:-ok}"
state="$FAKE_ADB_STATE"
[[ "$1" == "-s" ]] && shift 2
case "$1" in
    devices) printf 'List of devices attached\nemulator-5554\tdevice\n' ;;
    uninstall) exit 0 ;;
    install) [[ "$mode" == install-refused ]] && { echo 'Failure [INSTALL_FAILED_NO_MATCHING_ABIS]'; exit 1; } ; echo Success ;;
    logcat) ;;
    shell)
        shift
        case "$*" in
            "cmd package resolve-activity"*)
                printf 'priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\r\n'
                printf '%s/.MainActivity\r\n' "${@: -1}" ;;
            "am start"*)
                [[ "$mode" == launch-error ]] && { printf 'Status: timeout\r\n'; exit 0; }
                printf 'Starting: Intent { cmp=%s }\r\nStatus: ok\r\nLaunchState: COLD\r\nTotalTime: 812\r\n' "$4" ;;
            "pidof "*)
                calls=$(( $(cat "$state" 2>/dev/null || echo 0) + 1 )); echo "$calls" >"$state"
                if (( calls > 1 )); then
                    case "$mode" in died) exit 1 ;; restarted) printf '4242\r\n'; exit 0 ;; esac
                fi
                printf '3131\r\n' ;;
            input*) ;;
            "logcat -b crash -d")
                echo '--------- beginning of crash'
                case "$mode" in
                    java-crash)
                        echo '11-09 10:45:12.345  3131  3131 E AndroidRuntime: FATAL EXCEPTION: main'
                        echo '11-09 10:45:12.345  3131  3131 E AndroidRuntime: Process: io.github.ppigazzini.r47zen, PID: 3131' ;;
                    native-crash)
                        echo '11-09 10:45:12.999  5678  5678 F DEBUG   : pid: 3131, tid: 3140, name: r47-core  >>> io.github.ppigazzini.r47zen <<<' ;;
                    foreign-crash)
                        echo '11-09 10:40:01.000   901   901 E AndroidRuntime: Process: com.android.systemui, PID: 901' ;;
                esac ;;
        esac ;;
esac
FAKE
chmod +x "$work/bin/adb"

smoke() {
    FAKE_ADB_MODE="$1" FAKE_ADB_STATE="$work/state-$1" PATH="$work/bin:$PATH" \
        R47_SHIPPED_SMOKE_SETTLE_SECONDS=0 \
        bash "$SMOKE" --apk "$work/app.apk" --package "$PACKAGE" >"$work/out-$1" 2>&1
}

for mode in ok foreign-crash; do
    smoke "$mode" || contract_fail "the smoke failed a healthy launch ($mode):" "$(cat "$work/out-$mode")"
done
grep -q "as pid 3131" "$work/out-ok" ||
    contract_fail "the smoke's pass line does not name the pid it checked:" "$(cat "$work/out-ok")"
for mode in java-crash native-crash died restarted install-refused launch-error; do
    if smoke "$mode"; then
        contract_fail "the smoke passed a launch that should fail ($mode):" "$(cat "$work/out-$mode")"
    fi
done

# Each lane's wiring, live lines only.
release_live="$(strip_hash_comments <"$WORKFLOW_DIR/android-release.yml")"
dev_live="$(strip_hash_comments <"$WORKFLOW_DIR/android-ci.yml")"
# shellcheck disable=SC2016 # the workflow's own ${{ }} expressions, matched unexpanded
wiring_problems() {
    local live="$1" build_job="$2" test_job="$3" test build
    test="$(workflow_job_block /dev/stdin "$test_job" <<<"$live")"
    build="$(workflow_job_block /dev/stdin "$build_job" <<<"$live")"
    grep -q -- '--collect-host-pgo' <<<"$build" ||
        echo "$build_job does not build through build_android.sh --collect-host-pgo"
    grep -Eq 'pgoProfilePath|R47_PGO_PROFILE_PATH|validate-release-pgo' <<<"$build" &&
        echo "$build_job names a PGO profile beside the one build_android.sh collects and builds with"
    grep -q 'pgo_profile_artifact_id: \${{ steps.upload-pgo-profile.outputs.artifact-id }}' <<<"$build" ||
        echo "$build_job does not output its PGO profile's artifact id"
    grep -Eq "^      - $build_job\$" <<<"$test" ||
        echo "$test_job does not need the build job"
    grep -q "artifact-ids: \${{ needs.$build_job.outputs.pgo_profile_artifact_id }}" <<<"$test" ||
        echo "$test_job does not download the build job's PGO profile by id"
    grep -Eq '^      R47_PGO_PROFILE_PATH: ' <<<"$test" ||
        echo "$test_job does not set R47_PGO_PROFILE_PATH for the whole job"
    if grep -Eq 'pgoProfilePath|R47_PGO_PROFILE_PATH: *$|releaseMinify=true' <<<"$(grep -v '^      R47_PGO_PROFILE_PATH: ' <<<"$test")"; then
        echo "$test_job overrides the job's PGO profile in a step"
    fi
    grep -q 'run_shipped_build_smoke.sh --apk' <<<"$test" ||
        echo "$test_job's emulator step does not run the shipped-shape smoke"
    local smoke_build
    smoke_build="$(awk '/^      - name: Build the shipped-shape release for the launch smoke$/ { on = 1; print; next }
        on && /^      - name:/ { exit } on { print }' <<<"$test")"
    [[ -n "$smoke_build" ]] || echo "$test_job does not build the smoke APK"
    grep -q ':app:assembleRelease' <<<"$smoke_build" || echo "the smoke build does not assemble the release"
    grep -Eq 'releaseMinify|releaseShrinkResources|includeProgramLoadTestBridge=true|includeInstrumentationHost' <<<"$smoke_build" &&
        echo "the smoke build changes what ships: R8, shrinking, the test bridge, or the instrumentation host"
    return 0
}
check_lane() {
    local name="$1" live="$2" build_job="$3" test_job="$4" problems
    problems="$(wiring_problems "$live" "$build_job" "$test_job")"
    [[ -z "$problems" ]] || contract_fail "the $name lane does not run what it ships:" "$problems"
    seed() {
        local seed_name="$1" from="$2" to="$3" seeded
        seeded="$(sed "s|$from|$to|" <<<"$live")"
        [[ "$seeded" != "$live" ]] || contract_fail "$name seed '$seed_name' changed nothing; the contract is stale"
        [[ -n "$(wiring_problems "$seeded" "$build_job" "$test_job")" ]] ||
            contract_fail "the wiring check passed $name seed '$seed_name'"
    }
    seed 'no collection' '--collect-host-pgo' '--run-sim-tests'
    seed 'second profile' '--collect-host-pgo' '--collect-host-pgo -Pr47.pgoProfilePath=x'
    seed 'no needs' "^      - $build_job\$" '      - resolve-upstream-core'
    seed 'no profile' '^      R47_PGO_PROFILE_PATH: ' '      R47_UNUSED_PROFILE_PATH: '
    seed 'step override' '-Pr47.includeInstrumentationHost=true' '-Pr47.includeInstrumentationHost=true -Pr47.pgoProfilePath='
    seed 'no smoke' 'run_shipped_build_smoke.sh --apk' 'true --apk'
    seed 'minified off' ':app:assembleRelease \\$' ":app:assembleRelease -Pr47.releaseMinify=false \\\\"
}
check_lane release "$release_live" build-production-release-bundle verify-production-release
check_lane dev "$dev_live" android-build-test-package android-tests
contract_pass "the release and dev lanes build with the PGO profile they collect, run their device tests on it, and launch a shipped-shape build."
