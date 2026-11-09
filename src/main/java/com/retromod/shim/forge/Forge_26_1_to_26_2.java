/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.shim.common.Mc26_1To26_2CoreMoves;

import java.util.List;

/**
 * Forge 26.1 to 26.2 shim (shared Mojang-name class moves). Registered ahead of a Forge 26.2
 * release; the host-version gate keeps it inactive until a 26.2 host exists. Forge-API renames
 * follow when that release lands.
 */
public class Forge_26_1_to_26_2 implements VersionShim {

    private static final String TOOL_ACTIONS = "net/minecraftforge/common/ToolActions";
    private static final String FORGE_REGISTRIES = "net/minecraftforge/registries/ForgeRegistries";
    private static final String REGISTRY_KEYS = FORGE_REGISTRIES + "$Keys";
    private static final String RESOURCE_KEY = "Lnet/minecraft/resources/ResourceKey;";
    // Forge 1.20.2 moved the entity spawn packet out of PlayMessages; setCustomClientFactory takes the new one.
    static final String OLD_SPAWN_PACKET = "net/minecraftforge/network/PlayMessages$SpawnEntity";
    static final String SPAWN_PACKET = "net/minecraftforge/network/packets/SpawnEntity";

    @Override
    public String getShimName() {
        return "Forge 26.1 to 26.2";
    }

    @Override
    public String getSourceVersion() {
        return "26.1";
    }

    @Override
    public String getTargetVersion() {
        return "26.2";
    }

