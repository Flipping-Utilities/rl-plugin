JSON storage and recovery

The plugin uses Java 11 and RuneLite's existing Gson. It has one JSON persistence path and no SQLite dependency. Full history is still loaded into memory; pagination, lazy loading and a disk query index are deliberately outside this change.

Data lives in `.runelite/flipping/json-v2/`:

| File | Purpose |
| --- | --- |
| `checkpoint.json` | Versioned JSON records at a committed revision, with a SHA-256 checksum. |
| `checkpoint.previous.json` | Previous checkpoint. The journal retains the transactions needed to recover from it. |
| `journal.jsonl` | One complete JSON transaction per line: revision, changed records, deleted keys and checksum. |
| `store.lock` | Coordinates writers and recovery across RuneLite processes. |
| `conflict-<id>.json` | A client's captured data when another client has changed the same account. The message in the Storage status identifies the file. |

Normal saves append changed records. They do not rewrite every trade in an account. After the journal reaches the checkpoint threshold (8 MiB), storage publishes a new checkpoint and removes only journal entries already covered by the retained previous checkpoint. Temporary files are unique and in the same directory.

The container version is `2`; account and account-wide record schemas currently use version `1`. Account keys use URL-safe Base64 encoding of the display name, avoiding filename/path interpretation. Each account has a small metadata record, item metadata, individual ordered history offers, slot snapshots, recipe groups and recipe flips. Item metadata retains favorites, visibility and GE-limit counters. UI widgets, derived item statistics and slot timers are reconstructed after loading.

Offer prices are signed 64-bit integers and timestamps use ISO-8601 strings with nanosecond precision. Recipe components retain independent offer snapshots: the same offer UUID can refer to an older snapshot than visible trade history. Unresolved recipe references remain unresolved; the codec never invents zero-price trades. Record positions preserve history and recipe order. Unsupported schema versions, malformed records and duplicate identities fail explicitly.

Migration:

1. Read all legacy account files and account-wide settings. Historical numeric, ISO and object-shaped timestamps remain supported. If a primary account cannot be read, try its backup, including accounts whose only remaining file is a backup.
2. Normalize legacy offer identities and validate the complete new representation before publishing the initial store.
3. Publish the initial checkpoint and journal under the store lock. Concurrent first launches use the first completed migration.
4. Use `json-v2` thereafter. Original account JSON, backups and older source files are left unchanged, including after an account is deleted from the new store. They are migration sources, not ongoing mirrors.

Unreadable input stops migration rather than importing empty data. The maintainer confirmed SQLite was never shipped, so no SQLite importer is needed or included. An older plugin version will still read the retained legacy files, which do not contain subsequent changes made by this version.

Saving and multiple clients:

- Saving is always enabled. Once per second, the client thread captures pending changes into detached JSON records; one ordered worker performs disk I/O. Session-time-only updates capture just account metadata. A process crash can still lose changes that have not yet reached a successful durable commit, including queued work.
- The Statistics panel displays Saved, Saving… or Needs attention. A save becomes acknowledged only after the journal has been forced to disk. Failed saves stay pending. Logout requests a save; shutdown drains the preceding save, captures newer changes and waits for the final write.
- One journal transaction covers all captured account changes, deletions and account-wide settings. The interprocess file lock covers reading the latest revision, checking the client's baseline and publishing the transaction.
- Different accounts can be saved independently by different clients. A stale writer for the same account is rejected, including attempts to recreate an account another client deleted. Its captured data is retained in a recovery JSON file and the UI reports the conflict. This is conflict detection, not automatic trade merging.
- External refreshes never replace dirty local accounts. Callback generation checks prevent an old disk read from replacing a newer local save. Overlapping notifications are coalesced and rechecked after pending work.

Recovery:

- A final journal fragment without a newline is an interrupted, uncommitted transaction. Recovery keeps the preceding complete transactions and truncates that fragment under the lock.
- A damaged primary checkpoint can be rebuilt from the previous checkpoint plus the retained journal. Checksums and revision continuity reject damaged complete transactions or gaps; the plugin preserves those files for investigation instead of silently skipping committed history.
- Missing/unreadable checkpoints and unsupported versions do not cause an empty store to be written over existing data. Preserve the entire `json-v2` directory and the original legacy files before manual recovery.
- For a concurrent-edit conflict, close the other client before investigating the indicated recovery file. Keep both the canonical store and recovery file. A maintainer must reconcile the intended account changes; replacing the entire checkpoint with a conflict file can discard another client's valid updates.
- Checkpoints require atomic replacement. If the filesystem does not support it, publication fails safely. File contents are explicitly synchronized; directory synchronization is attempted where Java/the filesystem support it. This does not claim universal power-loss guarantees on unsupported or remote filesystems.

Mapping to the seven identified issues:

| Issue | Change and remaining limit |
| --- | --- |
| Autosave off / ten-minute default | Mandatory one-second capture, durable acknowledgment, visible errors and shutdown drain. Uncommitted/queued changes can still be lost on abrupt termination. |
| Whole-account rewrites | Independent records and journal deltas; full checkpoints occur periodically. Full account capture still examines its history for non-session edits. |
| Eager startup loading | Parsing, migration and disk I/O run on the storage worker. Full history remains resident by request; RuneLite-dependent hydration still runs on the client thread. |
| Competing clients and unsafe reloads | Lock plus account baseline checks, durable conflict copies, dirty/generation guards and coalesced external refreshes. |
| Weak durability / separate commits | Forced journal transaction spanning accounts/settings; checksummed checkpoints and required atomic publication. Directory-sync portability limits remain. |
| Coarse corruption recovery | Previous checkpoint plus journal replay, interrupted-tail recovery, strict validation and untouched migration sources. Damaged complete transactions require recovery rather than silent data loss. |
| Aggregate history processing | Account-wide aggregation sorts once per item instead of repeatedly sorting growing merged histories; existing view caching is retained. Queries still use the full in-memory model, as requested. |

Validation uses temporary directories and synthetic histories, including 50,000 trades and 200 recipe flips. Tests cover migration parity, independent snapshots, large prices, timestamp precision, concurrent clients, deletions, checksum failures, checkpoint recovery, interrupted initialization/writes, dirty retry, shutdown and stale refresh callbacks. No player's private trade files are required.
