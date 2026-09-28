package com.flippingutilities.ui;

import com.flippingutilities.controller.FlippingPlugin;
import org.junit.Test;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import java.awt.event.ItemEvent;
import java.util.HashSet;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AccountSelectorRefreshTest {
    @Test
    public void historyRefreshDoesNotChangeViewOrResetPagination() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComboBox<String> selector = selector();
            AtomicInteger changes = trackChanges(selector);
            MasterPanel.syncAccountSelector(selector, new HashSet<>(Arrays.asList("Alice", "Bob")));
            assertEquals("Alice", selector.getSelectedItem());
            assertEquals(0, changes.get());
        });
    }

    @Test
    public void otherAccountsCanChangeWithoutResettingTheSelectedView() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComboBox<String> selector = selector();
            AtomicInteger changes = trackChanges(selector);
            MasterPanel.syncAccountSelector(selector, new HashSet<>(Arrays.asList("Alice", "Carol")));
            assertEquals("Alice", selector.getSelectedItem());
            assertEquals(0, changes.get());
            assertEquals(3, selector.getItemCount());
        });
    }

    @Test
    public void deletedSelectionFallsBackToAccountwide() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComboBox<String> selector = selector();
            MasterPanel.syncAccountSelector(selector, new HashSet<>(Arrays.asList("Bob")));
            assertEquals(FlippingPlugin.ACCOUNT_WIDE, selector.getSelectedItem());
        });
    }

    @Test
    public void multipleDeletionsNeverSelectAnotherMissingAccount() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComboBox<String> selector = selector();
            selector.setSelectedItem("Bob");
            HashSet<String> remaining = new HashSet<>(Arrays.asList("Carol"));
            selector.addItemListener(event -> {
                if (event.getStateChange() == ItemEvent.SELECTED) {
                    assertTrue("View callbacks must only receive accounts still present in storage",
                        FlippingPlugin.ACCOUNT_WIDE.equals(event.getItem()) || remaining.contains(event.getItem()));
                }
            });
            MasterPanel.syncAccountSelector(selector, remaining);
            assertEquals(FlippingPlugin.ACCOUNT_WIDE, selector.getSelectedItem());
        });
    }

    private static JComboBox<String> selector() {
        JComboBox<String> selector = new JComboBox<>(new String[]{FlippingPlugin.ACCOUNT_WIDE, "Alice", "Bob"});
        selector.setSelectedItem("Alice");
        return selector;
    }

    private static AtomicInteger trackChanges(JComboBox<String> selector) {
        AtomicInteger changes = new AtomicInteger();
        selector.addItemListener(event -> {
            if (event.getStateChange() == ItemEvent.SELECTED) changes.incrementAndGet();
        });
        return changes;
    }
}
