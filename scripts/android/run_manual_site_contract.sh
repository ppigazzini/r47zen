#!/bin/bash
#
# Contract: the Pages lane publishes the cropped manual at the URL the app opens,
# rebuilds it only when its inputs change, and never replaces a good PDF with a bad
# one. Runs scripts/docs/prepare_manual_site.sh on a seeded tree, with a stub docker
# on PATH and a directory standing in for the live site, so every branch executes.
#
# It fails when:
#
# - a first run, a changed input, or a new TeX image does not build;
# - a scheduled run with current inputs builds or deploys;
# - any other event with current inputs builds, or does not republish the live PDF;
# - a build that fails or leaves an empty, cut-short, or non-PDF file does not fail the
#   run, a scheduled one deploys anyway, or any other event does not republish;
# - a file the container left in the output directory would be published;
# - the container can reach the running script, as a mount of the whole checkout
#   lets it: a stub that honors the mounts and overwrites the rest of the script
#   in place, as a -shell-escape build can, must change nothing the host runs;
# - the build job publishes anything but the PDF and a 64-hex VERSION, as regular
#   files (check_manual_artifact.sh, on fixtures);
# - a manual input upstream moved is not reported;
# - the build gets the network or a token, or a checkout keeps its credentials;
# - the workflow can write, loses its nightly run, or passes the wrong event;
# - a failed manual job stops the policy deploying, or the deploy runs off main;
# - the app's URL and the path the workflow publishes differ.
#
# Pure host check: no SDK, no TeX, no network. It cannot tell whether upstream's
# build works in the pinned image; only a run of the lane shows that.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

PREPARE="$PROJECT_ROOT/scripts/docs/prepare_manual_site.sh"
PAGES="$WORKFLOW_DIR/privacy-policy-pages.yml"
STRINGS="$PROJECT_ROOT/android/app/src/main/res/values/strings.xml"

for file in "$PREPARE" "$PAGES" "$STRINGS"; do
    [ -f "$file" ] || contract_fail "missing required file: ${file#"$PROJECT_ROOT/"}"
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

root="$work/root"
live="$work/live/manual"
stub="$work/bin"
log="$work/docker.log"
mkdir -p "$root/docs/manual/tex" "$root/docs/styles" "$root/docs/tools" "$root/res/fonts" "$live" "$stub"
for input in docs/manual/tex/book.tex docs/manual/Makefile docs/styles/a.sty docs/tools/crop.py res/fonts/a.ttf; do
    echo "$input" >"$root/$input"
done

