/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Names the old mod-bus events whose handlers a Forge host no longer accepts on a mod's own bus group. */
public final class LegacyModBusEvents {

    private static final String CLIENT = "net/minecraftforge/client/event/";

    // Forge 1.20.1 mod-bus events that Forge 26.2 (65.0.0) and 26.3 (66.1.2) keep under the same name with a game
    // BUS of their own, checked against both hosts' universal and fmlcore jars, plus AddGuiOverlayLayersEvent, which
    // Forge 1.21.1 mods subscribe on the mod bus. The CLI reads no Forge classes, so this list answers for it.
    static final Set<String> LEFT_ON_FORGE_26 = Set.of(
            CLIENT + "AddGuiOverlayLayersEvent",
            CLIENT + "EntityRenderersEvent",
            CLIENT + "EntityRenderersEvent$AddLayers",
            CLIENT + "EntityRenderersEvent$CreateSkullModels",
            CLIENT + "EntityRenderersEvent$RegisterLayerDefinitions",
            CLIENT + "EntityRenderersEvent$RegisterRenderers",
            CLIENT + "ModelEvent$BakingCompleted",
            CLIENT + "ModelEvent$ModifyBakingResult",
            CLIENT + "ModelEvent$RegisterGeometryLoaders",
            CLIENT + "RegisterClientReloadListenersEvent",
            CLIENT + "RegisterClientTooltipComponentFactoriesEvent",
            CLIENT + "RegisterColorHandlersEvent",
            CLIENT + "RegisterColorHandlersEvent$Block",
            CLIENT + "RegisterColorHandlersEvent$ColorResolvers",
            CLIENT + "RegisterEntitySpectatorShadersEvent",
            CLIENT + "RegisterItemDecorationsEvent",
            CLIENT + "RegisterKeyMappingsEvent",
            CLIENT + "RegisterNamedRenderTypesEvent",
            CLIENT + "RegisterParticleProvidersEvent",
            CLIENT + "RegisterTextureAtlasSpriteLoadersEvent",
            CLIENT + "TextureStitchEvent",
            CLIENT + "TextureStitchEvent$Post",
            CLIENT + "sound/SoundEngineLoadEvent",
            "net/minecraftforge/event/AddPackFindersEvent",
            "net/minecraftforge/event/BuildCreativeModeTabContentsEvent",
            "net/minecraftforge/event/entity/EntityAttributeCreationEvent",
            "net/minecraftforge/event/entity/EntityAttributeModificationEvent",
            "net/minecraftforge/event/entity/SpawnPlacementRegisterEvent",
            "net/minecraftforge/registries/DataPackRegistryEvent",
            "net/minecraftforge/registries/DataPackRegistryEvent$NewRegistry",
            "net/minecraftforge/registries/NewRegistryEvent");

    // Removed mod-bus events a bridge rebuilt with a bus of their own; they leave with the transformer's registrations.
    private static final Map<RetromodTransformer, Set<String>> REBUILT = Collections.synchronizedMap(new WeakHashMap<>());

    private LegacyModBusEvents() {}

    /** Marks a removed mod-bus event a bridge rebuilds on a bus of its own, so classes that subscribe it leave FML too. */
    public static void registerRebuilt(RetromodTransformer transformer, String oldEvent) {
        Set<String> rebuilt;
        synchronized (REBUILT) {
            rebuilt = REBUILT.computeIfAbsent(transformer, t -> ConcurrentHashMap.newKeySet());
        }
        if (rebuilt.add(oldEvent) && rebuilt.size() == 1) {
            transformer.onRegistrationReset(() -> REBUILT.remove(transformer));
        }
    }

    /** Whether an old handler of {@code event} on a mod's bus group would be refused on this host. */
    public static boolean leftModBus(RetromodTransformer transformer, String event) {
        Set<String> rebuilt = REBUILT.get(transformer);
        if (rebuilt != null && rebuilt.contains(event)) return true;
        // Only Forge's own events moved; a mod's own events follow the rebuilt event base.
        return event.startsWith("net/minecraftforge/") && ForgeHostShape.isOutsideModBus(event, LEFT_ON_FORGE_26);
    }
}
