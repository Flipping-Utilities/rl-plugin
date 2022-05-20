package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.controller.FlippingPlugin;
import net.runelite.client.util.ImageUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

public class Icons {
    public static final Dimension ICON_SIZE = new Dimension(32, 32);
    public static final int TOOLBAR_BUTTON_SIZE = 20;
    private static final int OPTION_DOT_WIDTH = 6;
    private static final int OPTION_DOT_HEIGHT = 6;

    public static final ImageIcon OPEN_ICON;
    public static final ImageIcon CLOSE_ICON;

    public static final ImageIcon RESET_ICON;
    public static final ImageIcon RESET_HOVER_ICON;

    public static final ImageIcon DELETE_ICON;

    public static final ImageIcon SETTINGS_ICON;
    public static final ImageIcon SETTINGS_ICON_OFF;

    public static final ImageIcon GNOME_CHILD;

    public static final ImageIcon DELETE_BUTTON;

    public static final ImageIcon HIGHLIGHT_DELETE_BUTTON;

    public static final ImageIcon STAR_ON_ICON;
    public static final ImageIcon STAR_HOVER_ICON;
    public static final ImageIcon STAR_OFF_ICON;

    public static final ImageIcon SMALL_STAR_ON_ICON;
    public static final ImageIcon SMALL_STAR_HOVER_ICON;
    public static final ImageIcon SMALL_STAR_OFF_ICON;

    public static final ImageIcon SORT_BY_RECENT_OFF_ICON;
    public static final ImageIcon SORT_BY_RECENT_ON_ICON;
    public static final ImageIcon SORT_BY_RECENT_HALF_ON_ICON;

    public static final ImageIcon SORT_BY_ROI_OFF_ICON;
    public static final ImageIcon SORT_BY_ROI_ON_ICON;
    public static final ImageIcon SORT_BY_ROI_HALF_ON_ICON;

    public static final ImageIcon SORT_BY_PROFIT_OFF_ICON;
    public static final ImageIcon SORT_BY_PROFIT_ON_ICON;
    public static final ImageIcon SORT_BY_PROFIT_HALF_ON_ICON;

    public static final ImageIcon ARROW_LEFT;
    public static final ImageIcon ARROW_RIGHT;
    public static final ImageIcon ARROW_LEFT_HOVER;
    public static final ImageIcon ARROW_RIGHT_HOVER;

    public static final ImageIcon HEART_ICON;

    public static final ImageIcon TRASH_ICON;
    public static final ImageIcon TRASH_ICON_OFF;

    public static final ImageIcon DOWNLOAD_ICON;
    public static final ImageIcon DONWLOAD_ICON_OFF;

    public static final ImageIcon GITHUB_ICON;
    public static final ImageIcon GITHUB_ICON_ON;

    public static final ImageIcon DISCORD_ICON;
    public static final ImageIcon DISCORD_ICON_ON;
    public static final ImageIcon DISCORD_CHEESE;

    public static final ImageIcon TWITTER_ICON;
    public static final ImageIcon TWITTER_ICON_ON;

    public static final ImageIcon PLUS_ICON;
    public static final ImageIcon PLUS_ICON_OFF;

    public static final ImageIcon GREEN_DOT;
    public static final ImageIcon GRAY_DOT;
    public static final ImageIcon RED_DOT;

    public static final ImageIcon HELP;
    public static final ImageIcon HELP_HOVER;

    public static final ImageIcon TEMPLATE;
    public static final ImageIcon TEMPLATE_HOVER;

    public static final ImageIcon QUANTITY_EDITOR_PIC;
    public static final ImageIcon PRICE_EDITOR_PIC;

    public static final ImageIcon SEARCH;
    public static final ImageIcon SEARCH_HOVER;

    public static final ImageIcon REFRESH;
    public static final ImageIcon REFRESH_HOVER;

    public static final ImageIcon TOGGLE_ON;
    public static final ImageIcon TOGGLE_OFF;

    public static final ImageIcon TRASH_CAN_ON;
    public static final ImageIcon TRASH_CAN_OFF;

    public static final ImageIcon DUMP_ALERT_PIC;

    public static final ImageIcon QUESTION_MARK;

    public static final ImageIcon USER;
    public static final ImageIcon USER_HOVER;
    public static final ImageIcon USER_LOGGED_IN;

