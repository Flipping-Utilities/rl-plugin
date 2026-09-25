package com.flippingutilities.ui.uiutilities;

import org.junit.Test;

import javax.accessibility.AccessibleRole;
import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class IconButtonsTest {
    @Test
    public void actionsAreNamedKeyboardButtonsAndDoNotActivateWhenDisabled() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger clicks = new AtomicInteger();
            JButton action = IconButtons.action("Export to CSV", Icons.DONWLOAD_ICON_OFF,
                Icons.DOWNLOAD_ICON, event -> clicks.incrementAndGet());
            assertEquals(AccessibleRole.PUSH_BUTTON, action.getAccessibleContext().getAccessibleRole());
            assertEquals("Export to CSV", action.getAccessibleContext().getAccessibleName());
            assertEquals("Export to CSV", action.getToolTipText());
            assertTrue(action.isFocusable());
            activate(action, KeyEvent.VK_ENTER, false);
            assertEquals(1, clicks.get());
            action.setEnabled(false);
            activate(action, KeyEvent.VK_ENTER, false);
            action.doClick();
            assertEquals(1, clicks.get());
        });
    }

    @Test
    public void favoriteToggleExposesSelectedStateAndSupportsSpaceAndEnter() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JToggleButton toggle = IconButtons.toggle("Show favorites only", Icons.SMALL_STAR_OFF_ICON,
                Icons.SMALL_STAR_HOVER_ICON, Icons.SMALL_STAR_ON_ICON);
            assertEquals(AccessibleRole.TOGGLE_BUTTON, toggle.getAccessibleContext().getAccessibleRole());
            assertFalse(toggle.isSelected());
            activate(toggle, KeyEvent.VK_SPACE, false);
            activate(toggle, KeyEvent.VK_SPACE, true);
            assertTrue(toggle.isSelected());
            activate(toggle, KeyEvent.VK_ENTER, false);
            assertFalse(toggle.isSelected());
        });
    }

    private static void activate(AbstractButton button, int key, boolean release) {
        Object keyName = button.getInputMap().get(KeyStroke.getKeyStroke(key, 0, release));
        assertNotNull(keyName);
        button.getActionMap().get(keyName).actionPerformed(new ActionEvent(button, ActionEvent.ACTION_PERFORMED, "key"));
    }
}
