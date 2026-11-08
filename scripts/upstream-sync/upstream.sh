#!/bin/bash
# scripts/upstream-sync/upstream.sh
#
# Resolve and hydrate the upstream C47 core this repo builds, and hold the line
# between upstream-owned and repo-owned paths.
#
#   resolve  picks one upstream URL and commit. --auto, the default, takes
#            --commit, then an upstream_commit pin in the git-ignored
#            upstream.lock, then the newest commit on upstream_ref; --latest
#            ignores any pin; --locked requires one.
#   sync     writes that commit's tree into the working tree, leaving out every
#            path in REPO_OWNED_RESTORE_PATHS, so it never writes a file this
#            repo tracks, staged or not. It writes only the files whose content
#            differs, so an unchanged file keeps its mtime and a changed one
#            gets the current time, and it removes the files the last sync
#            wrote that the commit no longer holds. A manifest in the git
#            directory records what each sync wrote; a sync refuses to
#            overwrite a hydrated file edited since, unless --force. The lock
#            is written only after the tree is complete.
#   hydrated-commit  prints the commit the last complete sync wrote, or
#            nothing when no sync recorded one or one stopped halfway.
#   verify-restore-boundary  fails when the restore list would re-own an
#            upstream root surface, or misses a tracked root path.
#   verify-source-policy     fails when the tracked upstream.source pins an
#            upstream_commit: the repo tracks upstream HEAD.
#
# test_resolver_policy.sh locks resolve's mode rules, test_fetch_retry.sh the
# retry_with_backoff every fetch goes through, and
# scripts/android/run_upstream_sync_contract.sh the sync; the restore-boundary
# and source-policy pre-commit hooks run the two verify commands.
# android/docs/dev/00-project-and-upstream.md owns the policy.

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
# Every Git-tracked root file this repo owns, plus the repo-owned directories.
# A sync leaves these out of what it writes. A root file missing from this list
# would be replaced by upstream's copy on the next sync: upstream ships its own
# AGENTS.md and CLAUDE.md, so those two are the live case.
# verify-restore-boundary fails when a tracked root path is missing here; the
# tracked root set in AGENTS.md is a copy no check holds.
REPO_OWNED_RESTORE_PATHS=(
    .gitignore
    .pre-commit-config.yaml
    AGENTS.md
    CLAUDE.md
    COPYING
    README.md
    pyproject.toml
    upstream.source
    uv.lock
    android/
    .github/
    __DEV/
    scripts/
)