# Write a PDF the way the real build does, or fail as R47_STUB_BUILD asks.
cat >"$stub/docker" <<'STUB'
#!/bin/bash
echo "$*" >>"$R47_STUB_LOG"
[ "$1" = "run" ] || exit 0
case "${R47_STUB_BUILD:-good}" in
    fail) exit 1 ;;
    escape)
        # A -shell-escape build overwriting the running script in place, through
        # whichever writable mount covers it. Bash reads a script as it runs, so a
        # covered script runs the planted lines next, on the host.
        target=/work/scripts/docs/prepare_manual_site.sh
        args=("$@")
        for ((i = 0; i < ${#args[@]}; i++)); do
            [ "${args[i]}" = "--volume" ] || continue
            IFS=: read -r src dst mode <<<"${args[i + 1]}"
            if [ "$mode" != ro ] && [[ "$target" == "$dst"/* ]]; then
                host="$src/${target#"$dst"/}"
                offset="$(grep -b -m 1 'build_pdf || build_status' "$host" | cut -d: -f1)"
                offset=$((offset + $(grep -m 1 'build_pdf || build_status' "$host" | wc -c)))
                printf 'echo ESCAPED >&2; echo deploy=true; exit 0\n' |
                    dd of="$host" bs=1 seek="$offset" conv=notrunc status=none
            fi
        done
        exit 1
        ;;
    short) printf '%%PDF-1.7 short\n%%%%EOF\n' >"$R47_MANUAL_ROOT/docs/manual/C47_R47_calc_Crop.pdf" ;;
    cut)
        {
            printf '%%PDF-1.7\n'
            head -c 600000 /dev/zero
        } >"$R47_MANUAL_ROOT/docs/manual/C47_R47_calc_Crop.pdf"
        ;;
    notpdf)
        {
            head -c 600000 /dev/zero
            printf '\n%%%%EOF\n'
        } >"$R47_MANUAL_ROOT/docs/manual/C47_R47_calc_Crop.pdf"
        ;;
    plant | good)
        {
            printf '%%PDF-1.7\n'
            head -c 600000 /dev/zero
            printf '\n%%%%EOF\n'
        } >"$R47_MANUAL_ROOT/docs/manual/C47_R47_calc_Crop.pdf"
        if [ "${R47_STUB_BUILD:-good}" = "plant" ]; then
            mkdir -p "$R47_STUB_OUT"
            echo planted >"$R47_STUB_OUT/index.html"
        fi
        ;;
esac
STUB
chmod +x "$stub/docker"

# Run the script as the lane does. Set $deploy, and $status for its exit code.
prepare() {
    local event="$1"
    local script="${2:-$PREPARE}"
    rm -rf "$work/out"
    : >"$log"
    status=0
    deploy="$(
        PATH="$stub:$PATH" R47_STUB_LOG="$log" R47_MANUAL_ROOT="$root" R47_STUB_OUT="$work/out" \
            R47_MANUAL_SITE_URL="file://$work/live" \
            bash "$script" "$work/out" "$event" 2>"$work/stderr"
    )" || status=$?
}

built() {
    grep -q '^run ' "$log"
}

publish_output() {
    cp "$work/out/C47_R47_calc_Crop.pdf" "$work/out/VERSION" "$live/"
}

prepare push
[ "$status" -eq 0 ] && [ "$deploy" = "deploy=true" ] && built ||
    contract_fail "a first run did not build and deploy." "$(cat "$work/stderr")"
[ -s "$work/out/VERSION" ] && [ -s "$work/out/C47_R47_calc_Crop.pdf" ] ||
    contract_fail "a first run did not leave the PDF and its VERSION."
docker_run="$(grep '^run ' "$log")"
grep -q -- '--network none' <<<"$docker_run" ||
    contract_fail "the manual build runs with the network." "$docker_run"
grep -q 'make -C docs/manual pdf VARIANT=crop' <<<"$docker_run" ||
    contract_fail "the manual build is not upstream's command." "$docker_run"
if grep -Eq 'TOKEN|SECRET|--env-file' <<<"$docker_run"; then
    contract_fail "the manual build gets a credential." "$docker_run"
fi
publish_output

prepare schedule
[ "$status" -eq 0 ] && [ "$deploy" = "deploy=false" ] && ! built ||
    contract_fail "a scheduled run with current inputs built or deployed."

prepare push
[ "$status" -eq 0 ] && [ "$deploy" = "deploy=true" ] && ! built ||
    contract_fail "a push with current inputs built, or did not republish."
cmp -s "$work/out/C47_R47_calc_Crop.pdf" "$live/C47_R47_calc_Crop.pdf" ||
    contract_fail "a push with current inputs did not republish the live PDF."

: >"$live/C47_R47_calc_Crop.pdf"
prepare push
[ "$status" -eq 0 ] && [ "$deploy" = "deploy=true" ] && built ||
    contract_fail "a push did not rebuild when the live PDF was unusable."
publish_output

for input in docs/manual/tex/book.tex docs/manual/Makefile docs/styles/a.sty docs/tools/crop.py res/fonts/a.ttf; do
    echo changed >>"$root/$input"
    prepare schedule
    [ "$status" -eq 0 ] && [ "$deploy" = "deploy=true" ] && built ||
        contract_fail "a change to $input did not rebuild."
    publish_output
done

sed 's/^TEX_IMAGE="\(.*\)"$/TEX_IMAGE="\1-bumped"/' "$PREPARE" >"$work/prepare-bumped.sh"
prepare schedule "$work/prepare-bumped.sh"
[ "$status" -eq 0 ] && [ "$deploy" = "deploy=true" ] && built ||
    contract_fail "a new TeX image did not rebuild."

echo changed >>"$root/docs/manual/tex/book.tex"
export R47_STUB_BUILD
for failure in fail short cut notpdf; do
    R47_STUB_BUILD="$failure"
    prepare schedule
    [ "$status" -ne 0 ] && [ -z "$deploy" ] ||
        contract_fail "a scheduled run with a bad build ($failure) did not fail, or deployed."
    prepare push
    [ "$status" -ne 0 ] && [ "$deploy" = "deploy=true" ] ||
        contract_fail "another event with a bad build ($failure) did not fail red and republish."
    cmp -s "$work/out/C47_R47_calc_Crop.pdf" "$live/C47_R47_calc_Crop.pdf" ||
        contract_fail "another event with a bad build ($failure) did not republish the live PDF."
    cmp -s "$work/out/VERSION" "$live/VERSION" ||
        contract_fail "a republish after a bad build ($failure) claimed the new inputs."
done
R47_STUB_BUILD=plant
prepare schedule
[ "$status" -eq 0 ] && [ "$(find "$work/out" -type f | wc -l)" -eq 2 ] ||
    contract_fail "a file the container left in the output directory would be published."

# The running script sits in the checkout; a copy at the same place in the seeded
# tree is what the container would see.
mkdir -p "$root/scripts/docs"
cp "$PREPARE" "$root/scripts/docs/prepare_manual_site.sh"
R47_STUB_BUILD=escape
prepare schedule "$root/scripts/docs/prepare_manual_site.sh"
if grep -q ESCAPED "$work/stderr" || [ "$deploy" = "deploy=true" ]; then
    contract_fail "the manual build can rewrite the script running it, outside the container." "$(cat "$work/stderr")"
fi
cmp -s "$PREPARE" "$root/scripts/docs/prepare_manual_site.sh" ||
    contract_fail "the manual build changed the script running it."
docker_run="$(grep '^run ' "$log")"
grep -q -- "--volume $root/docs:/work/docs " <<<"$docker_run" &&
    grep -q -- "--volume $root/res:/work/res:ro " <<<"$docker_run" ||
    contract_fail "the manual build does not mount docs read-write and res read-only." "$docker_run"
unset R47_STUB_BUILD

CHECK_ARTIFACT="$PROJECT_ROOT/scripts/docs/check_manual_artifact.sh"
artifact="$work/artifact"
seed_artifact() {
    rm -rf "$artifact"
    mkdir -p "$artifact"
    printf '%%PDF-1.7\n' >"$artifact/C47_R47_calc_Crop.pdf"
    printf '%064d\n' 0 >"$artifact/VERSION"
}
seed_artifact
bash "$CHECK_ARTIFACT" "$artifact" >/dev/null || contract_fail "check_manual_artifact.sh refused the PDF and its VERSION."
for bad in extra symlink version notpdf; do
    seed_artifact
    case "$bad" in
        extra) echo '<script>' >"$artifact/index.html" ;;
        symlink) ln -sf /etc/hostname "$artifact/VERSION.tmp" && mv "$artifact/VERSION.tmp" "$artifact/VERSION" ;;
        version) echo deploy >"$artifact/VERSION" ;;
        notpdf) echo '<html>' >"$artifact/C47_R47_calc_Crop.pdf" ;;
    esac
    if bash "$CHECK_ARTIFACT" "$artifact" >/dev/null 2>&1; then
        contract_fail "check_manual_artifact.sh passed an artifact with $bad."
    fi
done

rm -rf "$root/docs/styles"
prepare schedule
[ "$status" -ne 0 ] && grep -q 'docs/styles is missing' "$work/stderr" ||
    contract_fail "a moved manual input was not reported." "$(cat "$work/stderr")"

pages="$(strip_hash_comments <"$PAGES")"
manual_job="$(awk '
    /^  manual:[[:space:]]*$/ { inside = 1; print; next }
    inside && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { inside = 0 }
    inside { print }
' <<<"$pages")"
[ -n "$manual_job" ] || contract_fail "privacy-policy-pages.yml has no manual job."
grep -q 'prepare_manual_site\.sh' <<<"$manual_job" ||
    contract_fail "the manual job does not run prepare_manual_site.sh."
if grep -Eq '(:[[:space:]]*write|secrets\.)' <<<"$manual_job"; then
    contract_fail "the manual job runs upstream's build but holds a write permission or a secret." "$manual_job"
fi
grep -q '_site/manual' <<<"$pages" ||
    contract_fail "privacy-policy-pages.yml does not publish under _site/manual."
grep -q 'check_manual_artifact.sh _site/manual' <<<"$pages" ||
    contract_fail "the build job stages the manual artifact without check_manual_artifact.sh."
[ "$(grep -c 'contents: write' <<<"$pages")" -eq 0 ] ||
    contract_fail "privacy-policy-pages.yml can write the repository."
[ "$(grep -c 'persist-credentials: false' <<<"$pages")" -eq "$(grep -c 'actions/checkout@' <<<"$pages")" ] ||
    contract_fail "a checkout in privacy-policy-pages.yml keeps its credentials."
grep -Eq "cron: '[^']+'" <<<"$pages" ||
    contract_fail "privacy-policy-pages.yml has no nightly run."
grep -q 'EVENT_NAME: \${{ github.event_name }}' <<<"$manual_job" ||
    contract_fail "the manual job does not pass the real event."
grep -Fq "if: \${{ always() && needs.manual.outputs.deploy == 'true' }}" <<<"$pages" ||
    contract_fail "a failed manual job stops the policy deploying, or the build runs with nothing to stage."
grep -Fq "if: \${{ always() && steps.prepare.outputs.deploy == 'true' }}" <<<"$manual_job" ||
    contract_fail "a failed manual build does not upload the PDF it republishes."
grep -Fq "needs.build.result == 'success' && github.ref == 'refs/heads/main'" <<<"$pages" ||
    contract_fail "the deploy no longer follows the stage, or runs off main."

site_url="$(sed -n 's/^SITE_URL="\${R47_MANUAL_SITE_URL:-\(.*\)}"$/\1/p' "$PREPARE")"
pdf_name="$(sed -n 's/^PDF_NAME="\(.*\)"$/\1/p' "$PREPARE")"
grep -q ">$site_url/manual/$pdf_name<" "$STRINGS" ||
    contract_fail "the app's manual URL is not $site_url/manual/$pdf_name."

contract_pass "the Pages lane builds the manual only on a change, keeps the last good one, and serves the URL the app opens."
