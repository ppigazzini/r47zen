#!/bin/bash

# Contract: a job that reads a signing secret (secrets.R47_RELEASE_* or
# secrets.R47_PRERELEASE_*) never syncs, builds, compiles, tests, or emulates
# the upstream core. Those steps execute unreviewed upstream code, and a compile
# step alone can embed any readable file (a decoded keystore,
# /proc/self/environ) into the shipped library, so a key on that runner is a
# key an upstream commit can read. A signing job signs bytes that a
# secret-free job built, and nothing else. A secret in workflow-level env
# reaches every job, so it fails too.
#
# The checker first proves it can fail: every seeded fixture below must be
# flagged and the clean fixture must pass. Then it scans the real workflows.
# It matches command names, so it states a floor, not a proof: a step that
# reaches upstream code through a name this list does not know passes. Keep a
# signing job to checkout, defaults, JDK, SDK, download, sign, evidence, and
# upload. Pure host test, no SDK needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

# Print one line per violation in the given workflow files; print nothing when
# every secret-holding job is clean.
find_signing_isolation_violations() {
    awk '
        function report(    i) {
            if (job != "" && has_secret) {
                for (i = 1; i <= nhits; i++) {
                    printf "%s: job=%s holds a signing secret and runs upstream-facing work at line %s\n", short, job, hits[i]
                }
            }
        }
        FNR == 1 {
            if (NR > 1) {
                report()
            }
            short = FILENAME
            sub(/.*\//, "", short)
            injobs = 0
            job = ""
            has_secret = 0
            nhits = 0
        }
        /^[[:space:]]*#/ { next }
        /^jobs:/ {
            injobs = 1
            next
        }
        injobs && /^  [A-Za-z0-9_-]+:/ {
            report()
            job = $1
            sub(/:$/, "", job)
            has_secret = 0
            nhits = 0
            next
        }
        /secrets\.R47_(RELEASE|PRERELEASE)_/ {
            if (!injobs || job == "") {
                printf "%s: workflow-level signing secret reaches every job at line %d: %s\n", short, FNR, $0
            } else {
                has_secret = 1
            }
        }
        injobs && job != "" && ($0 ~ /upstream\.sh|hydrate_submodules|build_android\.sh|gradlew|build_sim_assets|prepare_native_build_inputs|stage_native_sources|collect_host_pgo_profile|run_workload_regressions|run_connected_android_tests|android-emulator-runner|setup-xlsxio-toolchain|install_linux_build_deps/ ||
            $0 ~ /(^|[^[:alnum:]_.\/-])(make|meson|ninja|cmake|gcc|clang)[[:space:]]/) {
            hits[++nhits] = FNR ": " $0
        }
        END { report() }
    ' "$@"
}

fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT

cat >"$fixture_dir/release-gradle.yml" <<'EOF'
jobs:
  sign:
    steps:
      - run: ./gradlew :app:bundleRelease
        env:
          PW: ${{ secrets.R47_RELEASE_KEY_PASSWORD }}
EOF

cat >"$fixture_dir/prerelease-sync.yml" <<'EOF'
jobs:
  sign:
    steps:
      - run: bash ./scripts/upstream-sync/upstream.sh sync --auto
      - run: echo sign
        env:
          KS: ${{ secrets.R47_PRERELEASE_STORE_FILE_BASE64 }}
EOF

cat >"$fixture_dir/release-make.yml" <<'EOF'
jobs:
  sign:
    env:
      PW: ${{ secrets.R47_RELEASE_STORE_PASSWORD }}
    steps:
      - run: make sim
EOF

cat >"$fixture_dir/workflow-env.yml" <<'EOF'
env:
  PW: ${{ secrets.R47_RELEASE_STORE_PASSWORD }}
jobs:
  build:
    steps:
      - run: echo hi
EOF

cat >"$fixture_dir/clean.yml" <<'EOF'
jobs:
  build:
    steps:
      - run: ./gradlew :app:bundleRelease
      - run: make sim
  sign:
    steps:
      # A comment that names ./gradlew or make sim runs nothing.
      - uses: ./.github/actions/setup-android-sdk
        with:
          cmake-version: 4.1.2
      - run: bash ./scripts/android/sign_android_artifacts.sh --keystore k
        env:
          PW: ${{ secrets.R47_RELEASE_KEY_PASSWORD }}
EOF

for fixture in release-gradle prerelease-sync release-make workflow-env; do
    if [ -z "$(find_signing_isolation_violations "$fixture_dir/$fixture.yml")" ]; then
        contract_fail "signing isolation checker missed the seeded violation in fixture $fixture.yml."
    fi
done
clean_report="$(find_signing_isolation_violations "$fixture_dir/clean.yml")"
if [ -n "$clean_report" ]; then
    contract_fail "signing isolation checker flagged the clean fixture:" "$clean_report"
fi

shopt -s nullglob
workflows=("$WORKFLOW_DIR"/*.yml)
shopt -u nullglob
[ "${#workflows[@]}" -gt 0 ] || contract_fail "no workflow files found under $WORKFLOW_DIR."

violations="$(find_signing_isolation_violations "${workflows[@]}")"
if [ -n "$violations" ]; then
    contract_fail \
        "a job that holds a signing secret also builds or runs upstream code:" \
        "$violations"
fi

contract_pass "no job that holds a signing secret builds or runs upstream code (${#workflows[@]} workflows)."
