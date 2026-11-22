/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import java.util.function.BiFunction;

/** Forge's {@code ConfigScreenHandler.ConfigScreenFactory} on NeoForge, which finds a mod's config screen as an {@code IConfigScreenFactory}. */
public final class LegacyConfigScreenFactory implements IConfigScreenFactory {

    private final BiFunction<Minecraft, Screen, Screen> screenFunction;

    public LegacyConfigScreenFactory(BiFunction<Minecraft, Screen, Screen> screenFunction) {
        this.screenFunction = screenFunction;
    }

    /** The record accessor Forge's factory had. */
    public BiFunction<Minecraft, Screen, Screen> screenFunction() {
        return screenFunction;
    }

    @Override
    public Screen createScreen(ModContainer container, Screen parent) {
        return screenFunction.apply(Minecraft.getInstance(), parent);
    }
}
