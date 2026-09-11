# Screenshots

`sidebar.png` is **rendered, not captured**. It is the real Swing component
tree the plugin builds — `MainPanel` and everything under it — laid out
headlessly and painted to a PNG, with real item sprites. No game client is
involved, so it can be regenerated on any machine and cannot quietly drift
from the product the way a screen grab taken once and forgotten does.

That matters because this is the first thing anyone sees when they click
through from the plugin hub, and it will need redoing every time the panel
changes.

## Regenerating

The renderer lives with the headless test harness rather than in `src/`,
since it is documentation tooling and has no business in the built jar. It
needs three things:

- the harness's `net.runelite.*` stubs, so the panels compile without the
  client jar;
- a directory of `<itemId>.png` item sprites, passed as
  `-Dpocketge.sprites=<dir>`. Without it everything still renders and the
  item icons come out blank.
- `-Djava.awt.headless=true`.

Two quirks are worth knowing before changing it, because both cost an hour
the first time:

1. **Lay out at the panel's own width (242px) from the start.** Laying out
   narrower first and widening afterwards leaves the recommendation card
   holding the earlier bounds, and the render shows text cut off that is not
   cut off in the product.
2. **Labels need widening after layout.** Swing sizes a `JLabel` to
   `FontMetrics.stringWidth` and then clips its text to that width. Headless,
   with no fontconfig, the glyphs actually drawn are a few pixels wider than
   that measurement, so the last letter of a line gets sliced in half. The
   renderer walks the tree afterwards and grows any label that needs it, into
   space its parent already has spare. This is a quirk of rendering here, not
   a bug in the panels — they ship their own fonts in the real client.
