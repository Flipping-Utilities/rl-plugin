package com.flippingutilities.ui.uiutilities;

import org.junit.Test;

import javax.accessibility.AccessibleRole;
import javax.swing.*;
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
            type(action, KeyEvent.VK_ENTER);
            assertEquals(1, clicks.get());
            action.setEnabled(false);
            type(action, KeyEvent.VK_ENTER);
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
            type(toggle, KeyEvent.VK_SPACE);
            assertTrue(toggle.isSelected());
            type(toggle, KeyEvent.VK_ENTER);
            assertFalse(toggle.isSelected());
        });
    }

    @Test
    public void heldEnterActivatesOnceOnRelease() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger clicks = new AtomicInteger();
            JButton action = IconButtons.action("Next page", Icons.ARROW_RIGHT, Icons.ARROW_RIGHT_HOVER,
                event -> clicks.incrementAndGet());
            // Key auto-repeat delivers several presses before a single release.
            for (int i = 0; i < 5; i++) {
                activate(action, KeyEvent.VK_ENTER, false);
            }
            assertEquals(0, clicks.get());
            activate(action, KeyEvent.VK_ENTER, true);
            assertEquals(1, clicks.get());

            JToggleButton toggle = IconButtons.toggle("Show favorites only", Icons.SMALL_STAR_OFF_ICON,
                Icons.SMALL_STAR_HOVER_ICON, Icons.SMALL_STAR_ON_ICON);
            for (int i = 0; i < 5; i++) {
                activate(toggle, KeyEvent.VK_ENTER, false);
            }
            activate(toggle, KeyEvent.VK_ENTER, true);
            assertTrue(toggle.isSelected());
        });
    }

    private static void type(AbstractButton button, int key) {
        activate(button, key, false);
        activate(button, key, true);
    }

    /** Dispatches a focused key binding the way Swing does, including the action's enabled check. */
    private static void activate(AbstractButton button, int key, boolean release) {
        KeyStroke stroke = KeyStroke.getKeyStroke(key, 0, release);
        Object keyName = button.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
        assertNotNull(keyName);
        Action action = button.getActionMap().get(keyName);
        assertNotNull(action);
        SwingUtilities.notifyAction(action, stroke, new KeyEvent(button, release ? KeyEvent.KEY_RELEASED : KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(), 0, key, KeyEvent.CHAR_UNDEFINED), button, 0);
    }
}
