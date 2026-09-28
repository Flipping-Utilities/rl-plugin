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
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.flippingutilities.controller;

import com.flippingutilities.db.JsonStorage;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Owns the live model, immutable save captures, and one ordered background I/O queue. */
@Slf4j
public class DataHandler {
    private final FlippingPlugin plugin;
    private AccountWideData accountWideData = new AccountWideData();
    private Map<String, AccountData> accountSpecificData = new HashMap<>();
    private final Map<String, Long> dirtyAccounts = new HashMap<>();
    private final Map<String, Long> dirtyMetadata = new HashMap<>();
    private final Map<String, Long> deletedAccounts = new HashMap<>();
    private final Map<String, Long> lastChange = new HashMap<>();
    private final Set<String> protectedAccounts = new HashSet<>();
    private long changeNumber;
    private long successfulSaveGeneration;
    private long accountWideRevision;
    private boolean accountWideDirty;
    private boolean initialized;
    private boolean closed;
    private boolean refreshing;
    private boolean refreshAgain;
    private boolean refreshAfterSave;
    private boolean refreshRetryScheduled;
    private Runnable refreshCallback;
    private JsonStorage storage;
    private CompletableFuture<Void> pendingSave = CompletableFuture.completedFuture(null);
    private volatile String storageError;
    private final ExecutorService io;

