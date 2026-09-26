package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.google.gson.Gson;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Share the strict import read with DataHandler's first load, without parsing every save twice. */
public final class SandboxTradePersister extends TradePersister {
    private Map<String, AccountData> initialAccounts;

    public SandboxTradePersister(Gson gson) { super(gson); }
    SandboxTradePersister(Gson gson, File directory) { super(gson, directory); }

    public Map<String, AccountData> preloadAccounts() {
        return preloadAccounts(super.loadAllAccountsForMigration());
    }

    /** Reuse validated SQLite import models after their working JSON files are written. */
    public Map<String, AccountData> preloadAccounts(Map<String, AccountData> imported) {
        Map<String, AccountData> loaded = new HashMap<>(imported);
        // The host uses its map to collect item metadata, then clears it. Retain the
        // models until DataHandler owns them and runs its usual preparation checks.
        initialAccounts = new HashMap<>(loaded);
        return loaded;
    }

    @Override public Map<String, AccountData> loadAllAccounts() {
        if (initialAccounts == null) return super.loadAllAccounts();
        Map<String, AccountData> loaded = initialAccounts;
        initialAccounts = null;
        return loaded;
    }
}
