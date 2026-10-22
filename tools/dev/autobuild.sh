#!/usr/bin/env bash
# Keeps a local checkout built: whenever the branch you are on moves on its
# remote, pull it and run the same build you would by hand
# (./gradlew clean build -x test). Start it once and leave the terminal
# open; when it says "ready", restart the client. (On Windows use
# autobuild.ps1, next to this file.)
#
#   ./tools/dev/autobuild.sh          check every 20 seconds
#   ./tools/dev/autobuild.sh 60       check every 60 seconds
#
# It only fast-forwards: with local changes or local commits in the way it
# says so and waits, rather than merging or throwing anything away.

set -u
cd "$(dirname "$0")/../.." || exit 1

every="${1:-20}"
branch="$(git rev-parse --abbrev-ref HEAD)"
remote="$(git config "branch.$branch.remote" || echo origin)"

build()
{
	echo "[$(date +%H:%M:%S)] building $(git log -1 --format='%h %s')"
	if ./gradlew clean build -x test -q; then
		printf '\a'
		echo "[$(date +%H:%M:%S)] ready: $(git log -1 --format='%h %s') — restart the client"
	else
		echo "[$(date +%H:%M:%S)] build failed; waiting for the next push"
	fi
}

echo "Watching $remote/$branch every ${every}s. Ctrl+C to stop."
git fetch -q "$remote" "$branch" 2>/dev/null && git merge --ff-only -q "$remote/$branch" 2>/dev/null
build
stuck=""
while true
do
	sleep "$every"
	git fetch -q "$remote" "$branch" 2>/dev/null || continue
	if [ "$(git rev-parse HEAD)" = "$(git rev-parse "$remote/$branch")" ]
	then
		continue
	fi
	if ! git merge --ff-only -q "$remote/$branch" 2>/dev/null
	then
		target="$(git rev-parse "$remote/$branch")"
		if [ "$target" != "$stuck" ]
		then
			stuck="$target"
			echo "[$(date +%H:%M:%S)] can't update to $remote/$branch - these local changes are in the way:"
			git status --short
			echo "  Run 'git stash' to set them aside ('git stash pop' brings them back); this then carries on by itself."
		fi
		continue
	fi
	stuck=""
	build
done
