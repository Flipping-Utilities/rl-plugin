package com.flippingutilities.ui.recipeflips;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.MasterPanel;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.Icons;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import com.flippingutilities.utilities.Recipe;
import lombok.Setter;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.util.AsyncBufferedImage;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * This is the panel that shows the available recipes when you click to create a recipe flip from an offer.
 */
public class RecipeFlipCreationPanel extends JPanel {
    static boolean offerSelectionPanelOpen = false;
    FlippingPlugin plugin;
    OfferEvent sourceOffer;
    @Setter
    JDialog modal;

    public RecipeFlipCreationPanel(FlippingPlugin plugin, OfferEvent sourceOffer) {
        this.plugin = plugin;
        this.sourceOffer = sourceOffer;
        List<Recipe> recipes = plugin.getApplicableRecipes(sourceOffer.getItemId(), sourceOffer.isBuy());
        recipes.sort(Comparator.comparing(r -> r.getIds().size()));
        Collections.reverse(recipes);

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10,10,10,10));

        add(createTitlePanel(recipes), BorderLayout.NORTH);
        add(createRecipeScrollPane(recipes), BorderLayout.CENTER);

    }

    private JScrollPane createRecipeScrollPane(List<Recipe> recipes) {
        JPanel recipePanelContainer = new JPanel(new DynamicGridLayout(recipes.size(), 1,0,3));
        recipes.forEach(r -> {
            recipePanelContainer.add(createRecipePanel(r));
        });

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(Color.BLACK);
        wrapper.add(recipePanelContainer, BorderLayout.NORTH);

        JScrollPane scrollPane = new JScrollPane(wrapper);
        scrollPane.setBackground(Color.BLACK);
        scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(2, 0));
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setPreferredSize(new Dimension(45 * (recipes.get(0).getIds().size() + 1), Math.min(500, 75 * recipes.size())));
        return scrollPane;
    }

    private JPanel createRecipePanel(Recipe recipe) {
        JPanel recipePanel = new JPanel(new BorderLayout());
        recipePanel.setBorder(new EmptyBorder(5,0,8,0));
        recipePanel.setBackground(Color.BLACK);

        recipePanel.setBorder(new CompoundBorder(
            new MatteBorder(1, 1, 1, 1, CustomColors.DARK_GRAY),
            new EmptyBorder(5,0,8,0)));

        JPanel recipeIconPanel = new JPanel();
        recipeIconPanel.setBackground(Color.BLACK);

        recipePanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (offerSelectionPanelOpen) {
                    JOptionPane.showMessageDialog(null, "You must close the other recipe creation menu before opening another one");
                    return;
                }
                modal.dispose();
                MasterPanel m = plugin.getMasterPanel();
                RecipeOfferSelectionPanel recipeOfferSelectionPanel = new RecipeOfferSelectionPanel(plugin, sourceOffer, recipe);
                offerSelectionPanelOpen = true;
                JDialog recipeOfferSelectionModal = UIUtilities.createModalFromPanel(m, recipeOfferSelectionPanel);
                recipeOfferSelectionModal.addComponentListener(new ComponentAdapter() {
                    @Override
                    public void componentHidden(ComponentEvent e) {
                        recipeOfferSelectionModal.dispose();
                        offerSelectionPanelOpen = false;
                    }
                });
                recipeOfferSelectionModal.pack();
                recipeOfferSelectionModal.setLocation(
                    Math.max(20, m.getLocationOnScreen().x - recipeOfferSelectionModal.getWidth() - 10),
                    Math.max(m.getLocationOnScreen().y - recipeOfferSelectionModal.getHeight()/2, 0) + 100);
                recipeOfferSelectionModal.setVisible(true);

                revalidate();
                repaint();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                recipePanel.setBackground(Color.DARK_GRAY);
                recipeIconPanel.setBackground(Color.DARK_GRAY);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                recipePanel.setBackground(Color.BLACK);
                recipeIconPanel.setBackground(Color.BLACK);
            }
        });

        recipe.getInputIds().forEach( id -> {
            AsyncBufferedImage itemImage = plugin.getItemManager().getImage(id);
            JLabel iconLabel = new JLabel();
            itemImage.addTo(iconLabel);
            recipeIconPanel.add(iconLabel);
        });

        recipeIconPanel.add(new JLabel(Icons.RIGHT_ARROW_LARGE));

        recipe.getOutputIds().forEach( id -> {
            AsyncBufferedImage itemImage = plugin.getItemManager().getImage(id);
            JLabel iconLabel = new JLabel();
            itemImage.addTo(iconLabel);
            recipeIconPanel.add(iconLabel);
        });

        JLabel recipeNameLabel = new JLabel(recipe.getName(), SwingConstants.CENTER);
        recipeNameLabel.setFont(new Font("Whitney", Font.PLAIN, 12));
        recipeNameLabel.setBorder(new EmptyBorder(3,0,0,0));

        recipePanel.add(recipeIconPanel, BorderLayout.CENTER);
        recipePanel.add(recipeNameLabel, BorderLayout.SOUTH);

        return recipePanel;
    }

    private JPanel createTitlePanel(List<Recipe> recipes) {
        JLabel titleLabel = new JLabel("Select a Recipe", SwingConstants.CENTER);
        titleLabel.setFont(new Font("Whitney", Font.PLAIN, 16));

        JLabel numRecipesLabel = new JLabel(
            String.format(
                "Showing %d %s for %s %s",
                recipes.size(),
                UIUtilities.maybePluralize("recipe", recipes.size()),
                sourceOffer.isBuy()? "buying":"selling",
                sourceOffer.getItemName()
            ),
            SwingConstants.CENTER);
        numRecipesLabel.setFont(new Font("Whitney", Font.ITALIC, 10));

        JPanel titlePanel = new JPanel(new BorderLayout());
        titlePanel.setBorder(new EmptyBorder(0,0,10,0));
        titlePanel.add(titleLabel, BorderLayout.CENTER);
        titlePanel.add(numRecipesLabel, BorderLayout.SOUTH);

        return titlePanel;
    }
}
