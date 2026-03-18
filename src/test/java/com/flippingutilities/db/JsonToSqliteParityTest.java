/*
 * Copyright (c) 2020, Belieal <https://github.com/Belieal>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.flippingutilities.db;

import com.flippingutilities.db.FlipRepository.AggregateStats;
import com.flippingutilities.model.*;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

public class JsonToSqliteParityTest {

    private static final String TEST_ACCOUNT = "ParityTestAccount";
    private static final int WHIP_ITEM_ID = 4151;

    private File testDbFile;
    private SqliteStorage storage;

    @Before
    public void setUp() throws IOException {
        testDbFile = Files.createTempFile("test_parity_db_", ".db").toFile();
        testDbFile.deleteOnExit();
        storage = new SqliteStorage(testDbFile);
        storage.initializeSchema();
    }

    @After
    public void tearDown() {
        if (storage != null) {
            storage.close();
        }
        if (testDbFile != null && testDbFile.exists()) {
            testDbFile.delete();
        }
    }

    @Test
    public void testProfitParity() {
        AccountData accountData = createAccountDataWithTrades();
        long expectedExpense = computeTotalExpense(accountData);
        long expectedRevenue = computeTotalRevenue(accountData);
        migrateToSqlite(accountData);
        AggregateStats sqliteStats = computeStatsFromSqlite();

        assertEquals("Expense should match", expectedExpense, sqliteStats.totalExpense);
        assertEquals("Revenue should match", expectedRevenue, sqliteStats.totalRevenue);
        long expectedProfit = expectedRevenue - expectedExpense;
        assertEquals("Profit should be revenue - expense", expectedProfit, sqliteStats.totalProfit);
    }

    @Test
    public void testAggregateStatsQuery() {
        storage.upsertAccount(TEST_ACCOUNT, "player-123");

        long now = Instant.now().toEpochMilli();
        storage.insertTrade(TEST_ACCOUNT, WHIP_ITEM_ID, now, 10, 50000, true);
        storage.insertTrade(TEST_ACCOUNT, WHIP_ITEM_ID, now + 1000, 10, 55000, false);

        Map<String, Object> stats = storage.queryAggregateStats(TEST_ACCOUNT, Instant.EPOCH);

        assertNotNull("Stats should not be null", stats);
        assertEquals("Total expense should be 500000", 500000L, stats.get("totalExpense"));
        assertEquals("Total revenue should be 550000", 550000L, stats.get("totalRevenue"));
        assertEquals("Total profit should be 50000", 50000L, stats.get("totalProfit"));
        assertEquals("Flip count should be 2", 2, stats.get("flipCount"));
    }

    @Test
    public void testTradeCountParity() {
        AccountData accountData = createAccountDataWithTrades();
        int expectedTradeCount = countTotalTrades(accountData);
        migrateToSqlite(accountData);

        List<Map<String, Object>> loadedTrades = storage.loadTrades(TEST_ACCOUNT, Instant.EPOCH);
        assertEquals("Trade count should match after migration", expectedTradeCount, loadedTrades.size());
    }

    private AccountData createAccountDataWithTrades() {
        AccountData accountData = new AccountData();

        FlippingItem item = new FlippingItem(WHIP_ITEM_ID, "Abyssal whip", 70, TEST_ACCOUNT);
        HistoryManager history = item.getHistory();

        long now = Instant.now().toEpochMilli();
        OfferEvent buyOffer = createCompleteOffer(WHIP_ITEM_ID, now - 2000, 10, 50000, true);
        OfferEvent sellOffer = createCompleteOffer(WHIP_ITEM_ID, now - 1000, 10, 55000, false);

        history.getCompressedOfferEvents().add(buyOffer);
        history.getCompressedOfferEvents().add(sellOffer);

        accountData.getTrades().add(item);
        return accountData;
    }

    private OfferEvent createCompleteOffer(int itemId, long timestamp, int qty, int price, boolean isBuy) {
        return new OfferEvent(
            UUID.randomUUID().toString(),
            isBuy,
            itemId,
            qty,
            price,
            Instant.ofEpochMilli(timestamp),
            0,
            isBuy ? GrandExchangeOfferState.BOUGHT : GrandExchangeOfferState.SOLD,
            0,
            1,
            qty,
            null,
            false,
            TEST_ACCOUNT,
            "Test Item",
            price,
            price * qty
        );
    }

    private long computeTotalExpense(AccountData accountData) {
        long total = 0;
        for (FlippingItem item : accountData.getTrades()) {
            for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
                if (offer.isBuy()) {
                    total += (long) offer.getCurrentQuantityInTrade() * offer.getPreTaxPrice();
                }
            }
        }
        return total;
    }

    private long computeTotalRevenue(AccountData accountData) {
        long total = 0;
        for (FlippingItem item : accountData.getTrades()) {
            for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
                if (!offer.isBuy()) {
                    total += (long) offer.getCurrentQuantityInTrade() * offer.getPreTaxPrice();
                }
            }
        }
        return total;
    }

    private AggregateStats computeStatsFromSqlite() {
        Map<String, Object> stats = storage.queryAggregateStats(TEST_ACCOUNT, Instant.EPOCH);

        long profit = getLongValue(stats, "totalProfit");
        long expense = getLongValue(stats, "totalExpense");
        long revenue = getLongValue(stats, "totalRevenue");

        return new AggregateStats(profit, expense, revenue, 0, 0L, 0L);
    }

    private void migrateToSqlite(AccountData accountData) {
        storage.upsertAccount(TEST_ACCOUNT, "player-" + TEST_ACCOUNT);

        for (FlippingItem item : accountData.getTrades()) {
            for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
                if (offer == null || !offer.isComplete() || offer.isCausedByEmptySlot()) {
                    continue;
                }
                long timestamp = offer.getTime() != null ? offer.getTime().toEpochMilli() : Instant.now().toEpochMilli();
                int qty = offer.getCurrentQuantityInTrade();
                int price = offer.getPreTaxPrice();
                boolean isBuy = offer.isBuy();

                storage.insertTrade(TEST_ACCOUNT, item.getItemId(), timestamp, qty, price, isBuy);
            }
        }

        storage.setSetting("migrated_" + TEST_ACCOUNT, Instant.now().toString());
    }

    private int countTotalTrades(AccountData accountData) {
        int count = 0;
        for (FlippingItem item : accountData.getTrades()) {
                count += item.getHistory().getCompressedOfferEvents().size();
            }
        return count;
    }

    private long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return 0L;
        if (value instanceof Long) return (Long) value;
        if (value instanceof Integer) return ((Integer) value).longValue();
        return 0L;
    }
}
