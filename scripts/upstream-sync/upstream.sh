#!/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=scripts/lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"
# The config paths default to the repo root but may be redirected for the
# resolver-policy test harness (scripts/upstream-sync/test_resolver_policy.sh),
# which needs to drive synthetic upstream.source/upstream.lock files without
# touching the real tracked ones. Production callers leave these unset.
SOURCE_CONFIG_PATH="${R47_UPSTREAM_SOURCE_FILE:-$PROJECT_ROOT/upstream.source}"
LOCKFILE_PATH="${R47_UPSTREAM_LOCK_FILE:-$PROJECT_ROOT/upstream.lock}"
DEFAULT_UPSTREAM_URL="https://gitlab.com/rpncalculators/c43.git"
DEFAULT_UPSTREAM_REF="HEAD"
UPSTREAM_REMOTE_NAME="r47-c43-upstream"
UPSTREAM_FONT_FILES=(
    "C47__NumericFont.ttf"
    "C47__StandardFont.ttf"
    "C47__TinyFont.ttf"
    "sortingOrder.xlsx"
)
UPSTREAM_REQUIRED_DIRS=(
    "src/c47"
    "dep/decNumberICU"
)
UPSTREAM_REQUIRED_FILES=(
    "Makefile"
    "meson.build"
    "meson_options.txt"
    "src/c47/meson.build"
    "dep/meson.build"
    "dep/decNumberICU/ICU-license.html"
    "tools/onARaspberry"
    "docs/code/meson.build"
    "subprojects/gmp-6.2.1.wrap"
    "subprojects/packagefiles/gmp-6.2.1/meson.build"
)
REPO_OWNED_RESTORE_PATHS=(
    .gitignore
    COPYING
    README.md
    android/
    .github/
    __DEV/
    scripts/
    upstream.source
)

RESOLVED_UPSTREAM_URL=""
RESOLVED_UPSTREAM_REF=""
RESOLVED_UPSTREAM_COMMIT=""
RESOLVED_UPSTREAM_MODE=""

usage() {
    cat <<'EOF'
Usage:
    scripts/upstream-sync/upstream.sh resolve [--auto|--locked|--latest] [--write-lock] [--format shell|none] [--url <url>] [--ref <ref>] [--commit <sha>]
    scripts/upstream-sync/upstream.sh sync [--auto|--locked|--latest] [--write-lock] [--if-missing] [--force] [--url <url>] [--ref <ref>] [--commit <sha>]
        scripts/upstream-sync/upstream.sh verify-restore-boundary
        scripts/upstream-sync/upstream.sh verify-source-policy

Commands:
  resolve   Resolve the authoritative upstream URL/ref/commit through the shared policy.
  sync      Overlay the resolved upstream C43 tree and restore repo-owned paths.
    verify-restore-boundary  Fail when the restore allowlist would re-own upstream root surfaces.
    verify-source-policy     Fail when upstream.source pins an upstream_commit (it must track the latest HEAD).

Resolution modes:
  --auto    Use --commit first, then an optional pinned upstream_commit (upstream.lock over upstream.source), else the latest upstream_ref. Latest is the normal path. (default)
  --locked  Require an explicit --commit or an optional pinned upstream_commit in upstream.lock (or upstream.source). For roadblock reproduction only.
  --latest  Ignore any pinned upstream_commit and resolve the latest commit from upstream_ref.

Options:
  --write-lock  Refresh upstream.lock with the resolved URL/ref/commit.
    --if-missing  Skip the sync when src/c47 and res/fonts are already hydrated.
  --force       Allow syncing over a dirty tracked worktree.
  --url         Override the upstream URL.
  --ref         Override the upstream ref. Defaults to upstream.source upstream_ref or HEAD.
  --commit      Override the upstream commit.
  --format      Output format for resolve. shell emits shell-safe assignments, none is silent.

Configuration:
  upstream.source is Git-tracked and defines the upstream_url/upstream_ref defaults.
    It does not pin a commit: the project tracks the latest upstream HEAD.
  upstream.lock is Git-ignored and may OPTIONALLY pin upstream_commit to hold a
    specific revision for a roadblock (regression bisect, reproducing an old build).
EOF
}

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

require_repo_root_contract() {
    [ -f "$SOURCE_CONFIG_PATH" ] ||
        fail "Expected upstream.source at $SOURCE_CONFIG_PATH. Check repo root detection in scripts/upstream-sync/upstream.sh."
}

upstream_checkout_has_canonical_fonts() {
    local font_file=""

    for font_file in "${UPSTREAM_FONT_FILES[@]}"; do
        [ -f "$PROJECT_ROOT/res/fonts/$font_file" ] || return 1
    done

    return 0
}

