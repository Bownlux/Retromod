/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.network.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;

/** The getters of the text classes mods for 1.18.2 and older built, read from the component that replaced them. */
public final class LegacyOldTexts {

    private LegacyOldTexts() {}

    /** {@code translatable.getKey()}: the translation key, or the plain text of a component that has none. */
    public static String getKey(Component text) {
        return text.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : text.getString();
    }

    /** {@code translatable.getArgs()}: the translation's arguments, or none. */
    public static Object[] getArgs(Component text) {
        return text.getContents() instanceof TranslatableContents translatable ? translatable.getArgs() : new Object[0];
    }

    /** {@code translatable.getArgument(index)}: one argument, as the old getter returned it. */
    public static Object getArgument(Component text, int index) {
        return getArgs(text)[index];
    }

    /** {@code component.getContents()} as mods before 1.19 called it: a literal's own text, and nothing for any other component. */
    public static String contentsText(Component text) {
        return text.getContents() instanceof PlainTextContents plain ? plain.text() : "";
    }

    /** A read of the old literal's {@code text} field: the same as {@link #getText}. */
    public static String textField(Object text) {
        return getText((Component) text);
    }

    /** A read of the old translatable's {@code key} field. */
    public static String keyField(Object text) {
        return getKey((Component) text);
    }

    /** A read of the old translatable's {@code args} field. */
    public static Object[] argsField(Object text) {
        return getArgs((Component) text);
    }

    /** {@code literal.getText()}: the literal's own text, without its siblings. */
    public static String getText(Component text) {
        return text.getContents() instanceof PlainTextContents plain ? plain.text() : text.getString();
    }
}
