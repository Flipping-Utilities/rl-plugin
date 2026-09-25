package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.flippingutilities.db.StorageTestOffers.complete;
import static org.junit.Assert.*;

public class RecipeAccountLoadTest {
    private static final String ACCOUNT = "Recipe account";
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void recipeQueryCountDoesNotGrowWithHistory() throws Exception {
        CountingStorage storage = new CountingStorage(temporaryFolder.newFile("recipe-queries.db"));
        try {
            storage.initializeSchema();
            storage.insertRecipeFlip(ACCOUNT, "recipe", recipe(ACCOUNT, TIME, 100));
            storage.recipeQueries = 0;
            assertEquals(1, storage.loadAccount(ACCOUNT).getRecipeFlipGroups().get(0).getRecipeFlips().size());
            int smallAccountQueries = storage.recipeQueries;

            for (int i = 1; i < 1_000; i++) {
                storage.insertRecipeFlip(ACCOUNT, "recipe", recipe(ACCOUNT, TIME.plusSeconds(i), 100 + i));
            }
            storage.recipeQueries = 0;
            AccountData loaded = storage.loadAccount(ACCOUNT);
            List<RecipeFlip> flips = loaded.getRecipeFlipGroups().get(0).getRecipeFlips();
            assertEquals(1_000, flips.size());
            for (int i = 0; i < flips.size(); i++) {
                assertEquals(100 + i, flips.get(i).getInputs().get(4151).get("shared").getOffer().getPreTaxPrice());
                assertEquals(150 + i, flips.get(i).getOutputs().get(4151).get("shared").getOffer().getPreTaxPrice());
            }
            assertEquals("Recipe loading must use a bounded number of queries", smallAccountQueries, storage.recipeQueries);
            assertTrue("Metadata, inputs and outputs need at most three queries", storage.recipeQueries <= 3);
        } finally {
            storage.close();
        }
    }

    @Test
    public void componentsStayScopedToAccountFlipAndSide() throws Exception {
        SqliteStorage storage = new SqliteStorage(temporaryFolder.newFile("recipe-components.db"));
        try {
            storage.initializeSchema();
            // Insert out of order; the same item/UUID appears on both sides, in both
            // accounts and in successive flips with different snapshots/consumption.
            RecipeFlip later = recipe(ACCOUNT, TIME.plusSeconds(10), 200);
            later.getInputs().get(4151).get("shared").setAmountConsumed(4);
            later.getOutputs().get(4151).get("shared").setOffer(null);
            storage.insertRecipeFlip(ACCOUNT, "recipe", later);
            storage.insertRecipeFlip("Other account", "recipe", recipe("Other account", TIME, 9_000));
            RecipeFlip earlier = recipe(ACCOUNT, TIME, 100);
            OfferEvent secondInput = complete(ACCOUNT, 4151, "second", TIME.toEpochMilli(), 20, 75, true);
            earlier.getInputs().get(4151).put("second", new PartialOffer(secondInput, 3));
            storage.insertRecipeFlip(ACCOUNT, "recipe", earlier);
            storage.insertRecipeFlip(ACCOUNT, "empty", new RecipeFlip(TIME, new HashMap<>(), new HashMap<>(), 7));
            storage.close();

            AccountData loaded = storage.loadAccount(ACCOUNT);
            assertTrue("Detached recipe snapshots must stay out of item history", loaded.getTrades().isEmpty());
            assertEquals(2, loaded.getRecipeFlipGroups().size());
            List<RecipeFlip> flips = loaded.getRecipeFlipGroups().stream()
                .filter(group -> group.getRecipeKey().equals("recipe")).findFirst().get().getRecipeFlips();
            assertEquals(2, flips.size());
            assertEquals(TIME, flips.get(0).getTimeOfCreation());
            assertEquals(TIME.plusSeconds(10), flips.get(1).getTimeOfCreation());
            assertEquals(12, flips.get(0).getCoinCost());
            assertEquals(2, flips.get(0).getInputs().get(4151).size());
            assertComponent(flips.get(0).getInputs().get(4151).get("shared"), 100, 2, true, ACCOUNT);
            assertComponent(flips.get(0).getInputs().get(4151).get("second"), 75, 3, true, ACCOUNT);
            assertComponent(flips.get(0).getOutputs().get(4151).get("shared"), 150, 1, false, ACCOUNT);
            assertComponent(flips.get(1).getInputs().get(4151).get("shared"), 200, 4, true, ACCOUNT);
            PartialOffer missing = flips.get(1).getOutputs().get(4151).get("shared");
            assertNull(missing.getOffer());
            assertEquals("shared", missing.getOfferUuid());
            assertEquals(1, missing.getAmountConsumed());

            RecipeFlip empty = loaded.getRecipeFlipGroups().stream()
                .filter(group -> group.getRecipeKey().equals("empty")).findFirst().get().getRecipeFlips().get(0);
            assertTrue(empty.getInputs().isEmpty());
            assertTrue(empty.getOutputs().isEmpty());
            assertEquals(7, empty.getCoinCost());
            RecipeFlip other = storage.loadAccount("Other account").getRecipeFlipGroups().get(0).getRecipeFlips().get(0);
            assertComponent(other.getInputs().get(4151).get("shared"), 9_000, 2, true, "Other account");
        } finally {
            storage.close();
        }
    }