upstream_checkout_has_required_paths() {
    local required_path=""

    for required_path in "${UPSTREAM_REQUIRED_DIRS[@]}"; do
        [ -d "$PROJECT_ROOT/$required_path" ] || return 1
    done

    for required_path in "${UPSTREAM_REQUIRED_FILES[@]}"; do
        [ -f "$PROJECT_ROOT/$required_path" ] || return 1
    done

    return 0
}

upstream_checkout_is_hydrated() {
    upstream_checkout_has_required_paths || return 1
    upstream_checkout_has_canonical_fonts
}

has_file_key() {
    local path="$1"
    local key="$2"

    [ -f "$path" ] && grep -q "^${key}=" "$path"
}

read_file_value() {
    local path="$1"
    local key="$2"

    has_file_key "$path" "$key" || return 1
    sed -n "s/^${key}=//p" "$path" | tail -n1
}

read_config_value() {
    local key="$1"

    if read_file_value "$LOCKFILE_PATH" "$key" 2>/dev/null; then
        return 0
    fi

    read_file_value "$SOURCE_CONFIG_PATH" "$key" 2>/dev/null
}

read_lock_value() {
    local key="$1"

    read_file_value "$LOCKFILE_PATH" "$key" 2>/dev/null
}

replace_if_different() {
    local temp_path="$1"
    local final_path="$2"

    if [ -f "$final_path" ] && cmp -s "$temp_path" "$final_path"; then
        rm -f "$temp_path"
        return 0
    fi

    mv "$temp_path" "$final_path"
}

write_lockfile() {
    local upstream_url="$1"
    local upstream_ref="$2"
    local upstream_commit="$3"
    local temp_path=""

    temp_path=$(mktemp)

    cat >"$temp_path" <<EOF
# Generated by scripts/upstream-sync/upstream.sh.
# Git ignores this file on purpose.
# Remove upstream_commit or rerun scripts/upstream-sync/upstream.sh resolve --latest --write-lock to follow the newest upstream revision.
upstream_url=$upstream_url
upstream_ref=$upstream_ref
upstream_commit=$upstream_commit
EOF

    replace_if_different "$temp_path" "$LOCKFILE_PATH"
}

resolve_latest_commit() {
    local upstream_url="$1"
    local upstream_ref="$2"
    local remote_line=""
    local upstream_commit=""

    remote_line=$(retry_with_backoff "Resolve ${upstream_ref} from ${upstream_url}" \
        git ls-remote "$upstream_url" "$upstream_ref" | head -n1)
    [ -n "$remote_line" ] || fail "Failed to resolve ${upstream_ref} from ${upstream_url}"

    upstream_commit=$(printf '%s\n' "$remote_line" | awk '{print $1}')
    [ -n "$upstream_commit" ] || fail "Failed to parse a commit from ${upstream_ref} at ${upstream_url}"

    printf '%s\n' "$upstream_commit"
}

ensure_clean_tracked_worktree() {
    git -C "$PROJECT_ROOT" diff --quiet --ignore-submodules --no-ext-diff ||
        fail "Tracked worktree changes detected. Commit, stash, or pass --force before syncing upstream."

    git -C "$PROJECT_ROOT" diff --cached --quiet --ignore-submodules --no-ext-diff ||
        fail "Staged changes detected. Commit, stash, or pass --force before syncing upstream."
}

ensure_remote() {
    local upstream_url="$1"

    if git -C "$PROJECT_ROOT" remote | grep -qx "$UPSTREAM_REMOTE_NAME"; then
        git -C "$PROJECT_ROOT" remote set-url "$UPSTREAM_REMOTE_NAME" "$upstream_url"
    else
        git -C "$PROJECT_ROOT" remote add "$UPSTREAM_REMOTE_NAME" "$upstream_url"
    fi
}

path_exists_in_head() {
    local path="$1"

    git -C "$PROJECT_ROOT" ls-tree -r --name-only HEAD -- "$path" | grep -q .
}

