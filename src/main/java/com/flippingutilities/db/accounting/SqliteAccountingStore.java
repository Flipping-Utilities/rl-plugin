package com.flippingutilities.db.accounting;

import com.flippingutilities.accounting.AccountingEngine;
import com.flippingutilities.accounting.AccountingPlan;
import com.flippingutilities.accounting.AccountingRecipe;
import com.flippingutilities.accounting.AccountingResult;
import com.flippingutilities.accounting.AccountingSource;
import com.flippingutilities.db.SqliteStorage;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import lombok.Value;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.flippingutilities.db.accounting.AccountingSourceStore.*;

/**
 * SQLite-backed accounting plans and materialized financial facts. All operations share the
 * storage monitor and its single connection. Read methods never invoke the matching engine.
 */
public final class SqliteAccountingStore {
    private static final String ALGORITHM = "margin_then_fifo_v1";
    private final SqliteStorage storage;
    private final AccountingEngine engine = new AccountingEngine();
    private volatile boolean retainedHistory;

    public SqliteAccountingStore(SqliteStorage storage) { this.storage = storage; }

    /** Once true, recovery callers can rely on this even if the database becomes unreadable. */
    public boolean hasRetainedHistory() {
        if (retainedHistory) return true;
        retainedHistory = read(connection -> {
            if (!exists(connection, "SELECT 1 FROM sqlite_master WHERE type='table' AND name LIKE 'accounting_%'")) {
                if (exists(connection, "SELECT 1 FROM sqlite_master WHERE type='table' AND name='settings'")
                    && exists(connection, "SELECT 1 FROM settings WHERE key='accounting_layout'")) {
                    throw new SQLException("Accounting layout marker exists but retained tables are missing");
                }
                return false;
            }
            return exists(connection, "SELECT 1 FROM accounting_observations WHERE enriched=1 LIMIT 1")
                || exists(connection, "SELECT 1 FROM accounting_orders WHERE suppressed_quantity>0 LIMIT 1")
                || exists(connection, "SELECT 1 FROM accounting_plans LIMIT 1");
        });
        return retainedHistory;
    }

    @Value
    public static class Preview {
        AccountingPlan plan;
        long sourceRevision;
        AccountingResult result;
        String digest;
    }

    @Value
    public static class Summary {
        Long profitGp;
        long knownProfitGp;
        Long costGp;
        Long grossGp;
        Long taxGp;
        long unknownCount;
        long realizationCount;
        long soldQuantity;
        long matchedQuantity;
        long estimatedCount;
        long undatedCount;
        long sourceRevision;
        long reportingRevision;
        boolean stale;
    }

    public Long findAccountId(String displayName) {
        return read(connection -> {
            try (PreparedStatement query = connection.prepareStatement("SELECT id FROM accounts WHERE display_name=?")) {
                bind(query, displayName);
                try (ResultSet row = query.executeQuery()) { return row.next() ? row.getLong(1) : null; }
            }
        });
    }

    public Map<Long, String> listAccounts() {
        return read(connection -> {
            Map<Long, String> accounts = new LinkedHashMap<>();
            try (PreparedStatement query = connection.prepareStatement("SELECT id,display_name FROM accounts ORDER BY display_name");
                 ResultSet row = query.executeQuery()) {
                while (row.next()) accounts.put(row.getLong(1), row.getString(2));
            }
            return Collections.unmodifiableMap(accounts);
        });
    }

    /** Explicit SQLite-to-SQLite backfill; it never reads external account files. */
    public void importLegacySources() {
        write(connection -> {
            for (Long accountId : listAccounts().keySet()) importAccount(connection, accountId);
            return null;
        });
    }

