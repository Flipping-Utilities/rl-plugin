package com.flippingutilities.db;

import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static com.flippingutilities.db.SqliteBindings.bind;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SqliteBindingsTest {
    @Test
    public void preservesValueTypesWithTheSqliteDriver() throws SQLException {
        String label = "'); DROP TABLE accounts; -- :name ?";
        byte[] payload = {0, 1, -1};
        Long missingTimestamp = null;

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT ? AS amount, ? AS quantity, ? AS label, ? AS enabled, " +
                     "? AS disabled, ? AS optional, ? AS payload")) {
            bind(statement, 8_000_000_000L, 7, label, true, false, missingTimestamp, payload);

            try (ResultSet results = statement.executeQuery()) {
                assertTrue(results.next());
                assertEquals(8_000_000_000L, results.getLong("amount"));
                assertEquals(7, results.getInt("quantity"));
                assertEquals(label, results.getString("label"));
                assertTrue(results.getBoolean("enabled"));
                assertFalse(results.getBoolean("disabled"));
                assertNull(results.getObject("optional"));
                assertArrayEquals(payload, results.getBytes("payload"));
                assertFalse(results.next());
            }
        }
    }
}
