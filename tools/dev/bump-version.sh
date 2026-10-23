#!/usr/bin/env bash
# Moves the version on by one patch — 0.7.3 to 0.7.4 — in the three places
# that carry it: runelite-plugin.properties (the one the plugin hub shows),
# build.gradle, and BuildInfo.VERSION (the one in the panel's footer and the
# report). BuildInfoTest fails if they ever disagree.
#
# Run once per update, before committing it, so every update has its own
# number in the footer: "add more detailed version numbers ... like personal
# space has version 1.9.37 way easier for me to confirm updates / latest
# version im on".
#
#   tools/dev/bump-version.sh          # 0.7.3 -> 0.7.4
#   tools/dev/bump-version.sh 0.8.0    # set it outright

set -euo pipefail
cd "$(dirname "$0")/../.."

old=$(sed -n 's/^version=//p' runelite-plugin.properties)
if [ -n "${1:-}" ]; then
	new=$1
else
	IFS=. read -r major minor patch <<<"$old"
	new="$major.$minor.$((patch + 1))"
fi
case "$new" in
	[0-9]*.[0-9]*.[0-9]*) ;;
	*) echo "not a version: $new" >&2; exit 2 ;;
esac

sed -i "s/^version=.*/version=$new/" runelite-plugin.properties
sed -i "s/^version = '.*'/version = '$new'/" build.gradle
sed -i "s/static final String VERSION = \".*\";/static final String VERSION = \"$new\";/" \
	src/main/java/com/pocketge/tracker/BuildInfo.java

grep -q "^version=$new$" runelite-plugin.properties
grep -q "^version = '$new'$" build.gradle
grep -q "static final String VERSION = \"$new\";" src/main/java/com/pocketge/tracker/BuildInfo.java
echo "$old -> $new"
