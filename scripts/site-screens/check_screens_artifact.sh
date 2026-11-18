#!/bin/bash
# Refuse a screens artifact that holds anything but the SVGs site_screens.c writes.
#
# The screens job builds and runs the upstream core, so whatever its artifact holds
# came from a job an upstream commit can steer. The Pages site's origin is the one
# Play Console holds for the privacy policy, so the job that stages the site, which
# runs no upstream code, publishes exactly one regular file per scene that
# scenes.txt lists, <scene>.svg, each one line in the shape site_screen_svg.c
# writes: a 400 x 240 svg holding one background rect and one path of rectangles,
# with no other element, attribute, or text. Beside them it publishes device.svg,
# one line in the shape SiteDeviceSvgTest and compose_device_svg.sh write: filled
# paths of lines, quads, and cubics, the one dark-scheme style compose_device_svg.sh
# adds, and one LCD window holding a screen in the shape above. A file over
# MAX_BYTES is refused too: a full screen of single pixels, and the device at its
# size, stay well under it.
#
# Usage: check_screens_artifact.sh <directory>

set -euo pipefail

dir="${1:?usage: $0 <directory>}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAX_BYTES=2000000

fail() {
    echo "The screens artifact is refused: $1" >&2
    exit 1
}

mapfile -t scenes < <(grep -Ev '^[[:space:]]*(#|$)' "$SCRIPT_DIR/scenes.txt")
[ "${#scenes[@]}" -gt 0 ] || fail "scenes.txt names no scene"
expected="$(printf '%s.svg\n' "${scenes[@]}" device | LC_ALL=C sort)"
actual="$(find "$dir" -mindepth 1 -printf '%P\n' | LC_ALL=C sort)"
[ "$actual" = "$expected" ] ||
    fail "it holds $(tr '\n' ' ' <<<"$actual"), not exactly $(tr '\n' ' ' <<<"$expected")"

screen_inner='<rect width="400" height="240" fill="#[0-9a-f]{6}"/><path fill="#[0-9a-f]{6}" d="(M[0-9]{1,3} [0-9]{1,3}h[0-9]{1,3}v[0-9]{1,3}h-[0-9]{1,3}z)*"/>'
svg_shape='^<svg xmlns="http://www\.w3\.org/2000/svg" viewBox="0 0 400 240" width="400" height="240" shape-rendering="crispEdges">'"$screen_inner"'</svg>$'
for scene in "${scenes[@]}"; do
    file="$dir/$scene.svg"
    [[ -f "$file" && ! -L "$file" ]] || fail "$scene.svg is not a regular file"
    [ "$(stat -c %s "$file")" -le "$MAX_BYTES" ] || fail "$scene.svg is over $MAX_BYTES bytes"
    [ "$(wc -l <"$file")" -eq 1 ] || fail "$scene.svg is not one line"
    grep -Eq "$svg_shape" "$file" || fail "$scene.svg is not in the shape site_screens.c writes"
done
# The style compose_device_svg.sh adds, taken out before the shape is matched, so
# the pattern below needs no copy of its punctuation.
dark_lcd_style="$(sed -n "s/^DARK_LCD_STYLE='\(.*\)'$/\1/p" "$SCRIPT_DIR/compose_device_svg.sh")"
[ -n "$dark_lcd_style" ] || fail "compose_device_svg.sh names no DARK_LCD_STYLE"
number='-?[0-9]{1,5}(\.[0-9])?'
device_shape='^<svg xmlns="http://www\.w3\.org/2000/svg" viewBox="0 0 [0-9]{1,5} [0-9]{1,5}" width="[0-9]{1,5}" height="[0-9]{1,5}">(<path fill="#[0-9a-f]{6}"( fill-opacity="0\.[0-9]")? d="([MLQCZ]|'"$number"'| )*"/>)*<svg class="lcd" x="'"$number"'" y="'"$number"'" width="'"$number"'" height="'"$number"'" viewBox="0 0 400 240" shape-rendering="crispEdges">'"$screen_inner"'</svg>(<path fill="#[0-9a-f]{6}"( fill-opacity="0\.[0-9]")? d="([MLQCZ]|'"$number"'| )*"/>)*</svg>$'
file="$dir/device.svg"
[[ -f "$file" && ! -L "$file" ]] || fail "device.svg is not a regular file"
[ "$(stat -c %s "$file")" -le "$MAX_BYTES" ] || fail "device.svg is over $MAX_BYTES bytes"
[ "$(wc -l <"$file")" -eq 1 ] || fail "device.svg is not one line"
device="$(cat "$file")"
root="${device%%>*}>"
[[ "${device:${#root}:${#dark_lcd_style}}" == "$dark_lcd_style" ]] ||
    fail "device.svg does not open with the style compose_device_svg.sh adds"
# Any other style fails the shape below, which has no style in it.
stripped="$root${device:$((${#root} + ${#dark_lcd_style}))}"
grep -Eq "$device_shape" <<<"$stripped" || fail "device.svg is not in the shape SiteDeviceSvgTest and compose_device_svg.sh write"

echo "The screens artifact holds ${#scenes[@]} screens and the device in the shapes site_screens.c and SiteDeviceSvgTest write, and nothing else."