# Upstream-tracked paths a build or test run rewrites in place, which a sync
# overwrites without counting them as edits: build_sim_assets.sh and
# run_upstream_suite_android_config.sh copy the built test programs to where
# upstream's suite reads them, and upstream's own constantsCheck.py rewrites its
# verification table. On a built checkout they are the only two hydrated files
# that differ from the commit.
UPSTREAM_BUILD_REWRITTEN_PATHS=(
    res/testPgms/testPgms.bin
    src/generated/constantsVerification.txt
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
    scripts/upstream-sync/upstream.sh hydrated-commit
    scripts/upstream-sync/upstream.sh verify-restore-boundary
    scripts/upstream-sync/upstream.sh verify-source-policy

Commands:
  resolve   Resolve the authoritative upstream URL/ref/commit through the shared policy.
  sync      Write the resolved upstream tree, leaving out repo-owned paths, and remove
            the files the last sync wrote that it no longer holds.
  hydrated-commit          Print the commit the last complete sync wrote, if any.
  verify-restore-boundary  Fail when the restore allowlist would re-own upstream root surfaces.
  verify-source-policy     Fail when upstream.source pins an upstream_commit (it must track the latest HEAD).

Resolution modes:
  --auto    Use --commit first, then an optional pinned upstream_commit (upstream.lock over upstream.source), else the latest upstream_ref. Latest is the normal path. (default)
  --locked  Require an explicit --commit or an optional pinned upstream_commit in upstream.lock (or upstream.source). For roadblock reproduction only.
  --latest  Ignore any pinned upstream_commit and resolve the latest commit from upstream_ref.

Options:
  --write-lock  Refresh upstream.lock with the resolved URL/ref/commit; a sync writes it
                only once the tree is complete.
  --if-missing  Skip the sync when src/c47 and res/fonts are hydrated and the last
                complete sync wrote the resolved commit.
  --force       Overwrite hydrated upstream files edited since the last sync, and a
                file of yours at a path the new commit adds.
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

ensure_remote() {
    local upstream_url="$1"
    local remotes

    remotes="$(git -C "$PROJECT_ROOT" remote)"
    if grep -qx "$UPSTREAM_REMOTE_NAME" <<<"$remotes"; then
        git -C "$PROJECT_ROOT" remote set-url "$UPSTREAM_REMOTE_NAME" "$upstream_url"
    else
        git -C "$PROJECT_ROOT" remote add "$UPSTREAM_REMOTE_NAME" "$upstream_url"
    fi
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

# Every Git-tracked entry at the repo root, file or directory, must be in the
# restore set, a directory as `name/`. The overlay writes upstream's tree over
# ours, so a root file left out, or a file in a directory left out whose name
# upstream also uses, is replaced by upstream's copy on the next sync, silently
# and without a diff to review. Read the index, not HEAD: a sync refuses staged
# changes, so the two agree there, and the pre-commit hook needs the entry
# being added, which HEAD lacks.
verify_restore_root_coverage() {
    local root_file=""
    local restore_path=""
    local covered=""
    local missing=()

    while IFS= read -r root_file; do
        [ -n "$root_file" ] || continue
        covered=""
        for restore_path in "${REPO_OWNED_RESTORE_PATHS[@]}"; do
            if [ "$restore_path" = "$root_file" ]; then
                covered=1
                break
            fi
        done
        [ -n "$covered" ] || missing+=("$root_file")
    done < <(git -C "$PROJECT_ROOT" ls-files |
        while IFS= read -r entry; do
            if [[ "$entry" == */* ]]; then
                printf '%s/\n' "${entry%%/*}"
            elif [ -f "$PROJECT_ROOT/$entry" ]; then
                printf '%s\n' "$entry"
            fi
        done | LC_ALL=C sort -u)

    if [ "${#missing[@]}" -gt 0 ]; then
        fail "Restore coverage gap: tracked root entries missing from REPO_OWNED_RESTORE_PATHS: ${missing[*]}. The upstream overlay would replace them with upstream's copies. Add each one to the list in $(basename "${BASH_SOURCE[0]}")."
    fi
}

verify_restore_boundary() {
    local restore_path=""

    for restore_path in "${REPO_OWNED_RESTORE_PATHS[@]}"; do
        if restore_path_reowns_upstream_root_surface "$restore_path"; then
            fail "Restore boundary drift detected: $restore_path would re-own an authoritative upstream root surface."
        fi
    done

    verify_restore_root_coverage
}

# Where a sync records what it wrote: the commit, and for each file the SHA-256
# of the content written (a link, "link:" and its target). It lives in the git
# directory, so no checkout tracks it and each worktree has its own.
sync_manifest_path() {
    git -C "$PROJECT_ROOT" rev-parse --path-format=absolute --git-path r47-upstream-manifest.json
}

print_hydrated_commit() {
    local manifest=""

    manifest="$(sync_manifest_path)"
    [ -f "$manifest" ] || return 0
    python3 -I -c 'import json, sys; print(json.load(open(sys.argv[1]))["commit"] or "")' "$manifest"
}

# Write the tree of the git archive $1, commit $2, into the working tree, with
# --force ($3 = true) overwriting edits. Every path under REPO_OWNED_RESTORE_PATHS
# is left out. Exits 3, writing nothing, on an edit it would overwrite.
apply_upstream_archive() {
    local archive="$1"
    local commit="$2"
    local force="$3"

    python3 -I - "$PROJECT_ROOT" "$archive" "$(sync_manifest_path)" "$commit" "$force" \
        "${R47_SYNC_TEST_FAIL_AFTER_WRITES:-}" "$(printf '%s\n' "${UPSTREAM_BUILD_REWRITTEN_PATHS[@]}")" \
        "${REPO_OWNED_RESTORE_PATHS[@]}" <<'PY'
import hashlib, json, os, sys, tarfile
from pathlib import Path

root, archive, manifest_path = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
commit, force, fail_after = sys.argv[4], sys.argv[5] == "true", sys.argv[6]
rewritten = set(sys.argv[7].split("\n")) - {""}
owned = {path.rstrip("/") for path in sys.argv[8:]}


def fail(message, status=1):
    print(f"ERROR: {message}", file=sys.stderr)
    sys.exit(status)


def wanted_members(tar):
    """Each file and link of the archive, outside this repo's own paths."""
    for member in tar:
        parts = member.name.split("/")
        if parts[0] in owned or member.isdir():
            continue
        if member.name.startswith("/") or ".." in parts:
            fail(f"the archive names {member.name}")
        if not (member.isfile() or member.issym()):
            fail(f"the archive holds {member.name}, neither a file, a link, nor a directory")
        yield member


def content(tar, member):
    """A link's target, or a file's SHA-256 and, when executable, +x."""
    if member.issym():
        return "link:" + member.linkname
    digest = hashlib.sha256(tar.extractfile(member).read()).hexdigest()
    return digest + ("+x" if member.mode & 0o111 else "")


def on_disk(name):
    path = root / name
    if path.is_symlink():
        return "link:" + os.readlink(path)
    if path.is_file():
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        return digest + ("+x" if path.stat().st_mode & 0o100 else "")
    return None


def owned_by_this_repo(name):
    return name.split("/")[0] in owned


def save(manifest):
    temp = manifest_path.with_suffix(".tmp")
    temp.write_text(json.dumps(manifest, indent=1, sort_keys=True))
    os.replace(temp, manifest_path)


with tarfile.open(archive) as tar:
    new = {member.name: content(tar, member) for member in wanted_members(tar)}

old = json.loads(manifest_path.read_text()) if manifest_path.exists() else None
if old is None:
    print("NOTE: no record of an earlier sync: files upstream has since removed stay, "
          "and edits to hydrated files are not checked this time.")
# A path this repo has owned since the last sync is never checked or removed.
accepted = {name: set(digests) for name, digests in (old or {"files": {}})["files"].items()
            if not owned_by_this_repo(name)}

# A hydrated file edited since the last sync, or a file of yours where the commit
# adds one, would be overwritten. A path a build rewrites is not an edit.
edited = sorted(name for name in (set(accepted) | set(new)) - rewritten
                if old is not None and (current := on_disk(name)) is not None
                and current not in accepted.get(name, set()) and current != new.get(name))
if edited and not force:
    for name in edited[:20]:
        print(f"  edited: {name}", file=sys.stderr)
    fail(f"{len(edited)} hydrated file(s) differ from what the last sync wrote; move the "
         "edits out of the hydrated tree, or pass --force to overwrite them. A file a "
         "build rewrites belongs in UPSTREAM_BUILD_REWRITTEN_PATHS.", status=3)
if edited:
    print(f"NOTE: --force overwrites {len(edited)} edited hydrated file(s).")

# While it writes, a file may hold its old or its new content, so a sync that
# stops halfway is resumed, not refused, and its removals still happen.
pending = {name: sorted(accepted.get(name, set()) | ({new[name]} if name in new else set()))
           for name in set(accepted) | set(new)}
save({"commit": None, "files": pending})

# Removals first, so a path that turns from a file into a directory, or back,
# is free before the write.
removed = 0
for name in sorted(set(pending) - set(new)):
    path = root / name
    if path.is_symlink() or path.is_file():
        path.unlink()
        removed += 1
    parent = path.parent
    while parent != root and parent.is_dir() and not any(parent.iterdir()):
        parent.rmdir()
        parent = parent.parent

written = unchanged = 0
mask = os.umask(0)
os.umask(mask)
with tarfile.open(archive) as tar:
    for member in wanted_members(tar):
        if on_disk(member.name) == new[member.name]:
            unchanged += 1
            continue
        if fail_after and written >= int(fail_after):
            fail("stopped by R47_SYNC_TEST_FAIL_AFTER_WRITES")
        path = root / member.name
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.is_symlink() or path.exists():
            if path.is_dir() and not path.is_symlink():
                fail(f"{member.name} is a directory where the commit has a file")
            path.unlink()
        if member.issym():
            os.symlink(member.linkname, path)
        else:
            temp = path.with_name(f".{path.name}.r47-sync")
            temp.write_bytes(tar.extractfile(member).read())
            temp.chmod((0o777 if member.mode & 0o111 else 0o666) & ~mask)
            os.replace(temp, path)
        written += 1

save({"commit": commit, "files": {name: [digest] for name, digest in new.items()}})
print(f"--- {written} file(s) written, {unchanged} unchanged, {removed} removed ---")
PY
}

emit_resolved_output() {
    local output_format="$1"

    case "$output_format" in
        shell)
            printf 'R47_RESOLVED_UPSTREAM_URL=%q\n' "$RESOLVED_UPSTREAM_URL"
            printf 'R47_RESOLVED_UPSTREAM_REF=%q\n' "$RESOLVED_UPSTREAM_REF"
            printf 'R47_RESOLVED_UPSTREAM_COMMIT=%q\n' "$RESOLVED_UPSTREAM_COMMIT"
            printf 'R47_RESOLVED_UPSTREAM_SHORT_COMMIT=%q\n' "${RESOLVED_UPSTREAM_COMMIT:0:8}"
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

    # A shallow fetch accepts only a full SHA, so an abbreviated pin would pass
    # here and fail at the fetch, minutes later and with a less direct error.
    [[ "$upstream_commit" =~ ^[0-9a-fA-F]{40}$ ]] ||
        fail "upstream commit must be a full 40-character SHA; got '$upstream_commit'."

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
    local write_lock="false"
    local -a resolution_args=()

    while [ "$#" -gt 0 ]; do
        case "$1" in
            --auto | --locked | --latest)
                resolution_args+=("$1")
                ;;
            --write-lock)
                write_lock="true"
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

    # Before anything is fetched or written: a tracked root entry missing from
    # the list would be written over.
    verify_restore_boundary

    resolve_upstream "${resolution_args[@]}" --format none

    # A tree that looks hydrated may be another commit's: one an earlier sync
    # wrote, or one from before syncs kept a record. Skip only when the last
    # complete sync wrote this commit.
    if [ "$if_missing" = "true" ] && upstream_checkout_is_hydrated; then
        local hydrated=""
        hydrated="$(print_hydrated_commit)"
        if [ "$hydrated" = "$RESOLVED_UPSTREAM_COMMIT" ]; then
            echo "--- Upstream core already hydrated at $RESOLVED_UPSTREAM_COMMIT; skipping sync. ---"
            return 0
        fi
        echo "--- The hydrated tree is ${hydrated:-not a recorded sync}, not $RESOLVED_UPSTREAM_COMMIT; syncing. ---"
    fi

    ensure_remote "$RESOLVED_UPSTREAM_URL"

    echo "--- Fetching authoritative upstream core (${RESOLVED_UPSTREAM_MODE} @ ${RESOLVED_UPSTREAM_COMMIT}) ---"
    retry_with_backoff "Fetch upstream core ${RESOLVED_UPSTREAM_COMMIT}" \
        git -C "$PROJECT_ROOT" fetch --depth 1 "$UPSTREAM_REMOTE_NAME" "$RESOLVED_UPSTREAM_COMMIT"
    git -C "$PROJECT_ROOT" cat-file -e "$RESOLVED_UPSTREAM_COMMIT^{commit}"

    # The whole archive first, so a failed archive writes nothing.
    SYNC_ARCHIVE="$(mktemp)"
    trap 'rm -f "$SYNC_ARCHIVE"' EXIT
    git -C "$PROJECT_ROOT" archive --format=tar -o "$SYNC_ARCHIVE" "$RESOLVED_UPSTREAM_COMMIT"

    echo "--- Writing upstream core from $RESOLVED_UPSTREAM_COMMIT ---"
    apply_upstream_archive "$SYNC_ARCHIVE" "$RESOLVED_UPSTREAM_COMMIT" "$force_sync"

    if [ "$write_lock" = "true" ]; then
        write_lockfile "$RESOLVED_UPSTREAM_URL" "$RESOLVED_UPSTREAM_REF" "$RESOLVED_UPSTREAM_COMMIT"
    fi

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
        hydrated-commit)
            print_hydrated_commit
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
