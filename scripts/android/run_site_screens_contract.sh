#!/bin/bash
#
# Contract: the Pages site publishes the calculator screens
# scripts/site-screens/scenes.txt lists, in the shape site_screen_svg.c writes, and
# the Android calculator, device.svg, in the shape SiteDeviceSvgTest and
# compose_device_svg.sh write, and nothing an upstream commit could slip in beside
# them.
#
# It fails when:
#
# - scenes.txt, the scenes site_screens.c implements, and the screens
#   android/site/index.html shows are not one set, or the page does not show the
#   device;
# - site_screen_svg.c, compiled alone, loses or adds a pixel of a seeded bitmap (an
#   empty screen, a full one, a hashed fill, a checkerboard, and blocks that repeat
#   down the rows), as awk here decodes its path, or writes a file
#   check_screens_artifact.sh refuses;
# - check_screens_artifact.sh passes a missing or an extra file, a symbolic link, a
#   directory, a file of two lines or over its size cap, a script, an event
#   handler, another element, or a path command but M, h, v, and z; or, in the
#   device, a script, an image, an event handler, a second style, the style moved
#   off the root, or a screen in its window that is not one;
# - compose_device_svg.sh fills a device with no LCD window, two of them, or a
#   screen that is not in the writer's shape;
# - fetch_live_screens.sh passes when the live site lacks a scene or the device,
#   or fetches anything but the live files;
# - render_site_screens.sh stops drawing the device through SiteDeviceSvgTest and
#   compose_device_svg.sh;
# - the screens job can write the repository or reads a secret, renders with
#   anything but render_site_screens.sh, falls back to the live screens other than
#   on a render that did not succeed, or uploads or reports ready without one of
#   the two; or the build job stages the home page without the screens checked, or
#   runs when they are not ready;
# - the push filter misses scripts/site-screens/.
#
# Pure host check: a C compiler, no SDK, no core build, no network. It cannot tell
# whether a scene still draws what its caption on the page says: only a render of
# the real core shows that.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

SCREENS_DIR="$PROJECT_ROOT/scripts/site-screens"
SCENES="$SCREENS_DIR/scenes.txt"
TOOL="$SCREENS_DIR/site_screens.c"
WRITER="$SCREENS_DIR/site_screen_svg.c"
COMPOSE="$SCREENS_DIR/compose_device_svg.sh"
CHECK="$SCREENS_DIR/check_screens_artifact.sh"
FETCH="$SCREENS_DIR/fetch_live_screens.sh"
RENDER="$SCREENS_DIR/render_site_screens.sh"
PAGE="$PROJECT_ROOT/android/site/index.html"
PAGES="$WORKFLOW_DIR/privacy-policy-pages.yml"

for file in "$SCENES" "$TOOL" "$WRITER" "$COMPOSE" "$CHECK" "$FETCH" "$RENDER" "$PAGE" "$PAGES"; do
    [ -f "$file" ] || contract_fail "missing required file: ${file#"$PROJECT_ROOT/"}"
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

listed="$(grep -Ev '^[[:space:]]*(#|$)' "$SCENES" | LC_ALL=C sort)"
[ -n "$listed" ] || contract_fail "scenes.txt names no scene."
implemented="$(strip_c_comments "$TOOL" |
    sed -n 's/^[[:space:]]*{"\([a-z0-9-]*\)", scene_[a-z0-9_]*},$/\1/p' | LC_ALL=C sort)"
shown="$(perl -0pe 's/<!--.*?-->//gs' "$PAGE" |
    grep -oE 'src="screens/[a-z0-9-]+\.svg"' | sed 's/^src="screens\/\(.*\)\.svg"$/\1/' | LC_ALL=C sort)"
[ "$implemented" = "$listed" ] ||
    contract_fail "site_screens.c implements a different set of scenes than scenes.txt lists." \
        "scenes.txt: $(tr '\n' ' ' <<<"$listed")" "site_screens.c: $(tr '\n' ' ' <<<"$implemented")"
grep -qx device <<<"$shown" ||
    contract_fail "index.html does not show screens/device.svg."
shown="$(grep -vx device <<<"$shown")"
[ "$shown" = "$listed" ] ||
    contract_fail "index.html shows a different set of screens than scenes.txt lists." \
        "scenes.txt: $(tr '\n' ' ' <<<"$listed")" "index.html: $(tr '\n' ' ' <<<"$shown")"
render="$(strip_hash_comments <"$RENDER")"
grep -q 'scenes\.txt' <<<"$render" ||
    contract_fail "render_site_screens.sh does not render the scenes scenes.txt lists."
