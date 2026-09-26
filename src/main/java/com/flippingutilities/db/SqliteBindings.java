package com.flippingutilities.db;

import java.sql.PreparedStatement;
import java.sql.SQLException;

final class SqliteBindings {
    private SqliteBindings() {
    }

    static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            statement.setObject(i + 1, values[i]);
        }
    }
}
