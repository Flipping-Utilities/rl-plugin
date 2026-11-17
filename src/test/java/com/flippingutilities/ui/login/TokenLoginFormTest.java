package com.flippingutilities.ui.login;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import net.runelite.client.ui.laf.RuneLiteLAF;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

public class TokenLoginFormTest {
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
            try { UIManager.setLookAndFeel(previousLookAndFeel); }
            catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
        });
    }

    @Test
    public void emptyInputAndDeclinedConsentDoNotSendCredentials() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger confirmations = new AtomicInteger();
            AtomicInteger requests = new AtomicInteger();
            TokenLoginForm form = new TokenLoginForm(() -> {
                confirmations.incrementAndGet();
                return false;
            }, value -> { requests.incrementAndGet(); return new CompletableFuture<>(); });
            JPasswordField token = find(form, JPasswordField.class);
            token.setText("  ");
            token.postActionEvent();
            assertEquals(0, confirmations.get());
            assertEquals("Enter a token before logging in.", find(form, JTextArea.class).getText());
            token.setText("example-token");
            token.postActionEvent();
            assertEquals(1, confirmations.get());
            assertEquals(0, requests.get());
            assertTrue(token.isEnabled());
            assertTrue(find(form, JButton.class).isEnabled());
        });
    }

    @Test
    public void enterSubmitsOnceAndFailureAllowsRetryFromAnotherThread() throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<TokenLoginForm> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            TokenLoginForm form = new TokenLoginForm(() -> true, value -> {
                assertEquals("example-token", value);
                requests.incrementAndGet();
                return result;
            });
            reference.set(form);
            JPasswordField token = find(form, JPasswordField.class);
            token.setText("  example-token  ");
            token.postActionEvent();
            token.postActionEvent();
            find(form, JButton.class).doClick();
            assertEquals(1, requests.get());
            assertFalse(token.isEnabled());
            assertFalse(find(form, JButton.class).isEnabled());
            assertEquals("Logging in…", find(form, JButton.class).getText());
        });
        result.completeExceptionally(new IllegalStateException("Do not expose server details or token"));
        SwingUtilities.invokeAndWait(() -> {
            TokenLoginForm form = reference.get();
            assertTrue(find(form, JPasswordField.class).isEnabled());
            assertTrue(find(form, JButton.class).isEnabled());
            assertEquals("Could not log in. Check your token or try again.", find(form, JTextArea.class).getText());
            find(form, JButton.class).doClick();
            assertEquals(2, requests.get());
        });
    }

    @Test
    public void completedRequestClearsTheTokenAndCannotBeResubmitted() throws Exception {
        AtomicReference<TokenLoginForm> reference = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            TokenLoginForm form = new TokenLoginForm(() -> true, value -> {
                requests.incrementAndGet();
                return CompletableFuture.completedFuture("jwt");
            });
            reference.set(form);
            find(form, JPasswordField.class).setText("example-token");
            JButton button = find(form, JButton.class);
            assertEquals(AccessibleRole.PUSH_BUTTON, button.getAccessibleContext().getAccessibleRole());
            assertTrue(button.isFocusable());
            for (String key : new String[]{"ENTER", "released ENTER"}) {
                Object actionKey = button.getInputMap().get(KeyStroke.getKeyStroke(key));
                assertNotNull(actionKey);
                button.getActionMap().get(actionKey).actionPerformed(new ActionEvent(button, 0, "keyboard"));
            }
        });
        SwingUtilities.invokeAndWait(() -> {
            TokenLoginForm form = reference.get();
            assertEquals(0, find(form, JPasswordField.class).getPassword().length);
            assertEquals("Logged in", find(form, JButton.class).getText());
            find(form, JPasswordField.class).postActionEvent();
            assertEquals(1, requests.get());
        });
    }

    @Test
    public void synchronousFailureRestoresUsableControls() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TokenLoginForm form = new TokenLoginForm(() -> true, value -> { throw new IllegalStateException(); });
            JPasswordField token = find(form, JPasswordField.class);
            assertNotEquals(0, token.getEchoChar());
            assertEquals("Discord login token", token.getAccessibleContext().getAccessibleName());
            token.setText("example-token");
            token.postActionEvent();
            assertTrue(token.isEnabled());
            assertTrue(find(form, JButton.class).isEnabled());
            assertEquals("Log in", find(form, JButton.class).getText());
        });
    }

    static <T> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) {
                T found = find((Container) child, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
