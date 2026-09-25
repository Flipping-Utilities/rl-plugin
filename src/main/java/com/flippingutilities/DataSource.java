package com.flippingutilities;

public enum DataSource {
    JSON,
    SQLITE;

    public boolean isSqlite() {
        return this == SQLITE;
    }
}
