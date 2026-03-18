# SQLite Migration - Remaining Tasks

## Overview

Migrating Flipping Utilities from JSON file storage to SQLite database with:
- Minimal in-memory data (on-demand SQL queries)
- Repository pattern for data access
- Full CRUD operations

**Key Constraint**: "The end goal is for this to be purely SQLite, so the feature must be ported to the optimal sqlite structure, and not have sqlite conform to the json format. There must be as little as possible in memory, everything must be loaded on new queries"

---

## Phase 1: Wire UI to FlipRepository

### 1.1 Create JsonFlipRepository
- [ ] Create `JsonFlipRepository.java` implementing `FlipRepository`
- [ ] Wrap existing `TradePersister` and `AccountData` access
- [ ] Implement `getItemSummaries()` using in-memory data
- [ ] Implement `getTradesForItem()` using FlippingItem history
- [ ] Implement `getAggregateStats()` using HistoryManager
- [ ] Implement `getItemCount()` from AccountData
- [ ] Implement `getSessionTime()` from AccountData

### 1.2 Add Repository Routing to FlippingPlugin
- [ ] Add `FlipRepository flipRepository` field to `FlippingPlugin.java`
- [ ] Create `getFlipRepository()` method with routing:
  - If `config.dataSource().isSqlite()` → `SqliteFlipRepository`
  - Else → `JsonFlipRepository`
- [ ] Initialize repository in `startUp()` after DataHandler

### 1.3 Update StatsPanel
- [ ] Locate stats aggregation code in `StatsPanel.java`
- [ ] Replace in-memory data access with `flipRepository.getAggregateStats()`
- [ ] Ensure DB operations run off Swing thread
- [ ] Update UI on callback

### 1.4 Update FlippingPanel
- [ ] Locate item list rendering in `FlippingPanel.java`
- [ ] Replace `viewItemsForCurrentView()` with `flipRepository.getItemSummaries()`
- [ ] Handle pagination via repository methods
- [ ] Ensure DB operations run off Swing thread

### 1.5 Verify End-to-End
- [ ] Delete JSON files after migration
- [ ] Start plugin with SQLite config
- [ ] Verify stats panel shows correct data
- [ ] Verify flipping panel shows item list
- [ ] Verify pagination works
- [ ] Compile and test: `./gradlew compileJava && ./gradlew test`

---

## Phase 2: Wave 4 Testing Tasks

### 2.1 Task 21 - Migration Rollback Tests
- [ ] Create `src/test/java/com/flippingutilities/db/MigrationRollbackTest.java`
- [ ] Test: Inject failure mid-migration, verify JSON unchanged
- [ ] Test: Corrupt JSON file, verify migration skips with warning
- [ ] Test: Toggle config back to JSON after migration, verify data intact
- [ ] Use hash comparison for JSON files

### 2.2 Task 22 - Dual-Write Consistency Tests
- [ ] Create `src/test/java/com/flippingutilities/db/DualWriteConsistencyTest.java`
- [ ] Test: Write with SQLITE config, verify both JSON and SQLite updated
- [ ] Test: Simulate SQLite failure, verify JSON still written
- [ ] Test: Toggle to JSON mode, verify reads from JSON
- [ ] Verify best-effort behavior (errors logged, not thrown)

### 2.3 Task 23 - Performance Validation and Indexing
- [ ] Create performance test with 50k trades fixture
- [ ] Verify key queries complete under 500ms
- [ ] Add indexes if needed: `(account_id, timestamp)`, `(item_id, timestamp)`
- [ ] Run `EXPLAIN QUERY PLAN` on key queries
- [ ] Document expected query performance

---

## Phase 3: Final Verification

### F1. Plan Compliance Audit (oracle)
- [ ] Read plan end-to-end
- [ ] For each "Must Have": verify implementation exists
- [ ] For each "Must NOT Have": search codebase for forbidden patterns
- [ ] Check evidence files
- [ ] Compare deliverables against plan
- [ ] Output: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [N/N] | VERDICT`

### F2. Code Quality Review
- [ ] Run `./gradlew build` + `./gradlew test`
- [ ] Review changed files for: empty catches, console.log in prod
- [ ] Check AI slop: excessive comments, over-abstraction
- [ ] Output: `Build [PASS/FAIL] | Tests [N pass/N fail] | Files [N clean/N issues]`

### F3. Real Integration QA
- [ ] Build plugin JAR
- [ ] Verify startup with SQLite config enabled
- [ ] Check logs for migration success
- [ ] Verify UI displays trade history correctly
- [ ] Output: `Startup [PASS/FAIL] | Migration [PASS/FAIL] | UI Display [PASS/FAIL]`

### F4. Scope Fidelity Check
- [ ] For each task: read "What to do", read actual diff
- [ ] Verify 1:1 — everything in spec was built, nothing beyond spec
- [ ] Check "Must NOT do" compliance
- [ ] Output: `Tasks [N/N compliant] | Contamination [CLEAN/N issues]`

---

## Files Reference

### Created (SQLite Infrastructure)
```
src/main/java/com/flippingutilities/DataSource.java
src/main/java/com/flippingutilities/db/SqliteSchema.java
src/main/java/com/flippingutilities/db/SqliteStorage.java
src/main/java/com/flippingutilities/db/MigrationService.java
src/main/java/com/flippingutilities/db/FlipRepository.java
src/main/java/com/flippingutilities/db/SqliteFlipRepository.java
```

### To Create
```
src/main/java/com/flippingutilities/db/JsonFlipRepository.java
src/test/java/com/flippingutilities/db/MigrationRollbackTest.java
src/test/java/com/flippingutilities/db/DualWriteConsistencyTest.java
```

### To Modify
```
src/main/java/com/flippingutilities/controller/FlippingPlugin.java
src/main/java/com/flippingutilities/ui/statistics/StatsPanel.java
src/main/java/com/flippingutilities/ui/flipping/FlippingPanel.java
```

---

## Guardrails (Must NOT Do)

- NO behavior changes to trading math
- NO new UI features or redesigns
- NO running DB operations on client/Swing thread
- NO expanding schema for future features
- NO full ORM layer (use plain JDBC)
- NO storing full OfferEvent history (only active slots + trades)

---

## Commands

```bash
# Compile
./gradlew compileJava

# Run tests
./gradlew test

# Run specific tests
./gradlew test --tests '*StorageParityTest*'

# Build plugin
./gradlew build
```
