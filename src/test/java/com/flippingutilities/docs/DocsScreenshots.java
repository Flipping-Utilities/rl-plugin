package com.flippingutilities.docs;

import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.MasterPanel;
import com.flippingutilities.ui.flipping.CustomizationPanel;
import com.flippingutilities.ui.flipping.FlippingPanel;
import com.flippingutilities.ui.gehistorytab.GeHistoryTabPanel;
import com.flippingutilities.ui.login.LoggedInPanel;
import com.flippingutilities.ui.login.LoginPanel;
import com.flippingutilities.ui.offereditor.OfferEditorContainerPanel;
import com.flippingutilities.ui.recipeflips.RecipeFlipCreationPanel;
import com.flippingutilities.ui.recipeflips.RecipeOfferSelectionPanel;
import com.flippingutilities.ui.slots.SlotPanel;
import com.flippingutilities.ui.slots.SlotsPanel;
import com.flippingutilities.ui.statistics.StatsPanel;
import com.flippingutilities.ui.statistics.recipes.RecipeFlipGroupPanel;
import com.flippingutilities.ui.statistics.recipes.customrecipes.CustomRecipeManagerPanel;
import com.flippingutilities.ui.uiutilities.GraphLoadState;
import com.flippingutilities.ui.uiutilities.Icons;
import com.flippingutilities.ui.uiutilities.QuickLookPanel;
import com.flippingutilities.ui.widgets.ChartStateFixtures;
import com.flippingutilities.utilities.Recipe;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.laf.RuneLiteLAF;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

import static com.flippingutilities.docs.DocsFixture.getFrom;
import static com.flippingutilities.docs.DocsFixture.setOn;

/**
 * Renders the screenshots of the user guide (docs/user-guide) from the plugin's real Swing panels, fed by
 * {@link DocsFixture}. Run with {@code ./gradlew docsScreenshots}. Numbered callouts sit in a gutter left of
 * the image with a leader line to the component they describe, so the guide's numbered lists match the images.
 */
public class DocsScreenshots {
    static final int SIDEBAR_WIDTH = 242;
    static final double SCALE = 2;
    static final int GUTTER = 26;
    static final Color CALLOUT = new Color(255, 152, 31);

    final File out;
    final DocsFixture plugin;

