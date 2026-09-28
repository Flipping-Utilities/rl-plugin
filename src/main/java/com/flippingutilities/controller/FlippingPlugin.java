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

import com.flippingutilities.FlippingConfig;
import com.flippingutilities.db.TradePersister;
import com.flippingutilities.jobs.SlotSenderJob;
import com.flippingutilities.jobs.TimeseriesFetcher;
import com.flippingutilities.model.*;
import com.flippingutilities.ui.MasterPanel;
import com.flippingutilities.ui.flipping.FlippingPanel;
import com.flippingutilities.ui.gehistorytab.GeHistoryTabPanel;
import com.flippingutilities.ui.login.LoginPanel;
import com.flippingutilities.ui.slots.SlotsPanel;
import com.flippingutilities.ui.statistics.StatsPanel;
import com.flippingutilities.ui.uiutilities.GeSpriteLoader;
import com.flippingutilities.ui.widgets.SlotActivityTimer;
import com.flippingutilities.jobs.CacheUpdaterJob;
import com.flippingutilities.ui.widgets.SlotStateDrawer;
import com.flippingutilities.ui.widgets.OfferGraphChartOverlay;
import com.flippingutilities.utilities.*;
import com.flippingutilities.jobs.WikiDataFetcherJob;
import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.ImageUtil;
import okhttp3.*;

import javax.inject.Inject;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@PluginDescriptor(
        name = "Flipping Utilities",
        description = "Provides utilities for GE flipping"
)
public class FlippingPlugin extends Plugin {
    public static final String CONFIG_GROUP = "flipping";
    public static final String ACCOUNT_WIDE = "Accountwide";



    @Inject
    @Getter
    private Client client;
    @Inject
    @Getter
    private ClientThread clientThread;
    @Inject
    @Getter
    private ScheduledExecutorService executor;
    private ScheduledFuture generalRepeatingTasks;
    @Inject
    private ClientToolbar clientToolbar;
    private NavigationButton navButton;

    @Inject
    private TooltipManager tooltipManager;

    @Inject
    @Getter
    private FlippingConfig config;

    @Inject
    @Getter
    private ItemManager itemManager;

    @Inject
    private KeyManager keyManager;

    @Inject
    @Getter
    private OkHttpClient httpClient;

    @Inject
    @Getter
    private ConfigManager configManager;

    @Inject
    @Getter
    private TimeseriesFetcher timeseriesFetcher;

    @Getter
    private FlippingPanel flippingPanel;
    @Getter
    private StatsPanel statPanel;
    @Getter
    private SlotsPanel slotsPanel;
    @Getter
    private MasterPanel masterPanel;
    @Getter
    private GeHistoryTabPanel geHistoryTabPanel;
    private LoginPanel loginPanel;

    //this flag is to know that when we see the login screen an account has actually logged out and its not just that the
    //client has started.
    private boolean previouslyLoggedIn;

    //the display name of the account whose trade list the user is currently looking at as selected
    //through the dropdown menu
    @Getter
    private String accountCurrentlyViewed = ACCOUNT_WIDE;

    //the display name of the currently logged in user. This is the only account that can actually receive offers
    //as this is the only account currently logged in.
    @Getter
    @Setter
    private String currentlyLoggedInAccount;

    //some events come before a display name has been retrieved and since a display name is crucial for figuring out
    //which account's trade list to add to, we queue the events here to be processed as soon as a display name is set.
    @Getter
    private List<OfferEvent> eventsReceivedBeforeFullLogin = new ArrayList<>();

    private final AccountViewHandler accountViewHandler = new AccountViewHandler(this);
    private final TradeHistoryHandler tradeHistoryHandler = new TradeHistoryHandler(this);
    private final FavoriteHandler favoriteHandler = new FavoriteHandler(this);
    private final RecipeFlipHandler recipeFlipHandler = new RecipeFlipHandler(this);
    private final SessionTimeHandler sessionTimeHandler = new SessionTimeHandler(this);

    //updates the cache by monitoring the directory and loading a file's contents into the cache if it has been changed
    private CacheUpdaterJob cacheUpdaterJob;
    private WikiDataFetcherJob wikiDataFetcherJob;
    private SlotSenderJob slotStateSenderJob;

    private ScheduledFuture slotTimersTask;
    private ScheduledFuture autoSaveTask;

    @Getter
    private int loginTickCount;