grep -q "SiteDeviceSvgTest" <<<"$render" && grep -q 'compose_device_svg\.sh' <<<"$render" ||
    contract_fail "render_site_screens.sh does not draw the device through SiteDeviceSvgTest and compose_device_svg.sh."

# Compile the writer alone with a driver that writes each seeded bitmap and prints
# it, one row of 0 and 1 per line, for the decoder below to match.
cat >"$work/writer_test.c" <<'C'
#include "site_screen_svg.h"

#include <stdio.h>
#include <stdlib.h>

static int mode;

static bool pixel(uint32_t x, uint32_t y) {
  switch (mode) {
  case 0:
    return false;
  case 1:
    return true;
  case 2: {
    uint32_t h = (x * 73856093u) ^ (y * 19349663u) ^ 0x9e3779b9u;
    h ^= h >> 13;
    h *= 0x5bd1e995u;
    h ^= h >> 15;
    return (h & 1u) != 0;
  }
  case 3:
    return ((x + y) & 1u) != 0;
  default:
    return (x % 50u < 20u && y % 30u < 25u) || (x / 7u + y / 5u) % 3u == 0;
  }
}

int main(int argc, char **argv) {
  if (argc != 3) {
    return 2;
  }
  mode = atoi(argv[1]);
  if (!site_screen_write_svg(argv[2], 400, 240, pixel)) {
    return 1;
  }
  for (uint32_t y = 0; y < 240; ++y) {
    for (uint32_t x = 0; x < 400; ++x) {
      putchar(pixel(x, y) ? '1' : '0');
    }
    putchar('\n');
  }
  return 0;
}
C
cc -std=gnu17 -O1 -Wall -Wextra -Werror -I"$SCREENS_DIR" \
    "$work/writer_test.c" "$WRITER" -o "$work/writer_test" ||
    contract_fail "site_screen_svg.c does not compile alone, warnings as errors."

# Print the bitmap an SVG's path draws, one row of 0 and 1 per line; fail on a
# command the writer never emits or on two rectangles that overlap.
decode_svg() {
    sed -n 's/.* d="\([^"]*\)".*/\1/p' "$1" | awk '
        BEGIN { for (y = 0; y < 240; y++) for (x = 0; x < 400; x++) lit[x, y] = 0 }
        {
            n = split($0, rects, "z")
            for (i = 1; i < n; i++) {
                if (rects[i] !~ /^M[0-9]+ [0-9]+h[0-9]+v[0-9]+h-[0-9]+$/) { print "bad command " rects[i] > "/dev/stderr"; exit 1 }
                split(substr(rects[i], 2), v, /[ hv]|h-/)
                if (v[3] != v[5]) { print "unclosed rectangle " rects[i] > "/dev/stderr"; exit 1 }
                for (y = v[2]; y < v[2] + v[4]; y++)
                    for (x = v[1]; x < v[1] + v[3]; x++) {
                        if (lit[x, y]) { print "overlap at " x "," y > "/dev/stderr"; exit 1 }
                        lit[x, y] = 1
                    }
            }
        }
        END { for (y = 0; y < 240; y++) { row = ""; for (x = 0; x < 400; x++) row = row lit[x, y]; print row } }'
}

for mode in 0 1 2 3 4; do
    "$work/writer_test" "$mode" "$work/mode$mode.svg" >"$work/mode$mode.expected" ||
        contract_fail "site_screen_svg.c failed to write seeded bitmap $mode."
    decode_svg "$work/mode$mode.svg" >"$work/mode$mode.decoded" ||
        contract_fail "seeded bitmap $mode's SVG does not decode."
    cmp -s "$work/mode$mode.expected" "$work/mode$mode.decoded" ||
        contract_fail "site_screen_svg.c lost or added pixels of seeded bitmap $mode."
done

# A device as SiteDeviceSvgTest writes one: filled paths of each command it emits,
# an empty LCD window between them, and one translucent path.
DEVICE_TEMPLATE='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 200" width="100" height="200"><path fill="#000000" d="M0 0L100 0L100 200L0 200Z"/><svg class="lcd" x="5.0" y="10.5" width="90.0" height="54.0" viewBox="0 0 400 240"/><path fill="#404040" fill-opacity="0.5" d="M10 100C10 90 20 90 20 100Q15 110 -1.5 100Z"/></svg>'
printf '%s\n' "$DEVICE_TEMPLATE" >"$work/device-template.svg"