    @Test
    public void deletionAfterMetadataReadKeepsCompleteRecipeSnapshot() throws Exception {
        assertConcurrentDeletionKeepsSnapshot("recipe_flip_inputs");
    }

    @Test
    public void deletionAfterInputsReadKeepsCompleteRecipeSnapshot() throws Exception {
        assertConcurrentDeletionKeepsSnapshot("recipe_flip_outputs");
    }

    private void assertConcurrentDeletionKeepsSnapshot(String beforeTable) throws Exception {
        File file = temporaryFolder.newFile("concurrent-deletion.db");
        CountingStorage reader = new CountingStorage(file);
        SqliteStorage writer = new SqliteStorage(file);
        try {
            reader.initializeSchema();
            reader.insertRecipeFlip(ACCOUNT, "recipe", recipe(ACCOUNT, TIME, 100));
            reader.beforeTable = beforeTable;
            reader.beforeRead = () -> writer.deleteRecipeFlip(ACCOUNT, "recipe", TIME);

            RecipeFlip restored = reader.loadAccount(ACCOUNT).getRecipeFlipGroups().get(0).getRecipeFlips().get(0);
            assertEquals(12, restored.getCoinCost());
            assertEquals(1, restored.getInputs().size());
            assertEquals(1, restored.getOutputs().size());
            assertComponent(restored.getInputs().get(4151).get("shared"), 100, 2, true, ACCOUNT);
            assertComponent(restored.getOutputs().get(4151).get("shared"), 150, 1, false, ACCOUNT);
            assertNull("The deletion must run between read batches", reader.beforeRead);
            assertTrue(reader.getConnection().getAutoCommit());
            assertTrue("The next load must observe the committed deletion", reader.loadAccount(ACCOUNT).getRecipeFlipGroups().isEmpty());
        } finally {
            reader.close();
            writer.close();
        }
    }

    @Test
    public void loadDoesNotCommitOrCloseACallerTransaction() throws Exception {
        File file = temporaryFolder.newFile("caller-transaction.db");
        SqliteStorage storage = new SqliteStorage(file);
        SqliteStorage observer = new SqliteStorage(file);
        try {
            storage.initializeSchema();
            storage.insertRecipeFlip(ACCOUNT, "recipe", recipe(ACCOUNT, TIME, 100));
            Connection connection = storage.getConnection();
            connection.setAutoCommit(false);
            storage.setSetting("pending-caller-write", "uncommitted");

            assertEquals(1, storage.loadAccount(ACCOUNT).getRecipeFlipGroups().size());
            assertFalse(connection.getAutoCommit());
            assertEquals("uncommitted", storage.getSetting("pending-caller-write"));
            assertNull("Loading must not commit the caller's pending writes", observer.getSetting("pending-caller-write"));
            connection.rollback();
            connection.setAutoCommit(true);
            assertNull(storage.getSetting("pending-caller-write"));
        } finally {
            storage.close();
            observer.close();
        }
    }

