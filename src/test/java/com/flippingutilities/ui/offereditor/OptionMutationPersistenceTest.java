package com.flippingutilities.ui.offereditor;

import com.flippingutilities.controller.DataHandler;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.AccountWideData;
import com.flippingutilities.model.Option;
import com.google.gson.Gson;
import net.runelite.client.callback.ClientThread;
import org.junit.Test;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class OptionMutationPersistenceTest {
    @Test
    public void additionsTemplatesAndDeletionMarkTheCompletedList() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StubPlugin plugin = new StubPlugin();
            PriceEditorPanel prices = new PriceEditorPanel(plugin) {
                @Override public void rebuild(List<Option> options) { }
            };
            QuantityEditorPanel quantities = new QuantityEditorPanel(plugin) {
                @Override public void rebuild(List<Option> options) { }
            };
            prices.addOptionPanel();
            assertEquals(1, plugin.data.lastSnapshot().getOptions().size());
            quantities.addOptionPanel();
            assertEquals(2, plugin.data.lastSnapshot().getOptions().size());
            prices.onTemplateClicked();
            assertEquals(8, plugin.data.lastSnapshot().getOptions().size());
            quantities.onTemplateClicked();
            assertEquals(11, plugin.data.lastSnapshot().getOptions().size());
            prices.deleteOption(plugin.data.wide.getOptions().get(0));
            assertEquals(10, plugin.data.lastSnapshot().getOptions().size());
        });
    }

    @Test
    public void editingAnOptionMarksAfterItsNewValueIsApplied() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StubPlugin plugin = new StubPlugin();
            Option option = Option.defaultPriceOption();
            plugin.data.wide.getOptions().add(option);
            JPanel fields = new OptionPanel(option, plugin).createBodyPanel();
            JTextField key = (JTextField) fields.getComponent(0);
            JComboBox<?> property = (JComboBox<?>) fields.getComponent(1);
            JTextField modifier = (JTextField) fields.getComponent(2);
            key.setText("q");
            key.postActionEvent();
            assertEquals("q", plugin.data.lastSnapshot().getOptions().get(0).getKey());
            property.setSelectedItem(Option.WIKI_SELL);
            assertEquals(Option.WIKI_SELL, plugin.data.lastSnapshot().getOptions().get(0).getProperty());
            modifier.setText("+15");
            modifier.postActionEvent();
            assertEquals("+15", plugin.data.lastSnapshot().getOptions().get(0).getModifier());
        });
    }

    private static final class StubPlugin extends FlippingPlugin {
        private final TrackingDataHandler data = new TrackingDataHandler(this);
        private final ClientThread clientThread = new ClientThread() {
            @Override public void invokeLater(Runnable runnable) { }
        };
        @Override public DataHandler getDataHandler() { return data; }
        @Override public ClientThread getClientThread() { return clientThread; }
        @Override public void markAccountTradesAsHavingChanged(String account) { data.markDataAsHavingChanged(account); }
    }

    private static final class TrackingDataHandler extends DataHandler {
        private final AccountWideData wide = new AccountWideData();
        private final List<AccountWideData> snapshots = new ArrayList<>();
        private final Gson gson = new Gson();
        TrackingDataHandler(FlippingPlugin plugin) { super(plugin); }
        @Override public AccountWideData viewAccountWideData() { return wide; }
        @Override public AccountWideData getAccountWideData() {
            markDataAsHavingChanged(FlippingPlugin.ACCOUNT_WIDE);
            return wide;
        }
        @Override public void markDataAsHavingChanged(String account) {
            assertEquals(FlippingPlugin.ACCOUNT_WIDE, account);
            snapshots.add(gson.fromJson(gson.toJson(wide), AccountWideData.class));
        }
        AccountWideData lastSnapshot() {
            assertFalse("Mutation should schedule persistence", snapshots.isEmpty());
            return snapshots.get(snapshots.size() - 1);
        }
    }
}
