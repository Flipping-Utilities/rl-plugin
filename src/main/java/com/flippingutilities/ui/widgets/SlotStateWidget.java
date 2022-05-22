package com.flippingutilities.ui.widgets;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.FontID;
import net.runelite.api.SpriteID;
import net.runelite.api.widgets.*;
import net.runelite.client.util.ColorUtil;

import java.awt.*;
import java.util.regex.Pattern;

@Slf4j
public class SlotStateWidget {

    private transient Widget slotWidget;
    private transient Widget slotItemNameText;

    public void setWidget(Widget slotWidget)
    {

        Color c = new Color(229, 94, 94);
        this.slotWidget = slotWidget;
        Widget w = slotWidget.createChild(26, WidgetType.TEXT);

        w.setText("yeeeeeet");
        w.setTextColor(0x800000);
        w.setFontId(FontID.VERDANA_11_BOLD);
        w.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
        w.setOriginalX(50);
        w.setOriginalY(50);
        w.setWidthMode(WidgetSizeMode.MINUS);
        w.setOriginalHeight(20);
        w.setOriginalWidth(20);
        w.setXPositionMode(2);
        w.setXTextAlignment(0);
        w.revalidate();
        slotItemNameText = slotWidget.getChild(19);
//        slotWidget.getChild(8).setSpriteId(132111);
        Pattern r = Pattern.compile(">= (.*) each");
//        String itemName = slotItemNameText.getText().mat("<br>")[0];
        slotItemNameText.setText("Infinity hat<br><br>" + ColorUtil.wrapWithColorTag("UNDERCUT", c));
//        "undercut"
//        "in range"

//        slotItemNameText.setText("<html>" + ColorUtil.wrapWithColorTag(itemName, c) + "<br>UNDERCUT </html>");
//        slotStateString = slotItemNameText.getText();
        slotWidget.revalidate();
    }
}
