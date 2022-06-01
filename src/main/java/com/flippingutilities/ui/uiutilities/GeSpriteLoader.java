package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.controller.FlippingPlugin;
import net.runelite.api.Client;
import net.runelite.client.util.ImageUtil;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GeSpriteLoader {
    public static final int TOP_CHILD_IDX = 5;
    public static final int BOTTOM_CHILD_IDX = 6;
    public static final int LEFT_CHILD_IDX = 7;
    public static final int RIGHT_CHILD_IDX = 8;
    public static final int TOP_LEFT_CHILD_IDX = 9;
    public static final int TOP_RIGHT_CHILD_IDX = 10;
    public static final int BOTTOM_LEFT_CHILD_IDX = 11;
    public static final int BOTTOM_RIGHT_CHILD_IDX = 12;
    public static final int HORIZONTAL_CHILD_IDX = 13;
    public static final int LEFT_INTERSECTION_CHILD_IDX = 14;
    public static final int RIGHT_INTERSECTION_CHILD_IDX = 15;
    public static final int ITEM_BOX_CHILD_IDX = 17;

    public static final List<Integer> DYNAMIC_CHILDREN_IDXS = Arrays.asList(
        BOTTOM_CHILD_IDX, BOTTOM_LEFT_CHILD_IDX,BOTTOM_RIGHT_CHILD_IDX,
        TOP_LEFT_CHILD_IDX, TOP_RIGHT_CHILD_IDX, HORIZONTAL_CHILD_IDX,
        LEFT_INTERSECTION_CHILD_IDX, RIGHT_INTERSECTION_CHILD_IDX,
        LEFT_CHILD_IDX, RIGHT_CHILD_IDX, TOP_CHILD_IDX, ITEM_BOX_CHILD_IDX);

    public static Map<Integer, Integer> CHILDREN_IDX_TO_DEFAULT_SPRITE_ID = createChildIdxToSpriteIdMap(CustomSpriteIds.DEFAULT_SLOT_SPRITES);
    public static Map<Integer, Integer> CHILDREN_IDX_TO_RED_SPRITE_ID = createChildIdxToSpriteIdMap(CustomSpriteIds.RED_SLOT_SPRITES);
    public static Map<Integer, Integer> CHILDREN_IDX_TO_BLUE_SPRITE_ID = createChildIdxToSpriteIdMap(CustomSpriteIds.BLUE_SLOT_SPRITES);
    public static Map<Integer, Integer> CHILDREN_IDX_TO_GREEN_SPRITE_ID = createChildIdxToSpriteIdMap(CustomSpriteIds.GREEN_SLOT_SPRITES);

    private static List<String> FILE_NAMES = Arrays.asList(
        "border_offer_bottom.png",
        "border_offer_corner_bottom_left.png",
        "border_offer_corner_bottom_right.png",
        "border_offer_corner_top_left.png",
        "border_offer_corner_top_right.png",
        "border_offer_horizontal.png",
        "border_offer_intersection_left.png",
        "border_offer_intersection_right.png",
        "border_offer_left.png",
        "border_offer_right.png",
        "border_offer_top.png",
        "selected_item_box.png"
    );

    public static void setClientSpriteOverrides(Client client) {
        setClientSpriteOverrides(client, "red", CustomSpriteIds.RED_SLOT_SPRITES);
        setClientSpriteOverrides(client, "green", CustomSpriteIds.GREEN_SLOT_SPRITES);
        setClientSpriteOverrides(client, "blue", CustomSpriteIds.BLUE_SLOT_SPRITES);
    }

    private static void setClientSpriteOverrides(Client client, String color, List<Integer> spriteIds) {
        for (int i = 0; i < spriteIds.size(); i++) {
            int spriteId = spriteIds.get(i);
            String filename = FILE_NAMES.get(i);
            BufferedImage image = ImageUtil.loadImageResource(FlippingPlugin.class, "/ge-sprites/" + color +  "/" + filename);
            client.getSpriteOverrides().put(spriteId, ImageUtil.getImageSpritePixels(image, client));
        }
    }

    private static Map<Integer, Integer> createChildIdxToSpriteIdMap(List<Integer> spriteIds) {
        Map<Integer, Integer> childIdToSpriteIdMap = new HashMap<>();
        for (int i = 0; i < spriteIds.size(); i++) {
            childIdToSpriteIdMap.put(DYNAMIC_CHILDREN_IDXS.get(i), spriteIds.get(i));
        }

        return childIdToSpriteIdMap;
    }

}

