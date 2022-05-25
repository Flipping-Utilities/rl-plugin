package com.flippingutilities.ui.widgets;

import com.flippingutilities.ui.uiutilities.GeSpriteLoader;
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

        GeSpriteLoader.DYNAMIC_CHILDREN_IDXS.forEach(idx -> {
            Widget child = slotWidget.getChild(idx);
            int spriteId = GeSpriteLoader.CHILDREN_IDX_TO_RED_SPRITE_ID.get(idx);
            child.setSpriteId(spriteId);
        });

        w.setText("UNDERCUT");
        w.setTextColor(c.getRGB());
        w.setFontId(FontID.PLAIN_11);
        w.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
        w.setOriginalX(45);
        w.setOriginalY(60);
        w.setWidthMode(WidgetSizeMode.MINUS);
        w.setOriginalHeight(10);
//        w.setOriginalWidth(20);
        w.setXPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);
        w.setXTextAlignment(0);
        w.setTextShadowed(true);
        w.revalidate();
//        slotItemNameText = slotWidget.getChild(19);
//        slotItemNameText.setText("Infinity hat<br><br>" + ColorUtil.wrapWithColorTag("UNDERCUT", c));
//        "undercut"
//        "in range"

//        slotItemNameText.setText("<html>" + ColorUtil.wrapWithColorTag(itemName, c) + "<br>UNDERCUT </html>");
//        slotStateString = slotItemNameText.getText();
        slotWidget.revalidate();
    }
}