    private OptionHandler optionHandler;

    @Getter
    private DataHandler dataHandler;
    private GameUiChangesHandler gameUiChangesHandler;
    private NewOfferEventPipelineHandler newOfferEventPipelineHandler;
    @Getter
    private ApiAuthHandler apiAuthHandler;
    @Getter
    private ApiRequestHandler apiRequestHandler;

    @Getter
    private WikiRequestWrapper lastWikiRequestWrapper;
    @Getter
    private Instant timeOfLastWikiRequest;

    @Inject
    public Gson gson;
    @Inject
    private EventBus eventBus;

    public TradePersister tradePersister;
    @Getter
    private RecipeHandler recipeHandler;
    private FlippingItemHandler flippingItemHandler;

    @Getter
    private SlotStateDrawer slotStateDrawer;
    @Inject
    private OfferGraphChartOverlay offerGraphChartOverlay;

    private volatile boolean storageReady;
    private volatile long lifecycleGeneration;

    public void setUpdateSinceLastItemAccountWideBuild(boolean changed) {
        accountViewHandler.setItemsChanged(changed);
    }

    public void setUpdateSinceLastRecipeFlipGroupAccountWideBuild(boolean changed) {
        accountViewHandler.setRecipesChanged(changed);
    }

    FlippingItemHandler getFlippingItemHandler() {
        return flippingItemHandler;
    }

    @Override
    protected void startUp() {
        final long startupGeneration = ++lifecycleGeneration;
        eventsReceivedBeforeFullLogin.clear();
        accountCurrentlyViewed = ACCOUNT_WIDE;
        currentlyLoggedInAccount = null;
        previouslyLoggedIn = false;
        storageReady = false;

        tradePersister = new TradePersister(gson);
        recipeHandler = new RecipeHandler(gson, httpClient, null);
        flippingItemHandler = new FlippingItemHandler(this);

        optionHandler = new OptionHandler(this);
        dataHandler = new DataHandler(this);
        gameUiChangesHandler = new GameUiChangesHandler(this, eventBus);
        newOfferEventPipelineHandler = new NewOfferEventPipelineHandler(this);
        apiAuthHandler = new ApiAuthHandler(this);
        apiRequestHandler = new ApiRequestHandler(this);
        slotStateDrawer = new SlotStateDrawer(this, this.tooltipManager, client, timeseriesFetcher);
        eventBus.register(slotStateDrawer);
        flippingPanel = new FlippingPanel(this);
        statPanel = new StatsPanel(this);
        geHistoryTabPanel = new GeHistoryTabPanel(this);
        slotsPanel = new SlotsPanel(this, itemManager);
        loginPanel = new LoginPanel(this);

        masterPanel = new MasterPanel(this, flippingPanel, statPanel, slotsPanel, loginPanel);
        masterPanel.addView(geHistoryTabPanel, "ge history");
        navButton = NavigationButton.builder()
                .tooltip("Flipping Utilities")
                .icon(ImageUtil.loadImageResource(getClass(), "/graph_icon_green.png"))
                .priority(3)
                .panel(masterPanel)
                .build();

        clientToolbar.addNavigation(navButton);
        keyManager.registerKeyListener(offerEditorKeyListener());
        java.util.concurrent.CompletableFuture<com.flippingutilities.db.JsonStorage.LoadedData> initialData = dataHandler.readDataAsync();
        clientThread.invokeLater(() ->
        {
            if (startupGeneration != lifecycleGeneration) return true;
            switch (client.getGameState()) {
                case STARTING:
                case UNKNOWN:
                    return false;
            }

            if (!initialData.isDone()) return false;
            try {
                dataHandler.installLoadedData(initialData.join());
            } catch (RuntimeException failure) {
                log.error("Cannot load JSON storage; original files were preserved", failure);
                SwingUtilities.invokeLater(() -> javax.swing.JOptionPane.showMessageDialog(masterPanel,
                    "Trading data could not be loaded. Original files were preserved.\n" + dataHandler.getStorageError(),
                    "Flipping Utilities storage", javax.swing.JOptionPane.ERROR_MESSAGE));
                return true;
            }
            storageReady = true;
            setUpdateSinceLastItemAccountWideBuild(true);
            setUpdateSinceLastRecipeFlipGroupAccountWideBuild(true);
            masterPanel.setupAccSelectorDropdown(dataHandler.getCurrentAccounts());
            generalRepeatingTasks = setupRepeatingTasks(1000);
            autoSaveTask = startAutoSave();
            startJobs();
            apiAuthHandler.subscribeToPremiumChecking((isPremium) -> { if (isPremium) WikiDataFetcherJob.requestInterval = 30; });
            apiAuthHandler.checkExistingJwt().thenRun(() -> apiAuthHandler.setPremiumStatus());

            //this is only relevant if the user downloads/enables the plugin after they login.
            if (client.getGameState() == GameState.LOGGED_IN) {
                log.debug("user is already logged in when they downloaded/enabled the plugin");
                onLoggedInGameState();
            }

            GeSpriteLoader.setClientSpriteOverrides(client);
            return true;
        });
    }

