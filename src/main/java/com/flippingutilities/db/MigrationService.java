package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.Flip;
import com.flippingutilities.model.OfferEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service to migrate data from JSON-based storage to SQLite.
 * Uses TradePersister to load accounts (handles deserialization correctly).
 */
public class MigrationService {
    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);
    
    private final SqliteStorage storage;
    private final TradePersister tradePersister;
    
    public MigrationService(SqliteStorage storage, TradePersister tradePersister) {
        this.storage = storage;
        this.tradePersister = tradePersister;
    }
    
    /**
     * Performs migration from JSON files to SQLite database.
     * @return Number of accounts migrated
     */
    public int migrate() {
        log.info("[MigrationService] Starting JSON -> SQLite migration...");
        storage.initializeSchema();
        
        int accountsMigrated = 0;
        int totalTrades = 0;
        
        // Use TradePersister to load all accounts - it handles deserialization correctly
        Map<String, AccountData> accounts = tradePersister.loadAllAccounts();
        
        if (accounts == null || accounts.isEmpty()) {
            log.info("[MigrationService] No account data found to migrate.");
            return 0;
        }
        
        for (Map.Entry<String, AccountData> entry : accounts.entrySet()) {
            String displayName = entry.getKey();
            AccountData accountData = entry.getValue();
            
            try {
                int tradesCount = migrateAccount(displayName, accountData);
                accountsMigrated++;
                totalTrades += tradesCount;
                log.info("[MigrationService] Migrated account: {} ({} trades)", displayName, tradesCount);
            } catch (Exception e) {
                log.error("[MigrationService] Failed to migrate account: {}", displayName, e);
            }
        }
        
        // Mark migration complete
        storage.setSetting("migration_completed", "true");
        storage.setSetting("migration_completed_at", Instant.now().toString());
        
        log.info("[MigrationService] Migration complete. Accounts: {}, Total trades: {}", 
            accountsMigrated, totalTrades);
        return accountsMigrated;
    }
    
    /**
     * Migrate a single account from AccountData to SQLite.
     * @return Number of trades migrated
     */
    private int migrateAccount(String displayName, AccountData accountData) {
        if (accountData == null) {
            log.warn("[MigrationService] Account data is null for: {}", displayName);
            return 0;
        }
        
        List<FlippingItem> trades = accountData.getTrades();
        if (trades == null || trades.isEmpty()) {
            log.debug("[MigrationService] No trades to migrate for: {}", displayName);
            return 0;
        }
        
        // 1. Upsert account (playerId unknown from JSON, use displayName as placeholder)
        storage.upsertAccount(displayName, displayName);
        
        int tradesCount = 0;
        
        // 2. Migrate trades (FlippingItem -> individual OfferEvents as trades)
        for (FlippingItem item : trades) {
            if (item.getHistory() == null) {
                continue;
            }
            
            List<OfferEvent> offers = item.getHistory().getCompressedOfferEvents();
            if (offers == null) {
                continue;
            }
            
            for (OfferEvent offer : offers) {
                try {
                    if (offer == null || !offer.isComplete() || offer.isCausedByEmptySlot()) {
                        continue;
                    }
                    // Only migrate completed trades with valid data
                    long timestamp = offer.getTime() != null ? offer.getTime().toEpochMilli() : Instant.now().toEpochMilli();
                    int qty = offer.getCurrentQuantityInTrade();
                    // Use getPreTaxPrice() instead of getPrice() - getPrice() throws NPE when time is null
                    int price = offer.getPreTaxPrice();
                    boolean isBuy = offer.isBuy();
                    
                    storage.insertTrade(displayName, item.getItemId(), timestamp, qty, price, isBuy);
                    tradesCount++;
                } catch (Exception e) {
                    log.warn("[MigrationService] Skipping offer with invalid data for item {}: {}", item.getItemId(), e.getMessage());
                }
            }
            
            // 3. Migrate GE limit state from FlippingItem
            try {
                Instant resetTime = item.getGeLimitResetTime();
                if (resetTime != null && resetTime != Instant.EPOCH) {
                    storage.upsertGeLimitState(displayName, item.getItemId(), 
                        resetTime, 
                        item.getItemsBoughtThisLimitWindow());
                }
            } catch (Exception e) {
                log.debug("[MigrationService] Could not migrate GE limit for item {}: {}", item.getItemId(), e.getMessage());
            }
        }
        
        // 4. Migrate last offers (active slots)
        Map<Integer, OfferEvent> lastOffers = accountData.getLastOffers();
        if (lastOffers != null) {
            for (Map.Entry<Integer, OfferEvent> entry : lastOffers.entrySet()) {
                int slotIndex = entry.getKey();
                OfferEvent offer = entry.getValue();
                if (offer != null && !offer.isComplete() && !offer.isCausedByEmptySlot()) {
                    try {
                        storage.upsertSlot(displayName, slotIndex, offer);
                    } catch (Exception e) {
                        log.debug("[MigrationService] Could not migrate slot {}: {}", slotIndex, e.getMessage());
                    }
                }
            }
        }
        
        // 5. Store migration metadata
        storage.setSetting("migrated_" + displayName, Instant.now().toString());
        
        return tradesCount;
    }
}
