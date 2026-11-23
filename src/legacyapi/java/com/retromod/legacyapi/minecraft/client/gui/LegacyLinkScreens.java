/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.gui;

import com.retromod.shim.common.embedded.LegacyLog;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.net.URI;
import java.net.URISyntaxException;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The String-link ConfirmLinkScreen constructors and helpers 26.3 replaced with URI ones. */
public final class LegacyLinkScreens {

    // Shown and opened in place of a link that cannot be read as a URI at all.
    static final URI UNREADABLE_LINK = URI.create("about:blank");

    private LegacyLinkScreens() {}

    /** {@code new ConfirmLinkScreen(callback, url, trusted)}: 26.3 takes the link as a URI. */
    public static ConfirmLinkScreen confirmLinkScreen(BooleanConsumer callback, String url, boolean trusted) {
        return new ConfirmLinkScreen(callback, uri(url), trusted);
    }

    /** {@code new ConfirmLinkScreen(callback, title, url, trusted)}, the same change with a title. */
    public static ConfirmLinkScreen confirmLinkScreen(BooleanConsumer callback, Component title, String url, boolean trusted) {
        return new ConfirmLinkScreen(callback, title, uri(url), trusted);
    }

    /** {@code ConfirmLinkScreen.confirmLink(lastScreen, url)}: a button action that asks before opening the link. */
    public static Button.OnPress confirmLink(Screen lastScreen, String url) {
        return ConfirmLinkScreen.confirmLink(lastScreen, uri(url));
    }

    /** {@code ConfirmLinkScreen.confirmLink(lastScreen, url, trusted)}, the same with the trusted flag. */
    public static Button.OnPress confirmLink(Screen lastScreen, String url, boolean trusted) {
        return ConfirmLinkScreen.confirmLink(lastScreen, uri(url), trusted);
    }

    /** {@code ConfirmLinkScreen.confirmLinkNow(lastScreen, url)}: asks right away. */
    public static void confirmLinkNow(Screen lastScreen, String url) {
        ConfirmLinkScreen.confirmLinkNow(lastScreen, uri(url));
    }

    /** {@code ConfirmLinkScreen.confirmLinkNow(lastScreen, url, trusted)}, the same with the trusted flag. */
    public static void confirmLinkNow(Screen lastScreen, String url, boolean trusted) {
        ConfirmLinkScreen.confirmLinkNow(lastScreen, uri(url), trusted);
    }

    /** The link as a URI. The old screen took any string, so characters a URI forbids are quoted rather than refused. */
    static URI uri(String url) {
        if (url != null) {
            try {
                return URI.create(url);
            } catch (IllegalArgumentException strict) {
                // The quoting constructor below accepts what a plain parse refuses.
            }
        }
        URI quoted = quoted(url);
        if (quoted != null) return quoted;
        LegacyLog.warnOnce("legacy link " + url, "An old mod opened a link screen for \"" + url
                + "\", which is not a link Minecraft can open, so the screen shows about:blank instead. "
                + "Report this line with the mod.");
        return UNREADABLE_LINK;
    }

    /** The URI the multi-part constructor builds, which quotes spaces and other characters a plain parse refuses. */
    private static URI quoted(String url) {
        if (url == null) return null;
        int colon = url.indexOf(':');
        if (colon <= 0) return null;
        // The fragment goes on its own, or its '#' would be quoted into the path.
        String rest = url.substring(colon + 1);
        int hash = rest.indexOf('#');
        String fragment = hash < 0 ? null : rest.substring(hash + 1);
        String part = hash < 0 ? rest : rest.substring(0, hash);
        try {
            return new URI(url.substring(0, colon), part, fragment);
        } catch (URISyntaxException unreadable) {
            return null;
        }
    }
}