    @Override
    protected void shutDown() {
        lifecycleGeneration++;
        log.debug("shutdown running!");
        if (generalRepeatingTasks != null) {
            generalRepeatingTasks.cancel(true);
            generalRepeatingTasks = null;
        }
        if (slotTimersTask != null) {
            slotTimersTask.cancel(true);
            slotTimersTask = null;
        }
        if (autoSaveTask != null) {
            autoSaveTask.cancel(true);
            autoSaveTask = null;
        }
        masterPanel.dispose();
        if (cacheUpdaterJob != null) cacheUpdaterJob.stop();
        if (wikiDataFetcherJob != null) wikiDataFetcherJob.stop();
        if (slotStateSenderJob != null) slotStateSenderJob.stop();

        storageReady = false;
        try {
            dataHandler.close().get();
        } catch (Exception failure) {
            log.error("Could not finish saving JSON storage on plugin shutdown", failure);
        }

        clientToolbar.removeNavigation(navButton);
    }

    //called when the X button on the client is pressed
    @Subscribe(priority = 101)
    public void onClientShutdown(ClientShutdown clientShutdownEvent) {
        lifecycleGeneration++;
        storageReady = false;
        if (autoSaveTask != null) autoSaveTask.cancel(false);
        if (generalRepeatingTasks != null) {
            generalRepeatingTasks.cancel(true);
        }
        if (slotTimersTask != null) {
            slotTimersTask.cancel(true);
            slotTimersTask = null;
        }
        Future<?> closed = dataHandler.close();
        if (closed != null) {
            clientShutdownEvent.waitFor(closed);
        }
        if (cacheUpdaterJob != null) cacheUpdaterJob.stop();
        if (wikiDataFetcherJob != null) wikiDataFetcherJob.stop();
        if (slotStateSenderJob != null) slotStateSenderJob.stop();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (!storageReady) return;
        if (event.getGameState() == GameState.LOGGED_IN) {
            onLoggedInGameState();
        } else if (event.getGameState() == GameState.LOGIN_SCREEN && previouslyLoggedIn) {
            //this randomly fired at night hours after i had logged off...so i'm adding this guard here.
            if (currentlyLoggedInAccount != null && client.getGameState() != GameState.LOGGED_IN) {
                handleLogout();
            }
        }
    }

    private void onLoggedInGameState() {
        //keep scheduling this task until it returns true (when we have access to a display name)
        clientThread.invokeLater(() ->
        {
            if (!storageReady) return true;
            //we return true in this case as something went wrong and somehow the state isn't logged in, so we don't
            //want to keep scheduling this task.
            if (client.getGameState() != GameState.LOGGED_IN) {
                return true;
            }

            final Player player = client.getLocalPlayer();

            //player is null, so we can't get the display name so, return false, which will schedule
            //the task on the client thread again.
            if (player == null) {
                return false;
            }

            final String name = player.getName();

            if (name == null) {
                return false;
            }

            if (name.equals("")) {
                return false;
            }
            previouslyLoggedIn = true;

            if (currentlyLoggedInAccount == null) {
                handleLogin(name);
            }
            //stops scheduling this task
            return true;
        });
    }

