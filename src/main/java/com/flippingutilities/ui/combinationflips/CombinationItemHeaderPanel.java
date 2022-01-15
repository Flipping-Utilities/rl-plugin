package com.flippingutilities.ui.combinationflips;

import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

import javax.swing.*;
import java.awt.*;

/**
 * Holds the item icon and multiplier text label along with the target value label
 */
public class CombinationItemHeaderPanel extends JPanel {

    JLabel targetValueLabel;
    JLabel itemIconAndConsumedAmountLabel;

    public CombinationItemHeaderPanel(AsyncBufferedImage itemImage) {
        setBackground(Color.BLACK);

        Icon itemIcon = new ImageIcon(itemImage);
        itemIconAndConsumedAmountLabel = new JLabel(itemIcon);
        itemIconAndConsumedAmountLabel.setHorizontalTextPosition(SwingConstants.RIGHT);
        itemIconAndConsumedAmountLabel.setFont(new Font("Whitney", Font.PLAIN, 20));
        itemIconAndConsumedAmountLabel.setText("x0");

        targetValueLabel = new JLabel("/0");
        targetValueLabel.setFont(new Font("Whitney", Font.PLAIN, 12));

        add(itemIconAndConsumedAmountLabel);
        add(targetValueLabel);
    }

    public void setConsumedAmountDisplay(int amount) {
        itemIconAndConsumedAmountLabel.setText("x" + QuantityFormatter.formatNumber(amount));
    }

    public void setTargetValueDisplay(int targetValue) {
        targetValueLabel.setText("/" + QuantityFormatter.formatNumber(targetValue));
    }

    public void setConsumedAmountDisplayColor(Color color) {
        itemIconAndConsumedAmountLabel.setForeground(color);
    }
}
