/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.renderer;

import com.retromod.legacyapi.minecraft.client.renderer.LegacyItemRendererCalls;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyItemRendererModel;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.client.event.RegisterItemModelsEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/** Registers {@code <namespace>:retromod_legacy_item} through NeoForge's item model event for the mod constructing now. */
final class LegacyItemModelTypes {

    // NeoForge refuses a type id registered twice, and a mod's constructors may each run the install.
    private static final Set<String> LISTENING = ConcurrentHashMap.newKeySet();

    private LegacyItemModelTypes() {}

    /** Listens on the constructing mod's bus; NeoForge posts the event once, before the first resource reload. */
    static void listen() {
        // Construction is the only time FML names an active mod.
        ModContainer container = ModLoadingContext.get().getActiveContainer();
        IEventBus bus = container == null ? null : container.getEventBus();
        if (bus == null) return;
        String namespace = container.getNamespace();
        if (!LISTENING.add(namespace)) return;
        // This copy's item models find a stack's renderer through NeoForge's client extensions.
        LegacyItemRendererCalls.useExtensions(IClientItemExtensions::of);
        bus.addListener(RegisterItemModelsEvent.class, event -> event.register(
                Identifier.fromNamespaceAndPath(namespace, LegacyItemRendererModel.TYPE_PATH), LegacyItemRendererModel.newType()));
    }
}
