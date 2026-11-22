# Settings

[User guide](README.md) › Settings

The plugin's settings are in RuneLite's configuration panel: open the wrench icon of the sidebar, find
**Flipping Utilities** and click its gear. Settings that are part of the plugin panel itself are described where
they appear: [card layout](flipping-tab.md#customizing-the-cards), [offer editor options](offer-setup.md) and
[slot enhancement](account-linking.md#once-logged-in).

## General

| Setting | Default | What it does |
| --- | --- | --- |
| Set ROI gradient range limit | 2% | The ROI at which the [ROI value](flipping-tab.md#item-cards) on the cards is bright green. Lower ROIs fade to yellow and red. |
| Account for margin check loss | On | Currently has no effect. |
| 12 hour format | On | Currently has no effect: the GE limit reset time is always shown in 12-hour format. |
| Calculate potential profit from remaining GE limit | Off | Off: **Potential profit** uses the item's full GE limit. On: only what's left of it in the current window. |
| Set trade stagnation time | 15 minutes | After how long without trading a [slot timer](slots.md#in-game-slot-timers) turns red. |
| toggle slot timers | On | Show the timers on the GE slots. |
| toggle colored slot borders | On | Color the borders of the GE slots (needs [slot enhancement](slots.md#in-game-slot-borders-and-quick-look)). |
| toggle verbose view | On | Off: cards in the [flipping tab](flipping-tab.md) start collapsed. |
| slot timer buy text color | Yellow-orange | Color of the **Buy** text of the slot timers. |
| slot timer sell text color | Yellow | Color of the **Sell** text of the slot timers. |

## Charts

| Setting | Default | What it does |
| --- | --- | --- |
| Quick lookup | On | Show the magnifier and its [quick look tooltip](slots.md#in-game-slot-borders-and-quick-look) on the GE slots. |
| Offer page charts | On | Show the [price chart](offer-setup.md#offer-page-chart) on the offer screens. |
| Time range | Last 24 Hours | Default range of the price charts: Last 24 Hours, Last 7 Days, Last 1 Month or Last Year. |

## Auto-save

The plugin saves your trades when you log out, close RuneLite or turn the plugin off. Auto-save also saves them
on a timer, in case RuneLite crashes.

| Setting | Default | What it does |
| --- | --- | --- |
| Enable auto-save | Off | Save trades at a regular interval. |
| Auto-save interval | 10 minutes | How often. |
| Show countdown timer | On | Show **Next auto-save** in the [stats tab](statistics.md) details. |

## Custom recipes

| Setting | Default | What it does |
| --- | --- | --- |
| Include noted items | Off | Offer noted items in the item search of the [custom recipe](recipe-flips.md#custom-recipes) editor. |
| Include untradeable items | Off | Offer untradeable items in that search. |
| Default sort option | Input Count | How the custom recipe manager sorts recipes: Input Count, Output Count or Name. |
| Default sort direction | Ascending | Ascending or descending. |