    public DataHandler(FlippingPlugin plugin) {
        this(plugin, Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
            .setNameFormat("flipping-json-%d").setDaemon(true).build()));
    }

    DataHandler(FlippingPlugin plugin, ExecutorService io) {
        this.plugin = plugin;
        this.io = Objects.requireNonNull(io);
        accountWideData.setDefaults();
    }

    private synchronized JsonStorage storage() {
        if (storage == null) {
            storage = new JsonStorage(plugin.tradePersister.getGson(), plugin.tradePersister.getAccountDirectory());
        }
        return storage;
    }

    public CompletableFuture<JsonStorage.LoadedData> readDataAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return storage().load();
            } catch (IOException | RuntimeException failure) {
                storageFailed(failure);
                throw new CompletionException(failure);
            }
        }, io);
    }

    /** Synchronous seam for callers that already own an I/O thread. Startup uses readDataAsync. */
    public void loadData() {
        try {
            installLoadedData(storage().load());
        } catch (IOException | RuntimeException failure) {
            storageFailed(failure);
        }
    }

    /** ItemManager and slot hydration stay on RuneLite's client thread; file parsing does not. */
    public synchronized void installLoadedData(JsonStorage.LoadedData loaded) {
        accountWideData = loaded.getAccountWideData();
        boolean defaultsChanged = accountWideData.setDefaults();
        plugin.getRecipeHandler().setLocalRecipes(accountWideData.getLocalRecipes());
        Map<String, AccountData> prepared = new HashMap<>();
        loaded.getAccounts().forEach((name, account) -> {
            try {
                account.startNewSession();
                account.prepareForUse(plugin);
                prepared.put(name, account);
                protectedAccounts.remove(name);
            } catch (RuntimeException | OutOfMemoryError failure) {
                protectedAccounts.add(name);
                storageFailed(failure);
                log.error("Cannot prepare account {}; its persisted records will not be replaced", name, failure);
            }
        });
        accountSpecificData = prepared;
        initialized = true;
        if (defaultsChanged) markDataAsHavingChanged(FlippingPlugin.ACCOUNT_WIDE);
    }

    public synchronized AccountWideData viewAccountWideData() { return accountWideData; }
    public synchronized AccountWideData getAccountWideData() {
        markDataAsHavingChanged(FlippingPlugin.ACCOUNT_WIDE);
        return accountWideData;
    }

    public synchronized void addAccount(String name) {
        if (protectedAccounts.contains(name)) {
            throw new IllegalStateException("This account needs storage recovery before it can be changed");
        }
        AccountData account = new AccountData();
        account.prepareForUse(plugin);
        accountSpecificData.put(name, account);
        deletedAccounts.remove(name);
        markDataAsHavingChanged(name);
    }

    public synchronized void deleteAccount(String name) {
        if (protectedAccounts.contains(name)) {
            storageFailed(new IllegalStateException("Cannot delete protected account " + name));
            return;
        }
        accountSpecificData.remove(name);
        dirtyAccounts.remove(name);
        dirtyMetadata.remove(name);
        long revision = ++changeNumber;
        deletedAccounts.put(name, revision);
        lastChange.put(name, revision);
    }

    public synchronized Collection<AccountData> getAllAccountData() {
        accountSpecificData.keySet().forEach(this::markDataAsHavingChanged);
        return new ArrayList<>(accountSpecificData.values());
    }

    public synchronized Collection<AccountData> viewAllAccountData() {
        return new ArrayList<>(accountSpecificData.values());
    }

    public synchronized AccountData getAccountData(String name) {
        markDataAsHavingChanged(name);
        return accountSpecificData.get(name);
    }

    public synchronized AccountData viewAccountData(String name) { return accountSpecificData.get(name); }
    public synchronized Set<String> getCurrentAccounts() { return new HashSet<>(accountSpecificData.keySet()); }

    public synchronized void markDataAsHavingChanged(String name) {
        if (FlippingPlugin.ACCOUNT_WIDE.equals(name)) {
            accountWideDirty = true;
            accountWideRevision = ++changeNumber;
        } else if (name != null) {
            long revision = ++changeNumber;
            dirtyAccounts.put(name, revision);
            lastChange.put(name, revision);
        }
    }

    public synchronized void markSessionTimeChanged(String name) {
        if (name != null) {
            long revision = ++changeNumber;
            dirtyMetadata.put(name, revision);
            lastChange.put(name, revision);
        }
    }

    /** Capture on the model's owning thread. Only immutable JSON records cross onto the I/O queue. */
    public synchronized boolean storeData() {
        if (closed || !initialized || !pendingSave.isDone()) return false;
        if (dirtyAccounts.isEmpty() && dirtyMetadata.isEmpty() && deletedAccounts.isEmpty() && !accountWideDirty) return true;
        Map<String, Long> fullVersions = new HashMap<>(dirtyAccounts);
        Map<String, Long> metadataVersions = new HashMap<>(dirtyMetadata);
        Map<String, Long> deletions = new HashMap<>(deletedAccounts);
        long wideVersion = accountWideRevision;
        boolean includeWide = accountWideDirty;
        try {
            Map<String, AccountData> full = changedData(fullVersions.keySet());
            Map<String, AccountData> metadata = changedData(metadataVersions.keySet());
            metadata.keySet().removeAll(full.keySet());
            for (String name : deletions.keySet()) {
                if (protectedAccounts.contains(name)) throw new IllegalStateException("Cannot delete protected account " + name);
            }
            JsonStorage.CapturedSave captured = storage().capture(full, metadata,
                includeWide ? accountWideData : null, deletions.keySet());
            pendingSave = CompletableFuture.runAsync(() -> {
                try {
                    storage().commit(captured);
                    synchronized (DataHandler.this) {
                        fullVersions.forEach((name, version) -> dirtyAccounts.remove(name, version));
                        metadataVersions.forEach((name, version) -> dirtyMetadata.remove(name, version));
                        deletions.forEach((name, version) -> deletedAccounts.remove(name, version));
                        if (includeWide && accountWideRevision == wideVersion) accountWideDirty = false;
                        successfulSaveGeneration++;
                    }
                    synchronized (DataHandler.this) {
                        if (protectedAccounts.isEmpty()) storageError = null;
                    }
                } catch (IOException | RuntimeException failure) {
                    storageFailed(failure);
                    throw new CompletionException(failure);
                }
            }, io);
            pendingSave.whenComplete((ignored, failure) -> {
                synchronized (DataHandler.this) {
                    if (failure == null && refreshAfterSave) {
                        refreshAgain = true;
                        refreshAfterSave = false;
                    }
                    queueRefreshRetry();
                }
            });
        } catch (RuntimeException failure) {
            storageFailed(failure);
        }
        return false;
    }

    private Map<String, AccountData> changedData(Set<String> names) {
        Map<String, AccountData> result = new HashMap<>();
        for (String name : names) {
            if (protectedAccounts.contains(name)) throw new IllegalStateException("Cannot save protected account " + name);
            AccountData account = accountSpecificData.get(name);
            if (account == null) throw new IllegalStateException("Cannot capture missing account " + name);
            result.put(name, account);
        }
        return result;
    }

    /** Wait for the preceding capture, then capture any newer edits before closing the queue. */
    public Future<?> close() {
        CompletableFuture<Void> preceding;
        synchronized (this) {
            if (closed) return pendingSave;
            preceding = pendingSave;
        }
        try { preceding.join(); } catch (CompletionException failure) { /* retry the still-dirty capture */ }
        synchronized (this) {
            if (closed) return pendingSave;
            storeData();
            if ((!initialized || hasUnsavedChanges()) && pendingSave.isDone()) {
                pendingSave = new CompletableFuture<>();
                pendingSave.completeExceptionally(new IOException(storageError == null
                    ? "JSON storage could not capture all pending changes" : storageError));
            }
            closed = true;
            io.shutdown();
            return pendingSave;
        }
    }

    public String getStorageError() { return storageError; }

    public synchronized boolean isSavePending() {
        return !pendingSave.isDone() || hasUnsavedChanges();
    }

    private boolean hasUnsavedChanges() {
        return !dirtyAccounts.isEmpty() || !dirtyMetadata.isEmpty() || !deletedAccounts.isEmpty() || accountWideDirty;
    }

    private void storageFailed(Throwable failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (!message.equals(storageError)) log.error("JSON storage needs attention: {}", message, failure);
        storageError = message;
    }

    /** Watch notifications only request reads; adopting a result rechecks local dirtiness on the client thread. */
    public synchronized void refreshExternalData(Runnable changed) {
        if (!initialized || closed) return;
        refreshCallback = changed;
        if (refreshing || !pendingSave.isDone()) {
            refreshAgain = true;
            return;
        }
        refreshAgain = false;
        refreshAfterSave = false;
        Map<String, Long> observedChanges = new HashMap<>(lastChange);
        long observedSaveGeneration = successfulSaveGeneration;
        long observedWideRevision = accountWideRevision;
        refreshing = true;
        io.execute(() -> {
            try {
                JsonStorage.Updates updates = storage().readUpdates();
                plugin.getClientThread().invokeLater(() -> {
                    boolean adopted = false;
                    synchronized (DataHandler.this) {
                        try {
                            if (closed) return;
                            if (!pendingSave.isDone() || successfulSaveGeneration != observedSaveGeneration) {
                                refreshAgain = true;
                                return;
                            }
                            Set<String> accepted = new HashSet<>();
                            Set<String> names = new HashSet<>(updates.getAccounts().keySet());
                            names.addAll(updates.getDeletedAccounts());
                            for (String name : names) {
                                if (dirtyAccounts.containsKey(name) || dirtyMetadata.containsKey(name)
                                        || deletedAccounts.containsKey(name)
                                        || !Objects.equals(lastChange.get(name), observedChanges.get(name))) {
                                    refreshAfterSave = true;
                                    continue;
                                }
                                AccountData account = updates.getAccounts().get(name);
                                if (account == null) {
                                    accountSpecificData.remove(name);
                                } else {
                                    try {
                                        account.prepareForUse(plugin);
                                    } catch (RuntimeException | OutOfMemoryError failure) {
                                        protectedAccounts.add(name);
                                        storageFailed(failure);
                                        continue;
                                    }
                                    accountSpecificData.put(name, account);
                                }
                                protectedAccounts.remove(name);
                                accepted.add(name);
                            }
                            boolean acceptWide = updates.getAccountWideData() != null && !accountWideDirty
                                && accountWideRevision == observedWideRevision;
                            if (updates.getAccountWideData() != null && !acceptWide) refreshAfterSave = true;
                            boolean wideDefaultsChanged = false;
                            if (acceptWide) {
                                AccountWideData wide = updates.getAccountWideData();
                                wideDefaultsChanged = wide.setDefaults();
                                plugin.getRecipeHandler().setLocalRecipes(wide.getLocalRecipes());
                                accountWideData = wide;
                            }
                            storage().adopt(updates, accepted, acceptWide);
                            if (wideDefaultsChanged) markDataAsHavingChanged(FlippingPlugin.ACCOUNT_WIDE);
                            adopted = !accepted.isEmpty() || acceptWide;
                        } catch (RuntimeException failure) {
                            storageFailed(failure);
                        } finally {
                            refreshing = false;
                            queueRefreshRetry();
                        }
                    }
                    if (adopted) changed.run();
                });
            } catch (IOException | RuntimeException failure) {
                storageFailed(failure);
                synchronized (DataHandler.this) {
                    refreshing = false;
                    queueRefreshRetry();
                }
            }
        });
    }

    /** Coalesce overlapping file notifications, but retry dirty accounts only after a successful save. */
    private void queueRefreshRetry() {
        if (closed || !initialized || refreshing || !pendingSave.isDone()
                || !refreshAgain || refreshRetryScheduled || refreshCallback == null) return;
        refreshRetryScheduled = true;
        plugin.getClientThread().invokeLater(() -> {
            synchronized (DataHandler.this) {
                refreshRetryScheduled = false;
                if (closed || !refreshAgain) return;
                refreshExternalData(refreshCallback);
            }
        });
    }

    public synchronized void loadAccountData(String name) {
        if (!initialized || closed || !pendingSave.isDone() || dirtyAccounts.containsKey(name)
                || dirtyMetadata.containsKey(name) || deletedAccounts.containsKey(name)) return;
        try {
            AccountData account = storage().loadAccount(name);
            if (account != null) {
                account.prepareForUse(plugin);
                accountSpecificData.put(name, account);
                protectedAccounts.remove(name);
            } else {
                accountSpecificData.remove(name);
                protectedAccounts.remove(name);
            }
        } catch (IOException | RuntimeException failure) {
            protectedAccounts.add(name);
            storageFailed(failure);
        }
    }

    public synchronized void loadAccountWideData() {
        if (!initialized || closed || !pendingSave.isDone() || accountWideDirty) return;
        try {
            AccountWideData wide = storage().loadAccountWideData();
            boolean defaultsChanged = wide.setDefaults();
            plugin.getRecipeHandler().setLocalRecipes(wide.getLocalRecipes());
            accountWideData = wide;
            if (defaultsChanged) markDataAsHavingChanged(FlippingPlugin.ACCOUNT_WIDE);
        } catch (IOException | RuntimeException failure) {
            storageFailed(failure);
        }
    }
}
