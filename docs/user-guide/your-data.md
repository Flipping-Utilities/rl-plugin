# Your data

[User guide](README.md) › Your data

## Multiple accounts

Each account's trades are kept apart. Once two or more accounts have trades, the account selector at the top of
the panel lets you view one account or **Accountwide**, which merges them all.

In the Accountwide view:

- profit matches buys and sells across accounts, so buying on one account and selling on another still counts
  as profit. The **Flips** list of an item only pairs offers of the same account, so the two can differ;
- favoriting an item or changing its quick search code applies to every account;
- offers can't be deleted and recipe flips can't be recorded; select the account first.

If you run several RuneLite clients, each picks up the trades saved by the others.

Ironman accounts and seasonal worlds are not tracked.

## Hardcore and Deadman worlds

Trades on hardcore worlds have their own market, so they are kept in a separate history with prices from the
wiki's hardcore market. Hopping between a hardcore and a regular world saves one history and loads the other.

On Deadman worlds the wiki prices come from the Deadman market and are shown in red, but trades go into your
regular history.

## Where trades are stored

Everything is stored on your computer, in RuneLite's data folder for the plugin:
`.runelite/plugin-data/flipping-utilities` in your home folder (older installs may still use
`.runelite/flipping`). Hardcore histories are in its `hc` subfolder.

| File | Contents |
| --- | --- |
| `<account name>.json` | That account's offers, recipe flips and slot timers |
| `<account name>.backup.json` | A backup of it |
| `accountwide.json` | Offer editor options, card layout, custom recipes, slot enhancement and your Flipping Utilities session |

Your trades are saved when you log out, close RuneLite or turn the plugin off, and on a timer if
[auto-save](settings.md#auto-save) is on. Each time the plugin starts, it backs up every account that changed
since its last backup. If an account's file can't be read, the plugin loads the backup instead.

To move your history to another computer, copy this folder while RuneLite is closed.

## How profit is calculated

- **GE tax.** Sells pay 2% tax, rounded down, at most 5,000,000 coins per item (items sold for 250m or more
  pay the cap). Sells before 29 May 2025 used the old 1% rate, and sells before the tax was introduced in
  December 2021 pay none. Items exempt from tax are taken into account. Your sell prices are stored before tax;
  profit, totals and the stats tab use the price after tax. "Pre tax" on a card marks the exceptions.
- **Offer price.** An offer that filled at several prices uses its average: coins spent divided by quantity.
- **Profit.** For each item, the plugin takes the smaller of the quantity bought and the quantity sold in the
  time range, and subtracts the cost of that many bought items, in order, from the after-tax value of that many
  sold items. Items you bought but haven't sold yet don't count as a loss.
- **ROI** is that profit divided by the cost of the items it matched.
- **Margin checks** are offers for one item that complete within two game ticks. They give the *insta buy* and
  *insta sell* prices of the cards; a margin check buy and sell less than a minute apart is shown as one
  **Margin Checked** flip.
- **Recipe flips** count the outputs sold minus the inputs bought and any coins you added. The offers they use
  are removed from their items' own statistics.
- **GE limit.** The 4-hour window starts at your first buy after the last reset. The remaining limit is the
  item's limit minus what you bought in the window.
