Your goal is to migrate the data storage from the current json store to sqlite.
Review the current json storage structure and its pros and cons.
Review the sqlite branch on how to use a simple sqlite implementation,, but do not copy its implementation.

The final sqlite schema should be similar to this proposed format:

- Slot
    Same data as current storage: Only in progress slots (max 8 per account)
- Account
    - id
    - rsn
    - playerId (in game user id, to be used across name changes)
- Trade
    - id
    - timesamp
    - item_id
    - total_qty
    - total_price
    - account_id
- consumed_trade
    - id
    - trade_id
    - qty
    - event_id (nullable?)
- event
    - id
    - timestamp
    - type (string: "flip" | "recipe" | "repair" | "..")
    - cost
    - profit
    - note (Free text from the user)

You can "void" a trade (ex: Something you consumed yourself) by not having an event_id within the store.

You also will need a table for recipes and other metadata, like plugin token and settings.

Your final goal is to allow the migration of all existing data to this data structure, backporting all flips from the old format to this new one. 
The UI when configured to use this format should run SQLite queries to the UI to get the relevant data on-the-fly,, instead of storing and computing everything in memory.

Both systems must be able to work in parallel: The old system must remain working even if the user has selected the new system.
When the user picks the new system, the data will be migrated, and all data displayed must be in reference to this new system.

Ask any questions to clarify requirements or gain insight on this task.

