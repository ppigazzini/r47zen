#!/bin/bash
# Lay a rendered LCD screen into the Android calculator SVG.
#
# SiteDeviceSvgTest draws the replica through the app's own code and leaves its LCD
# window an empty <svg class="lcd" .../> at the place the shell draws the LCD. This
# fills that window with the screen's background rect and pixel path, and adds the
# one style the device carries: under a dark color scheme the LCD turns negative,
# light pixels on dark glass, as the app's Negative LCD setting draws it and as the
# page shows the other screens. check_screens_artifact.sh accepts this exact style
# and nothing else.
#
# Usage: compose_device_svg.sh <device.svg> <screen.svg> <output.svg>

set -euo pipefail

device="${1:?usage: $0 <device.svg> <screen.svg> <output.svg>}"
screen="${2:?usage: $0 <device.svg> <screen.svg> <output.svg>}"
output="${3:?usage: $0 <device.svg> <screen.svg> <output.svg>}"

DARK_LCD_STYLE='<style>@media (prefers-color-scheme: dark) { .lcd rect { fill: #363436; } .lcd path { fill: #e6e6e6; } }</style>'

DARK_LCD_STYLE="$DARK_LCD_STYLE" perl -0777 -e '
    my ($device_file, $screen_file, $output_file) = @ARGV;
    local $/;
    open(my $d, "<", $device_file) or die "cannot read $device_file\n";
    my $device = <$d>;
    open(my $s, "<", $screen_file) or die "cannot read $screen_file\n";
    my $screen = <$s>;
    $screen =~ /^<svg [^>]*shape-rendering="crispEdges">(<rect [^>]*\/><path [^>]*\/>)<\/svg>\n?\z/
        or die "$screen_file is not a screen in the shape site_screen_svg.c writes\n";
    my $inner = $1;
    my $windows = () = $device =~ /<svg class="lcd" [^>]*\/>/g;
    $windows == 1 or die "$device_file holds $windows empty LCD windows, not one\n";
    $device =~ s/(<svg class="lcd" [^>]*)\/>/$1 shape-rendering="crispEdges">$inner<\/svg>/;
    $device =~ s/^(<svg [^>]*>)/$1$ENV{DARK_LCD_STYLE}/ or die "$device_file has no root svg\n";
    open(my $o, ">", $output_file) or die "cannot write $output_file\n";
    print $o $device;
    close($o) or die "cannot finish $output_file\n";
' "$device" "$screen" "$output"
