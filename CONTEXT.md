# Flipping Utilities

Flipping Utilities tracks Grand Exchange trading and recipe conversions for RuneScape accounts, including the acquisition costs and proceeds of sold items.

## Language

**Account**:
A player's trading history and preferences. An account-wide report combines results across accounts without treating their inventories as shared.

**Offer**:
A buy or sell order placed in a Grand Exchange slot. One offer can be partially filled over time; reusing its slot starts a different offer.
_Avoid_: Using “offer” to mean both the order and every observation of it.

**Offer observation**:
The cumulative state of an offer when the plugin sees it, including the quantity filled so far. Several observations can describe the same offer.

**Observed fill batch**:
The additional filled quantity and value learned from an offer observation. A batch may contain several executions whose individual prices and times are unavailable.
_Avoid_: Execution, when individual exchange executions are not known.

**Ordinary flip**:
A sale of an item associated with purchases of that same item in the same account. A flip may have several sale batches and may have an unknown acquisition cost for some units.

**Margin check**:
A small buy and sell used to estimate an item's trading margin. Its proceeds, cost, and tax remain part of the account's trading results.

**Recipe flip**:
A player-recorded conversion that associates purchased inputs, sold outputs, and any additional coin cost. Its components belong to one account and can involve different items.

**Cost basis**:
The acquisition cost assigned to sold units. Buying an item alone does not realize profit.

**Realized profit**:
Under sale-time accounting, gross sale proceeds less the associated acquisition cost, tax, and other allocated costs, recognized in the period of the sale. An eligible purchase from an earlier period still supplies the cost basis for that sale.

**Unknown basis**:
A sold quantity for which the acquisition cost is unavailable. Unknown cost is distinct from a known zero cost.

**Open inventory**:
Recorded purchases not yet assigned to ordinary sales or recipe inputs. It describes tracked inventory, which may differ from the player's actual inventory when history is incomplete.

**Accounting mode**:
The calculation policy a player chooses for an account's reports: legacy calculation, sale-time calculation, or a transition between them. It is independent of where the history is stored.

**Cutover**:
The chosen instant separating preserved earlier reporting from a new accounting period. The player can instead start a new period while keeping earlier history only as an archive.

**Opening inventory**:
Purchased quantities the player elects to carry into a new accounting period, together with their supported acquisition costs. An unallocated purchase in old history is a candidate, not proof that the item is still owned.

**Purchase cutoff**:
The earliest purchase date eligible for opening inventory. It does not delete old purchases, reset their price to zero, or expire carried inventory later.