    public void handleLogin(String displayName) {
        if (wikiDataFetcherJob != null) {
            wikiDataFetcherJob.onWorldSwitch(client.getWorldType());
        }
        if (client.getVarbitValue(VarbitID.IRONMAN) != 0) {
                log.debug("account is an ironman, not adding it to the cache");
                return;
            }

        log.debug("{} has just logged in!", displayName);
        if (!dataHandler.getCurrentAccounts().contains(displayName)) {
            log.debug("data handler does not contain data for {}", displayName);
            dataHandler.addAccount(displayName);
            masterPanel.getAccountSelector().addItem(displayName);
        }
        //see documentation for AccountData.fixIncorrectItemNames
        if (client.getWorldType().contains(WorldType.MEMBERS)) {
            dataHandler.viewAccountData(displayName).fixIncorrectItemNames(itemManager);
        }

        loginTickCount = client.getTickCount();
        currentlyLoggedInAccount = displayName;

        //now that we have a display name we can process any events that we received before the display name
        //was set.
        eventsReceivedBeforeFullLogin.forEach(newOfferEventPipelineHandler::onNewOfferEvent);
        eventsReceivedBeforeFullLogin.clear();

        if (dataHandler.getCurrentAccounts().size() > 1) {
            masterPanel.getAccountSelector().setVisible(true);
        }
        accountCurrentlyViewed = displayName;
        //this will cause changeView to be invoked which will cause a rebuildItemsDisplay of
        //flipping and stats panel
        masterPanel.getAccountSelector().setSelectedItem(displayName);

        if (slotTimersTask == null && config.slotTimersEnabled()) {
            log.debug("starting slot timers on login");
            slotTimersTask = startSlotTimers();
        }
        apiAuthHandler.checkRsn(displayName);
        slotStateSenderJob.justLoggedIn = true;
    }

    public void handleLogout() {
        log.debug("{} is logging out", currentlyLoggedInAccount);

        dataHandler.viewAccountData(currentlyLoggedInAccount).setLastSessionTimeUpdate(null);
        dataHandler.markSessionTimeChanged(currentlyLoggedInAccount);
        dataHandler.storeData();

        if (slotTimersTask != null && !slotTimersTask.isCancelled()) {
            log.debug("cancelling slot timers task on logout");
            slotTimersTask.cancel(true);
        }
        slotTimersTask = null;
        currentlyLoggedInAccount = null;
        masterPanel.revertToSafeDisplay();
    }

    /**
     * Currently used for updating time sensitive displays such as the accumulated session time,
     * how long ago an item was flipped, etc.
     *
     * @return a future object that can be used to cancel the tasks
     */
    public ScheduledFuture setupRepeatingTasks(int msStartDelay) {
        return executor.scheduleAtFixedRate(() ->
        {
            try {
                flippingPanel.updateTimerDisplays();
                statPanel.updateTimeDisplay();
                clientThread.invokeLater(() -> {
                    if (storageReady) sessionTimeHandler.updateSessionTime();
                });
                if (storageReady) {
                    statPanel.updateAutoSaveDisplay();
                }
            } catch (ConcurrentModificationException e) {
                log.warn("concurrent modification exception. This is fine, will just restart tasks after delay." +
                        " Cancelling general repeating tasks and starting it again after 5000 ms delay");
                generalRepeatingTasks.cancel(true);
                generalRepeatingTasks = setupRepeatingTasks(5000);
            } catch (Exception e) {
                log.warn("unknown exception in repeating tasks, error = {}, will cancel and restart them after 5 sec delay", e);
                generalRepeatingTasks.cancel(true);
                generalRepeatingTasks = setupRepeatingTasks(5000);
            }

        }, msStartDelay, 1000, TimeUnit.MILLISECONDS);
    }