restore_path_reowns_upstream_root_surface() {
    local path="$1"

    case "$path" in
        Makefile | meson.build | meson_options.txt | src | src/* | dep | dep/* | docs | docs/* | res | res/* | subprojects | subprojects/* | tools | tools/*)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

verify_source_policy() {
    if has_file_key "$SOURCE_CONFIG_PATH" "upstream_commit"; then
        local pinned=""
        pinned="$(read_file_value "$SOURCE_CONFIG_PATH" "upstream_commit" 2>/dev/null || true)"
        fail "Policy violation: ${SOURCE_CONFIG_PATH} defines upstream_commit=${pinned}. The Git-tracked upstream.source must not pin a commit; the project tracks the latest upstream HEAD. Remove the upstream_commit line from upstream.source. Record a roadblock pin only in the Git-ignored upstream.lock via: scripts/upstream-sync/upstream.sh resolve --latest --write-lock"
    fi
}

verify_restore_boundary() {
    local restore_path=""

    for restore_path in "${REPO_OWNED_RESTORE_PATHS[@]}"; do
        if restore_path_reowns_upstream_root_surface "$restore_path"; then
            fail "Restore boundary drift detected: $restore_path would re-own an authoritative upstream root surface."
        fi
    done
}

restore_repo_owned_paths() {
    local tracked_restore_paths=()
    local restore_path=""

    verify_restore_boundary

    for restore_path in "${REPO_OWNED_RESTORE_PATHS[@]}"; do
        if path_exists_in_head "$restore_path"; then
            tracked_restore_paths+=("$restore_path")
        fi
    done

    if [ "${#tracked_restore_paths[@]}" -gt 0 ]; then
        git -C "$PROJECT_ROOT" checkout HEAD -- "${tracked_restore_paths[@]}" ||
            fail "Failed to restore repo-owned paths after the upstream overlay: ${tracked_restore_paths[*]}. The worktree may now hold upstream copies of repo-owned files; resolve the checkout error and rerun before building."
    fi
}

emit_resolved_output() {
    local output_format="$1"

    case "$output_format" in
        shell)
            printf 'R47_RESOLVED_UPSTREAM_URL=%q\n' "$RESOLVED_UPSTREAM_URL"
            printf 'R47_RESOLVED_UPSTREAM_REF=%q\n' "$RESOLVED_UPSTREAM_REF"
            printf 'R47_RESOLVED_UPSTREAM_COMMIT=%q\n' "$RESOLVED_UPSTREAM_COMMIT"
            printf 'R47_RESOLVED_UPSTREAM_SHORT_COMMIT=%q\n' "${RESOLVED_UPSTREAM_COMMIT:0:12}"
            printf 'R47_RESOLVED_UPSTREAM_MODE=%q\n' "$RESOLVED_UPSTREAM_MODE"
            ;;
        none) ;;
        *)
            fail "Unknown resolve output format: $output_format"
            ;;
    esac
}

resolve_upstream() {
    local resolution_mode="auto"
    local write_lock="false"
    local output_format="shell"
    local upstream_url=""
    local upstream_ref=""
    local upstream_commit=""
    local pinned_commit=""

    while [ "$#" -gt 0 ]; do
        case "$1" in
            --auto)
                resolution_mode="auto"
                ;;
            --locked)
                resolution_mode="locked"
                ;;
            --latest)
                resolution_mode="latest"
                ;;
            --write-lock)
                write_lock="true"
                ;;
            --format)
                shift
                [ "$#" -gt 0 ] || fail "Missing value for --format"
                output_format="$1"
                ;;
            --url)
                shift
                [ "$#" -gt 0 ] || fail "Missing value for --url"
                upstream_url="$1"
                ;;
            --ref)
                shift
                [ "$#" -gt 0 ] || fail "Missing value for --ref"
                upstream_ref="$1"
                ;;
            --commit)
                shift
                [ "$#" -gt 0 ] || fail "Missing value for --commit"
                upstream_commit="$1"
                ;;
            -h | --help)
                usage
                exit 0
                ;;
            *)
                fail "Unknown resolve option: $1"
                ;;
        esac
        shift
    done

    # upstream_url and upstream_ref come from the Git-tracked upstream.source, NOT
    # the Git-ignored upstream.lock. The lock legitimately pins only a commit; if
    # it also overrode the URL/ref, a provisioned dev machine would keep resolving
    # against a stale URL after upstream.source changed, with no warning.
    [ -n "$upstream_url" ] || upstream_url="$(read_file_value "$SOURCE_CONFIG_PATH" upstream_url 2>/dev/null || true)"
    [ -n "$upstream_ref" ] || upstream_ref="$(read_file_value "$SOURCE_CONFIG_PATH" upstream_ref 2>/dev/null || true)"

    [ -n "$upstream_url" ] || upstream_url="$DEFAULT_UPSTREAM_URL"
    [ -n "$upstream_ref" ] || upstream_ref="$DEFAULT_UPSTREAM_REF"

    if [ -z "$upstream_commit" ]; then
        pinned_commit="$(read_config_value upstream_commit 2>/dev/null || true)"
    fi

    case "$resolution_mode" in
        locked)
            [ -n "$upstream_commit" ] || upstream_commit="$pinned_commit"
            [ -n "$upstream_commit" ] || fail "Locked mode requires upstream_commit in ${SOURCE_CONFIG_PATH} or ${LOCKFILE_PATH}, or --commit."
            RESOLVED_UPSTREAM_MODE="locked"
            ;;
        latest)
            upstream_commit="$(resolve_latest_commit "$upstream_url" "$upstream_ref")"
            RESOLVED_UPSTREAM_MODE="latest"
            ;;
        auto)
            if [ -n "$upstream_commit" ]; then
                RESOLVED_UPSTREAM_MODE="locked"
            elif [ -n "$pinned_commit" ]; then
                upstream_commit="$pinned_commit"
                RESOLVED_UPSTREAM_MODE="locked"
            else
                upstream_commit="$(resolve_latest_commit "$upstream_url" "$upstream_ref")"
                RESOLVED_UPSTREAM_MODE="latest"
            fi
            ;;
        *)
            fail "Unknown resolution mode: $resolution_mode"
            ;;
    esac

    RESOLVED_UPSTREAM_URL="$upstream_url"
    RESOLVED_UPSTREAM_REF="$upstream_ref"
    RESOLVED_UPSTREAM_COMMIT="$upstream_commit"

    # Surface a locked-pin resolution loudly (to stderr, so it does not corrupt
    # the eval'd stdout). Every ordinary build resolves through here, so a dev
    # machine that pinned a commit in upstream.lock and forgot no longer silently
    # stops following the latest HEAD.
    if [ "$RESOLVED_UPSTREAM_MODE" = "locked" ] &&
        [ -n "$pinned_commit" ] && [ "$RESOLVED_UPSTREAM_COMMIT" = "$pinned_commit" ]; then
        echo "NOTE: resolving upstream from the pinned commit ${RESOLVED_UPSTREAM_COMMIT} in ${LOCKFILE_PATH}. Delete upstream.lock (or run 'resolve --latest --write-lock') to resume following the latest ${RESOLVED_UPSTREAM_REF}." >&2
    fi

    if [ "$write_lock" = "true" ]; then
        write_lockfile "$RESOLVED_UPSTREAM_URL" "$RESOLVED_UPSTREAM_REF" "$RESOLVED_UPSTREAM_COMMIT"
    fi

    emit_resolved_output "$output_format"
}

sync_upstream() {
    local if_missing="false"
    local force_sync="false"
    local -a resolution_args=()

    while [ "$#" -gt 0 ]; do
        case "$1" in
            --auto | --locked | --latest | --write-lock)
                resolution_args+=("$1")
                ;;
            --format)
                shift
                [ "$#" -gt 0 ] || fail "Missing value for --format"
                resolution_args+=(--format "$1")
                ;;
            --url | --ref | --commit)
                local option_name="$1"
                shift
                [ "$#" -gt 0 ] || fail "Missing value for ${option_name}"
                resolution_args+=("$option_name" "$1")
                ;;
            --if-missing)
                if_missing="true"
                ;;
            --force)
                force_sync="true"
                ;;
            -h | --help)
                usage
                exit 0
                ;;
            *)
                fail "Unknown sync option: $1"
                ;;
        esac
        shift
    done

    if [ "$if_missing" = "true" ] && upstream_checkout_is_hydrated; then
        echo "--- Upstream core and shared root build inputs already hydrated; skipping sync. ---"
        return 0
    fi

    if [ "$force_sync" != "true" ]; then
        ensure_clean_tracked_worktree
    fi

    resolve_upstream "${resolution_args[@]}" --format none
    ensure_remote "$RESOLVED_UPSTREAM_URL"

    echo "--- Fetching authoritative upstream core (${RESOLVED_UPSTREAM_MODE} @ ${RESOLVED_UPSTREAM_COMMIT}) ---"
    retry_with_backoff "Fetch upstream core ${RESOLVED_UPSTREAM_COMMIT}" \
        git -C "$PROJECT_ROOT" fetch --depth 1 "$UPSTREAM_REMOTE_NAME" "$RESOLVED_UPSTREAM_COMMIT"
    git -C "$PROJECT_ROOT" cat-file -e "$RESOLVED_UPSTREAM_COMMIT^{commit}"

    echo "--- Overlaying upstream core from $RESOLVED_UPSTREAM_COMMIT ---"
    git -C "$PROJECT_ROOT" archive --format=tar "$RESOLVED_UPSTREAM_COMMIT" | tar -C "$PROJECT_ROOT" -xf -

    echo "--- Restoring repo-owned files ---"
    restore_repo_owned_paths

    echo "--- Sync complete: upstream $RESOLVED_UPSTREAM_COMMIT hydrated through $UPSTREAM_REMOTE_NAME ---"
}

main() {
    local command="${1:-}"

    require_repo_root_contract

    case "$command" in
        resolve)
            shift
            resolve_upstream "$@"
            ;;
        sync)
            shift
            sync_upstream "$@"
            ;;
        verify-restore-boundary)
            verify_restore_boundary
            echo "Restore boundary OK: repo-owned restore paths stay off upstream root surfaces."
            ;;
        verify-source-policy)
            verify_source_policy
            echo "Source policy OK: upstream.source pins no commit; the project tracks the latest upstream HEAD."
            ;;
        -h | --help | '')
            usage
            ;;
        *)
            fail "Unknown command: $command"
            ;;
    esac
}

main "$@"
