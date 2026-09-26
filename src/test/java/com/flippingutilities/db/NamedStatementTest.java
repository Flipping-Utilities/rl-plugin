package com.flippingutilities.db;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NamedStatementTest {
    private Connection connection;

    @Before
    public void openConnection() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    @After
    public void closeConnection() throws SQLException {
        connection.close();
    }

    @Test
    public void bindsRepeatedNamesOutOfOrderWithoutInterpolatingValues() throws SQLException {
        String payload = "'); DROP TABLE accounts; --";
        try (NamedStatement query = NamedStatement.prepare(connection,
            "SELECT :quantity + :quantity AS total, :label AS label, :optional IS NULL AS empty, :price AS price")) {
            query.bind("price", 4_000_000_000L).bind("optional", null).bind("label", payload).bind("quantity", 7);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(14, rows.getInt("total"));
                assertEquals(payload, rows.getString("label"));
                assertTrue(rows.getBoolean("empty"));
                assertEquals(4_000_000_000L, rows.getLong("price"));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    public void ignoresParameterSyntaxInQuotedTextIdentifiersAndComments() throws SQLException {
        String sql = "SELECT 'it''s :literal ? @ignored $ignored' AS \"quoted\"\":identifier\", " +
            ":value AS [bracket:identifier], :value AS `backtick``:identifier`, " +
            ":other /* :comment ? @ignored $ignored */ AS actual -- :line ? @ignored $ignored\r :ignoredAfterCarriageReturn\n";
        try (NamedStatement query = NamedStatement.prepare(connection, sql)) {
            query.bind("other", 9).bind("value", 3);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("it's :literal ? @ignored $ignored", rows.getString(1));
                assertEquals(3, rows.getInt(2));
                assertEquals(3, rows.getInt(3));
                assertEquals(9, rows.getInt(4));
            }
        }
    }

    @Test
    public void rejectsUnknownAndMissingBindingsBeforeExecuting() throws SQLException {
        try (NamedStatement query = NamedStatement.prepare(connection, "SELECT :required")) {
            assertSqlException(() -> query.bind("typo", 1), "Unknown SQL parameter: typo");
            assertSqlException(query::executeQuery, "Missing SQL parameter: required");
            assertSqlException(query::addBatch, "Missing SQL parameter: required");
            query.bind("required", null);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(null, rows.getObject(1));
            }
        }
    }

    @Test
    public void rejectsMixedOrUnsupportedParameterSyntax() throws SQLException {
        for (String sql : new String[]{"SELECT :named, ?", "SELECT ?2, :named", "SELECT @named",
            "SELECT $named", "SELECT :", "SELECT :name\u00e9", "SELECT :name(suffix)"}) {
            assertSqlException(() -> NamedStatement.prepare(connection, sql), null);
        }
    }

    @Test
    public void batchesDistinctValuesAndReturnsGeneratedKeys() throws SQLException {
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("CREATE TABLE entries (id INTEGER PRIMARY KEY, label TEXT, quantity INTEGER)");
        }
        try (NamedStatement insert = NamedStatement.prepare(connection,
            "INSERT INTO entries (label, quantity) VALUES (:label, :quantity)")) {
            insert.bind("quantity", 2).bind("label", "first").addBatch();
            insert.bind("label", "second").bind("quantity", 3).addBatch();
            assertEquals(2, insert.executeBatch().length);
        }
        try (NamedStatement insert = NamedStatement.prepare(connection,
            "INSERT INTO entries (label, quantity) VALUES (:label, :quantity)", Statement.RETURN_GENERATED_KEYS)) {
            insert.bind("label", "third").bind("quantity", 4);
            assertEquals(1, insert.executeUpdate());
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertTrue(keys.next());
                assertEquals(3, keys.getLong(1));
            }
        }
        try (Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("SELECT label, quantity FROM entries ORDER BY id")) {
            assertTrue(rows.next());
            assertEquals("first", rows.getString(1));
            assertEquals(2, rows.getInt(2));
            assertTrue(rows.next());
            assertEquals("second", rows.getString(1));
            assertEquals(3, rows.getInt(2));
            assertTrue(rows.next());
            assertEquals("third", rows.getString(1));
            assertEquals(4, rows.getInt(2));
            assertFalse(rows.next());
        }
    }

    @Test
    public void bindsGeneratedListNamesAlongsideScalarAndRepeatedParameters() throws SQLException {
        String sql = "SELECT :owner WHERE :owner IN (" + NamedStatement.placeholders("owner", 3) + ")";
        try (NamedStatement query = NamedStatement.prepare(connection, sql)) {
            query.bindList("owner", Arrays.asList("first", "second", "third")).bind("owner", "second");
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("second", rows.getString(1));
                assertFalse(rows.next());
            }
        }
    }

    private static void assertSqlException(SqlAction action, String expectedMessage) throws SQLException {
        try {
            action.run();
            fail("Expected SQLException");
        } catch (SQLException error) {
            if (expectedMessage != null) {
                assertEquals(expectedMessage, error.getMessage());
            }
        }
    }

    private interface SqlAction {
        void run() throws SQLException;
    }
}
