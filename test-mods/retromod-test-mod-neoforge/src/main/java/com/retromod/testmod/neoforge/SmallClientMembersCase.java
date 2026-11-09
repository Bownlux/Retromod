/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/** Client members a 1.21.1 mod still calls, which 1.21.11 to 26.2 removed or reshaped. */
final class SmallClientMembersCase {

    private SmallClientMembersCase() {}

    /** A translation check, a chat line, a filtered text field, and a screen's resize override. */
    static String run() {
        Minecraft minecraft = Minecraft.getInstance();
        // 26.2 removed I18n.exists; the language still answers.
        if (!I18n.exists("block.minecraft.stone")) return "fail: stone has no translation";
        // 26.1 removed addMessage(text) on the chat. A null receiver proves linkage only.
        try {
            ((ChatComponent) null).addMessage(Component.literal("retromod"));
        } catch (IncompatibleClassChangeError | NoClassDefFoundError e) {
            return "fail: ChatComponent.addMessage(text) did not link: " + e;
        } catch (RuntimeException linkedThenRefused) {
            // The null receiver.
        }
        // 26.1 removed the edit box's filter; what it refuses must still be refused.
        EditBox box = new EditBox(minecraft.font, 0, 0, 80, 20, Component.empty());
        StringBuilder seen = new StringBuilder();
        box.setValue("12");
        box.setFilter(text -> text.chars().allMatch(Character::isDigit));
        box.setResponder(text -> seen.append(text).append(';'));
        box.insertText("x");
        box.insertText("3");
        if (!box.getValue().equals("123") || !seen.toString().equals("123;")) {
            return "fail: the box read " + box.getValue() + " and reported " + seen;
        }
        // 1.21.11 dropped resize's Minecraft argument; an old override must still run.
        ResizeProbe probe = new ResizeProbe();
        try {
            ((Screen) probe).resize(minecraft, 30, 20);
        } catch (RuntimeException afterTheOverride) {
            // A bare screen that was never opened may refuse to lay out; the override already ran.
        }
        return probe.seen.equals("30x20") ? "success" : "fail: the override saw " + probe.seen;
    }

    /** A screen that records the size its old resize override is given. */
    static final class ResizeProbe extends Screen {
        String seen = "";

        ResizeProbe() {
            super(Component.literal("retromod resize probe"));
        }

        @Override
        public void resize(Minecraft minecraft, int width, int height) {
            seen = width + "x" + height;
            super.resize(minecraft, width, height);
        }
    }
}