    private void importAccount(Connection connection, long accountId) throws SQLException {
        state(connection, accountId);
        if (exists(connection, "SELECT 1 FROM accounting_state WHERE account_id=? AND imported=1", accountId)) return;
        // Persisted row ordering, not random UUID order, resolves legacy timestamp ties.
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT offer_json FROM trades WHERE account_id=? ORDER BY timestamp,id")) {
            bind(query, accountId);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) capture(connection, accountId, SqliteStorage.deserializeOffer(row.getString(1)), Collections.emptyList(), true, false);
            }
        }
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT offer_json,history_visible FROM active_slots WHERE account_id=? ORDER BY slot_index")) {
            bind(query, accountId);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) capture(connection, accountId, SqliteStorage.deserializeOffer(row.getString(1)), Collections.emptyList(), row.getBoolean(2), false);
            }
        }
        syncRecipes(connection, accountId);
        execute(connection, "UPDATE accounting_state SET imported=1 WHERE account_id=?", accountId);
    }

    /** Called within the source writer's transaction, before compressed predecessors disappear. */
    public void captureOffer(Connection connection, long accountId, OfferEvent offer, List<String> predecessors,
                             boolean reportable) throws SQLException {
        importAccount(connection, accountId);
        capture(connection, accountId, offer, predecessors, reportable, false);
        if (offer != null && offer.getItemId() > 0 && (offer.getCumulativeAmount() != null || offer.getOrderId() != null || offer.getObservedAt() != null)) retainedHistory = true;
    }

    public void suppressOffers(Connection connection, long accountId, List<String> uuids) throws SQLException {
        importAccount(connection, accountId);
        suppress(connection, accountId, uuids);
        retainedHistory = true;
    }

    public void recipesChanged(Connection connection, long accountId) throws SQLException {
        importAccount(connection, accountId);
        syncRecipes(connection, accountId);
    }

    /** Legacy imports retain conflicts; new declarations in a new ledger must fit known source quantities. */
    public void validateRecipeAddition(Connection connection, long accountId, String recipeId) throws SQLException {
        AccountingPlan active = getActivePlan(accountId);
        if (active == null || active.getMode() == AccountingPlan.Mode.LEGACY) return;
        for (AccountingRecipe recipe : recipes(connection, accountId)) {
            if (!recipe.getId().equals(recipeId)) continue;
            for (AccountingRecipe.Component input : recipe.getInputs()) {
                if (input.getSourceId().startsWith("missing:")) throw new AccountingValidationException("Recipe input exceeds available source quantity or has unresolved evidence");
            }
            for (AccountingRecipe.Component output : recipe.getOutputs()) {
                if (output.getSourceId().startsWith("missing:")) throw new AccountingValidationException("Recipe output exceeds available source quantity or has unresolved evidence");
            }
        }
    }

    public List<AccountingSource> loadSources(long accountId) { return read(connection -> sources(connection, accountId)); }
    public List<AccountingRecipe> loadRecipes(long accountId) { return read(connection -> recipes(connection, accountId)); }

    public long getSourceRevision(long accountId) {
        return read(connection -> revision(connection, accountId));
    }

    public boolean isDirty(long accountId) {
        return read(connection -> exists(connection, "SELECT 1 FROM accounting_state WHERE account_id=? AND dirty=1", accountId));
    }

    public AccountingPlan getActivePlan(long accountId) {
        return read(connection -> {
            try (PreparedStatement query = connection.prepareStatement("SELECT active_plan_id FROM accounting_state WHERE account_id=?")) {
                bind(query, accountId);
                try (ResultSet row = query.executeQuery()) { return row.next() ? loadPlan(connection, row.getString(1)) : null; }
            }
        });
    }

    public List<AccountingPlan> listPlans(long accountId) {
        return read(connection -> {
            List<AccountingPlan> plans = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("SELECT plan_id FROM accounting_plans WHERE account_id=? AND activated=1 ORDER BY rowid DESC")) {
                bind(query, accountId);
                try (ResultSet row = query.executeQuery()) { while (row.next()) plans.add(loadPlan(connection, row.getString(1))); }
            }
            return plans;
        });
    }

    public Preview preview(AccountingPlan plan) {
        return write(connection -> {
            importAccount(connection, plan.getAccountId());
            long revision = revision(connection, plan.getAccountId());
            retainedHistory = true;
            AccountingResult result = engine.calculate(plan, sources(connection, plan.getAccountId()), recipes(connection, plan.getAccountId()));
            String digest = digest(plan, revision);
            if (exists(connection, "SELECT 1 FROM accounting_plans WHERE plan_id=?", plan.getId())) {
                if (!exists(connection, "SELECT 1 FROM accounting_plans WHERE plan_id=? AND account_id=? AND preview_digest=?",
                    plan.getId(), plan.getAccountId(), digest)) throw new IllegalArgumentException("Create a new plan to change a previous preview");
            } else {
                AccountData frozen = plan.getMode() == AccountingPlan.Mode.HYBRID ? freeze(connection, plan) : null;
                execute(connection, "INSERT INTO accounting_plans(plan_id,account_id,mode,cutover,purchase_cutoff,source_revision,preview_digest,frozen_account,algorithm_version) " +
                    "VALUES (?,?,?,?,?,?,?,?,?)", plan.getId(), plan.getAccountId(), plan.getMode().name(), epoch(plan.getCutover()),
                    epoch(plan.getPurchaseCutoff()), revision, digest, frozen == null ? null : SqliteStorage.serializeAccount(frozen), ALGORITHM);
                for (Map.Entry<String, Long> selection : plan.getOpeningQuantities().entrySet()) {
                    AccountingSource selected = sources(connection, plan.getAccountId()).stream().filter(source -> source.getId().equals(selection.getKey()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown opening source"));
                    long selectedOffset = result.getOpeningCandidates().stream().filter(candidate -> candidate.getSourceId().equals(selected.getId()))
                        .mapToLong(AccountingResult.OpeningCandidate::getOffset).findFirst().orElse(0);
                    execute(connection, "INSERT INTO accounting_opening_selections(plan_id,source_id,quantity,item_id,source_fingerprint,source_offset) VALUES (?,?,?,?,?,?)",
                        plan.getId(), selection.getKey(), selection.getValue(), selected.getItemId(), sourceFingerprint(selected), selectedOffset);
                }
            }
            return new Preview(plan, revision, result, digest);
        });
    }

    /** Publication and policy switch commit together; stale previews cannot replace newer activity. */
    public void activate(Preview preview) {
        write(connection -> {
            AccountingPlan plan = preview.getPlan();
            if (!digest(plan, preview.getSourceRevision()).equals(preview.getDigest())) throw new IllegalArgumentException("Preview parameters changed");
            if (exists(connection, "SELECT 1 FROM accounting_state WHERE account_id=? AND active_plan_id=?", plan.getAccountId(), plan.getId())) return null;
            if (revision(connection, plan.getAccountId()) != preview.getSourceRevision()) {
                throw new IllegalStateException("Trading data changed. Refresh the migration preview before applying it.");
            }
            if (!exists(connection, "SELECT 1 FROM accounting_plans WHERE plan_id=? AND account_id=? AND preview_digest=? AND source_revision=?",
                plan.getId(), plan.getAccountId(), preview.getDigest(), preview.getSourceRevision())) {
                throw new IllegalArgumentException("The migration preview is not a stored plan for this account");
            }
            // Recompute from retained sources to prevent an externally constructed Preview replacing financial facts.
            AccountingResult checked = engine.calculate(plan, sources(connection, plan.getAccountId()), recipes(connection, plan.getAccountId()));
            publish(connection, plan, checked, null);
            checkpoint(connection, plan, sources(connection, plan.getAccountId()).stream().map(AccountingSource::getItemId).collect(Collectors.toSet()));
            execute(connection, "UPDATE accounting_plans SET activated=1 WHERE plan_id=?", plan.getId());
            execute(connection, "UPDATE accounting_state SET active_plan_id=?,dirty=0,reporting_revision=reporting_revision+1 WHERE account_id=?",
                plan.getId(), plan.getAccountId());
            execute(connection, "DELETE FROM accounting_dirty_items WHERE account_id=?", plan.getAccountId());
            return null;
        });
    }

    /** Explicit worker operation, separate from reporting. Replays only connected dirty item partitions. */
    public void reconcile(long accountId) {
        write(connection -> {
            importAccount(connection, accountId);
            AccountingPlan plan = getActivePlan(accountId);
            if (plan == null || plan.getMode() == AccountingPlan.Mode.LEGACY || !isDirty(accountId)) return null;
            Set<Integer> affected = dirtyClosure(connection, accountId);
            if (affected.isEmpty()) return null;
            if (append(connection, plan, affected)) {
                execute(connection, "UPDATE accounting_state SET dirty=0,reporting_revision=reporting_revision+1 WHERE account_id=?", accountId);
                execute(connection, "DELETE FROM accounting_dirty_items WHERE account_id=?", accountId);
                return null;
            }
            List<AccountingSource> sourceSubset = partitionSources(connection, accountId, affected);
            sourceSubset = preserveOpeningEvidence(connection, plan, sourceSubset);
            List<AccountingRecipe> recipeSubset = partitionRecipes(connection, accountId, affected);
            Set<String> sourceIds = sourceSubset.stream().map(AccountingSource::getId).collect(Collectors.toSet());
            Map<String, Long> selections = new LinkedHashMap<>();
            for (Map.Entry<String, Long> selection : plan.getOpeningQuantities().entrySet()) {
                if (sourceIds.contains(selection.getKey())) selections.put(selection.getKey(), selection.getValue());
                else if (openingItemAffected(connection, plan.getId(), selection.getKey(), affected)) {
                    throw new IllegalStateException("A carried purchase changed or was deleted. Review a replacement accounting plan.");
                }
            }
            AccountingPlan partitionPlan = new AccountingPlan(plan.getId(), accountId, plan.getMode(), plan.getCutover(), plan.getPurchaseCutoff(), selections);
            AccountingResult result = engine.calculate(partitionPlan, sourceSubset, recipeSubset);
            Set<String> shifted = changedOpeningOffsets(connection, plan, result);
            if (!shifted.isEmpty()) {
                sourceSubset = sourceSubset.stream().map(source -> shifted.contains(source.getId()) ? unknownBasis(source) : source).collect(Collectors.toList());
                result = engine.calculate(partitionPlan, sourceSubset, recipeSubset);
            }
            publish(connection, plan, result, affected);
            checkpoint(connection, plan, affected);
            execute(connection, "UPDATE accounting_state SET dirty=0,reporting_revision=reporting_revision+1 WHERE account_id=?", accountId);
            execute(connection, "DELETE FROM accounting_dirty_items WHERE account_id=?", accountId);
            return null;
        });
    }

    public void reconcileAll() {
        List<Long> dirty = read(connection -> {
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("SELECT account_id FROM accounting_state WHERE dirty=1 AND active_plan_id IS NOT NULL");
                 ResultSet row = query.executeQuery()) { while (row.next()) ids.add(row.getLong(1)); }
            return ids;
        });
        for (Long accountId : dirty) reconcile(accountId);
    }

    private Set<Integer> dirtyClosure(Connection connection, long accountId) throws SQLException {
        Set<Integer> items = new LinkedHashSet<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT item_id FROM accounting_dirty_items WHERE account_id=?")) {
            bind(query, accountId);
            try (ResultSet row = query.executeQuery()) { while (row.next()) items.add(row.getInt(1)); }
        }
        Map<String, Set<Integer>> recipes = new LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT recipe_id,item_id FROM accounting_recipe_components WHERE account_id=?")) {
            bind(query, accountId);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) recipes.computeIfAbsent(row.getString(1), ignored -> new HashSet<>()).add(row.getInt(2));
            }
        }
        boolean changed;
        do {
            changed = false;
            for (Set<Integer> recipeItems : recipes.values()) {
                if (!Collections.disjoint(items, recipeItems)) changed |= items.addAll(recipeItems);
            }
        } while (changed);
        return items;
    }

    private boolean append(Connection connection, AccountingPlan plan, Set<Integer> items) throws SQLException {
        Map<Integer, AccountingResult> additions = new LinkedHashMap<>();
        for (Integer item : items) {
            if (!exists(connection, "SELECT 1 FROM accounting_dirty_items WHERE account_id=? AND item_id=? AND append_only=1", plan.getAccountId(), item)
                || exists(connection, "SELECT 1 FROM accounting_recipe_components WHERE account_id=? AND item_id=?", plan.getAccountId(), item)) return false;
            long sequence, latestSequence;
            Instant latest;
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM accounting_checkpoints WHERE plan_id=? AND item_id=?")) {
                bind(query, plan.getId(), item);
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) return false;
                    sequence = row.getLong("source_sequence"); latest = instant(row, "latest_at"); latestSequence = row.getLong("latest_sequence");
                }
            }
            List<AccountingSource> newSources = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM accounting_sources WHERE account_id=? AND item_id=? AND sequence>? ORDER BY recognized_at,sequence")) {
                bind(query, plan.getAccountId(), item, sequence);
                try (ResultSet row = query.executeQuery()) { while (row.next()) newSources.add(source(row)); }
            }
            if (newSources.isEmpty()) return false;
            List<AccountingResult.OpenLot> lots = new ArrayList<>();
            List<AccountingSource> evidence = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM accounting_open_lots WHERE plan_id=? AND item_id=? ORDER BY available_at,source_id")) {
                bind(query, plan.getId(), item);
                try (ResultSet row = query.executeQuery()) {
                    while (row.next()) lots.add(new AccountingResult.OpenLot(row.getString("source_id"), item, row.getLong("source_offset"),
                        row.getLong("quantity"), nullableLong(row, "cost_gp"), instant(row, "acquired_at"), instant(row, "available_at"), row.getBoolean("estimated")));
                }
            }
            for (AccountingResult.OpenLot lot : lots) {
                try (PreparedStatement query = connection.prepareStatement("SELECT * FROM accounting_sources WHERE account_id=? AND source_id=?")) {
                    bind(query, plan.getAccountId(), lot.getSourceId());
                    try (ResultSet row = query.executeQuery()) { if (row.next()) evidence.add(source(row)); else return false; }
                }
            }
            try { additions.put(item, engine.calculateAppend(plan, newSources, evidence, lots, latest, latestSequence)); }
            catch (IllegalArgumentException unsafeAppend) { return false; }
        }
        for (Map.Entry<Integer, AccountingResult> addition : additions.entrySet()) {
            publish(connection, plan, addition.getValue(), Collections.singleton(addition.getKey()), true);
        }
        checkpoint(connection, plan, items);
        return true;
    }

    private void checkpoint(Connection connection, AccountingPlan plan, Set<Integer> items) throws SQLException {
        for (Integer item : items) {
            long latestSequence = 0, maxSequence = 0;
            Long latest = null;
            try (PreparedStatement query = connection.prepareStatement("SELECT MAX(sequence) FROM accounting_sources WHERE account_id=? AND item_id=?")) {
                bind(query, plan.getAccountId(), item);
                try (ResultSet row = query.executeQuery()) { if (row.next()) maxSequence = row.getLong(1); }
            }
            try (PreparedStatement query = connection.prepareStatement("SELECT recognized_at,sequence FROM accounting_sources WHERE account_id=? AND item_id=? ORDER BY recognized_at DESC,sequence DESC LIMIT 1")) {
                bind(query, plan.getAccountId(), item);
                try (ResultSet row = query.executeQuery()) { if (row.next()) { latest = nullableLong(row, 1); latestSequence = row.getLong(2); } }
            }
            execute(connection, "INSERT INTO accounting_checkpoints(plan_id,item_id,source_sequence,latest_at,latest_sequence) VALUES (?,?,?,?,?) " +
                "ON CONFLICT(plan_id,item_id) DO UPDATE SET source_sequence=excluded.source_sequence,latest_at=excluded.latest_at,latest_sequence=excluded.latest_sequence",
                plan.getId(), item, maxSequence, latest, latestSequence);
        }
    }

    private static AccountingSource source(ResultSet row) throws SQLException {
        return new AccountingSource(row.getString("source_id"), row.getString("order_id"), row.getLong("account_id"), row.getInt("item_id"),
            row.getBoolean("is_buy"), row.getLong("quantity"), nullableLong(row, "amount_gp"), nullableLong(row, "tax_gp"),
            instant(row, "recognized_at"), row.getLong("sequence"), row.getBoolean("margin_eligible"), row.getBoolean("restricted"), row.getBoolean("estimated"));
    }

    private List<AccountingSource> preserveOpeningEvidence(Connection connection, AccountingPlan plan, List<AccountingSource> sources) throws SQLException {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT source_id,source_fingerprint FROM accounting_opening_selections WHERE plan_id=?")) {
            bind(query, plan.getId());
            try (ResultSet row = query.executeQuery()) { while (row.next()) fingerprints.put(row.getString(1), row.getString(2)); }
        }
        List<AccountingSource> checked = new ArrayList<>();
        for (AccountingSource source : sources) {
            String original = fingerprints.get(source.getId());
            if (original != null && !original.equals(sourceFingerprint(source))) {
                checked.add(unknownBasis(source));
            } else checked.add(source);
        }
        return checked;
    }

    private Set<String> changedOpeningOffsets(Connection connection, AccountingPlan plan, AccountingResult result) throws SQLException {
        Map<String, Long> original = new LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT source_id,source_offset FROM accounting_opening_selections WHERE plan_id=?")) {
            bind(query, plan.getId());
            try (ResultSet row = query.executeQuery()) { while (row.next()) original.put(row.getString(1), row.getLong(2)); }
        }
        Set<String> changed = new HashSet<>();
        for (AccountingResult.OpeningCandidate candidate : result.getOpeningCandidates()) {
            Long priorOffset = original.get(candidate.getSourceId());
            if (priorOffset != null && priorOffset != candidate.getOffset()) changed.add(candidate.getSourceId());
        }
        return changed;
    }

    private static AccountingSource unknownBasis(AccountingSource source) {
        return new AccountingSource(source.getId(), source.getOrderId(), source.getAccountId(), source.getItemId(), source.isBuy(),
            source.getQuantity(), null, source.getTaxGp(), source.getTime(), source.getSequence(), source.isMarginEligible(), source.isRestricted(), true);
    }

    private static String sourceFingerprint(AccountingSource source) {
        return stable(source.getId() + ":" + source.getQuantity() + ":" + source.getAmountGp() + ":" + source.getTaxGp() + ":" + source.getTime());
    }

    private List<AccountingSource> partitionSources(Connection connection, long accountId, Set<Integer> items) throws SQLException {
        // Indexed item predicates bound work to affected histories rather than hydrating every account offer.
        List<AccountingSource> result = new ArrayList<>();
        for (Integer item : items) {
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM accounting_sources WHERE account_id=? AND item_id=? ORDER BY recognized_at,sequence")) {
                bind(query, accountId, item);
                try (ResultSet row = query.executeQuery()) {
                    while (row.next()) result.add(new AccountingSource(row.getString("source_id"), row.getString("order_id"), accountId,
                        item, row.getBoolean("is_buy"), row.getLong("quantity"), nullableLong(row, "amount_gp"), nullableLong(row, "tax_gp"),
                        instant(row, "recognized_at"), row.getLong("sequence"), row.getBoolean("margin_eligible"), row.getBoolean("restricted"), row.getBoolean("estimated")));
                }
            }
        }
        return result;
    }

    private List<AccountingRecipe> partitionRecipes(Connection connection, long accountId, Set<Integer> items) throws SQLException {
        Set<String> ids = new HashSet<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT recipe_id,item_id FROM accounting_recipe_components WHERE account_id=?")) {
            bind(query, accountId);
            try (ResultSet row = query.executeQuery()) { while (row.next()) if (items.contains(row.getInt(2))) ids.add(row.getString(1)); }
        }
        return recipes(connection, accountId).stream().filter(recipe -> ids.contains(recipe.getId())).collect(Collectors.toList());
    }

    private boolean openingItemAffected(Connection connection, String planId, String source, Set<Integer> affected) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT item_id FROM accounting_opening_selections WHERE plan_id=? AND source_id=?")) {
            bind(query, planId, source);
            try (ResultSet row = query.executeQuery()) { return row.next() && affected.contains(row.getInt(1)); }
        }
    }

    private void publish(Connection connection, AccountingPlan plan, AccountingResult result, Set<Integer> items) throws SQLException {
        publish(connection, plan, result, items, false);
    }

    private void publish(Connection connection, AccountingPlan plan, AccountingResult result, Set<Integer> items, boolean append) throws SQLException {
        String id = plan.getId();
        if (items == null) {
            for (String table : new String[]{"accounting_allocations", "accounting_realizations", "accounting_open_lots", "accounting_warnings"}) {
                execute(connection, "DELETE FROM " + table + " WHERE plan_id=?", id);
            }
        } else {
            for (Integer item : items) {
                if (!append) {
                execute(connection, "DELETE FROM accounting_allocations WHERE plan_id=? AND realization_id IN " +
                    "(SELECT realization_id FROM accounting_realizations WHERE plan_id=? AND item_id=?)", id, id, item);
                execute(connection, "DELETE FROM accounting_realizations WHERE plan_id=? AND item_id=?", id, item);
                }
                execute(connection, "DELETE FROM accounting_open_lots WHERE plan_id=? AND item_id=?", id, item);
            }
            if (!append) for (Integer item : items) {
                execute(connection, "DELETE FROM accounting_warnings WHERE plan_id=? AND (entity_id IN " +
                    "(SELECT observation_id FROM accounting_observations WHERE account_id=? AND item_id=?) OR entity_id IN " +
                    "(SELECT recipe_id FROM accounting_recipe_components WHERE account_id=? AND item_id=?))", id, plan.getAccountId(), item, plan.getAccountId(), item);
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO accounting_realizations(plan_id,realization_id,flip_id,kind,item_id,recipe_id,source_id," +
            "recognized_at,quantity,matched_quantity,cost_gp,gross_gp,tax_gp,adjustment_gp,profit_gp,estimated) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (AccountingResult.Realization row : result.getRealizations()) {
                bind(insert, id, row.getId(), row.getFlipId(), row.getKind().name(), row.getItemId(), row.getRecipeId(), row.getSaleSourceId(),
                    epoch(row.getRecognizedAt()), row.getQuantity(), row.getMatchedQuantity(), row.getCostGp(), row.getGrossGp(), row.getTaxGp(),
                    row.getAdjustmentGp(), row.getProfitGp(), row.isEstimated());
                insert.addBatch();
            }
            insert.executeBatch();
        }
        long allocationId = 0;
        try (PreparedStatement query = connection.prepareStatement("SELECT COALESCE(MAX(allocation_id),0) FROM accounting_allocations WHERE plan_id=?")) {
            bind(query, id);
            try (ResultSet row = query.executeQuery()) { row.next(); allocationId = row.getLong(1); }
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO accounting_allocations(plan_id,allocation_id,realization_id,source_id,quantity,cost_gp,is_buy,source_offset) VALUES (?,?,?,?,?,?,?,?)")) {
            for (AccountingResult.Allocation row : result.getAllocations()) {
                bind(insert, id, ++allocationId, row.getRealizationId(), row.getSourceId(), row.getQuantity(), row.getAmountGp(), row.isBuy(), row.getOffset());
                insert.addBatch();
            }
            insert.executeBatch();
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO accounting_open_lots(plan_id,source_id,item_id,quantity,cost_gp,source_offset,acquired_at,available_at,estimated) VALUES (?,?,?,?,?,?,?,?,?)")) {
            for (AccountingResult.OpenLot row : result.getOpenLots()) {
                bind(insert, id, row.getSourceId(), row.getItemId(), row.getQuantity(), row.getCostGp(), row.getOffset(), epoch(row.getAcquiredAt()), epoch(row.getAvailableAt()), row.isEstimated());
                insert.addBatch();
            }
            insert.executeBatch();
        }
        int index = 0;
        if (items != null) {
            try (PreparedStatement query = connection.prepareStatement("SELECT COALESCE(MAX(ordinal)+1,0) FROM accounting_warnings WHERE plan_id=?")) {
                bind(query, id);
                try (ResultSet row = query.executeQuery()) { row.next(); index = row.getInt(1); }
            }
        }
        for (String warning : result.getWarnings()) {
            String entity = warning.contains(": ") ? warning.substring(warning.lastIndexOf(": ") + 2) : null;
            execute(connection, "INSERT INTO accounting_warnings(plan_id,ordinal,entity_id,message) VALUES (?,?,?,?)", id, index++, entity, warning);
        }
    }

    public Summary getSummary(List<Long> accountIds, Instant from, Instant to) {
        return read(connection -> {
            Bounds bounds = bounds(accountIds, from, to);
            String sql = "SELECT COUNT(*),COALESCE(SUM(r.quantity),0),COALESCE(SUM(r.matched_quantity),0)," +
                "COALESCE(SUM(CASE WHEN r.profit_gp IS NULL THEN 1 ELSE 0 END),0),COALESCE(SUM(r.profit_gp),0)," +
                "CASE WHEN COUNT(*)=COUNT(r.profit_gp) THEN COALESCE(SUM(r.profit_gp),0) END," +
                "CASE WHEN COUNT(*)=COUNT(r.cost_gp) THEN COALESCE(SUM(r.cost_gp),0) END," +
                "CASE WHEN COUNT(*)=COUNT(r.gross_gp) THEN COALESCE(SUM(r.gross_gp),0) END," +
                "CASE WHEN COUNT(*)=COUNT(r.tax_gp) THEN COALESCE(SUM(r.tax_gp),0) END,COALESCE(SUM(r.estimated),0) " +
                reportFrom() + bounds.sql;
            long count, quantity, matched, unknown, known, estimated;
            Long profit, cost, gross, tax;
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                bind(query, bounds.values.toArray());
                try (ResultSet row = query.executeQuery()) {
                    row.next(); count = row.getLong(1); quantity = row.getLong(2); matched = row.getLong(3); unknown = row.getLong(4);
                    known = row.getLong(5); profit = nullableLong(row, 6); cost = nullableLong(row, 7); gross = nullableLong(row, 8);
                    tax = nullableLong(row, 9); estimated = row.getLong(10);
                }
            }
            long sourceRevision = 0, reportingRevision = 0, undated = 0;
            boolean stale = false;
            for (Long account : accountIds) {
                try (PreparedStatement query = connection.prepareStatement("SELECT source_revision,reporting_revision,dirty," +
                    "(SELECT COUNT(*) FROM accounting_realizations WHERE plan_id=active_plan_id AND recognized_at IS NULL) FROM accounting_state WHERE account_id=?")) {
                    bind(query, account);
                    try (ResultSet row = query.executeQuery()) {
                        if (row.next()) { sourceRevision += row.getLong(1); reportingRevision += row.getLong(2); stale |= row.getBoolean(3); undated += row.getLong(4); }
                    }
                }
            }
            return new Summary(profit, known, cost, gross, tax, unknown, count, quantity, matched, estimated, undated, sourceRevision, reportingRevision, stale);
        });
    }

    public List<AccountingResult.Realization> pageRealizations(List<Long> accounts, Instant from, Instant to, int limit, int offset) {
        if (limit < 1 || limit > 500 || offset < 0) throw new IllegalArgumentException("Invalid report page");
        return read(connection -> {
            Bounds bounds = bounds(accounts, from, to);
            List<AccountingResult.Realization> rows = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("SELECT r.*,s.account_id " + reportFrom() + bounds.sql +
                " ORDER BY r.recognized_at DESC,r.realization_id DESC,s.account_id LIMIT ? OFFSET ?")) {
                List<Object> values = new ArrayList<>(bounds.values); values.add(limit); values.add(offset); bind(query, values.toArray());
                try (ResultSet row = query.executeQuery()) {
                    while (row.next()) {
                        Long gross = nullableLong(row, "gross_gp"), tax = nullableLong(row, "tax_gp");
                        rows.add(new AccountingResult.Realization(row.getString("realization_id"), row.getString("flip_id"),
                            AccountingResult.Kind.valueOf(row.getString("kind")), row.getString("recipe_id"), row.getLong("account_id"),
                            row.getInt("item_id"), row.getString("source_id"), instant(row, "recognized_at"), row.getLong("quantity"),
                            row.getLong("matched_quantity"), nullableLong(row, "cost_gp"), gross, tax, nullableLong(row, "adjustment_gp"),
                            gross == null || tax == null ? null : Math.subtractExact(gross, tax), nullableLong(row, "profit_gp"), row.getBoolean("estimated")));
                    }
                }
            }
            return rows;
        });
    }

    public List<String> getWarnings(long accountId) {
        return read(connection -> {
            List<String> warnings = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("SELECT w.message FROM accounting_warnings w JOIN accounting_state s ON s.active_plan_id=w.plan_id WHERE s.account_id=? ORDER BY ordinal")) {
                bind(query, accountId);
                try (ResultSet row = query.executeQuery()) { while (row.next()) warnings.add(row.getString(1)); }
            }
            return warnings;
        });
    }

    public AccountData loadFrozenLegacyAccount(String planId) {
        return read(connection -> {
            try (PreparedStatement query = connection.prepareStatement("SELECT frozen_account FROM accounting_plans WHERE plan_id=?")) {
                bind(query, planId);
                try (ResultSet row = query.executeQuery()) { return row.next() ? SqliteStorage.deserializeAccount(row.getString(1)) : null; }
            }
        });
    }

    private AccountData freeze(Connection connection, AccountingPlan plan) throws SQLException {
        String name = listAccounts().get(plan.getAccountId());
        AccountData account = storage.loadAccount(name);
        Map<Integer, FlippingItem> items = new LinkedHashMap<>();
        Map<String, Long> quarantined = new LinkedHashMap<>();
        for (RecipeFlipGroup group : account.getRecipeFlipGroups()) for (RecipeFlip recipe : group.getRecipeFlips()) {
            if (legacyRecipe(recipe, plan.getCutover())) continue;
            for (PartialOffer component : recipe.getPartialOffers()) {
                OfferEvent evidence = component.getOffer();
                Instant time = effectiveTime(evidence);
                if (time == null || !time.isBefore(plan.getCutover())) continue;
                String order = orderFor(connection, plan.getAccountId(), component.getOfferUuid());
                if (order != null) quarantined.merge(order, (long) component.getAmountConsumed(), Math::addExact);
            }
        }
        String sql = "SELECT o.item_id,o.payload,o.order_id,(SELECT COALESCE(SUM(b.quantity),0) FROM accounting_sources b " +
            "WHERE b.account_id=o.account_id AND b.order_id=o.order_id AND b.recognized_at<?) AS visible_quantity " +
            "FROM accounting_observations o JOIN accounting_orders d ON d.account_id=o.account_id AND d.order_id=o.order_id " +
            "WHERE o.account_id=? AND o.restricted=0 AND o.observed_at<? AND o.sequence=(SELECT MAX(p.sequence) " +
            "FROM accounting_observations p WHERE p.account_id=o.account_id AND p.order_id=o.order_id AND p.observed_at<?) ORDER BY o.observed_at,o.sequence";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            bind(query, epoch(plan.getCutover()), plan.getAccountId(), epoch(plan.getCutover()), epoch(plan.getCutover()));
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    int item = row.getInt(1);
                    OfferEvent offer = SqliteStorage.deserializeOffer(row.getString(2)); offer.setMadeBy(name);
                    long visible = Math.max(0, row.getLong("visible_quantity") - quarantined.getOrDefault(row.getString("order_id"), 0L));
                    if (visible == 0) continue;
                    offer.setCurrentQuantityInTrade(Math.toIntExact(visible));
                    items.computeIfAbsent(item, ignored -> new FlippingItem(item, "Item " + item, 0, name)).getHistory().getCompressedOfferEvents().add(offer);
                }
            }
        }
        account.setTrades(new ArrayList<>(items.values()));
        account.setLastOffers(new LinkedHashMap<>());
        for (RecipeFlipGroup group : account.getRecipeFlipGroups()) {
            group.setRecipeFlips(group.getRecipeFlips().stream().filter(recipe -> legacyRecipe(recipe, plan.getCutover())).collect(Collectors.toList()));
        }
        return account;
    }

    private boolean legacyRecipe(RecipeFlip recipe, Instant cutover) {
        if (recipe.getTimeOfCreation() == null || !recipe.getTimeOfCreation().isBefore(cutover)) return false;
        for (Map<String, PartialOffer> outputs : recipe.getOutputs().values()) for (PartialOffer output : outputs.values()) {
            Instant time = effectiveTime(output.getOffer());
            if (time == null || !time.isBefore(cutover)) return false;
        }
        return true;
    }

    private static Instant effectiveTime(OfferEvent offer) {
        return offer == null ? null : offer.getObservedAt() == null ? offer.getTime() : offer.getObservedAt();
    }

    private AccountingPlan loadPlan(Connection connection, String id) throws SQLException {
        if (id == null) return null;
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM accounting_plans WHERE plan_id=?")) {
            bind(query, id);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return null;
                Map<String, Long> opening = new LinkedHashMap<>();
                try (PreparedStatement selection = connection.prepareStatement("SELECT source_id,quantity FROM accounting_opening_selections WHERE plan_id=? ORDER BY source_id")) {
                    bind(selection, id);
                    try (ResultSet entry = selection.executeQuery()) { while (entry.next()) opening.put(entry.getString(1), entry.getLong(2)); }
                }
                return new AccountingPlan(id, row.getLong("account_id"), AccountingPlan.Mode.valueOf(row.getString("mode")),
                    instant(row, "cutover"), instant(row, "purchase_cutoff"), opening);
            }
        }
    }

    private static String reportFrom() {
        return "FROM accounting_state s JOIN accounting_realizations r ON r.plan_id=s.active_plan_id ";
    }

    private static Bounds bounds(List<Long> accounts, Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) throw new IllegalArgumentException("Report end must follow its start");
        String sql = "WHERE s.account_id IN (" + (accounts.isEmpty() ? "NULL" : String.join(",", Collections.nCopies(accounts.size(), "?"))) + ")";
        List<Object> values = new ArrayList<>(accounts);
        if (from != null) { sql += " AND r.recognized_at>=?"; values.add(epoch(from)); }
        if (to != null) { sql += " AND r.recognized_at<?"; values.add(epoch(to)); }
        return new Bounds(sql, values);
    }

    private static String digest(AccountingPlan plan, long revision) {
        return stable(plan.getId() + ":" + plan.getAccountId() + ":" + plan.getMode() + ":" + plan.getCutover() + ":" +
            plan.getPurchaseCutoff() + ":" + new java.util.TreeMap<>(plan.getOpeningQuantities()) + ":" + revision + ":" + ALGORITHM);
    }

    private static long revision(Connection connection, long accountId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT source_revision FROM accounting_state WHERE account_id=?")) {
            bind(query, accountId);
            try (ResultSet row = query.executeQuery()) { return row.next() ? row.getLong(1) : 0; }
        }
    }

    @Value
    private static class Bounds { String sql; List<Object> values; }
    private interface Work<T> { T apply(Connection connection) throws SQLException; }

    private <T> T read(Work<T> work) {
        synchronized (storage) {
            try { return work.apply(storage.getConnection()); }
            catch (SQLException e) { throw new IllegalStateException("Could not read SQLite accounting", e); }
        }
    }

    private <T> T write(Work<T> work) {
        synchronized (storage) {
            try {
                Connection connection = storage.getConnection();
                boolean autoCommit = connection.getAutoCommit();
                if (!autoCommit) return work.apply(connection);
                connection.setAutoCommit(false);
                try {
                    T result = work.apply(connection); connection.commit(); return result;
                } catch (SQLException | RuntimeException e) { connection.rollback(); throw e; }
                finally { connection.setAutoCommit(true); }
            } catch (SQLException e) { throw new IllegalStateException("Could not persist SQLite accounting", e); }
        }
    }
}
