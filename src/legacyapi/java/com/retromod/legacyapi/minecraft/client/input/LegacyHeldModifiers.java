/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.input;

import net.minecraft.client.Minecraft;

/** The modifier keys held now, in the GLFW bits a typed character event used to carry. */
public final class LegacyHeldModifiers {

    // GLFW's modifier bits, which old mods compare against as compiled-in constants.
    static final int SHIFT = 0x1;
    static final int CONTROL = 0x2;
    static final int ALT = 0x4;

    private LegacyHeldModifiers() {}

    /** {@code CharacterTyped.getModifiers()}: Shift, Control and Alt as held while the character arrives. */
    public static int modifiers(Object event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return 0;
        // The event no longer says which modifiers were down, so the keyboard answers for the moment it is read.
        int held = 0;
        if (minecraft.hasShiftDown()) held |= SHIFT;
        if (minecraft.hasControlDown()) held |= CONTROL;
        if (minecraft.hasAltDown()) held |= ALT;
        return held;
    }
}
