package com.flippingutilities.ui.widgets;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.SpriteID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.ColorUtil;

@Slf4j
public class SlotStateWidget {

    private transient Widget slotWidget;
    private transient Widget slotItemNameText;

    public void setWidget(Widget slotWidget)
    {

        this.slotWidget = slotWidget;
        slotItemNameText = slotWidget.getChild(19);
        this.log.info("widget id is {}, sprite id is {}", slotWidget.getId(),slotWidget.getSpriteId());
//        slotItemNameText.setText("  <html>" + ColorUtil.wrapWithColorTag(slotStateString, stateTextColor) + spacer + ColorUtil.wrapWithColorTag(timeString, timeColor) + "</html>");
        slotItemNameText.setText("yeeet");
        slotWidget.revalidate();
//        slotStateString = slotItemNameText.getText();
    }
}
