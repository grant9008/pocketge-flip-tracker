#!/bin/sh
# Type-check the whole plugin, and run the unit tests, without net.runelite:client.
#
#   ./tools/typecheck/check.sh
#
# Needs a gson jar and (for the tests) junit + hamcrest. It looks in the usual
# places; override with CP=/path/a.jar:/path/b.jar if it guesses wrong.
set -e

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
STUBS="$ROOT/tools/typecheck/stubs"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT INT TERM

if [ -z "$CP" ]; then
	for d in /opt/gradle-*/lib "$HOME/.gradle/caches/modules-2/files-2.1"; do
		[ -d "$d" ] || continue
		GSON=$(find "$d" -name 'gson-*.jar' 2>/dev/null | head -1)
		JUNIT=$(find "$d" -name 'junit-4*.jar' 2>/dev/null | head -1)
		HAM=$(find "$d" -name 'hamcrest-core-*.jar' 2>/dev/null | head -1)
		[ -n "$GSON" ] && break
	done
	CP="$GSON:$JUNIT:$HAM"
fi
case "$CP" in
	*gson*) ;;
	*) echo "no gson jar found; set CP=..." >&2; exit 2 ;;
esac

echo "== compiling stubs + src/main + src/test =="
# PocketGeTrackerPluginTest boots a real RuneLite client, so it is the one file
# that genuinely cannot build without the real jar. Everything else can.
javac -nowarn -encoding UTF-8 -d "$OUT" -cp "$CP" \
	$(find "$STUBS" "$ROOT/src/main/java" -name '*.java') \
	$(find "$ROOT/src/test/java" -name '*.java' ! -name 'PocketGeTrackerPluginTest.java')

echo "== running unit tests =="
TESTS=$(find "$ROOT/src/test/java" -name '*Test.java' ! -name 'PocketGeTrackerPluginTest.java' \
	| sed 's|.*/java/||; s|\.java$||; s|/|.|g' | tr '\n' ' ')
java -Djava.awt.headless=true -cp "$CP:$OUT" org.junit.runner.JUnitCore $TESTS

echo
echo "OK — everything compiles and the tests pass."