    @Test
    public void malformedSnapshotReleasesOwnedReadTransaction() throws Exception {
        File file = temporaryFolder.newFile("invalid-snapshot.db");
        SqliteStorage storage = new SqliteStorage(file);
        SqliteStorage writer = new SqliteStorage(file);
        try {
            storage.initializeSchema();
            storage.insertRecipeFlip(ACCOUNT, "recipe", recipe(ACCOUNT, TIME, 100));
            try (PreparedStatement corrupt = writer.getConnection().prepareStatement("UPDATE recipe_flip_inputs SET offer_json = ?")) {
                corrupt.setString(1, "{");
                corrupt.executeUpdate();
            }
            try {
                storage.loadAccount(ACCOUNT);
                fail("A malformed offer snapshot must fail the load");
            } catch (com.google.gson.JsonSyntaxException expected) {
                assertTrue(storage.getConnection().getAutoCommit());
            }
            try (PreparedStatement repair = writer.getConnection().prepareStatement("UPDATE recipe_flip_inputs SET offer_json = ?")) {
                repair.setString(1, "null");
                repair.executeUpdate();
            }
            RecipeFlip restored = storage.loadAccount(ACCOUNT).getRecipeFlipGroups().get(0).getRecipeFlips().get(0);
            assertNull(restored.getInputs().get(4151).get("shared").getOffer());
            assertComponent(restored.getOutputs().get(4151).get("shared"), 150, 1, false, ACCOUNT);
        } finally {
            storage.close();
            writer.close();
        }
    }

    private static RecipeFlip recipe(String account, Instant time, int price) {
        OfferEvent input = complete(account, 4151, "shared", time.toEpochMilli(), 10, price, true);
        OfferEvent output = complete(account, 4151, "shared", time.toEpochMilli(), 10, price + 50, false);
        Map<Integer, Map<String, PartialOffer>> inputs = new HashMap<>();
        inputs.put(4151, new HashMap<>(Collections.singletonMap("shared", new PartialOffer(input, 2))));
        Map<Integer, Map<String, PartialOffer>> outputs = new HashMap<>();
        outputs.put(4151, new HashMap<>(Collections.singletonMap("shared", new PartialOffer(output, 1))));
        return new RecipeFlip(time, outputs, inputs, 12);
    }

    private static void assertComponent(PartialOffer component, int price, int consumed, boolean buy, String account) {
        assertNotNull(component);
        assertEquals(consumed, component.getAmountConsumed());
        assertNotNull(component.getOffer());
        assertEquals(price, component.getOffer().getPreTaxPrice());
        assertEquals(buy, component.getOffer().isBuy());
        assertEquals(account, component.getOffer().getMadeBy());
        assertEquals("Item 4151", component.getOffer().getItemName());
    }

    /** Counts executed JDBC reads while delegating all storage work to a real SQLite database. */
    private static final class CountingStorage extends SqliteStorage {
        private int recipeQueries;
        private String beforeTable;
        private Runnable beforeRead;

        private CountingStorage(File file) {
            super(file);
        }

        @Override
        public synchronized Connection getConnection() throws SQLException {
            Connection delegate = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {
                    Object result = invoke(delegate, method, arguments);
                    if (method.getName().equals("prepareStatement") && ((String) arguments[0]).contains("recipe_flip")) {
                        String sql = (String) arguments[0];
                        PreparedStatement statement = (PreparedStatement) result;
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                            (statementProxy, statementMethod, statementArguments) -> {
                                if (statementMethod.getName().equals("executeQuery")) {
                                    recipeQueries++;
                                    if (beforeRead != null && sql.contains(beforeTable)) {
                                        Runnable action = beforeRead;
                                        beforeRead = null;
                                        action.run();
                                    }
                                }
                                return invoke(statement, statementMethod, statementArguments);
                            });
                    }
                    return result;
                });
        }

        private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
