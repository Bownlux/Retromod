/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 * 
 * Forge Event System API Compatibility Shim
 */
package com.retromod.shim.api.forge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.shim.forge.embedded.TickEventPhaseSynthetic;
import com.retromod.util.McReflect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Maps Forge event-system classes to their NeoForge equivalents (package moves, bus
 * registration, event result types).
 */
public class ForgeEventApiShim implements MinecraftVersionedApiShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-ForgeEventApiShim");
    
    @Override
    public String getShimName() {
        return "Forge Event System API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "1.20.1";
    }
    
    @Override
    public String getTargetVersion() {
        return "1.21.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "forge";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // These redirects map Forge names to NeoForge ones, so they only apply on a NeoForge
        // runtime; on Forge they'd rewrite mods to reference NeoForge classes that don't exist.
        if (!McReflect.isNeoForge()) {
            LOGGER.debug("Skipping Forge → NeoForge event API migration (runtime is not NeoForge)");
            return;
        }

        SyntheticEmbedder.registerClassResource(
                transformer,
                "com/retromod/shim/neoforge/embedded/ReloadListenerEventShim",
                ForgeEventApiShim.class);

        // Bulk package renames first; the hand-listed special cases below run after, so a rename
        // (LivingHurtEvent -> LivingDamageEvent, world/* -> level/*) wins over a same-name bulk entry.
        loadBulkEventRenames(transformer);
        loadBulkFmlRenames(transformer);

        // FMLJavaModLoadingContext.get().getModEventBus() is the first thing almost every Forge @Mod
        // constructor calls, and NeoForge DELETED the class (unlike the fml/** classes above that it
        // kept under the same name, so it's excluded from the bulk FML table). Redirect the Forge
        // reference to the name ForgeNeoForgeSynthetics registers the B4 bridge synthetic under, so
        // SyntheticEmbedder embeds that bridge per-mod (com/retromod/embedded/<mod>/). Without this the
        // Forge reference survives, the embedder never matches it, and the mod dies at construct with
        // NoClassDefFoundError (#85, Macaw's on NeoForge 26.2). Registered here (not in the runtime-only
        // Forge_1_20_to_NeoForge_1_21 shim) so it applies on BOTH the offline CLI/AOT batch and the
        // live NeoForge runtime; the API shims run on both paths, that migration shim only at runtime.
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext",
            "net/neoforged/fml/javafmlmod/FMLJavaModLoadingContext"
        );

        // event bus
        transformer.registerClassRedirect(
            "net/minecraftforge/eventbus/api/IEventBus",
            "net/neoforged/bus/api/IEventBus"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/eventbus/api/Event",
            "net/neoforged/bus/api/Event"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/eventbus/api/SubscribeEvent",
            "net/neoforged/bus/api/SubscribeEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/eventbus/api/EventPriority",
            "net/neoforged/bus/api/EventPriority"
        );
        
        // Event.Result -> EventResult
        transformer.registerClassRedirect(
            "net/minecraftforge/eventbus/api/Event$Result",
            "net/neoforged/bus/api/EventResult"
        );

        // common events
        registerTickEventSplit(transformer);
        registerClientReloadListenerMove(transformer);

        // entity events
        transformer.registerClassRedirect(
            "net/minecraftforge/event/entity/EntityEvent",
            "net/neoforged/neoforge/event/entity/EntityEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/event/entity/EntityJoinWorldEvent",
            "net/neoforged/neoforge/event/entity/EntityJoinLevelEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/event/entity/EntityJoinLevelEvent",
            "net/neoforged/neoforge/event/entity/EntityJoinLevelEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/event/entity/living/LivingEvent",
            "net/neoforged/neoforge/event/entity/living/LivingEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/event/entity/living/LivingDeathEvent",
            "net/neoforged/neoforge/event/entity/living/LivingDeathEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/event/entity/living/LivingHurtEvent",
            "net/neoforged/neoforge/event/entity/living/LivingDamageEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/event/entity/living/LivingDamageEvent",
            "net/neoforged/neoforge/event/entity/living/LivingDamageEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/event/entity/living/LivingDropsEvent",
            "net/neoforged/neoforge/event/entity/living/LivingDropsEvent"
        );
        
        // player events
        transformer.registerClassRedirect(
            "net/minecraftforge/event/entity/player/PlayerEvent",
            "net/neoforged/neoforge/event/entity/player/PlayerEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/event/entity/player/PlayerInteractEvent",
            "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/event/entity/player/PlayerInteractEvent$RightClickBlock",
            "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent$RightClickBlock"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/event/entity/player/PlayerInteractEvent$RightClickItem",
            "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent$RightClickItem"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/event/entity/player/PlayerInteractEvent$LeftClickBlock",
            "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent$LeftClickBlock"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/event/entity/player/ItemTooltipEvent",
            "net/neoforged/neoforge/event/entity/player/ItemTooltipEvent"
        );
        
        // world/level events
        transformer.registerClassRedirect(
            "net/minecraftforge/event/world/WorldEvent",
            "net/neoforged/neoforge/event/level/LevelEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/event/level/LevelEvent",
            "net/neoforged/neoforge/event/level/LevelEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/event/world/BlockEvent",
            "net/neoforged/neoforge/event/level/BlockEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/event/level/BlockEvent",
            "net/neoforged/neoforge/event/level/BlockEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/event/world/ChunkEvent",
            "net/neoforged/neoforge/event/level/ChunkEvent"
        );
        
        // client events
        transformer.registerClassRedirect(
            "net/minecraftforge/client/event/RenderGuiOverlayEvent",
            "net/neoforged/neoforge/client/event/RenderGuiLayerEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/client/event/RenderLevelStageEvent",
            "net/neoforged/neoforge/client/event/RenderLevelStageEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/client/event/InputEvent",
            "net/neoforged/neoforge/client/event/InputEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/client/event/InputEvent$Key",
            "net/neoforged/neoforge/client/event/InputEvent$Key"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/client/event/ScreenEvent",
            "net/neoforged/neoforge/client/event/ScreenEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/client/event/RenderPlayerEvent",
            "net/neoforged/neoforge/client/event/RenderPlayerEvent"
        );
        
        // registration events
        transformer.registerClassRedirect(
            "net/minecraftforge/event/RegistryEvent",
            "net/neoforged/neoforge/registries/RegisterEvent"
        );
        
        transformer.registerClassRedirect(
            "net/minecraftforge/registries/RegisterEvent",
            "net/neoforged/neoforge/registries/RegisterEvent"
        );
        
        // Forge bus -> NeoForge bus
        transformer.registerClassRedirect(
            "net/minecraftforge/common/MinecraftForge",
            "net/neoforged/neoforge/common/NeoForge"
        );
        
        transformer.registerFieldRedirect(
            "net/minecraftforge/common/MinecraftForge",
            "EVENT_BUS",
            "Lnet/minecraftforge/eventbus/api/IEventBus;",
            "net/neoforged/neoforge/common/NeoForge",
            "EVENT_BUS",
            "Lnet/neoforged/bus/api/IEventBus;"
        );
    }

    /**
     * Forge fired one tick event twice, carrying a {@code phase} of {@code START} then {@code END}.
     * NeoForge split that into two events, {@code Pre} and {@code Post}, made the shared parent
     * abstract, and deleted both the {@code phase} field and the {@code TickEvent.Phase} enum.
     *
     * <p>Three things therefore have to move together, or the mod is left worse off than before:
     *
     * <ul>
     *   <li>the listener's parameter, because NeoForge refuses a listener registered on an abstract
     *       event ("Register a listener to one of its subclasses instead") and fails the whole mod
     *       at construction, which is what stopped S33R More Food from starting (#184);</li>
     *   <li>the {@code phase} read, which no longer has a field to read;</li>
     *   <li>the {@code Phase} constants it is compared against, which no longer exist.</li>
     * </ul>
     *
     * <p>The listener goes to {@code Post}, because that is where Forge's {@code END} fired, and
     * {@code END} is the phase essentially every Forge mod guards on. A mod that guarded on
     * {@code START} instead now runs at the end of the tick rather than the beginning: later than
     * it asked for, but on the same tick, which is a far smaller change than not loading at all.
     * Verified against neoforge-26.2.0.0-beta.
     */
    void registerTickEventSplit(RetromodTransformer transformer) {
        // The Forge name, then the NeoForge parent whose concrete Post subclass a listener binds to.
        String[][] tickEvents = {
            {"net/minecraftforge/event/TickEvent$ServerTickEvent",
             "net/neoforged/neoforge/event/tick/ServerTickEvent"},
            // The client one lives with the other client events, not beside its siblings in
            // event/tick. There is no event/tick/ClientTickEvent to point at.
            {"net/minecraftforge/event/TickEvent$ClientTickEvent",
             "net/neoforged/neoforge/client/event/ClientTickEvent"},
            {"net/minecraftforge/event/TickEvent$LevelTickEvent",
             "net/neoforged/neoforge/event/tick/LevelTickEvent"},
            {"net/minecraftforge/event/TickEvent$PlayerTickEvent",
             "net/neoforged/neoforge/event/tick/PlayerTickEvent"},
        };

        transformer.registerSyntheticClass(
                TickEventPhaseSynthetic.INTERNAL, TickEventPhaseSynthetic.generate());
        transformer.registerClassRedirect(
                "net/minecraftforge/event/TickEvent$Phase", TickEventPhaseSynthetic.INTERNAL);

        // Forge's bare TickEvent parent has no NeoForge counterpart under any name, so it is left
        // alone rather than pointed at a class that was never there.
        for (String[] pair : tickEvents) {
            String forgeName = pair[0];
            String neoParent = pair[1];
            transformer.registerClassRedirect(forgeName, neoParent + "$Post");

            // The phase read is matched under every spelling the owner can carry, because the class
            // redirect may reach the owner before or after this bridge is consulted.
            for (String owner : new String[]{forgeName, neoParent, neoParent + "$Post"}) {
                transformer.registerFieldStaticBridge(
                        owner, "phase",
                        TickEventPhaseSynthetic.INTERNAL,
                        TickEventPhaseSynthetic.PHASE_OF_NAME, TickEventPhaseSynthetic.PHASE_OF_DESC,
                        TickEventPhaseSynthetic.PHASE_SET_NAME, TickEventPhaseSynthetic.PHASE_SET_DESC);
            }
        }

        // PlayerTickEvent.player became the inherited PlayerEvent.getEntity(). The field was final,
        // so a write from another class could not exist and the setter slot is unreachable.
        String playerDesc = "()Lnet/minecraft/world/entity/player/Player;";
        for (String owner : new String[]{
                "net/minecraftforge/event/TickEvent$PlayerTickEvent",
                "net/neoforged/neoforge/event/tick/PlayerTickEvent",
                "net/neoforged/neoforge/event/tick/PlayerTickEvent$Post"}) {
            transformer.registerFieldAccessorRedirect(
                    owner, "player", "getEntity", playerDesc, "getEntity", playerDesc);
        }

        LOGGER.debug("Registered the Forge tick event split (listener -> Post, phase -> bridge)");
    }

    /** Forge and early NeoForge client reload registration onto the 26.x sorted event. */
    void registerClientReloadListenerMove(RetromodTransformer transformer) {
        String forgeEvent =
                "net/minecraftforge/client/event/RegisterClientReloadListenersEvent";
        String oldNeoEvent =
                "net/neoforged/neoforge/client/event/RegisterClientReloadListenersEvent";
        String newNeoEvent =
                "net/neoforged/neoforge/client/event/AddClientReloadListenersEvent";
        transformer.registerClassRedirect(forgeEvent, newNeoEvent);
        transformer.registerClassRedirect(oldNeoEvent, newNeoEvent);
        for (String owner : new String[]{forgeEvent, oldNeoEvent, newNeoEvent}) {
            transformer.registerMethodRedirect(
                    owner, "registerReloadListener",
                    "(Lnet/minecraft/server/packs/resources/PreparableReloadListener;)V",
                    "com/retromod/shim/neoforge/embedded/ReloadListenerEventShim", "addListener",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", true);
        }
    }

    static final String EVENT_RENAMES_RESOURCE = "/retromod/forge-event-renames.json";

    static final String FML_RENAMES_RESOURCE = "/retromod/forge-fml-renames.json";

    /**
     * Forge event-package classes NeoForge kept under the same simple name. The hand-listed renames
     * in {@link #registerRedirects} run after this and override same-name entries. Package-private so
     * tests can drive it without a NeoForge runtime.
     */
    void loadBulkEventRenames(RetromodTransformer transformer) {
        loadRenameTable(transformer, EVENT_RENAMES_RESOURCE, "event");
    }

    /**
     * Forge {@code fml/**} classes NeoForge kept under the same name in {@code net/neoforged/fml/**}
     * (the {@code @Mod} lifecycle: FMLCommonSetupEvent, ModConfigEvent, ...). FMLJavaModLoadingContext
     * is not in this table because NeoForge deleted it; {@link #registerRedirects} redirects it to the
     * B4 bridge synthetic's name explicitly (a same-name bulk entry would point at a class that
     * doesn't exist on NeoForge).
     */
    void loadBulkFmlRenames(RetromodTransformer transformer) {
        loadRenameTable(transformer, FML_RENAMES_RESOURCE, "FML");
    }

    /**
     * Load a {@code {oldInternalName: newInternalName}} JSON table and register each as a class
     * redirect. On a load failure it logs and registers nothing rather than aborting the shim.
     */
    private void loadRenameTable(RetromodTransformer transformer, String resource, String label) {
        int count = 0;
        try (InputStream in = ForgeEventApiShim.class.getResourceAsStream(resource)) {
            if (in == null) {
                LOGGER.warn("Rename table {} not found - bulk {} renames disabled", resource, label);
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                    transformer.registerClassRedirect(e.getKey(), e.getValue().getAsString());
                    count++;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load bulk Forge → NeoForge {} renames: {}", label, e.toString());
        }
        LOGGER.info("Loaded {} bulk Forge → NeoForge {} class renames", count, label);
    }

    @Override
    public String[] getShimClasses() {
        return new String[] {
            TickEventPhaseSynthetic.INTERNAL.replace('/', '.'),
            "com.retromod.shim.neoforge.embedded.ReloadListenerEventShim"
        };
    }
}
