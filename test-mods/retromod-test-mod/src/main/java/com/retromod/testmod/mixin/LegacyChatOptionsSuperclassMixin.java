/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.ChatOptionsScreen;
import net.minecraft.client.gui.screen.option.SimpleOptionsScreen;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;

/** Load-time regression for the options-screen superclass removed on 26.x hosts. */
@Mixin(ChatOptionsScreen.class)
public abstract class LegacyChatOptionsSuperclassMixin extends SimpleOptionsScreen {

    protected LegacyChatOptionsSuperclassMixin(Screen parent, GameOptions options, Text title,
            SimpleOption<?>[] optionList) {
        super(parent, options, title, optionList);
    }

    @Override
    protected void init() {
        super.init();
    }
}