    DocsScreenshots(File out) throws Exception {
        this.out = out;
        out.mkdirs();
        this.plugin = new DocsFixture(new File("build/docs-icons"));
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "build/docs-screenshots");
        SwingUtilities.invokeAndWait(RuneLiteLAF::setup);
        DocsScreenshots shots = new DocsScreenshots(out);
        int status = 0;
        try {
            shots.renderAll();
        } catch (Exception e) {
            e.printStackTrace();
            status = 1;
        } finally {
            shots.plugin.executor.shutdownNow();
        }
        System.exit(status);
    }

    void renderAll() throws Exception {
        MasterPanel master = onEdt(() -> {
            FlippingPanel flipping = new FlippingPanel(plugin);
            plugin.set("flippingPanel", flipping);
            StatsPanel stats = new StatsPanel(plugin);
            plugin.set("statPanel", stats);
            SlotsPanel slots = new SlotsPanel(plugin, plugin.getItemManager());
            plugin.set("slotsPanel", slots);
            LoginPanel login = new LoginPanel(plugin);
            plugin.set("loginPanel", login);
            MasterPanel m = new MasterPanel(plugin, flipping, stats, slots, login);
            plugin.set("masterPanel", m);
            GeHistoryTabPanel geHistory = new GeHistoryTabPanel(plugin);
            plugin.set("geHistoryTabPanel", geHistory);
            m.addView(geHistory, "ge history");
            m.setupAccSelectorDropdown(plugin.getDataHandler().getCurrentAccounts());
            return m;
        });
        flush();
        FlippingPanel flipping = plugin.getFlippingPanel();
        StatsPanel stats = plugin.getStatPanel();

        renderFlipping(master, flipping);
        renderSlots(master);
        renderStats(master, stats);
        renderRecipes(master, stats);
        renderGeHistory(master);
        renderLogin();

        // charts drawn in game, rendered through the existing chart fixtures
        save("quick-look-tooltip", ChartStateFixtures.tooltip(GraphLoadState.READY), 0, 0, none());
        save("offer-page-chart", ChartStateFixtures.overlay(GraphLoadState.READY), 0, 0, none());
    }

    void renderFlipping(MasterPanel master, FlippingPanel flipping) throws Exception {
        onEdt(() -> { flipping.rebuild(plugin.viewItemsForCurrentView()); return null; });
        flush();
        selectTab(master, "flipping");
        save("sidebar-flipping", master, SIDEBAR_WIDTH, 820, callouts(
            find(master, JComboBox.class, null), 1,
            find(master, JLabel.class, l -> l.getIcon() == Icons.USER), 2,
            parentOf(tab(master, "slots")), 3,
            parentOf(find(flipping, IconTextField.class, null)), 4,
            firstOf(flipping, com.flippingutilities.ui.flipping.FlippingItemPanel.class), 5,
            flipping.getPaginator(), 6));

        // one card, every section open
        com.flippingutilities.ui.flipping.FlippingItemPanel card =
            firstOf(flipping, com.flippingutilities.ui.flipping.FlippingItemPanel.class);
        onEdt(() -> { click(find(card, JLabel.class, l -> "Other".equals(l.getText()))); return null; });
        JPanel cardHolder = holder(card);
        save("flipping-card", cardHolder, SIDEBAR_WIDTH, 0, callouts(
            find(card, JLabel.class, l -> l.getIcon() == Icons.STAR_ON_ICON || l.getIcon() == Icons.STAR_OFF_ICON), 1,
            find(card, JLabel.class, l -> "customize look".equals(l.getText())), 2,
            row(card, "Wiki insta buy"), 3,
            row(card, "Last insta sell"), 4,
            row(card, "Last buy price"), 5,
            row(card, "Wiki profit ea"), 6,
            row(card, "ROI"), 7,
            parentOf(find(card, JLabel.class, l -> l.getToolTipText() != null && l.getToolTipText().contains("local time"))), 8,
            parentOf(find(card, JLabel.class, l -> l.getIcon() == Icons.REFRESH)), 9,
            find(card, JLabel.class, l -> l.getText() != null && l.getText().contains("quick search code")), 10,
            find(card, JLabel.class, l -> l.getIcon() == Icons.SEARCH), 11));
        restore(card);

        CustomizationPanel customization = onEdt(() -> {
            CustomizationPanel c = new CustomizationPanel(plugin);
            c.rebuild(plugin.getDataHandler().viewAccountWideData().getSections());
            return c;
        });
        save("customize-look", customization, 400, 0, none());

        // offer setup: the GE offer screen is open for the whip, so the tab shows only it plus the editor
        FlippingItem whip = item(4151);
        plugin.highlight(whip);
        onEdt(() -> { flipping.highlightItem(whip); return null; });
        flush();
        flush();
        OfferEditorContainerPanel editor = flipping.getOfferEditorContainerPanel();
        save("offer-editor-price", editor, SIDEBAR_WIDTH - 15, 0, none());
        onEdt(() -> { editor.selectQuantityEditor(); return null; });
        flush();
        save("offer-editor-quantity", editor, SIDEBAR_WIDTH - 15, 0, none());
        onEdt(() -> { flipping.dehighlightItem(); return null; });
        flush();

        search(flipping, "bandos");
        save("flipping-search", master, SIDEBAR_WIDTH, 560, none());
        search(flipping, "");
    }

    void renderSlots(MasterPanel master) throws Exception {
        SlotsPanel slots = plugin.getSlotsPanel();
        onEdt(() -> {
            slots.update(plugin.liveOffer(0, 4151, false, 12, 42, 1_475_000));
            slots.update(plugin.liveOffer(1, 536, false, 5_000, 7_500, 2_450));
            slots.update(plugin.liveOffer(2, 12934, true, 18_400, 25_000, 172));
            slots.update(plugin.liveOffer(3, 6685, true, 1_500, 1_500, 7_570));
            slots.update(plugin.liveOffer(4, 19553, true, 3, 8, 9_290_000));
            slots.update(plugin.liveOffer(5, 2, true, 0, 11_000, 150));
            slots.update(plugin.liveOffer(6, 11832, false, 0, 8, 15_190_000));
            slots.onWikiRequest(plugin.wikiRequest);
            String[] timers = {"00:02:41", "00:11:08", "00:00:37", "", "00:24:55", "01:02:10", "00:04:12"};
            for (int i = 0; i < timers.length; i++) slots.updateTimerDisplays(i, timers[i]);
            return null;
        });
        flush();
        selectTab(master, "slots");
        save("sidebar-slots", master, SIDEBAR_WIDTH, 1000, none());
        QuickLookPanel quickLook = onEdt(() -> {
            SlotPanel slot = firstOf(slots, SlotPanel.class);
            slot.updateQuickLookPanel();
            return (QuickLookPanel) getFrom(slot, "quickLookPanel");
        });
        save("slots-quick-look", quickLook, 0, 0, none());
    }

    void renderStats(MasterPanel master, StatsPanel stats) throws Exception {
        selectTab(master, "stats");
        onEdt(() -> { find(stats, JComboBox.class, null).setSelectedItem("All"); return null; });
        flush();
        save("sidebar-stats", master, SIDEBAR_WIDTH, 900, callouts(
            find(stats, JComboBox.class, null), 1,
            named(stats, "Export to CSV"), 2,
            find(stats, IconTextField.class, null), 3,
            named(stats, "Reset statistics for this interval"), 4,
            listening(find(stats, JLabel.class, l -> "Total Profit: ".equals(l.getText()))), 5,
            parentOf(parentOf(find(stats, JLabel.class, l -> "ROI: ".equals(l.getText())))), 6,
            parentOf(tab(stats, "Items")), 7,
            named(stats, "Sort history"), 8,
            firstOf(stats, com.flippingutilities.ui.statistics.items.FlippingItemPanel.class), 9));

        onEdt(() -> {
            stats.getExpandedItems().add("Abyssal whip");
            stats.rebuildItemsDisplay(plugin.viewItemsForCurrentView());
            return null;
        });
        flush();
        com.flippingutilities.ui.statistics.items.FlippingItemPanel statItem = find(stats,
            com.flippingutilities.ui.statistics.items.FlippingItemPanel.class, p -> p.getItem().getItemId() == 4151);
        onEdt(() -> { click(listening(find(statItem, JLabel.class, l -> "View Trade History".equals(l.getText())))); return null; });
        flush();
        onEdt(() -> { click(tab(statItem, "Offers")); return null; });
        flush();
        JPanel statHolder = holder(statItem);
        save("stats-item-offers", statHolder, SIDEBAR_WIDTH, 0, none());
        onEdt(() -> { click(tab(statItem, "Flips")); return null; });
        flush();
        save("stats-item-flips", statHolder, SIDEBAR_WIDTH, 0, none());
        restore(statItem);
    }

    void renderRecipes(MasterPanel master, StatsPanel stats) throws Exception {
        onEdt(() -> { find(master, JComboBox.class, null).setSelectedItem(DocsFixture.MAIN); return null; });
        flush();
        onEdt(() -> { click(tab(stats, "Recipes")); return null; });
        flush();
        onEdt(() -> {
            RecipeFlipGroupPanel group = firstOf(stats, RecipeFlipGroupPanel.class);
            if (group != null) {
                JLabel name = find(group, JLabel.class, l -> l.getText() != null && l.getText().toLowerCase().contains("godsword"));
                Component c = listening(name);
                if (c != null) click(c);
            }
            return null;
        });
        flush();
        save("stats-recipes", master, SIDEBAR_WIDTH, 800, none());

        RecipeFlipCreationPanel creation = onEdt(() -> new RecipeFlipCreationPanel(plugin, plugin.latestBgsSell));
        save("recipe-select", creation, 0, 0, none());

        Recipe bgsRecipe = plugin.getApplicableRecipes(11804, false).stream()
            .filter(r -> r.getInputIds().contains(11812)).findFirst().orElse(null);
        if (bgsRecipe != null) {
            RecipeOfferSelectionPanel selection = onEdt(() -> new RecipeOfferSelectionPanel(plugin, plugin.latestBgsSell, bgsRecipe));
            flush();
            save("recipe-offers", selection, 0, 0, none());
        } else {
            System.err.println("no bandos godsword recipe in the remote dataset");
        }

        CustomRecipeManagerPanel manager = onEdt(() -> new CustomRecipeManagerPanel(plugin));
        flush();
        save("custom-recipes", manager, 0, 0, none());
    }

    void renderGeHistory(MasterPanel master) throws Exception {
        GeHistoryTabPanel geHistory = (GeHistoryTabPanel) getFrom(plugin, "geHistoryTabPanel");
        List<OfferEvent> history = new ArrayList<>();
        history.add(geOffer(4151, false, 12, 1_475_000));
        history.add(geOffer(2434, true, 900, 9_830));
        history.add(geOffer(536, false, 5_000, 2_450));
        history.add(geOffer(561, true, 18_000, 108));
        List<List<OfferEvent>> matches = new ArrayList<>();
        for (OfferEvent o : history) matches.add(plugin.findOfferMatches(o, 5));
        Widget[] widgets = new Widget[history.size() * 6];
        for (int i = 0; i < widgets.length; i++) widgets[i] = org.mockito.Mockito.mock(Widget.class);
        onEdt(() -> { geHistory.rebuild(history, matches, widgets, false); return null; });
        flush();
        onEdt(() -> {
            List<JCheckBox> boxes = new ArrayList<>();
            collect(geHistory, JCheckBox.class, boxes);
            if (boxes.size() > 3) {
                boxes.get(1).setSelected(true);
                boxes.get(3).setSelected(true);
            }
            return null;
        });
        flush();
        onEdt(() -> { master.showView("ge history"); return null; });
        flush();
        save("ge-history", master, SIDEBAR_WIDTH, 760, none());
    }

    void renderLogin() throws Exception {
        LoginPanel login = (LoginPanel) getFrom(plugin, "loginPanel");
        save("login", login, 0, 0, none());
        setOn(plugin.getApiAuthHandler(), "hasValidJWT", true);
        onEdt(() -> { login.showLoggedInView(); return null; });
        flush();
        onEdt(() -> {
            LoggedInPanel in = (LoggedInPanel) getFrom(login, "loggedInPanel");
            in.setSlotFeatureHealthText("Active (4s since last update)");
            return null;
        });
        flush();
        save("login-status", login, 0, 0, none());
        login.dispose();
    }

    // ---------------------------------------------------------------- helpers

    OfferEvent geOffer(int id, boolean buy, int qty, long price) {
        OfferEvent o = plugin.liveOffer(0, id, buy, qty, qty, price);
        o.setSlot(-1);
        return o;
    }

    void search(FlippingPanel flipping, String text) throws Exception {
        onEdt(() -> {
            IconTextField search = find(flipping, IconTextField.class, null);
            search.setText(text);
            find(search, JTextField.class, null).postActionEvent();
            return null;
        });
        plugin.executor.submit(() -> {}).get();
        Thread.sleep(300);
        flush();
    }

    FlippingItem item(int id) {
        return plugin.viewItemsForCurrentView().stream().filter(i -> i.getItemId() == id).findFirst()
            .orElseThrow(IllegalStateException::new);
    }

    void selectTab(Container root, String name) throws Exception {
        onEdt(() -> { click(tab(root, name)); return null; });
        flush();
    }

    static MaterialTab tab(Container root, String name) {
        return find(root, MaterialTab.class, t -> name.equals(t.getText()));
    }

    static JButton named(Container root, String accessibleName) {
        return find(root, JButton.class, b -> accessibleName.equals(b.getAccessibleContext().getAccessibleName()));
    }

    /** The row panel holding a description label that starts with the given text. */
    static Component row(Container root, String labelStart) {
        JLabel label = find(root, JLabel.class, l -> l.getText() != null && l.getText().startsWith(labelStart));
        Component c = parentOf(label);
        // "Pre tax" labels nest the description one level deeper
        if (c instanceof JPanel && !(((JPanel) c).getLayout() instanceof BorderLayout)) c = parentOf(c);
        return c;
    }

    /** The closest component, starting at c, that has a mouse listener. */
    static Component listening(Component c) {
        while (c != null && c.getMouseListeners().length == 0) c = c.getParent();
        return c;
    }

    static Component parentOf(Component c) {
        return c == null ? null : c.getParent();
    }

    static <T extends Component> void collect(Container root, Class<T> type, List<T> out) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) out.add(type.cast(child));
            if (child instanceof Container) collect((Container) child, type, out);
        }
    }

    // A component is moved into a standalone holder to render it alone; restore puts it back.
    private Container cardParent;
    private int cardIndex;

    JPanel holder(JComponent card) throws Exception {
        return onEdt(() -> {
            cardParent = card.getParent();
            cardIndex = Arrays.asList(cardParent.getComponents()).indexOf(card);
            JPanel holder = new JPanel(new BorderLayout());
            holder.setBackground(ColorScheme.DARK_GRAY_COLOR);
            holder.setBorder(new EmptyBorder(8, 8, 8, 7));
            holder.add(card, BorderLayout.CENTER);
            return holder;
        });
    }

    void restore(JComponent card) throws Exception {
        onEdt(() -> { cardParent.add(card, cardIndex); return null; });
    }

    static void click(Component c) {
        MouseEvent press = new MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
            MouseEvent.BUTTON1_DOWN_MASK, 2, 2, 1, false, MouseEvent.BUTTON1);
        for (MouseListener l : c.getMouseListeners()) l.mousePressed(press);
        MouseEvent clicked = new MouseEvent(c, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
            0, 2, 2, 1, false, MouseEvent.BUTTON1);
        for (MouseListener l : c.getMouseListeners()) l.mouseClicked(clicked);
    }

    static <T extends Component> T firstOf(Container root, Class<T> type) {
        return find(root, type, null);
    }

    static <T extends Component> T find(Container root, Class<T> type, Predicate<T> test) {
        if (root == null) return null;
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && (test == null || test.test(type.cast(child)))) return type.cast(child);
            if (child instanceof Container) {
                T found = find((Container) child, type, test);
                if (found != null) return found;
            }
        }
        return null;
    }

    static List<Callout> none() {
        return new ArrayList<>();
    }

    static List<Callout> callouts(Object... pairs) {
        List<Callout> list = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i] == null) {
                System.err.println("callout " + pairs[i + 1] + " has no target");
                continue;
            }
            list.add(new Callout((Component) pairs[i], (Integer) pairs[i + 1]));
        }
        return list;
    }

    // ---------------------------------------------------------------- rendering

    static class Callout {
        final Component target;
        final int number;
        Callout(Component target, int number) { this.target = target; this.number = number; }
    }

    void save(String name, JComponent component, int width, int height, List<Callout> callouts) throws Exception {
        onEdt(() -> {
            Container previousParent = component.getParent();
            Object constraints = previousParent != null && previousParent.getLayout() instanceof BorderLayout
                ? ((BorderLayout) previousParent.getLayout()).getConstraints(component) : null;
            int previousIndex = previousParent == null ? -1 : Arrays.asList(previousParent.getComponents()).indexOf(component);
            JPanel frame = new JPanel(new BorderLayout());
            frame.setBackground(ColorScheme.DARK_GRAY_COLOR);
            frame.add(component, BorderLayout.CENTER);
            invalidateTree(frame);
            int w = width > 0 ? width : component.getPreferredSize().width;
            int h = height;
            if (h <= 0) {
                component.setSize(w, Short.MAX_VALUE);
                layoutTree(component);
                invalidateTree(component);
                h = Math.max(30, component.getPreferredSize().height);
            }
            frame.setSize(w, h);
            frame.validate();
            layoutTree(frame);
            int gutter = callouts.isEmpty() ? 0 : GUTTER;
            BufferedImage image = new BufferedImage((int) ((w + gutter) * SCALE), (int) (h * SCALE), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.scale(SCALE, SCALE);
            if (gutter > 0) {
                g.setColor(new Color(20, 20, 20));
                g.fillRect(0, 0, gutter, h);
            }
            Graphics2D content = (Graphics2D) g.create();
            content.translate(gutter, 0);
            frame.printAll(content);
            content.dispose();
            drawCallouts(g, frame, callouts, gutter, h);
            g.dispose();
            if (callouts.isEmpty()) image = cropRight(image, image.getRGB(image.getWidth() - 1, 0));
            ImageIO.write(image, "png", new File(out, name + ".png"));
            frame.remove(component);
            if (previousParent != null) {
                if (constraints != null) previousParent.add(component, constraints);
                else previousParent.add(component, Math.min(Math.max(previousIndex, 0), previousParent.getComponentCount()));
            }
            return null;
        });
        System.out.println("rendered " + name);
    }

    /** Badges sit in a gutter left of the screenshot, each with a leader line to its outlined component. */
    static void drawCallouts(Graphics2D g, Container root, List<Callout> callouts, int gutter, int height) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int d = 16;
        List<Rectangle> rects = new ArrayList<>();
        for (Callout c : callouts) rects.add(SwingUtilities.convertRectangle(c.target.getParent(), c.target.getBounds(), root));
        Integer[] order = new Integer[callouts.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> {
            int dy = anchorY(rects.get(x)) - anchorY(rects.get(y));
            // components on the same row keep their callout order
            return Math.abs(dy) < 8 ? Integer.compare(callouts.get(x).number, callouts.get(y).number) : dy;
        });
        // stack the badges top-down without overlap, then push them back up if they ran off the bottom
        int[] ys = new int[order.length];
        int lastBottom = -100;
        for (int k = 0; k < order.length; k++) {
            ys[k] = Math.max(anchorY(rects.get(order[k])) - d / 2, lastBottom + 2);
            lastBottom = ys[k] + d;
        }
        int limit = height - d - 1;
        for (int k = order.length - 1; k >= 0; k--) {
            ys[k] = Math.max(1, Math.min(ys[k], limit));
            limit = ys[k] - d - 2;
        }
        for (int k = 0; k < order.length; k++) {
            int i = order[k];
            Callout c = callouts.get(i);
            Rectangle r = new Rectangle(rects.get(i));
            r.translate(gutter, 0);
            int by = ys[k];
            int bx = (gutter - d) / 2;
            g.setColor(CALLOUT);
            g.setStroke(new BasicStroke(1.25f));
            g.drawRoundRect(r.x + 1, r.y + 1, r.width - 2, r.height - 2, 6, 6);
            int ly = Math.max(r.y + 2, Math.min(by + d / 2, r.y + r.height - 2));
            g.drawLine(bx + d, by + d / 2, r.x + 1, ly);
            g.fillOval(bx, by, d, d);
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            String s = String.valueOf(c.number);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(s, bx + (d - fm.stringWidth(s)) / 2f, by + (d + fm.getAscent() - fm.getDescent()) / 2f);
        }
    }

    /** Drops empty background columns on the right (fixtures that are wider than what they paint). */
    static BufferedImage cropRight(BufferedImage image, int background) {
        int w = image.getWidth();
        outer:
        while (w > 1) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(w - 1, y) & 0xFFFFFF) != (background & 0xFFFFFF)) break outer;
            }
            w--;
        }
        return w == image.getWidth() ? image : image.getSubimage(0, 0, w, image.getHeight());
    }

    static int anchorY(Rectangle r) {
        return r.y + Math.min(r.height / 2, 12);
    }

    static void invalidateTree(Component c) {
        c.invalidate();
        if (c instanceof Container) for (Component child : ((Container) c).getComponents()) invalidateTree(child);
    }

    static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) layoutTree((Container) child);
        }
    }

    static <T> T onEdt(Callable<T> c) throws Exception {
        Object[] result = new Object[1];
        Exception[] error = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                result[0] = c.call();
            } catch (Exception e) {
                error[0] = e;
            }
        });
        if (error[0] != null) throw error[0];
        @SuppressWarnings("unchecked") T t = (T) result[0];
        return t;
    }

    static void flush() throws Exception {
        for (int i = 0; i < 3; i++) SwingUtilities.invokeAndWait(() -> {});
    }
}
