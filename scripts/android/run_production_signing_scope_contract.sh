#!/bin/bash

# Contract: each signing key is named by exactly one job. The production key
# (secrets.R47_RELEASE_STORE_FILE_BASE64 and friends) belongs to
# sign-production-release in android-release.yml; the prerelease key
# (secrets.R47_PRERELEASE_*) belongs to sign-dev-prerelease in android-ci.yml.
# Every other job, the emulator lanes included, signs test installs with a
# throwaway key, so each real key is materialized in one place.
# run_signing_isolation_contract.sh owns what those two jobs may run.
#
# The checker first proves it can fail on seeded fixtures, then scans every
# workflow and composite action and requires each key to appear at least once,
# so a scan that silently reads nothing cannot pass. Pure host test, no SDK
# needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

# Print "violation: ..." for a key named outside its owning job and
# "seen: <key>" once per key named by its owner.
scan_signing_scope() {
    awk '
        FNR == 1 {
            injobs = 0
            job = ""
            short = FILENAME
            sub(/.*\//, "", short)
        }
        /^[[:space:]]*#/ { next }
        /^jobs:/ { injobs = 1 }
        injobs && /^  [A-Za-z0-9_-]+:/ {
            job = $1
            sub(/:$/, "", job)
        }
        /secrets\.R47_RELEASE_/ {
            if (short == "android-release.yml" && job == "sign-production-release") {
                seen_release = 1
            } else {
                printf "violation: %s:%d: job=%s: %s\n", short, FNR, job, $0
            }
        }
        /secrets\.R47_PRERELEASE_/ {
            if (short == "android-ci.yml" && job == "sign-dev-prerelease") {
                seen_prerelease = 1
            } else {
                printf "violation: %s:%d: job=%s: %s\n", short, FNR, job, $0
            }
        }
        END {
            if (seen_release) {
                print "seen: release"
            }
            if (seen_prerelease) {
                print "seen: prerelease"
            }
        }
    ' "$@"
}

fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
mkdir -p "$fixture_dir/stray" "$fixture_dir/owned"

cat >"$fixture_dir/stray/android-release.yml" <<'EOF'
jobs:
  verify-production-release:
    steps:
      - env:
          PW: ${{ secrets.R47_RELEASE_STORE_PASSWORD }}
EOF

cat >"$fixture_dir/stray/android-ci.yml" <<'EOF'
jobs:
  android-tests:
    steps:
      - env:
          PW: ${{ secrets.R47_PRERELEASE_KEY_PASSWORD }}
EOF

cat >"$fixture_dir/owned/android-release.yml" <<'EOF'
jobs:
  sign-production-release:
    steps:
      # secrets.R47_PRERELEASE_KEY_PASSWORD in a comment names nothing.
      - env:
          PW: ${{ secrets.R47_RELEASE_STORE_PASSWORD }}
EOF

cat >"$fixture_dir/owned/android-ci.yml" <<'EOF'
jobs:
  sign-dev-prerelease:
    steps:
      - env:
          PW: ${{ secrets.R47_PRERELEASE_KEY_PASSWORD }}
EOF

for stray in android-release android-ci; do
    if ! scan_signing_scope "$fixture_dir/stray/$stray.yml" | grep -q '^violation: '; then
        contract_fail "signing scope checker missed the seeded stray secret in fixture $stray.yml."
    fi
done
owned_report="$(scan_signing_scope "$fixture_dir/owned/android-release.yml" "$fixture_dir/owned/android-ci.yml")"
if printf '%s\n' "$owned_report" | grep -q '^violation: '; then
    contract_fail "signing scope checker flagged the owning jobs:" "$owned_report"
fi

shopt -s nullglob
scanned=("$WORKFLOW_DIR"/*.yml "$PROJECT_ROOT"/.github/actions/*/action.yml)
shopt -u nullglob
[ "${#scanned[@]}" -gt 0 ] || contract_fail "no workflow or composite action files found."

report="$(scan_signing_scope "${scanned[@]}")"
violations="$(printf '%s\n' "$report" | sed -n 's/^violation: //p')"
if [ -n "$violations" ]; then
    contract_fail "a signing secret is named outside the job that owns it:" "$violations"
fi
for key in release prerelease; do
    printf '%s\n' "$report" | grep -qx "seen: $key" ||
        contract_fail "no owning job names the $key signing secrets; the scan read nothing, or the owning job was renamed without this contract."
done

contract_pass "each signing key is named only by its owning job (${#scanned[@]} files scanned)."