    /**
     * This method is invoked every time the plugin receives a GrandExchangeOfferChanged event which is
     * when the user set an offer, cancelled an offer, or when an offer was updated (items bought/sold partially
     * or completely).
     *
     * @param offerChangedEvent the offer event that represents when an offer is updated
     *                          (buying, selling, bought, sold, cancelled sell, or cancelled buy)
     */
    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged offerChangedEvent) {
        newOfferEventPipelineHandler.onGrandExchangeOfferChanged(offerChangedEvent);
    }

    public boolean isAccountWideView() {
        return accountViewHandler.isAccountWideView();
    }

    public boolean isAccountInCurrentView(String accountName) {
        return accountViewHandler.isAccountInCurrentView(accountName);
    }

    List<String> getAccountNamesForCurrentView() {
        return accountViewHandler.getAccountNamesForCurrentView();
    }

    Collection<AccountData> getAccountsForCurrentView() {
        return accountViewHandler.getAccountsForCurrentView();
    }

    public List<FlippingItem> getItemsForCurrentView() {
        return accountViewHandler.getItemsForCurrentView();
    }

    public List<FlippingItem> viewItemsForCurrentView() {
        return accountViewHandler.viewItemsForCurrentView();
    }

    public List<RecipeFlipGroup> viewRecipeFlipGroupsForCurrentView() {
        return accountViewHandler.viewRecipeFlipGroupsForCurrentView();
    }

    public Duration viewAccumulatedTimeForCurrentView() {
        return accountViewHandler.viewAccumulatedTimeForCurrentView();
    }

    public Instant viewStartOfSessionForCurrentView() {
        return accountViewHandler.viewStartOfSessionForCurrentView();
    }

    @Provides
    FlippingConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(FlippingConfig.class);
    }

    public void truncateTradeList() {
        tradeHistoryHandler.truncateTradeList();
    }

    /**
     * This method is invoked every time a user selects a username from the dropdown at the top of the
     * panel. If the username selected does not exist in the cache, it uses loadTradeHistory to load it from
     * disk and set the cache. Otherwise, it just reads what in the cache for that username. It updates the displays
     * with the trades it either found in the cache or from disk.
     *
     * @param selectedName the username the user selected from the dropdown menu.
     */
    public void changeView(String selectedName) {
        log.debug("changing view to {}", selectedName);
        accountCurrentlyViewed = selectedName;
        List<FlippingItem> itemsForCurrentView = viewItemsForCurrentView();
        statPanel.resetPaginators();
        flippingPanel.getPaginator().setPageNumber(1);
        statPanel.rebuildItemsDisplay(itemsForCurrentView);
        statPanel.rebuildRecipesDisplay(viewRecipeFlipGroupsForCurrentView());
        flippingPanel.rebuild(itemsForCurrentView);
    }

    private void startJobs() {
        cacheUpdaterJob = new CacheUpdaterJob();
        cacheUpdaterJob.subscribe(this::onDirectoryUpdate);
        cacheUpdaterJob.start();

        wikiDataFetcherJob = new WikiDataFetcherJob(this, httpClient);
        wikiDataFetcherJob.subscribe(this::onWikiFetch);
        wikiDataFetcherJob.start();

        slotStateSenderJob = new SlotSenderJob(this, httpClient);
        slotStateSenderJob.subscribe((success) -> loginPanel.onSlotRequest(success));
        slotStateSenderJob.start();
    }

    private void onWikiFetch(WikiRequestWrapper wikiRequestWrapper, Instant timeOfRequestCompletion) {
        lastWikiRequestWrapper = wikiRequestWrapper;
        timeOfLastWikiRequest = timeOfRequestCompletion;
        flippingPanel.onWikiRequest(wikiRequestWrapper, timeOfRequestCompletion);
        slotStateDrawer.onWikiRequest(wikiRequestWrapper.getWikiRequest());
        slotsPanel.onWikiRequest(wikiRequestWrapper.getWikiRequest());
    }

    /**
     * This is a callback executed by the cacheUpdater when it notices the directory has changed. If the
     * file changed belonged to a different acc than the currently logged in one, it updates the cache of that
     * account to ensure this client has the most up to date data on each account. If the user is currently looking
     * at the account that had its cache updated, a rebuildItemsDisplay takes place to display the most recent trade list.
     *
     * @param fileName name of the file which was modified.
     */
    public void onDirectoryUpdate(String fileName) {
        if (!fileName.equals("journal.jsonl") && !fileName.equals("checkpoint.json")) return;
        dataHandler.refreshExternalData(() -> {
            masterPanel.setupAccSelectorDropdown(dataHandler.getCurrentAccounts());
            setUpdateSinceLastItemAccountWideBuild(true);
            setUpdateSinceLastRecipeFlipGroupAccountWideBuild(true);
            List<FlippingItem> items = viewItemsForCurrentView();
            flippingPanel.rebuild(items);
            statPanel.rebuildItemsDisplay(items);
            statPanel.rebuildRecipesDisplay(viewRecipeFlipGroupsForCurrentView());
        });
    }

    public List<FlippingItem> sortItems(List<FlippingItem> items, SORT sort, Instant startOfInterval) {
        return flippingItemHandler.sortItems(items, sort, startOfInterval);
    }

    public List<RecipeFlipGroup> sortRecipeFlipGroups(List<RecipeFlipGroup> recipeFlipGroups, SORT sort, Instant startOfInterval) {
        return recipeHandler.sortRecipeFlipGroups(recipeFlipGroups, sort, startOfInterval);
    }

    /**
     * What hands the slot widgets on the screen to the SlotStateDrawer. This is called by
     * the GameUiChangesHandler in response to the appropriate UI changes that cause the
     * slot widgets to appear/get rebuilt.
     */
    public void setWidgetsOnSlotStateDrawer() {
        Widget slotWidgets = client.getWidget(InterfaceID.GeOffers.INDEX);
        if (slotWidgets != null) {
            slotStateDrawer.setSlotWidgets(slotWidgets.getStaticChildren());
        }
    }

    public void setWidgetsOnSlotTimers() {
        if (currentlyLoggedInAccount == null) {
            return;
        }
        AccountData accountData = dataHandler.viewAccountData(currentlyLoggedInAccount);
        if (accountData == null || accountData.getSlotTimers() == null) {
            return;
        }

        Widget geOfferSlots = client.getWidget(InterfaceID.GeOffers.INDEX);
        if (geOfferSlots == null) {
            return;
        }

        Widget[] staticChildren = geOfferSlots.getStaticChildren();
        if (staticChildren == null || staticChildren.length < 9) {
            return;
        }

        for (int slotIndex = 0; slotIndex < 8; slotIndex++) {
            SlotActivityTimer timer = accountData.getSlotTimers().get(slotIndex);
            if (timer == null) {
                continue;
            }

            // We add one to the index, as the first widget is the text above the offer slots
            Widget offerSlot = staticChildren[slotIndex + 1];
            if (offerSlot == null) {
                continue;
            }

            timer.setWidget(offerSlot);
            clientThread.invokeLater(timer::updateTimerDisplay);
        }
    }

    public void setFavoriteOnAllAccounts(FlippingItem item, boolean favoriteStatus) {
        favoriteHandler.setFavoriteOnAllAccounts(item, favoriteStatus);
    }

    public void setFavoriteCodeOnAllAccounts(FlippingItem item, String favoriteCode) {
        favoriteHandler.setFavoriteCodeOnAllAccounts(item, favoriteCode);
    }

    /** Marks an item changed after a single-account favorite toggle in the panel. */
    public void persistFavoriteOnAccount(String accountName, FlippingItem item) {
        favoriteHandler.persistFavoriteOnAccount(accountName, item);
    }

    /** Single-account quick-search code change; see {@link #persistFavoriteOnAccount}. */
    public void persistFavoriteCodeOnAccount(String accountName, FlippingItem item) {
        favoriteHandler.persistFavoriteCodeOnAccount(accountName, item);
    }

    /** Marks the affected account after deleting one recipe flip. */
    public void deleteRecipeFlipFromStorage(String recipeKey, RecipeFlip flip) {
        recipeFlipHandler.deleteRecipeFlipFromStorage(recipeKey, flip);
    }

    /** Marks the affected account after resetting a recipe interval. */
    public void deleteRecipeFlipsSinceFromStorage(String recipeKey, Instant since) {
        recipeFlipHandler.deleteRecipeFlipsSinceFromStorage(recipeKey, since);
    }

    public void addSelectedGeTabOffers(List<OfferEvent> selectedOffers) {
        tradeHistoryHandler.addSelectedGeTabOffers(selectedOffers);
    }

    /** Snapshot the offer before handing it to the ordered storage queue. */
    public void recordTrade(String account, OfferEvent offer) {
        tradeHistoryHandler.recordTrade(account, offer);
    }

    public void setItemVisible(FlippingItem item, boolean visible) {
        tradeHistoryHandler.setItemVisible(item, visible);
    }

    public void showGeHistoryTabPanel() {
        clientThread.invokeLater(() -> {
            Widget[] geHistoryTabWidgets = client.getWidget(InterfaceID.GeHistory.LIST).getDynamicChildren();
            List<OfferEvent> offerEvents = GeHistoryTabExtractor.convertWidgetsToOfferEvents(geHistoryTabWidgets);
            List<List<OfferEvent>> matchingOffers = new ArrayList<>();
            offerEvents.forEach(o -> {
                o.setItemName(itemManager.getItemComposition(o.getItemId()).getName());
                o.setMadeBy(getCurrentlyLoggedInAccount());
                matchingOffers.add(findOfferMatches(o, 5));
            });
            geHistoryTabPanel.rebuild(offerEvents, matchingOffers, geHistoryTabWidgets, false);
            masterPanel.showView("ge history");
        });
    }

    public List<OfferEvent> findOfferMatches(OfferEvent offerEvent, int limit) {
        return tradeHistoryHandler.findOfferMatches(offerEvent, limit);
    }

    public Font getFont() {
        return FontManager.getRunescapeSmallFont();
    }

    /**
     * Used by the stats panel to invalidate all offers for a certain interval when a user hits the reset button.
     */
    public void deleteOffers(Instant startOfInterval) {
        tradeHistoryHandler.deleteOffers(startOfInterval);
    }

    public void deleteOffers(List<OfferEvent> offers, FlippingItem item) {
        tradeHistoryHandler.deleteOffers(offers, item);
    }

    /**
     * Used by the flipping panel to hide all items (set the validfFippingItem property to false) when a user hits the
     * reset button
     */
    public void setAllFlippingItemsAsHidden() {
        tradeHistoryHandler.setAllFlippingItemsAsHidden();
    }

    public void exportToCsv(File parentDirectory, Instant startOfInterval, String startOfIntervalName) throws IOException {
        tradeHistoryHandler.exportToCsv(parentDirectory, startOfInterval, startOfIntervalName);
    }

    public long calculateOptionValue(Option option) throws InvalidOptionException {
        return optionHandler.calculateOptionValue(option, gameUiChangesHandler.highlightedItem, gameUiChangesHandler.highlightedItemId);
    }

    public void markAccountTradesAsHavingChanged(String displayName) {
        dataHandler.markDataAsHavingChanged(displayName);
    }

    public Set<String> getCurrentDisplayNames() {
        return dataHandler.getCurrentAccounts();
    }

    private KeyListener offerEditorKeyListener() {
        return new KeyListener() {
            @Override
            public void keyTyped(KeyEvent e) {

            }

            @Override
            public void keyPressed(KeyEvent e) {
                if (gameUiChangesHandler.quantityOrPriceChatboxOpen && gameUiChangesHandler.highlightedItem.isPresent()) {
                    String keyPressed = KeyEvent.getKeyText(e.getKeyCode()).toLowerCase();
                    if (flippingPanel.getOfferEditorContainerPanel() == null) {
                        return;
                    }
                    boolean currentlyViewingQuantityEditor = flippingPanel.getOfferEditorContainerPanel().currentlyViewingQuantityEditor();

                    Optional<Option> optionExercised = dataHandler.viewAccountWideData().getOptions().stream().filter(option -> option.isQuantityOption() == currentlyViewingQuantityEditor && option.getKey().equals(keyPressed)).findFirst();

                    optionExercised.ifPresent(option -> clientThread.invoke(() -> {
                        try {
                            long optionValue = calculateOptionValue(option);
                            client.getWidget(InterfaceID.Chatbox.MES_TEXT2).setText(optionValue + "*");
                            client.setVarcStrValue(VarClientStr.INPUT_TEXT, String.valueOf(optionValue));
                            flippingPanel.getOfferEditorContainerPanel().highlightPressedOption(keyPressed);
                            e.consume();
                        } catch (InvalidOptionException ex) {
                            //ignore
                        } catch (Exception ex) {
                            log.warn("exception during key press for offer editor", ex);
                        }
                    }));
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {

            }
        };
    }

    public void deleteAccount(String displayName) {
        dataHandler.deleteAccount(displayName);
        if (accountCurrentlyViewed.equals(displayName)) {
            masterPanel.getAccountSelector().setSelectedItem(dataHandler.getCurrentAccounts().toArray()[0]);
        }
        if (dataHandler.getCurrentAccounts().size() < 2) {
            masterPanel.getAccountSelector().setVisible(false);
        }
        masterPanel.getAccountSelector().removeItem(displayName);
    }

    private ScheduledFuture startSlotTimers() {
        return executor.scheduleAtFixedRate(() ->
                dataHandler.viewAccountData(currentlyLoggedInAccount).getSlotTimers().forEach(slotWidgetTimer ->
                        clientThread.invokeLater(() -> {
                            try {
                                slotsPanel.updateTimerDisplays(slotWidgetTimer.getSlotIndex(), slotWidgetTimer.createFormattedTimeString());
                                slotWidgetTimer.updateTimerDisplay();
                            } catch (Exception e) {
                                log.error("exception when trying to update timer", e);
                            }
                        })), 1000, 1000, TimeUnit.MILLISECONDS);
    }

    private ScheduledFuture startAutoSave() {
        // Saving is always enabled. Capture after game events; disk writes run on the ordered I/O queue.
        return executor.scheduleAtFixedRate(() -> clientThread.invokeLater(() -> {
            if (!storageReady) return;
            dataHandler.storeData();
            SwingUtilities.invokeLater(() -> statPanel.updateAutoSaveDisplay());
        }), 1, 1, TimeUnit.SECONDS);
    }

    //see RecipeHandler.getItemsInRecipe
    public Map<Integer, Optional<FlippingItem>> getItemsInRecipe(Recipe recipe) {
        return recipeHandler.getItemsInRecipe(recipe, getItemsForCurrentView());
    }
    //see RecipeHandler.getApplicableRecipes
    public List<Recipe> getApplicableRecipes(int parentId, boolean isBuy) {
        return recipeHandler.getApplicableRecipes(parentId, isBuy);
    }
    //see RecipeHandler.getTargetValuesForMaxRecipeCount
    public Map<Integer, Integer> getTargetValuesForMaxRecipeCount(Recipe recipe, Map<Integer, List<PartialOffer>> itemIdToPartialOffers, boolean useRemainingOffer) {
        return recipeHandler.getTargetValuesForMaxRecipeCount(recipe, itemIdToPartialOffers, useRemainingOffer);
    }
    //see RecipeHandler.getItemIdToMaxRecipesThatCanBeMade
    public Map<Integer, Integer> getItemIdToMaxRecipesThatCanBeMade(Recipe recipe, Map<Integer, List<PartialOffer>> itemIdToPartialOffers, boolean useRemainingOffer) {
        return recipeHandler.getItemIdToMaxRecipesThatCanBeMade(recipe, itemIdToPartialOffers, useRemainingOffer);
    }
    public Map<String, PartialOffer> getOfferIdToPartialOffer(int itemId) {
        return recipeHandler.getOfferIdToPartialOffer(viewRecipeFlipGroupsForCurrentView(), itemId);
    }
    public void addRecipeFlip(RecipeFlip recipeFlip, Recipe recipe) {
        recipeFlipHandler.addRecipeFlip(recipeFlip, recipe);
    }

    /**
     * Adds the dummy item that was favorited to the trades list. Dummy items are created for display purposes
     * when items are searched for/highlighted but they don't actually exist in history. However, if a user then
     * favorites it, we need to add it to the history.
     */
    public void addFavoritedItem(FlippingItem flippingItem) {
        favoriteHandler.addFavoritedItem(flippingItem);
    }

    public void toggleEnhancedSlots(boolean shouldEnhance) {
        dataHandler.viewAccountWideData().setEnhancedSlots(shouldEnhance);
        dataHandler.markDataAsHavingChanged(FlippingPlugin.ACCOUNT_WIDE);
        if (shouldEnhance) {
            slotStateDrawer.refreshSlotVisuals();
        }
        else {
            getClientThread().invokeLater(() -> slotStateDrawer.resetAllSlots());
        }
    }

    public boolean shouldEnhanceSlots() {
        return dataHandler.viewAccountWideData().isEnhancedSlots();
    }

    @Subscribe
    public void onGrandExchangeSearched(GrandExchangeSearched event) {
        favoriteHandler.onGrandExchangeSearched(event);
    }
    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!event.getGroup().equals(CONFIG_GROUP)) {
            return;
        }

        handleSlotTimersConfigChange(event);
        statPanel.rebuildItemsDisplay(viewItemsForCurrentView());
        flippingPanel.rebuild(viewItemsForCurrentView());
    }

    private void handleSlotTimersConfigChange(ConfigChanged event) {
        if (!event.getKey().equals(FlippingConfig.SLOT_TIMERS_ENABLED)) {
            return;
        }

        if (config.slotTimersEnabled()) {
            slotTimersTask = startSlotTimers();
            return;
        }

        if (slotTimersTask != null) {
            slotTimersTask.cancel(true);
        }
        dataHandler.viewAccountData(currentlyLoggedInAccount).getSlotTimers().forEach(SlotActivityTimer::resetToDefault);
    }

}
