# Testing 0.6.5 before it goes to the hub

Three commits sit on `main` past what the hub is serving. Two of them change how
**money is counted**, which is the part of this plugin that must not be wrong, so
this is ordered by risk rather than by feature.

**Back up first.** A cost-basis bug would corrupt real numbers, and the state
file is the only copy:

```
cp ~/.runelite/settings.properties ~/settings.properties.bak
cp -r ~/.runelite/pocketge-flip-tracker ~/pocketge-ledger.bak
```

## Running it

From a clone of `main`, in IntelliJ or your IDE of choice, run
`PocketGeTrackerPluginTest.main()` — it starts a full RuneLite client with the
plugin loaded. That is the only way to test this build; the hub copy is 0.6.3.

## The instrument

Don't judge cost basis by squinting at the sidebar. The tracker's entire state is
one JSON blob in RuneLite's config:

```
grep '^pocketgetracker.state=' ~/.runelite/settings.properties \
  | sed 's/^pocketgetracker\.state=//; s/\\\([:=]\)/\1/g' \
  | python3 -m json.tool
```

Two keys matter:

- `openBuys` — `itemId: [[qty, gpSpent, fillTimeMillis], ...]`. This is your cost
  basis. A `fillTimeMillis` of `0` means "bought at a moment nobody recorded",
  which is correct for anything seeded or filled offline.
- `slotsByAccount` — `accountHash: {slot: [itemId, isBuy, qtySold, spent, price, totalQty]}`.
  This is what stops the same offer being counted twice.

(If that key isn't in the file, RuneLite may be syncing config to your account
instead — fall back to the sidebar observables noted in each test.)

The closed-flip ledger is plain JSONL and easier:

```
tail -3 ~/.runelite/pocketge-flip-tracker/flips.jsonl | python3 -m json.tool
```

---

## T1 — Relogging must not inflate your cost basis  ← the one that matters

New in 0.6.5: an offer the plugin meets for the first time gets its
already-filled portion **booked**, using the units and gold the offer itself
reports. The hazard is obvious — if a relog makes an offer look new again, the
same gold gets counted every time you log in.

1. Put out a buy offer and let it partially fill.
2. Dump `openBuys` for that item. Note the numbers.
3. Log out. Log back in. Dump again.
4. Do that **three more times**, without letting the offer fill any further
   (pick something slow, or price it badly on purpose).

**Pass:** the `[qty, spent]` for that item is identical every time.
**Fail:** it grows on each login. Stop testing and tell me — restore the backup.

Sidebar fallback: the **Unrealized** figure in the stats header must not move
across relogs when nothing has filled.

## T2 — The first login after upgrading must NOT jump

Your current state came from a build with no slot baselines in it, so its open
lots may already cover part of whatever is in your slots right now. The upgrade
is supposed to take one pass of the old behaviour to avoid double counting.

1. With an offer already running, note `openBuys` **before** first launching
   0.6.5 (from the backup you just made).
2. Launch 0.6.5, log in, dump `openBuys` again.

**Pass:** unchanged, apart from anything that genuinely filled in between.
**Fail:** the quantity roughly doubles for an item you have an offer out on.

## T3 — Offers that fill while you're logged out get counted

This is the fix you asked for.

1. Place a buy offer on something that fills steadily. Note `openBuys`.
2. **Fully close the client.** Wait for it to fill further.
3. Reopen and log in.

**Pass:** `openBuys` for that item has grown by exactly what filled, and the new
lot's third field (`fillTimeMillis`) is `0` — the gold is exact, the moment isn't
knowable, and it must not pretend otherwise.
**Fail:** nothing was added; or the new lot carries a real timestamp.

## T4 — The offer sitting finished in a slot

The case the tick sweep exists for: collecting an offer wipes it, so the plugin
has to read it *before* you collect.

1. Let a buy offer complete (100%). Don't collect it.
2. Note `openBuys`, then collect it.

**Pass:** the full quantity is in `openBuys`, both before and after collecting.
**Fail:** collecting drops it.

---

## T5 — The card that started all this

Find a sell suggestion for a stack the plugin only partly tracked.

**Pass:** the headline is a signed P&L, and **red if you're underwater**, with
`bought N at X gp ea` above it and `+Y gp from M at unknown cost` below.
**Fail:** a large green number on a stack you know you're losing on. That's the
original bug back.

## T6 — A stack with no tracked cost

**Pass:** `N gp sale value` in plain cream — *not* profit green — with
`Cost unknown — proceeds, not profit` under it.
**Fail:** green, or the word "profit" anywhere on that card.

## T7 — Bank tooltip

Hover a sellable stack in your bank.

**Pass:** `Worth selling: X gp after tax`, where X is roughly quantity × price.
**Fail:** a small number (it'd be showing profit) or a negative one.

## T8 — Stats header and flip rows

**Pass:** `Profit` labels the big number; row captions are bold and brighter;
under the header, your last five flips newest-first as `18K × Item` with signed
profit in green/red. Hover gives buy, sell, tax and hold; a flip with no recorded
buy time says `Held — (buy time not recorded)`, never `0:00:00`. Clicking a row
opens that item's chart.

## T9 — Free-to-play

You're on F2P. With all three slots busy, the advisor should stop proposing new
buys and talk about the offers you already have out. Nothing should mention eight
slots.

---

## If something's wrong

Send me:

1. The `openBuys` and `slotsByAccount` blocks, before and after.
2. A screenshot of the card.
3. Anything in RuneLite's log — `~/.runelite/logs/client.log` — mentioning
   `pocketge`.

The one I'd most want to hear about immediately is **T1**. Everything else is
recoverable by reading; a double count quietly corrupts the record.