# Seed an artifact of the listed scenes from one written bitmap, and the device
# composed with it.
seed_artifact() {
    local dir="$1" source="$2" scene
    rm -rf "$dir"
    mkdir -p "$dir"
    while read -r scene; do
        cp "$source" "$dir/$scene.svg"
    done <<<"$listed"
    bash "$COMPOSE" "$work/device-template.svg" "$source" "$dir/device.svg" ||
        contract_fail "compose_device_svg.sh could not fill the seeded device."
}
first_scene="$(head -n 1 <<<"$listed")"

for mode in 0 1 2 3 4; do
    seed_artifact "$work/artifact" "$work/mode$mode.svg"
    bash "$CHECK" "$work/artifact" >/dev/null 2>"$work/stderr" ||
        contract_fail "check_screens_artifact.sh refused what site_screen_svg.c wrote for bitmap $mode." \
            "$(cat "$work/stderr")"
done

# Each refused artifact: the hashed fill's, with one change.
refuse() {
    local reason="$1"
    if bash "$CHECK" "$work/artifact" >/dev/null 2>&1; then
        contract_fail "check_screens_artifact.sh passed an artifact with $reason."
    fi
}
mutate_first() {
    seed_artifact "$work/artifact" "$work/mode2.svg"
    perl -pi -e "$1" "$work/artifact/$first_scene.svg"
}
seed_artifact "$work/artifact" "$work/mode2.svg"
rm "$work/artifact/$first_scene.svg"
refuse "a missing scene"
seed_artifact "$work/artifact" "$work/mode2.svg"
cp "$work/mode2.svg" "$work/artifact/extra.svg"
refuse "an extra file"
seed_artifact "$work/artifact" "$work/mode2.svg"
mkdir "$work/artifact/nested"
refuse "a directory"
seed_artifact "$work/artifact" "$work/mode2.svg"
ln -sf "$work/mode2.svg" "$work/artifact/$first_scene.svg"
refuse "a symbolic link"
mutate_first 's#</svg>$#</svg>\n<!-- -->#'
refuse "a second line"
mutate_first 's#"/></svg>#"/><script>alert(1)</script></svg>#'
refuse "a script"
mutate_first 's#<svg #<svg onload="alert(1)" #'
refuse "an event handler"
mutate_first 's#<path #<image href="https://example.com/a.png"/><path #'
refuse "another element"
mutate_first 's# d="M# d="L#'
refuse "a path command other than M, h, v, and z"
seed_artifact "$work/artifact" "$work/mode2.svg"
perl -e 'print "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 400 240\" width=\"400\" height=\"240\" shape-rendering=\"crispEdges\"><rect width=\"400\" height=\"240\" fill=\"#dcdedb\"/><path fill=\"#000000\" d=\"", "M1 1h1v1h-1z" x 200000, "\"/></svg>\n"' \
    >"$work/artifact/$first_scene.svg"
refuse "a file over its size cap"

# Each refused device: the composed one, with one change.
mutate_device() {
    seed_artifact "$work/artifact" "$work/mode2.svg"
    perl -pi -e "$1" "$work/artifact/device.svg"
}
mutate_device 's#<path fill="\#404040"#<script>alert(1)</script><path fill="\#404040"#'
refuse "a script in the device"
mutate_device 's#<path fill="\#404040"#<image href="https://example.com/a.png"/><path fill="\#404040"#'
refuse "an image in the device"
mutate_device 's#<path fill="\#000000"#<path onload="alert(1)" fill="\#000000"#'
refuse "an event handler in the device"
mutate_device 's#(<style>.*?</style>)#$1$1#'
refuse "a second style in the device"
mutate_device 's#(<style>.*?</style>)(.*)</svg>$#$2$1</svg>#'
refuse "the style moved off the device's root"
mutate_device 's#<rect width="400" height="240" fill="\#[0-9a-f]{6}"/>##'
refuse "a device whose LCD window holds no screen background"

# compose_device_svg.sh fills exactly one window with a screen in the writer's shape.
printf '%s\n' "${DEVICE_TEMPLATE/<svg class=\"lcd\" x=\"5.0\" y=\"10.5\" width=\"90.0\" height=\"54.0\" viewBox=\"0 0 400 240\"\/>/}" >"$work/no-window.svg"
if bash "$COMPOSE" "$work/no-window.svg" "$work/mode2.svg" "$work/out.svg" 2>/dev/null; then
    contract_fail "compose_device_svg.sh filled a device with no LCD window."
