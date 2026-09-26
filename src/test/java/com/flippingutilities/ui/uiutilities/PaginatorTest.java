package com.flippingutilities.ui.uiutilities;

import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import net.runelite.client.ui.laf.RuneLiteLAF;
import javax.accessibility.AccessibleRole;

import javax.swing.*;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class PaginatorTest {
    private static LookAndFeel previousLookAndFeel;

    @BeforeClass
    public static void useRuneLiteLookAndFeel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            previousLookAndFeel = UIManager.getLookAndFeel();
            assertTrue(RuneLiteLAF.setup());
        });
    }

    @AfterClass
    public static void restoreLookAndFeel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(previousLookAndFeel);
            } catch (UnsupportedLookAndFeelException e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    public void shrinkingResultsSelectsTheLastAvailablePageWithoutAnotherRebuild() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger changes = new AtomicInteger();
            Paginator paginator = new Paginator(changes::incrementAndGet);
            paginator.setPageSize(2);
            paginator.updateTotalPages(6);
            paginator.setPageNumber(3);
            paginator.updateTotalPages(3);

            assertEquals(2, paginator.getPageNumber());
            assertEquals("of 2", paginator.getPageOfLabel().getText());
            assertEquals(Collections.singletonList("third"),
                paginator.getCurrentPageItems(Arrays.asList("first", "second", "third")));
            assertEquals("2", input(paginator).getText());
            assertFalse(button(paginator, "Next page").isEnabled());
            assertTrue(button(paginator, "Previous page").isEnabled());
            assertEquals(0, changes.get());

            assertTrue(paginator.getCurrentPageItems(Collections.emptyList()).isEmpty());
            assertEquals(1, paginator.getPageNumber());
            assertEquals("of 1", paginator.getPageOfLabel().getText());
            assertFalse(button(paginator, "Previous page").isEnabled());
            assertFalse(button(paginator, "Next page").isEnabled());
            assertFalse(input(paginator).isEnabled());
        });
    }

    @Test
    public void buttonsSupportKeyboardActivationAndRespectBoundaries() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger changes = new AtomicInteger();
            Paginator paginator = new Paginator(changes::incrementAndGet);
            paginator.updateTotalPages(40);
            JButton next = button(paginator, "Next page");
            JButton previous = button(paginator, "Previous page");

            assertTrue(next.isFocusable());
            assertEquals(AccessibleRole.PUSH_BUTTON, next.getAccessibleContext().getAccessibleRole());
            activate(next, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
            assertEquals(2, paginator.getPageNumber());
            assertFalse(next.isEnabled());
            activate(next, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
            assertEquals(1, changes.get());

            activate(previous, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, false));
            activate(previous, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, true));
            assertEquals(1, paginator.getPageNumber());
            assertEquals(2, changes.get());
            assertFalse(previous.isEnabled());
        });
    }

    @Test
    public void inputRejectsInvalidPagesAndRollsBackAFailedPageChange() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Paginator paginator = new Paginator(() -> { throw new IllegalStateException("Cannot rebuild"); });
            paginator.updateTotalPages(100);
            JTextField input = input(paginator);
            for (String value : new String[]{"", "text", "0", "-1", "6", "9999999999999999999", "2"}) {
                input.setText(value);
                input.postActionEvent();
                assertEquals(1, paginator.getPageNumber());
                assertEquals("1", input.getText());
                assertFalse(button(paginator, "Previous page").isEnabled());
                assertTrue(button(paginator, "Next page").isEnabled());
            }
        });
    }

    @Test
    public void directInputAndPageSizeChangesKeepControlsInSync() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger changes = new AtomicInteger();
            Paginator paginator = new Paginator(changes::incrementAndGet);
            paginator.updateTotalPages(60);
            input(paginator).setText(" 3 ");
            input(paginator).postActionEvent();
            assertEquals(3, paginator.getPageNumber());
            assertEquals(1, changes.get());
            paginator.setPageSize(100);
            assertEquals(1, paginator.getPageNumber());
            assertEquals("1", input(paginator).getText());
            assertFalse(input(paginator).isEnabled());
            paginator.setPageSize(1);
            paginator.updateTotalPages(Integer.MAX_VALUE);
            assertEquals("of 2147483647", paginator.getPageOfLabel().getText());
            paginator.setPageNumber(9999);
            paginator.setSize(225, 30);
            paginator.doLayout();
            assertEquals("9999", input(paginator).getText());
            assertTrue(input(paginator).getWidth() >= input(paginator).getFontMetrics(input(paginator).getFont()).stringWidth("9999"));
        });
    }

    private static JButton button(Paginator paginator, String name) {
        for (Component component : paginator.getComponents()) {
            if (component instanceof JButton
                && name.equals(component.getAccessibleContext().getAccessibleName())) {
                return (JButton) component;
            }
        }
        throw new AssertionError("Missing button: " + name);
    }

    private static JTextField input(Paginator paginator) {
        return Arrays.stream(paginator.getComponents()).filter(JTextField.class::isInstance)
            .map(JTextField.class::cast).findFirst().orElseThrow(AssertionError::new);
    }

    private static void activate(JButton button, KeyStroke key) {
        Object actionKey = button.getInputMap().get(key);
        assertNotNull("Missing key binding for " + key, actionKey);
        Action action = button.getActionMap().get(actionKey);
        assertNotNull(action);
        action.actionPerformed(new ActionEvent(button, ActionEvent.ACTION_PERFORMED, "keyboard"));
    }
}
