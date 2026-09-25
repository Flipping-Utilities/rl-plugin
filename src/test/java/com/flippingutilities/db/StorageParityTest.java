package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
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
    public void testSettingsKeyValue() {
        String key = "test_setting";
        String value = "test_value";
        
        storage.setSetting(key, value);
        assertEquals("Should retrieve same value", value, storage.getSetting(key));
        
        storage.clearSetting(key);
        assertNull("Should be null after clear", storage.getSetting(key));
    }

    @Test
    public void testGeLimitState() {
        String displayName = "GeLimitPlayer";
        storage.upsertAccount(displayName, "pid-gelimit");
        
        int itemId = 4151;
        Instant nextRefresh = Instant.now().plusSeconds(3600);
        int itemsBought = 50;
        int itemsBoughtThroughComplete = 40;

        storage.upsertGeLimitState(displayName, itemId, nextRefresh, itemsBought, itemsBoughtThroughComplete);

        Map<String, Object> state = storage.loadGeLimitState(displayName, itemId);
        assertNotNull("State should not be null", state);
        assertEquals("Items bought should match", itemsBought, state.get("itemsBought"));
        assertEquals("Complete-offer base should match", itemsBoughtThroughComplete, state.get("itemsBoughtThroughCompleteOffers"));
    }
    
}
