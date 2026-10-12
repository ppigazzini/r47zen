#!/bin/bash

# Contract: a job that holds a release credential never syncs, builds,
# compiles, tests, or emulates the upstream core. The credentials are the two
# signing keys (secrets.R47_RELEASE_*, secrets.R47_PRERELEASE_*) and the Play
# upload identity (vars.R47_PLAY_*, which a job exchanges through
# google-github-actions/auth for a token that can release to Play). Those steps
# execute unreviewed upstream code, and a compile step alone can embed any
# readable file (a decoded keystore, the auth action's credentials file,
# /proc/self/environ) into the shipped library, so a credential on that runner
# is one an upstream commit can read. A credential job signs or uploads bytes
# that a credential-free job built, and nothing else. A credential in
# workflow-level env reaches every job, so it fails too.
#
# A credential job also restores no Actions cache. Any job on the branch can
# write a cache entry, and the build jobs that write the SDK cache run upstream
# code before the entry is saved, so a cached binary is one an upstream commit
# could have replaced; a signing job runs apksigner with the key on disk. The
# actions a credential job uses are therefore an allowlist, not a denylist:
# checkout, setup-java without a cache, artifact download and upload, the
# setup-signing-tools composite, and the two Play upload actions. Anything else,
# the SDK composite or actions/cache among them, fails, and so does a `cache:`
# input. The setup-signing-tools composite itself may use setup-android only.
#
# The checker first proves it can fail: every seeded fixture below must be
# flagged and the clean fixture must pass. Then it scans the real workflows.
# Its run-step half matches command names, so it states a floor, not a proof:
# a step that reaches upstream code through a name this list does not know
# passes. Keep a credential job to checkout, defaults, JDK, signing tools,
# download, verify, sign or upload, evidence, and artifact upload. Pure host
# test, no SDK needed.

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
                    printf "%s: job=%s holds a release credential and %s\n", short, job, hits[i]
                }
            }
        }
        BEGIN {
            split("actions/checkout actions/setup-java actions/download-artifact actions/upload-artifact ./.github/actions/setup-signing-tools google-github-actions/auth r0adkll/upload-google-play", names, " ")
            for (i in names) {
                allowed[names[i]] = 1
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
        /secrets\.R47_(RELEASE|PRERELEASE)_|vars\.R47_PLAY_|google-github-actions\/auth@/ {
            if (!injobs || job == "") {
                printf "%s: workflow-level release credential reaches every job at line %d: %s\n", short, FNR, $0
            } else {
                has_secret = 1
            }
        }
        injobs && job != "" && ($0 ~ /upstream\.sh|hydrate_submodules|build_android\.sh|gradlew|build_sim_assets|prepare_native_build_inputs|stage_native_sources|collect_host_pgo_profile|run_workload_regressions|run_connected_android_tests|android-emulator-runner|setup-xlsxio-toolchain|install_linux_build_deps/ ||
            $0 ~ /(^|[^[:alnum:]_.\/-])(make|meson|ninja|cmake|gcc|clang)[[:space:]]/) {
            hits[++nhits] = "runs upstream-facing work at line " FNR ": " $0
        }
        injobs && job != "" && /^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]*/ {
            action = $0
            sub(/^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]*/, "", action)
            sub(/@.*/, "", action)
            sub(/[[:space:]].*/, "", action)
            if (!(action in allowed)) {
                hits[++nhits] = "uses " action ", which is not on the credential-job allowlist, at line " FNR ": " $0
            }
        }
        injobs && job != "" && /^[[:space:]]+(enable-)?cache(-[a-z-]+)?:/ {
            hits[++nhits] = "restores an Actions cache at line " FNR ": " $0
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

cat >"$fixture_dir/play-gradle.yml" <<'EOF'
jobs:
  upload:
    steps:
      - run: ./gradlew :app:bundleRelease
      - uses: google-github-actions/auth@0000000000000000000000000000000000000000 # v3.0.0
        with:
          workload_identity_provider: ${{ vars.R47_PLAY_WIF_PROVIDER }}
EOF

cat >"$fixture_dir/play-workflow-env.yml" <<'EOF'
env:
  PROVIDER: ${{ vars.R47_PLAY_WIF_PROVIDER }}
jobs:
  build:
    steps:
      - run: echo hi
EOF

cat >"$fixture_dir/release-sdk-cache.yml" <<'EOF'
jobs:
  sign:
    steps:
      - uses: ./.github/actions/setup-android-sdk
        with:
          build-tools-version: 37.0.0
      - run: bash ./scripts/android/sign_android_artifacts.sh --keystore k
        env:
          PW: ${{ secrets.R47_RELEASE_KEY_PASSWORD }}
EOF

cat >"$fixture_dir/prerelease-cache.yml" <<'EOF'
jobs:
  sign:
    steps:
      - uses: actions/cache/restore@0000000000000000000000000000000000000000 # v6.1.0
        with:
          key: sdk
      - run: echo sign
        env:
          KS: ${{ secrets.R47_PRERELEASE_STORE_FILE_BASE64 }}
EOF

cat >"$fixture_dir/release-java-cache.yml" <<'EOF'
jobs:
  sign:
    steps:
      - uses: actions/setup-java@0000000000000000000000000000000000000000 # v6.0.1
        with:
          distribution: temurin
          cache: gradle
      - run: echo sign
        env:
          PW: ${{ secrets.R47_RELEASE_STORE_PASSWORD }}
EOF

cat >"$fixture_dir/clean.yml" <<'EOF'
jobs:
  build:
    steps:
      - run: ./gradlew :app:bundleRelease
      - run: make sim
      - uses: ./.github/actions/setup-android-sdk
      - uses: actions/cache@0000000000000000000000000000000000000000 # v6.1.0
  sign:
    steps:
      # A comment that names ./gradlew, make sim, or actions/cache runs nothing.
      - uses: actions/checkout@0000000000000000000000000000000000000000 # v7.0.1
      - uses: actions/setup-java@0000000000000000000000000000000000000000 # v6.0.1
        with:
          distribution: temurin
      - uses: ./.github/actions/setup-signing-tools
        with:
          build-tools-version: 37.0.0
      - run: bash ./scripts/android/sign_android_artifacts.sh --keystore k
        env:
          PW: ${{ secrets.R47_RELEASE_KEY_PASSWORD }}
  upload:
    steps:
      - run: gh attestation verify release-aab/app.aab --repo o/r
      - uses: google-github-actions/auth@0000000000000000000000000000000000000000 # v3.0.0
        with:
          workload_identity_provider: ${{ vars.R47_PLAY_WIF_PROVIDER }}
EOF

for fixture in release-gradle prerelease-sync release-make workflow-env play-gradle play-workflow-env \
    release-sdk-cache prerelease-cache release-java-cache; do
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
        "a job that holds a release credential also builds or runs upstream code, or uses an action off the allowlist:" \
        "$violations"
fi

# The allowlist admits setup-signing-tools by name, so hold its body too: it
# may call setup-android and nothing else, and setup-android restores no cache.
signing_tools="$PROJECT_ROOT/.github/actions/setup-signing-tools/action.yml"
[ -f "$signing_tools" ] || contract_fail "missing the setup-signing-tools composite: $signing_tools"
signing_tools_uses="$(strip_hash_comments <"$signing_tools" | grep -E '^[[:space:]]*(-[[:space:]]+)?uses:' || true)"
[ -n "$signing_tools_uses" ] || contract_fail "setup-signing-tools uses no action; the scan read nothing."
stray_uses="$(grep -vE 'uses:[[:space:]]*android-actions/setup-android@[0-9a-f]{40}([[:space:]]|$)' <<<"$signing_tools_uses" || true)"
[ -z "$stray_uses" ] ||
    contract_fail "setup-signing-tools may use the SHA-pinned android-actions/setup-android only:" "$stray_uses"

contract_pass "no job that holds a release credential builds or runs upstream code or restores a cache (${#workflows[@]} workflows)."