    public static final ImageIcon FU_ICON;

    public static final ImageIcon KEY;

    public static final ImageIcon FLASH;

    public static final ImageIcon RIGHT_ARROW_LARGE;
    public static final ImageIcon LEFT_ARROW_LARGE;

    public static final ImageIcon SORT;
    public static final ImageIcon SORT_HOVER;

    public static final ImageIcon CONSTRUCTION;

    public static final ImageIcon RECIPE_HELP;

    public static final BufferedImage account;

    static
    {
        final BufferedImage openIcon = ImageUtil
                .getResourceStreamFromClass(FlippingPlugin.class, "/small_open_arrow.png");
        CLOSE_ICON = new ImageIcon(openIcon);
        OPEN_ICON = new ImageIcon(ImageUtil.rotateImage(openIcon, Math.toRadians(90)));

        final BufferedImage resetIcon = ImageUtil
                .getResourceStreamFromClass(FlippingPlugin.class, "/reset.png");
        RESET_ICON = new ImageIcon(resetIcon);
        RESET_HOVER_ICON = new ImageIcon(ImageUtil.alphaOffset(resetIcon, 0.53f));

        final BufferedImage deleteIcon = ImageUtil
                .getResourceStreamFromClass(FlippingPlugin.class, "/delete_icon.png");
        DELETE_ICON = new ImageIcon(deleteIcon);

        final BufferedImage settingsIcon = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/settings_icon.png");
        SETTINGS_ICON = new ImageIcon(settingsIcon);
        SETTINGS_ICON_OFF = new ImageIcon(ImageUtil.alphaOffset(settingsIcon, 0.53f));

        BufferedImage accountIcon = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/gnome.png");
        GNOME_CHILD = new ImageIcon(accountIcon);

        final BufferedImage deleteButton = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/deleteButton.png");
        DELETE_BUTTON = new ImageIcon(deleteButton);

        final BufferedImage highlightDeleteButton = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/highlightDeleteButton.png");
        HIGHLIGHT_DELETE_BUTTON = new ImageIcon(highlightDeleteButton);


        final BufferedImage starOn = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/toolbar-icons/star-gold.png");
        final BufferedImage sortByRecentOn = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/toolbar-icons/clock-gold.png");
        final BufferedImage sortByRoiOn = ImageUtil.loadImageResource(FlippingPlugin.class, "/toolbar-icons/roi-gold.png");
        final BufferedImage sortByProfitOn = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/toolbar-icons/profit-gold.png");
        final BufferedImage starOff = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/toolbar-icons/star_off_white.png");
        final BufferedImage sortByRecentOff = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/toolbar-icons/clock_white.png");
        final BufferedImage sortByRoiOff = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/toolbar-icons/thick_roi_white.png");
        final BufferedImage sortByProfitOff = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/toolbar-icons/potential_profit_white.png");

        STAR_ON_ICON = new ImageIcon(starOn.getScaledInstance(TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, Image.SCALE_SMOOTH));
        SORT_BY_RECENT_ON_ICON = new ImageIcon(sortByRecentOn.getScaledInstance(TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, Image.SCALE_SMOOTH));
        SORT_BY_ROI_ON_ICON = new ImageIcon(sortByRoiOn.getScaledInstance(TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, Image.SCALE_SMOOTH));
        SORT_BY_PROFIT_ON_ICON = new ImageIcon(sortByProfitOn.getScaledInstance(TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, Image.SCALE_SMOOTH));

        STAR_HOVER_ICON = new ImageIcon(starOff.getScaledInstance(TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, Image.SCALE_SMOOTH));
        SORT_BY_PROFIT_HALF_ON_ICON = new ImageIcon(sortByProfitOff.getScaledInstance(TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, Image.SCALE_SMOOTH));
        SORT_BY_RECENT_HALF_ON_ICON = new ImageIcon(sortByRecentOff.getScaledInstance(TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, Image.SCALE_SMOOTH));
        SORT_BY_ROI_HALF_ON_ICON = new ImageIcon(sortByRoiOff.getScaledInstance(TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, Image.SCALE_SMOOTH));

        STAR_OFF_ICON = new ImageIcon(ImageUtil.alphaOffset(starOff, 0.53f).getScaledInstance(TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, Image.SCALE_SMOOTH));
        SORT_BY_RECENT_OFF_ICON = new ImageIcon(ImageUtil.alphaOffset(sortByRecentOff, 0.53f).getScaledInstance(TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, Image.SCALE_SMOOTH));
        SORT_BY_ROI_OFF_ICON = new ImageIcon(ImageUtil.alphaOffset(sortByRoiOff, 0.53f).getScaledInstance(TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, Image.SCALE_SMOOTH));
        SORT_BY_PROFIT_OFF_ICON = new ImageIcon(ImageUtil.alphaOffset(sortByProfitOff, 0.53f).getScaledInstance(TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, Image.SCALE_SMOOTH));

        SMALL_STAR_ON_ICON = new ImageIcon(starOn.getScaledInstance(13, 13, Image.SCALE_SMOOTH));
        SMALL_STAR_HOVER_ICON = new ImageIcon(starOff.getScaledInstance(13, 13, Image.SCALE_SMOOTH));
        SMALL_STAR_OFF_ICON = new ImageIcon(ImageUtil.alphaOffset(starOff, 0.53f).getScaledInstance(13, 13, Image.SCALE_SMOOTH));

        final BufferedImage arrowLeft = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/left-arrow.png");
        ARROW_LEFT = new ImageIcon(ImageUtil.alphaOffset(arrowLeft,0.70f));

        final BufferedImage arrowRight = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/right-arrow.png");
        ARROW_RIGHT = new ImageIcon(ImageUtil.alphaOffset(arrowRight,0.70f));

        ARROW_LEFT_HOVER = new ImageIcon(arrowLeft);
        ARROW_RIGHT_HOVER = new ImageIcon(arrowRight);

        final BufferedImage heart = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/heart.png");
        HEART_ICON = new ImageIcon(heart);

        final BufferedImage trashIcon = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/trash.png");
        TRASH_ICON = new ImageIcon(trashIcon);
        TRASH_ICON_OFF = new ImageIcon(ImageUtil.alphaOffset(trashIcon, 0.53f));

        final BufferedImage downloadIcon = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/download.png");
        DOWNLOAD_ICON = new ImageIcon(downloadIcon);
        DONWLOAD_ICON_OFF = new ImageIcon(ImageUtil.alphaOffset(downloadIcon, 0.53f));

        final BufferedImage githubIcon = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/github-alch.png");
        final BufferedImage githubIconOn = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/githubon.png");
        GITHUB_ICON = new ImageIcon(githubIcon);
        GITHUB_ICON_ON = new ImageIcon(githubIconOn);

        final BufferedImage twitterIcon = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/twitter-alch.png");
        final BufferedImage twitterIconOn = ImageUtil.loadImageResource(FlippingPlugin.class, "/twitteron.png");
        TWITTER_ICON = new ImageIcon(twitterIcon);
        TWITTER_ICON_ON = new ImageIcon(twitterIconOn);

        final BufferedImage discordIcon = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/discord-alch.png");
        final BufferedImage discordIconOn = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/discordon.png");
        final BufferedImage discordCheese = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/discord-cheese.png");
        DISCORD_ICON = new ImageIcon(discordIcon);
        DISCORD_ICON_ON = new ImageIcon(discordIconOn);
        DISCORD_CHEESE = new ImageIcon(discordCheese);

        final BufferedImage plusIcon = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/plus.png");
        PLUS_ICON = new ImageIcon(plusIcon.getScaledInstance(24,24,Image.SCALE_SMOOTH));
        PLUS_ICON_OFF = new ImageIcon(ImageUtil.alphaOffset(plusIcon, 0.53f).getScaledInstance(24,24,Image.SCALE_SMOOTH));

        final BufferedImage greenDot = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/greendot.png");
        GREEN_DOT = new ImageIcon(greenDot.getScaledInstance(OPTION_DOT_WIDTH,OPTION_DOT_HEIGHT,Image.SCALE_SMOOTH));

        final BufferedImage grayDot = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/graydot.png");
        GRAY_DOT = new ImageIcon(grayDot.getScaledInstance(OPTION_DOT_WIDTH,OPTION_DOT_HEIGHT,Image.SCALE_SMOOTH));

        final BufferedImage redDot = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/reddot.png");
        RED_DOT = new ImageIcon(redDot.getScaledInstance(OPTION_DOT_WIDTH,OPTION_DOT_HEIGHT,Image.SCALE_SMOOTH));

        final BufferedImage help = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/help.png");
        HELP = new ImageIcon(help);
        HELP_HOVER = new ImageIcon(ImageUtil.alphaOffset(help, 0.53f));

        final BufferedImage template = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/template.png");
        TEMPLATE = new ImageIcon(template);
        TEMPLATE_HOVER = new ImageIcon(ImageUtil.alphaOffset(template, 0.53f));

        final BufferedImage quantityEditorPic = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/quantityeditorpic.png");
        QUANTITY_EDITOR_PIC = new ImageIcon(quantityEditorPic);

        final BufferedImage priceEditorPic = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/priceeditorpic.png");
        PRICE_EDITOR_PIC = new ImageIcon(priceEditorPic);

        final BufferedImage searchIcon = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/search.png");
        SEARCH = new ImageIcon(searchIcon.getScaledInstance(12,12,Image.SCALE_SMOOTH));

        final BufferedImage searchIconHover = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/search.png");
        SEARCH_HOVER = new ImageIcon(ImageUtil.alphaOffset(searchIconHover,.53f).getScaledInstance(12,12,Image.SCALE_SMOOTH));

        final BufferedImage refreshIcon = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/refresh.png");
        REFRESH = new ImageIcon(refreshIcon.getScaledInstance(12,12,Image.SCALE_SMOOTH));

        final BufferedImage refreshIconHover = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/refresh.png");
        REFRESH_HOVER = new ImageIcon(ImageUtil.alphaOffset(refreshIconHover,.53f).getScaledInstance(12,12,Image.SCALE_SMOOTH));

        final BufferedImage toggleOn = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/toggle_on.png");
        TOGGLE_ON = new ImageIcon(toggleOn);

        TOGGLE_OFF = new ImageIcon(ImageUtil.flipImage(
                ImageUtil.luminanceScale(
                        ImageUtil.grayscaleImage(toggleOn),
                        0.61f
                ),
                true,
                false
        ));

        final BufferedImage trashCanIcon = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/trashicon.png");
        TRASH_CAN_ON = new ImageIcon(trashCanIcon.getScaledInstance(10,10,Image.SCALE_SMOOTH));
        TRASH_CAN_OFF = new ImageIcon(ImageUtil.alphaOffset(trashCanIcon, 0.53f).getScaledInstance(10,10, Image.SCALE_SMOOTH));

        final BufferedImage dumpPic = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/dumpwithprofit.png");
        DUMP_ALERT_PIC = new ImageIcon(dumpPic.getScaledInstance(450, 300, Image.SCALE_SMOOTH));

        final BufferedImage questionMark = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/question-mark.png");
        QUESTION_MARK = new ImageIcon(questionMark);

        final BufferedImage userIcon = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/user-alch.png");
        final BufferedImage userIconCheese = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/user-cheese.png");
        final BufferedImage userIconLoggedIn = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/user-loggedin.png");
        USER_HOVER = new ImageIcon(userIconCheese);
        USER = new ImageIcon(userIcon);
        USER_LOGGED_IN = new ImageIcon(userIconLoggedIn);

        final BufferedImage fuIcon = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/graph_icon_green.png");
        FU_ICON = new ImageIcon(fuIcon);

        final BufferedImage keyIcon = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/key.png");
        KEY = new ImageIcon(keyIcon);

        final BufferedImage flashIcon = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/flash-cheese.png");
        FLASH = new ImageIcon(flashIcon);

        final BufferedImage rightArrowLarge = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/right-arrow-large.png");
        RIGHT_ARROW_LARGE = new ImageIcon(rightArrowLarge);

        final BufferedImage leftArrowLarge = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/left-arrow-large.png");
        LEFT_ARROW_LARGE = new ImageIcon(leftArrowLarge);

        final BufferedImage sort = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/sort.png");
        SORT = new ImageIcon(ImageUtil.alphaOffset(sort, 0.53f));

        final BufferedImage sortHover = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/sort.png");
        SORT_HOVER = new ImageIcon(sort);

        final BufferedImage construction = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/construction.png");
        CONSTRUCTION = new ImageIcon(construction);

        final BufferedImage recipeHelp = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/recipehelp.png");
        RECIPE_HELP = new ImageIcon(recipeHelp);

        account = ImageUtil.getResourceStreamFromClass(FlippingPlugin.class, "/gnome.png");
    }
}
