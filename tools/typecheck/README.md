# Offline type-check

`./tools/typecheck/check.sh` compiles the whole plugin and runs the unit tests
**without** `net.runelite:client`.

## Why this exists

`gradle build` needs the RuneLite client jar from `repo.runelite.net`. Where that
host is unreachable, nothing in `src/main/java` can be compiled at all — and the
largest and most-edited file, `PocketGeTrackerPlugin.java`, is the one that
imports the most RuneLite API, so it is exactly the file that goes unchecked.
That means changes can be written, unit-tested and pushed having never once been
put in front of a compiler. This closes that gap.

`stubs/` holds hand-written, signature-only versions of the RuneLite (and okhttp)
API this plugin touches. Method bodies throw or return zero; nothing here runs.
They exist so `javac` can do its job.

## What it is not

**Not a substitute for the real build.** A stub only proves the code is
consistent with what the stub *says* the API is. CI, and the plugin hub's own
build, remain the authority.

That makes fidelity the whole game: a stub whose signature differs from the real
API is *worse than no stub*, because it turns a red CI build into a green local
one. So every file here was written against upstream source at tag
`runelite-parent-1.12.38`:

```
https://raw.githubusercontent.com/runelite/runelite/runelite-parent-1.12.38/runelite-api/src/main/java/net/runelite/api/<Type>.java
https://raw.githubusercontent.com/runelite/runelite/runelite-parent-1.12.38/runelite-client/src/main/java/net/runelite/client/<path>
```

okhttp is checked against `square/okhttp` at `parent-4.12.0`, translated from
Kotlin to the signatures a Java caller sees — the `@get:JvmName` annotations are
why `Response` exposes `code()` and `body()` rather than `getCode()`/`getBody()`.

One file is not verified that way: `net/runelite/http/api/item/ItemPrice.java`.
`net.runelite.http.api.*` is not published in the `runelite/runelite` tree — every
ref 404s. Its two members are pinned instead by `ItemManager.search(String)`
being declared `List<ItemPrice>` upstream, and by the call site that consumes it
already having been compiled successfully by the plugin hub. Its header says so.

This is already worth its keep: standing it up caught `ItemManager.search()`
declared as returning `List<net.runelite.client.game.ItemPrice>`, a package that
does not exist upstream.

## Keeping it honest

When RuneLite's pinned version moves, or a change starts calling API that isn't
stubbed yet, `javac` will say so. Add the member by **reading the real
declaration**, not by inferring it from the call site — inferring is how a stub
drifts into agreeing with the code instead of with RuneLite.

`Client` and `Widget` declare their methods `default` so the test fakes don't
have to restate the whole interface. That is invisible to callers, which is all
this harness type-checks.

Nothing here ships. The plugin hub builds `src/main/java` with its own
`build.gradle`; this directory is inert to it. Deleting `tools/` would cost only
this check.
