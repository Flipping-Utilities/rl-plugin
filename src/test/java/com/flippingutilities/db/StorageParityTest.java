package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.OfferEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests that SQLite storage operations work correctly and produce expected results.
 */
public class StorageParityTest {
    
    private File testDbFile;
    private SqliteStorage storage;
    
    @Before
    public void setUp() throws Exception {
        testDbFile = Files.createTempFile("test_parity_", ".db").toFile();
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
    public void testAccountUpsertAndLoad() {
        String displayName = "TestPlayer";
        String playerId = "player-123";
        
        // Upsert account
        storage.upsertAccount(displayName, playerId);
        
        // Load and verify
        AccountData loaded = storage.loadAccount(displayName);
        assertNotNull("AccountData should not be null", loaded);
    }
    
    @Test
    public void testAccountList() {
        storage.upsertAccount("Player1", "pid-1");
        storage.upsertAccount("Player2", "pid-2");
        storage.upsertAccount("Player3", "pid-3");
        
        List<String> accounts = storage.listAccounts();
        assertEquals("Should have 3 accounts", 3, accounts.size());
        assertTrue("Should contain Player1", accounts.contains("Player1"));
        assertTrue("Should contain Player2", accounts.contains("Player2"));
        assertTrue("Should contain Player3", accounts.contains("Player3"));
    }
    
    @Test
    public void testTradeInsertAndLoad() {
        String displayName = "TradePlayer";
        storage.upsertAccount(displayName, "pid-trade");
        
        // Insert some trades
        long now = Instant.now().toEpochMilli();
        storage.insertTrade(displayName, 4151, now, 10, 50000, true);  // buy whip
        storage.insertTrade(displayName, 4151, now + 60000, 10, 55000, false); // sell whip
        
        // Load trades
        List<Map<String, Object>> trades = storage.loadTrades(displayName, Instant.EPOCH);
        assertEquals("Should have 2 trades", 2, trades.size());
        
        // Verify item filter
        List<Map<String, Object>> itemTrades = storage.loadTradesByItem(displayName, 4151, Instant.EPOCH);
        assertEquals("Should have 2 trades for item 4151", 2, itemTrades.size());
    }
    
    @Test
    public void testItemsListQueryWithAggregation() {
        String displayName = "ItemsPlayer";
        storage.upsertAccount(displayName, "pid-items");
        
        // Insert trades for multiple items
        long now = Instant.now().toEpochMilli();
        storage.insertTrade(displayName, 4151, now, 10, 50000, true);      // buy 10 whips
        storage.insertTrade(displayName, 4151, now + 1000, 5, 52000, false); // sell 5 whips
        storage.insertTrade(displayName, 4587, now + 2000, 20, 1000, true);  // buy 20 dragon daggers
        
        // Query items list
        List<Map<String, Object>> items = storage.queryItemsList(displayName, Instant.EPOCH, "TIME", 10, 0);
        assertEquals("Should have 2 items", 2, items.size());
        
        // Find whip item
        Map<String, Object> whipItem = items.stream()
            .filter(m -> (Integer)m.get("itemId") == 4151)
            .findFirst().orElse(null);
        assertNotNull("Should find whip item", whipItem);
        assertTrue("Should have totalProfit key", whipItem.containsKey("totalProfit"));
        assertTrue("Should have roi key", whipItem.containsKey("roi"));
    }
    
    @Test
    public void testAggregateStatsQuery() {
        String displayName = "StatsPlayer";
        storage.upsertAccount(displayName, "pid-stats");
        
        // Insert sample trades
        long now = Instant.now().toEpochMilli();
        storage.insertTrade(displayName, 4151, now, 10, 50000, true);       // buy 10 @ 50000 = 500000 cost
        storage.insertTrade(displayName, 4151, now + 1000, 10, 60000, false); // sell 10 @ 60000 = 600000 revenue
        
        Map<String, Object> stats = storage.queryAggregateStats(displayName, Instant.EPOCH);
        
        assertNotNull("Stats should not be null", stats);
        assertTrue("Should have totalProfit", stats.containsKey("totalProfit"));
        assertTrue("Should have totalExpense", stats.containsKey("totalExpense"));
        assertTrue("Should have totalRevenue", stats.containsKey("totalRevenue"));
        assertTrue("Should have flipCount", stats.containsKey("flipCount"));
        assertTrue("Should have taxPaid", stats.containsKey("taxPaid"));
        
        // Verify profit = (10 * 60000) - (10 * 50000) = 100000
        assertEquals("Total profit should be 100000", 100000L, stats.get("totalProfit"));
        assertEquals("Total expense should be 500000", 500000L, stats.get("totalExpense"));
        assertEquals("Total revenue should be 600000", 600000L, stats.get("totalRevenue"));
        assertEquals("Flip count should be 2", 2, stats.get("flipCount"));
    }
    
    @Test
    public void testSettingsKeyValue() {
        String key = "test_setting";
        String value = "test_value";
        
        storage.setSetting(key, value);
        assertEquals("Should retrieve same value", value, storage.getSetting(key));
        
        storage.clearSetting(key);
        assertNull("Should be null after clear", storage.getSetting(key));
    }
    
    @Test
    public void testEventInsertAndLoad() {
        String displayName = "EventPlayer";
        storage.upsertAccount(displayName, "pid-event");
        
        int eventId = storage.insertEvent(displayName, "flip", 50000, 10000, "Test flip note");
        assertTrue("Event ID should be positive", eventId > 0);
        
        List<Map<String, Object>> events = storage.loadEvents(displayName, Instant.EPOCH);
        assertEquals("Should have 1 event", 1, events.size());
        assertEquals("Event type should be flip", "flip", events.get(0).get("type"));
        assertEquals("Event note should match", "Test flip note", events.get(0).get("note"));
    }
    
    @Test
    public void testSlotUpsertAndLoad() {
        String displayName = "SlotPlayer";
        storage.upsertAccount(displayName, "pid-slot");
        
        // Create a simple offer event JSON representation
        // Note: This tests the slot storage mechanism - actual OfferEvent construction
        // would require more setup. Here we test the SQL layer.
        
        // For now, just verify the slot operations don't throw
        Map<Integer, OfferEvent> slots = storage.loadAllSlots(displayName);
        assertNotNull("Slots map should not be null", slots);
    }
    
    @Test
    public void testSlotTimer() {
        String displayName = "TimerPlayer";
        storage.upsertAccount(displayName, "pid-timer");
        
        Instant activity = Instant.now();
        storage.upsertSlotTimer(displayName, 0, activity);
        
        Instant loaded = storage.loadSlotTimer(displayName, 0);
        assertNotNull("Timer should not be null", loaded);
        assertEquals("Timer should match", activity.toEpochMilli(), loaded.toEpochMilli());
        
        Map<Integer, Instant> allTimers = storage.loadAllSlotTimers(displayName);
        assertNotNull("All timers map should not be null", allTimers);
        assertEquals("Should have 8 slots", 8, allTimers.size());
    }
    
    @Test
    public void testGeLimitState() {
        String displayName = "GeLimitPlayer";
        storage.upsertAccount(displayName, "pid-gelimit");
        
        int itemId = 4151;
        Instant nextRefresh = Instant.now().plusSeconds(3600);
        int itemsBought = 50;
        
        storage.upsertGeLimitState(displayName, itemId, nextRefresh, itemsBought);
        
        Map<String, Object> state = storage.loadGeLimitState(displayName, itemId);
        assertNotNull("State should not be null", state);
        assertEquals("Items bought should match", itemsBought, state.get("itemsBought"));
    }
    
    @Test
    public void testRecipeFlipGroupsQuery() {
        String displayName = "RecipePlayer";
        storage.upsertAccount(displayName, "pid-recipe");
        
        // Insert a recipe event
        int eventId = storage.insertEvent(displayName, "recipe", 10000, 5000, "Potion recipe");
        assertTrue("Event should be created", eventId > 0);
        
        // Query recipe flip groups
        List<Map<String, Object>> groups = storage.queryRecipeFlipGroups(displayName, Instant.EPOCH);
        // May be empty since we haven't populated recipe_flips table
        assertNotNull("Groups list should not be null", groups);
    }
}