    @Override
    public String getModLoaderType() {
        return "forge";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        Mc26_1To26_2CoreMoves.register(transformer);
        // 26.2 made structure processor types map codecs, so a 1.21 mod's register event converts them too.
        // A chain that starts at 1.21 never passes the 1.20.5 step that registers the conversion otherwise.
        com.retromod.shim.common.LegacyRegistryMapCodecBridge.register(transformer);

        // Forge 26.2 (65.x) swapped EventBus 6 for EventBus 7: IEventBus is gone, so every old
        // Forge mod dies at construction on getModEventBus()/DeferredRegister.register(bus)/
        // MinecraftForge.EVENT_BUS. Bridge the old idiom onto BusGroup (#85). LexForge-only:
        // on a NeoForge runtime (or the CLI's neoforge target) the Forge->NeoForge migration owns
        // these idioms, and this shim only survives there by services-ordering luck (review).
        if (com.retromod.util.McReflect.isNeoForge()) {
            return;
        }
        ForgeEventBusSynthetics.register(transformer);
        // EventBus 7 also removed the old Event base that old mods' own events extend, and Event.Result.
        LegacyEventBaseBridge.register(transformer);
        // Forge 26.2 made EntityEvent, LivingEvent, PlayerEvent, LevelEvent and BlockEvent interfaces, which a
        // mod's own event cannot extend, so those extend rebuilt classes on the base above.
        if (LegacyEventBaseBridge.hostLacksEventBase()) LegacyInterfaceEventBases.register(transformer);
        // FML subscribes only EventBus 7 listeners, so a subscriber class with legacy ones is
        // subscribed from the end of its mod's constructor, where Forge 1.20.1 subscribed it.
        LegacyAutoSubscribeBridge.register(transformer);
        // EventBus 7 split each tick event into Pre and Post records, so old tick listeners and
        // their phase and side reads go through Retromod's rebuilt tick helpers.
        LegacyTickEventBridge.register(transformer);
        // Forge 26.2 replaced the HUD overlay registry and its render events with layers, so old overlays and their
        // listeners go to classes rebuilt inside each mod, the render events on the rebuilt event base above.
        LegacyGuiOverlayBridge.register(transformer);
        // EventBus 7 events keep no cancel flag: a listener cancels by returning true. Old cancel calls on host
        // events go to Retromod's per-thread state, and old listeners added to a cancellable bus return it.
        LegacyCancelBridge.register(transformer);
        // Some EventBus 7 events lost the result every Forge 1.20.1 event had, so old result calls on them
        // go to Retromod's helper, which passes a name tag or left click denial on the way the host takes it.
        LegacyForgeEventResultBridge.register(transformer);
        // EventBus 7 reads a byte priority, so an old EventPriority value fails registration.
        ForgeSubscribeAnnotations.register(transformer);
        // Old mods pick their client or server proxy with DistExecutor, often in a static
        // initializer, and Forge 26.2 removed it. Like every bridge after it, it asks ForgeHostShape.
        LegacyDistExecutorBridge.register(transformer);
        // Forge 26.2 dropped RenderLevelStageEvent for frame passes, so the old event is rebuilt inside each mod and
        // fired from one shared pass. Its listeners go through the rebuilt event base and dispatch registered above.
        LegacyForgeRenderBridge.register(transformer);
        // Forge 26.2 dropped the item color event and Minecraft 26.1 the block and item color handlers, so old
        // handlers become tint sources, and the old item event is rebuilt and posted after every block handler.
        LegacyForgeColorBridge.register(transformer);
        // Forge 26.2 dropped ChunkRenderTypeSet and the int[] quad API with 26.x's record quads, so render type sets,
        // render type groups, quad transformers and old quad constructors go to classes rebuilt inside each mod.
        LegacyForgeModelBridge.register(transformer);
        // Minecraft 1.21.4 removed BlockEntityWithoutLevelRenderer and Forge 26.2 getCustomRenderer, so old item renderers
        // extend a base rebuilt inside each mod and draw through an item model type each mod adds.
        LegacyForgeItemRendererBridge.register(transformer);
        // AddLayers lost getSkin(String), getSkins() and getRenderer(EntityType) for model types and getEntityRenderer.
        LegacyForgeAddLayersBridge.register(transformer);

        // Smaller removals with a same-meaning replacement on the host: menu opening and spawn
        // packets, the biome builders, ForgeMod's reach and step attributes, renamed tags, the
        // shield ToolAction, and the griefing hook, which now takes a ServerLevel.
        LegacyNetworkHooksBridge.register(transformer);
        LegacyBiomeGenerationBridge.register(transformer);
        LegacyForgeModAttributes.registerForge(transformer);
        LegacyForgeTagsBridge.register(transformer);
        if (ForgeHostShape.matches(List.of(TOOL_ACTIONS), List.of())) {
            com.retromod.shim.neoforge.LegacyItemAbilitiesBridge.registerForge(transformer);
        }
        LegacyForgeEventFactoryBridge.registerForge(transformer);
        // Enchantments became a data registry, and Forge's key list dropped its alias of the vanilla key.
        if (ForgeHostMembers.lacksField(REGISTRY_KEYS, "ENCHANTMENTS", ForgeHostShape.FIRST_CHECKED_VERSION)) {
            transformer.registerFieldRedirect(REGISTRY_KEYS, "ENCHANTMENTS", RESOURCE_KEY,
                    "net/minecraft/core/registries/Registries", "ENCHANTMENT", RESOURCE_KEY);
        }
        // ForgeRegistries dropped the enchantment registry itself, so old reads get a view over the world's data.
        if (ForgeHostMembers.lacksField(FORGE_REGISTRIES, "ENCHANTMENTS", ForgeHostShape.FIRST_CHECKED_VERSION)) {
            com.retromod.shim.api.forge.ForgeRegistryApiShim.registerForgeEnchantmentView(transformer);
        }
        // Forge 26.2 removed EntityInteract and SaplingGrowTreeEvent in favor of events with the same members.
        LegacyForgeEventRenames.register(transformer);
        // Its render and screen events dropped getEntity, a typed character's modifiers and getScrollDelta.
        LegacyForgeEventMembersBridge.register(transformer);
        // Forge 26.2 removed its folder pack; mod packs are vanilla ones, which old mods extend and test for.
        LegacyPathPackResourcesBridge.register(transformer);
        // Forge 26.2 has no FakePlayer, which Create, Quark and others use to act as a player.
        LegacyFakePlayerBridge.register(transformer);
        // Forge 26.2 can add no recipe book type, so an old RecipeBookType.create answers an existing one.
        LegacyRecipeBookTypeBridge.registerForge(transformer);
        // Forge 26.2's ArmPose.create also asks about the offhand, and its transformer poses from a render state.
        LegacyArmPoseBridge.registerForge(transformer);
        // Forge 26.2 removed the villager and wandering trader trade events; 26.x reads trades from data.
        LegacyTradeBridge.registerForge(transformer);
        // Forge 26.2 builds brewing from an event, so the static brewing registry is rebuilt on it.
        LegacyBrewingRegistryBridge.registerForge(transformer);
        // Forge 26.2 made ModLoader static and removed get(). A load warning is logged, because passing it
        // to Forge would stop every game event once loading ends.
        LegacyModLoaderBridge.registerForge(transformer);
        // Forge 26.2 also made ModList and LoadingModList static. A mod built for 1.21.1 or newer starts its chain
        // after the 1.21 shim that registers this bridge for older mods, so it is registered here as well.
        LegacyModListBridge.register(transformer);
        // Forge 26.2's ItemStack has no capNBT, which Sophisticated Core reads by reflection to compare stacks.
        LegacyCapNbtFieldAdapter.register(transformer);
        // Forge 26.2 split the attach event per holder kind and removed RegisterCapabilitiesEvent. It comes after
        // the bus bridge and the rebuilt event base, which old attach and register listeners go through.
        LegacyCapabilityEventBridge.register(transformer);
        // Forge 26.2 passes registries to every capability, handler and entity save and load, and asks an item
        // for getCapabilityProvider instead of initCapabilities. Old providers, calls and items get both.
        LegacyNbtSerializableBridge.register(transformer);
        LegacyItemCapabilityHookAdapter.register(transformer);
        // Forge 26.2 dropped a block entity's persistent data; it is rebuilt beside the block entity and saved
        // under ForgeData from the first mod class under a Minecraft block entity.
        LegacyBlockEntityDataBridge.register(transformer);
        // CraftingHelper is gone from Forge 1.21.11 on, and Forge 26.2 reads ingredients through codecs from its own
        // serializer registry. Old serializers are registered there and old ingredients answer serializer().
        LegacyIngredientSerializerBridge.register(transformer);
        // Forge's stack extension answers the crafting remainder as a template now.
        com.retromod.shim.neoforge.LegacyCraftingRemainderBridge.registerForge(transformer);
        // The old compound, difference, intersection and NBT ingredient types moved with them.
        LegacyIngredientTypesBridge.register(transformer);
        // A custom client factory names Forge's spawn packet in its lambda, which loads while the entity type registers.
        if (ForgeHostShape.matches(List.of(SPAWN_PACKET), List.of(OLD_SPAWN_PACKET))) {
            transformer.registerClassRedirect(OLD_SPAWN_PACKET, SPAWN_PACKET);
        }
    }
}
