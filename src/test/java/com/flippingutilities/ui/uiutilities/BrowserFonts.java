package com.flippingutilities.ui.uiutilities;

import net.runelite.client.ui.FontManager;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Keeps RuneLite's loaded font faces when CheerpJ cannot resolve their registered family names. */
public final class BrowserFonts {
    private static boolean installed;

    private BrowserFonts() {}

    public static synchronized void install() throws IOException, FontFormatException, ReflectiveOperationException {
        if (installed) return;
        // Let RuneLite finish its initializer before replacing the fonts it resolved by family.
        FontManager.getRunescapeFont();
        Font regular = load("runescape.ttf", Font.PLAIN);
        Font bold = load("runescape_bold.ttf", Font.BOLD);
        Font small = load("runescape_small.ttf", Font.PLAIN);
        replace("runescapeFont", regular);
        replace("runescapeBoldFont", bold);
        replace("runescapeSmallFont", small);
        installed = true;
    }

    private static Font load(String resource, int style) throws IOException, FontFormatException {
        try (InputStream input = FontManager.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing RuneLite font resource: " + resource);
            // Derive from the created font directly. Resolving its name through StyleContext
            // lets CheerpJ substitute SansSerif before consulting Java's registered fonts.
            return Font.createFont(Font.TRUETYPE_FONT, input).deriveFont(style, 16f);
        }
    }

    private static void replace(String name, Font font) throws ReflectiveOperationException {
        Field field = FontManager.class.getDeclaredField(name);
        field.setAccessible(true);
        // The browser distribution targets Java 11. Its reflection API requires clearing
        // FINAL on this Field object before it can assign RuneLite's static font constants.
        Field modifiers = Field.class.getDeclaredField("modifiers");
        modifiers.setAccessible(true);
        int original = field.getModifiers();
        modifiers.setInt(field, original & ~Modifier.FINAL);
        try { field.set(null, font); }
        finally { modifiers.setInt(field, original); }
    }
}