fi
perl -pe 's#(<svg class="lcd"[^>]*/>)#$1$1#' "$work/device-template.svg" >"$work/two-windows.svg"
if bash "$COMPOSE" "$work/two-windows.svg" "$work/mode2.svg" "$work/out.svg" 2>/dev/null; then
    contract_fail "compose_device_svg.sh filled a device with two LCD windows."
fi
perl -pe 's#"/></svg>#"/><script>alert(1)</script></svg>#' "$work/mode2.svg" >"$work/bad-screen.svg"
if bash "$COMPOSE" "$work/device-template.svg" "$work/bad-screen.svg" "$work/out.svg" 2>/dev/null; then
    contract_fail "compose_device_svg.sh laid a screen with a script into the device."
fi

# The fallback fetches every listed scene from a directory standing in for the
# live site, and fails when one is missing there.
mkdir -p "$work/live/screens"
while read -r scene; do
    printf '%s\n' "$scene" >"$work/live/screens/$scene.svg"
done <<<"$listed"
printf 'device\n' >"$work/live/screens/device.svg"
R47_SCREENS_SITE_URL="file://$work/live" bash "$FETCH" "$work/fetched" 2>"$work/stderr" ||
    contract_fail "fetch_live_screens.sh failed with every scene live." "$(cat "$work/stderr")"
diff -r "$work/live/screens" "$work/fetched" >/dev/null ||
    contract_fail "fetch_live_screens.sh fetched other files than the live ones."
rm "$work/live/screens/$first_scene.svg"
if R47_SCREENS_SITE_URL="file://$work/live" bash "$FETCH" "$work/fetched" >/dev/null 2>&1; then
    contract_fail "fetch_live_screens.sh passed with $first_scene.svg missing from the live site."
fi

pages="$(strip_hash_comments <"$PAGES")"
job_block() {
    awk -v job="$1" '
        $0 ~ "^  " job ":[[:space:]]*$" { inside = 1; next }
        inside && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { inside = 0 }
        inside { print }
    ' <<<"$pages"
}
screens_job="$(job_block screens)"
build_job="$(job_block build)"
[ -n "$screens_job" ] || contract_fail "privacy-policy-pages.yml has no screens job."
[ -n "$build_job" ] || contract_fail "privacy-policy-pages.yml has no build job."
if grep -Eq '(:[[:space:]]*write|secrets\.)' <<<"$screens_job"; then
    contract_fail "the screens job runs the upstream core but holds a write permission or a secret."
fi
grep -Fq 'run: bash ./scripts/site-screens/render_site_screens.sh _screens' <<<"$screens_job" ||
    contract_fail "the screens job does not render with render_site_screens.sh."
grep -Fq "if: \${{ always() && steps.render.outcome != 'success' }}" <<<"$screens_job" &&
    grep -Fq 'run: bash ./scripts/site-screens/fetch_live_screens.sh _screens' <<<"$screens_job" ||
    contract_fail "the screens job does not fall back to the live screens when the render does not succeed."
ready_expr="steps.render.outcome == 'success' || steps.fallback.outcome == 'success'"
grep -Fq "ready: \${{ $ready_expr }}" <<<"$screens_job" &&
    grep -Fq "if: \${{ always() && ($ready_expr) }}" <<<"$screens_job" ||
    contract_fail "the screens job uploads or reports ready without a render or the fallback."
grep -Fq "needs.screens.outputs.ready == 'true'" <<<"$build_job" ||
    contract_fail "the build job runs when the screens are not ready."
download_line="$(grep -nF 'path: _site/screens' <<<"$build_job" | head -n 1 | cut -d: -f1 || true)"
check_line="$(grep -nF 'run: bash ./scripts/site-screens/check_screens_artifact.sh _site/screens' <<<"$build_job" | head -n 1 | cut -d: -f1 || true)"
stage_line="$(grep -nF 'run: bash ./scripts/docs/stage_pages_home.sh _site' <<<"$build_job" | head -n 1 | cut -d: -f1 || true)"
[ -n "$download_line" ] && [ -n "$check_line" ] && [ -n "$stage_line" ] &&
    [ "$check_line" -gt "$download_line" ] && [ "$stage_line" -gt "$check_line" ] ||
    contract_fail "the build job stages the home page without checking the screens it downloaded."
grep -Eq -- '^[[:space:]]+- scripts/site-screens/\*\*$' <<<"$pages" ||
    contract_fail "privacy-policy-pages.yml's push paths do not name scripts/site-screens/**; an edit would not republish."

contract_pass "the site publishes the $(wc -l <<<"$listed") screens scenes.txt lists, as site_screen_svg.c writes them, and nothing else."
