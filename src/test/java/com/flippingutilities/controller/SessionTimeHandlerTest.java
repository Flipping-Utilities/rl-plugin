package com.flippingutilities.controller;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.OfferEvent;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.*;

public class SessionTimeHandlerTest {
    @Test
    public void inactiveTicksOnlyPersistTheTransitionOutOfAnActiveSession() {
        TestPlugin plugin = new TestPlugin();
        SessionTimeHandler handler = new SessionTimeHandler(plugin);
        handler.updateSessionTime();
        assertEquals(0, plugin.data.sessionChanges);
        plugin.data.account.setLastSessionTimeUpdate(Instant.EPOCH);
        handler.updateSessionTime();
        assertNull(plugin.data.account.getLastSessionTimeUpdate());
        assertEquals(1, plugin.data.sessionChanges);
        handler.updateSessionTime();
        assertEquals(1, plugin.data.sessionChanges);
    }

    @Test
    public void activeTickChangesSessionMetadataWithoutMarkingHistoryDirty() {
        TestPlugin plugin = new TestPlugin();
        OfferEvent active = new OfferEvent();
        active.setState(GrandExchangeOfferState.BUYING);
        plugin.data.account.getLastOffers().put(0, active);
        plugin.data.account.setAccumulatedSessionTimeMillis(500);
        plugin.data.account.setLastSessionTimeUpdate(Instant.EPOCH);
        new SessionTimeHandler(plugin).updateSessionTime();
        assertTrue(plugin.data.account.getAccumulatedSessionTimeMillis() > 500);
        assertTrue(plugin.data.account.getLastSessionTimeUpdate().isAfter(Instant.EPOCH));
        assertEquals(1, plugin.data.sessionChanges);
        assertSame(active, plugin.data.account.getLastOffers().get(0));
    }

    private static final class TestPlugin extends FlippingPlugin {
        private final TrackingDataHandler data = new TrackingDataHandler(this);
        @Override public String getCurrentlyLoggedInAccount() { return "Player"; }
        @Override public DataHandler getDataHandler() { return data; }
        @Override public boolean isAccountInCurrentView(String account) { return false; }
    }

    private static final class TrackingDataHandler extends DataHandler {
        private final AccountData account = new AccountData();
        private int sessionChanges;

        TrackingDataHandler(FlippingPlugin plugin) { super(plugin); }
        @Override public AccountData viewAccountData(String displayName) { return account; }
        @Override public AccountData getAccountData(String displayName) {
            throw new AssertionError("Session tick must not mark full history dirty");
        }
        @Override public void markDataAsHavingChanged(String displayName) {
            throw new AssertionError("Session tick must not mark full history dirty");
        }
        @Override public void markSessionTimeChanged(String displayName) { sessionChanges++; }
    }
}
