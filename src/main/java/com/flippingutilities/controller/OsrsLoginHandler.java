package com.flippingutilities.controller;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;

@Slf4j
public class OsrsLoginHandler {
    private boolean previouslyLoggedIn;
    @Getter
    private String currentDisplayName;
    @Getter
    private String previousDisplayName;
    private final FlippingPlugin plugin;
    private final WorldType[] unSupportedWorlds = {WorldType.BETA_WORLD,
        WorldType.DEADMAN,
        WorldType.FRESH_START_WORLD,
        WorldType.NOSAVE_MODE,
        WorldType.PVP_ARENA,
        WorldType.SEASONAL,
        WorldType.QUEST_SPEEDRUNNING,
        WorldType.TOURNAMENT_WORLD};

    @Getter
    private boolean invalidState = true;

    OsrsLoginHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
        previouslyLoggedIn = false;
        currentDisplayName = null;
    }

    boolean isLoggedIn() {
        return currentDisplayName != null;
    }


    void init() {
        if (plugin.getClient().getGameState() == GameState.LOGGED_IN) {
            onLoggedInGameState();
            plugin.offerEventFilter.setToLoggedIn();
            //plugin.accountStatus.setOffers(plugin.getClient().getGrandExchangeOffers());
            plugin.getAssistantPanel().statePanel.showState(plugin.accountStatus);
        }
    }

    void handleGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            onLoggedInGameState();
        } else if (event.getGameState() == GameState.LOGIN_SCREEN && previouslyLoggedIn) {
            //this randomly fired at night hours after i had logged off...so i'm adding this guard here.
            if (currentDisplayName != null && plugin.getClient().getGameState() != GameState.LOGGED_IN) {
                handleLogout();
            }
        }
    }

    private void onLoggedInGameState() {
        //keep scheduling this task until it returns true (when we have access to a display name)
        plugin.getClientThread().invokeLater(() ->
        {
            //we return true in this case as something went wrong and somehow the state isn't logged in, so we don't
            //want to keep scheduling this task.
            if (plugin.getClient().getGameState() != GameState.LOGGED_IN) {
                return true;
            }

            final Player player = plugin.getClient().getLocalPlayer();

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

            handleLogin(name);
            return true;
        });
    }

    public void handleLogin(String displayName) {
        for (WorldType worldType : unSupportedWorlds) {
            if (plugin.getClient().getWorldType().contains(worldType)) {
                log.info("World is a {}", worldType);
                plugin.getAssistantPanel().suggestionPanel.setMessage(worldType + " worlds<br>are not supported");
                invalidState = true;
                return;
            }
        }

        if (plugin.getClient().getAccountType().isIronman()) {
            log.info("account is an ironman");
            plugin.getAssistantPanel().suggestionPanel.setMessage("Ironman accounts<br>are not supported");
            invalidState = true;
            return;
        }

        plugin.accountStatus.setMember(plugin.getClient().getWorldType().contains(WorldType.MEMBERS));
        plugin.accountStatus.setDisplayName(displayName);
        currentDisplayName = displayName;
        previousDisplayName = displayName;
        invalidState = false;
    }
    public void handleLogout() {
        log.info("{} is logging out", currentDisplayName);
        currentDisplayName = null;
        plugin.offerEventFilter.onLogout();
        plugin.getAssistantPanel().suggestionPanel.suggestLogin();
    }

}
