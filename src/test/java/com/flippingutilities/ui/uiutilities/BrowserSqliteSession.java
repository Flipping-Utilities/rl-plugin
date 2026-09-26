package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.db.MigrationService;
import com.flippingutilities.db.SqliteStorage;
import com.flippingutilities.db.TradePersister;
import com.flippingutilities.model.AccountData;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Run the production migration and read its result before opening the browser sandbox. */
public final class BrowserSqliteSession {
    private BrowserSqliteSession() {}

    /** A null snapshot opens an existing database; a non-null snapshot migrates JSON. */
    public static Map<String, AccountData> prepare(SqliteStorage storage, TradePersister persister,
                                                  Map<String, AccountData> snapshot,
                                                  Consumer<String> progress) throws SQLException {
        if (snapshot != null) {
            for (String name : snapshot.keySet()) BrowserSqliteImporter.validateAccountName(name);
            progress.accept("Converting JSON saves to SQLite…");
            new MigrationService(storage, persister).migrate(snapshot);
            if (!"true".equalsIgnoreCase(storage.getSetting("migration_completed"))) {
                throw new SQLException("JSON to SQLite conversion did not complete. No converted database is available.");
            }
            if (!new HashSet<>(storage.listAccounts()).equals(snapshot.keySet())) {
                throw new SQLException("The converted database does not contain every imported account.");
            }
        }
        progress.accept("Checking the SQLite database…");
        BrowserSqliteImporter.validateSchema(storage.getConnection());
        List<String> names = storage.listAccounts();
        Map<String, AccountData> accounts = new LinkedHashMap<>();
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            BrowserSqliteImporter.validateAccountName(name);
            progress.accept("Reading SQLite data: account " + (index + 1) + " of " + names.size() + "…");
            AccountData account = storage.loadAccount(name);
            if (account == null) throw new SQLException("Could not read an account from SQLite.");
            accounts.put(name, account);
        }
        if (snapshot != null) {
            storage.clearSetting("migration_pending");
            storage.markSynchronized();
        }
        return accounts;
    }
}
