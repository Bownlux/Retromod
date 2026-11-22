// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.gui.screens;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.net.URI;
import net.minecraft.network.chat.Component;

public class ConfirmLinkScreen {
    public static net.minecraft.client.gui.components.Button.OnPress confirmLink(Screen lastScreen, URI uri) { throw new UnsupportedOperationException(); }
    public static net.minecraft.client.gui.components.Button.OnPress confirmLink(Screen lastScreen, URI uri, boolean trusted) { throw new UnsupportedOperationException(); }
    public static void confirmLinkNow(Screen lastScreen, URI uri) { throw new UnsupportedOperationException(); }
    public static void confirmLinkNow(Screen lastScreen, URI uri, boolean trusted) { throw new UnsupportedOperationException(); }
    public ConfirmLinkScreen(BooleanConsumer callback, URI uri, boolean trusted) { throw new UnsupportedOperationException(); }
    public ConfirmLinkScreen(BooleanConsumer callback, Component title, URI uri, boolean trusted) { throw new UnsupportedOperationException(); }
}
