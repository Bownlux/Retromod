/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.Text;

/** Client members a 1.21.1 mod still calls, which 1.21.11 to 26.2 removed or reshaped. */
public final class Test60SmallClientMembers implements Test {

    @Override
    public String description() {
        return "a translation check, a chat line, a filtered text field, and a screen's resize override";
    }

    @Override
    public TestResult run() {
        MinecraftClient client = MinecraftClient.getInstance();
        // 26.2 removed I18n.hasTranslation; the loaded language still answers.
        if (!I18n.hasTranslation("block.minecraft.stone")) return TestResult.fail("stone has no translation");
        // 26.1 removed addMessage(text) on the chat. A null receiver proves linkage only.
        try {
            ((ChatHud) null).addMessage(Text.literal("retromod"));
        } catch (IncompatibleClassChangeError | NoClassDefFoundError e) {
            return TestResult.fail("ChatHud.addMessage(text) did not link: " + e);
        } catch (RuntimeException linkedThenRefused) {
            // The null receiver.
        }
        // 26.1 removed the text field's filter; what it refuses must still be refused.
        TextFieldWidget field = new TextFieldWidget(client.textRenderer, 0, 0, 80, 20, Text.empty());
        StringBuilder seen = new StringBuilder();
        field.setText("12");
        field.setTextPredicate(text -> text.chars().allMatch(Character::isDigit));
        field.setChangedListener(text -> seen.append(text).append(';'));
        field.write("x");
        field.write("3");
        if (!field.getText().equals("123") || !seen.toString().equals("123;")) {
            return TestResult.fail("the field read " + field.getText() + " and reported " + seen);
        }
        // 1.21.11 dropped resize's client argument; an old override must still run.
        ResizeProbe probe = new ResizeProbe();
        try {
            ((Screen) probe).resize(client, 30, 20);
        } catch (RuntimeException afterTheOverride) {
            // A bare screen that was never opened may refuse to lay out; the override already ran.
        }
        return probe.seen.equals("30x20") ? TestResult.success() : TestResult.fail("the override saw " + probe.seen);
    }

    /** A screen that records the size its old resize override is given. */
    static final class ResizeProbe extends Screen {
        String seen = "";

        ResizeProbe() {
            super(Text.literal("retromod resize probe"));
        }

        @Override
        public void resize(MinecraftClient client, int width, int height) {
            seen = width + "x" + height;
            super.resize(client, width, height);
        }
    }
}
