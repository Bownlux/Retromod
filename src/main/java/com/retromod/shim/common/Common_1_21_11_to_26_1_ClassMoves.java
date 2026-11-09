/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.Opcodes;

import java.util.List;
import java.util.Set;

/** Maps the vanilla classes Mojang moved or renamed from 1.21.11 to 26.1, for every loader (#64). */
public final class Common_1_21_11_to_26_1_ClassMoves {

    private Common_1_21_11_to_26_1_ClassMoves() {}

    /** Moves the sprite particle factory from {@code ParticleEngine} to {@code ParticleResources}. */
    public static void registerParticleRegistrationMove(RetromodTransformer transformer) {
        transformer.registerClassRedirect("net/minecraft/client/particle/ParticleEngine$SpriteParticleRegistration",
                "net/minecraft/client/particle/ParticleResources$SpriteParticleRegistration");
    }

    /** Registers every vanilla and blaze3d move; loader API renames stay in each loader's shim. */
    public static void register(RetromodTransformer transformer) {
        // The Gson adapter that ResourceLocation.Serializer moves to, built on the game's own Gson.
        LegacyIdentifierSerializer.register(transformer);
        registerIdentifierMemberRenames(transformer);
        // 26.1 removed the single-argument is() overloads from BlockState, ItemStack and FluidState,
        // so block placement, tag checks and item comparisons failed with NoSuchMethodError. A per-mod
        // synthetic bridges them.
        IsOverloadBridgeSynthetic.register(transformer);
        // The loot entry type constants and getType() went with the type records the line above stands in for.
        LegacyLootEntryTypeBridge.register(transformer);
        // TagsProvider$TagAppender is gone from 26.1 too, and Registrate's provider types name it at startup.
        LegacyDatagenStandIns.registerVanilla(transformer);

        // Deleted item bases that mods extend. A class move cannot express a deletion, and
        // pointing one at the nearest surviving name produced an inheritance error instead (#260).
        RemovedItemBaseBridge.register(transformer);
        RemovedBaseToParentBridge.register(transformer);

        // Biome fog, sky, and water fog colors, particles, sounds, and music became environment
        // attributes in 1.21.11, so an old code-built biome lost its setters.
        LegacyBiomeEffectsBridge.register(transformer);
        LegacyBiomeSpawnsBridge.register(transformer);
        LegacyPaintingVariantBridge.register(transformer);
        // Random patch features became placement data; a mod's own patch feature keeps the old configuration.
        LegacyRandomPatchApi.register(transformer);
        LegacyArmorLayerBridge.register(transformer);
        LegacyPoseGuiBridge.register(transformer);
        LegacyEitherHolderBridge.register(transformer);
        // Mods build item renderers while items register, before 26.x has a model manager to ask.
        LegacyEntityModelsBridge.register(transformer);
        // A mod's own sprite atlas, such as JEI's GUI sprites, built on the removed TextureAtlasHolder.
        LegacyTextureAtlasHolderBridge.register(transformer);
        // A container component's slots hold item templates, not stacks.
        LegacyContainerSlotBridge.register(transformer);
        // getSkinMap(), the private renderer map and old RenderLayer subclasses, through which mods add player layers.
        LegacyPlayerLayersBridge.register(transformer);
        // ItemRenderer and the dispatcher's old entity draw, which old layers and renderers call while they draw.
        LegacyItemDrawBridge.register(transformer);
        // ItemProperties, which client setup calls so that model overrides can pick a model by a mod's own value.
        LegacyItemPropertiesBridge.register(transformer);
        // Block model elements, faces and rotations, which old geometry code reads while it bakes.
        LegacyBlockElementBridge.register(transformer);
        // 26.1 dropped the connection's own id, which JEI reads while joining a world.
        LegacyClientConnectionBridge.registerConnectionId(transformer);
        registerParticleRegistrationMove(transformer);

        registerOfficialEntityTypeBuildBridge(transformer);
        registerLegacyContainerInput(transformer);
        // World gen options left the level data for the server's world gen settings.
        LegacyWorldGenOptionsAdapter.register(transformer);

        // GuiGraphics -> GuiGraphicsExtractor
        transformer.registerClassRedirect(
            "net/minecraft/client/gui/GuiGraphics",
            "net/minecraft/client/gui/GuiGraphicsExtractor");

        // RenderType + RenderTypes moved into a rendertype sub-package.
        transformer.registerClassRedirect(
            "net/minecraft/client/renderer/RenderType",
            "net/minecraft/client/renderer/rendertype/RenderType");
        transformer.registerClassRedirect(
            "net/minecraft/client/renderer/RenderTypes",
            "net/minecraft/client/renderer/rendertype/RenderTypes");

        // BlockAndTintGetter became client-only and moved to client/renderer/block/.
        transformer.registerClassRedirect(
            "net/minecraft/world/level/BlockAndTintGetter",
            "net/minecraft/client/renderer/block/BlockAndTintGetter");

        // ItemNameBlockItem folded into BlockItem in 26.x; same (Block, Item.Properties)
        // ctor, so an extending mod loads. Place-time custom naming is lost.
        transformer.registerClassRedirect(
            "net/minecraft/world/item/ItemNameBlockItem",
            "net/minecraft/world/item/BlockItem");

        // ResourceKey.location() -> identifier() in 26.x. A method rename, not a redirect:
        // the call site is a method reference (ResourceKey::location in Resourceful Lib's
        // ExtraByteCodecs) that the direct-call pass can't reach.
        transformer.registerMethodRename(
            "net/minecraft/resources/ResourceKey", "location", "identifier");
        // Only a server level has game rules, and they are read with get(rule), not typed getters.
        LegacyGameRuleCallBridge.register(transformer);
        // 26.1 moved the firework recipes' ingredient constants and shape table into recipe data.
        LegacyFireworkConstantBridge.register(transformer);
        // ItemStack.getItemHolder() is the typed instance's typeHolder() in 26.1.
        transformer.registerMethodRename(
            "net/minecraft/world/item/ItemStack", "getItemHolder", "typeHolder");

        // ChunkPos(long) ctor removed in 26.x; rewrite to the static ChunkPos.unpack(long).
        transformer.registerConstructorRedirect(
            "net/minecraft/world/level/ChunkPos", "(J)V",
            "net/minecraft/world/level/ChunkPos", "unpack",
            "(J)Lnet/minecraft/world/level/ChunkPos;");
        // The BlockPos constructor went too; containing(pos) takes the same section coordinates.
        transformer.registerConstructorRedirect(
            "net/minecraft/world/level/ChunkPos", "(Lnet/minecraft/core/BlockPos;)V",
            "net/minecraft/world/level/ChunkPos", "containing",
            "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/ChunkPos;");
        // ChunkPos became a record, so its public x and z fields went private behind x() and z().
        // They were final, so a read is all an old mod can do and no setter is needed.
        String chunkPos = "net/minecraft/world/level/ChunkPos";
        transformer.registerFieldAccessorRedirect(chunkPos, "x", "x", "()I", null, null);
        transformer.registerFieldAccessorRedirect(chunkPos, "z", "z", "()I", null, null);
        // The packed-long helpers took the name pack and pack the same bits. TerraBlender keys
        // its region layer cache with asLong(x, z) while it builds biomes for a new world.
        transformer.registerMethodRedirect(chunkPos, "asLong", "(II)J", chunkPos, "pack", "(II)J");
        transformer.registerMethodRedirect(chunkPos, "asLong", "(Lnet/minecraft/core/BlockPos;)J",
                chunkPos, "pack", "(Lnet/minecraft/core/BlockPos;)J");
        transformer.registerMethodRedirect(chunkPos, "toLong", "()J", chunkPos, "pack", "()J");

        // Painting + PaintingVariant moved into an entity/decoration/painting sub-package in 26.1
        // (verified: absent at the old path, present at the new path on 26.1 and 26.2). Fixes any mod
        // referencing them AND the @Mixin(Painting) target of Deeper and Darker's PaintingMixin (#28).
        transformer.registerClassRedirect(
            "net/minecraft/world/entity/decoration/Painting",
            "net/minecraft/world/entity/decoration/painting/Painting");
        transformer.registerClassRedirect(
            "net/minecraft/world/entity/decoration/PaintingVariant",
            "net/minecraft/world/entity/decoration/painting/PaintingVariant");

        // Husk moved into an entity/monster/zombie sub-package in 26.1 (verified: old monster/Husk
        // absent, monster/zombie/Husk present on 26.1 and 26.2).
        transformer.registerClassRedirect(
            "net/minecraft/world/entity/monster/Husk",
            "net/minecraft/world/entity/monster/zombie/Husk");

        // MobSpawnType was renamed to EntitySpawnReason in 26.1 (verified: MobSpawnType absent,
        // EntitySpawnReason present on 26.1). Very common in mob-spawn checkXxxSpawnRules signatures.
        transformer.registerClassRedirect(
            "net/minecraft/world/entity/MobSpawnType",
            "net/minecraft/world/entity/EntitySpawnReason");

        // Neutralize the imperative RenderSystem state setters deleted in the blaze3d
        // GpuDevice/RenderPipeline refactor.
        RemovedRenderStateNeutralize.register(transformer);

        registerRegistryValueGetterRename(transformer);
        registerSoundEventLocation(transformer, "net/minecraft/resources/Identifier");
        registerRegistryLookupRenames(transformer);
        registerClientAccessorRenames26_1(transformer);
        registerCorpus26xDescriptorAdaptations(transformer);
        LegacyGuiDrawBridge.register(transformer);
        LegacyGuiExtractBridge.register(transformer);
        LegacyDepthBlendBridge.registerDepthState(transformer);
        // The chunk's moved flag, the spawn entry codec and the villager type constants, for every loader on 26.1.
        LegacyWorldgenMembersBridge.registerOlder(transformer);

        // BlockState.getBlockHolder() took the TypedInstance spelling typeHolder() in 26.1 (same
        // Holder return; getBlockHolder is absent on 26.1.2, 26.2 and 26.3). The fuzzy resolver
        // only reached 60% on it, so Hold My Items' block queries died NoSuchMethodError.
        for (String owner : new String[]{"net/minecraft/world/level/block/state/BlockState",
                "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase"}) {
            transformer.registerMethodRedirect(owner, "getBlockHolder", "()Lnet/minecraft/core/Holder;",
                    owner, "typeHolder", "()Lnet/minecraft/core/Holder;");
        }
    
        // Every loader needs this: the old RULE_* spelling is the pre-26.1 Mojang name, so a
        // NeoForge or Forge mod from 1.20 or 1.21 carries it just as a Fabric one does.
        registerGameRuleFieldRenames(transformer);
        registerPathTypeRenames26_1(transformer);
        registerTagRenames26_1(transformer);
}

    /** The PathType constants 26.1 renamed; each keeps its ordinal and malus. */
    public static void registerPathTypeRenames26_1(RetromodTransformer transformer) {
        String pathType = "net/minecraft/world/level/pathfinder/PathType";
        String[][] renames = {
            {"DAMAGE_FIRE", "FIRE"}, {"DANGER_FIRE", "FIRE_IN_NEIGHBOR"},
            {"DAMAGE_OTHER", "DAMAGING"}, {"DANGER_OTHER", "DAMAGING_IN_NEIGHBOR"},
            {"DANGER_POWDER_SNOW", "ON_TOP_OF_POWDER_SNOW"}, {"DANGER_TRAPDOOR", "ON_TOP_OF_TRAPDOOR"}};
        for (String[] rename : renames) {
            transformer.registerFieldRedirect(pathType, rename[0], pathType, rename[1]);
        }
    }

    /** The block and item tags 26.1 renamed, with the same entries or the same job under the new name. */
    public static void registerTagRenames26_1(RetromodTransformer transformer) {
        String[][] blockTags = {
                {"BAMBOO_PLANTABLE_ON", "SUPPORTS_BAMBOO"},
                {"BIG_DRIPLEAF_PLACEABLE", "SUPPORTS_BIG_DRIPLEAF"},
                {"DRY_VEGETATION_MAY_PLACE_ON", "SUPPORTS_DRY_VEGETATION"},
                {"MUSHROOM_GROW_BLOCK", "OVERRIDES_MUSHROOM_LIGHT_REQUIREMENT"},
                {"SMALL_DRIPLEAF_PLACEABLE", "SUPPORTS_SMALL_DRIPLEAF"},
                {"SNOW_LAYER_CAN_SURVIVE_ON", "SUPPORT_OVERRIDE_SNOW_LAYER"},
                {"SNOW_LAYER_CANNOT_SURVIVE_ON", "CANNOT_SUPPORT_SNOW_LAYER"}};
        String tagKey = "Lnet/minecraft/tags/TagKey;";
        for (String[] rename : blockTags) {
            transformer.registerFieldRedirect("net/minecraft/tags/BlockTags", rename[0], tagKey,
                    "net/minecraft/tags/BlockTags", rename[1], tagKey);
        }
        // The cauldron reads the same items from the new tag; a dye check by tag keeps working.
        transformer.registerFieldRedirect("net/minecraft/tags/ItemTags", "DYEABLE", tagKey,
                "net/minecraft/tags/ItemTags", "CAULDRON_CAN_REMOVE_DYE", tagKey);
    }

    /**
     * Repairs the GameRules constant rename that 26.1 made.
     *
     * <p>This runs for every loader. It was Fabric only, on the reasoning that a Mojang-named
     * loader already carries the current names, which holds only for a mod built after the rename.
     * A 1.20 or 1.21 NeoForge or Forge mod writes {@code GameRules.RULE_MOBGRIEFING} because that
     * was the Mojang name then, and it died with {@code NoSuchFieldError} on 26.x. The test mod
     * catches it, which is how this was found; game rules are common enough in modded code that it
     * is worth stating plainly that the old spelling is a Mojang name too, not a Fabric artifact.
     *
     * <p>Fabric reaches it by a second route: Fabric kept the same field ids, so a current Fabric
     * mod still carries names such as {@code field_19388}, and the base map expands those ids to
     * the old {@code RULE_*} spelling before this owner-scoped pass selects the 26.1 field. Every
     * entry below was matched through the 1.21.11 intermediary mapping and verified on the 26.1.2
     * host class.
     *
     * <p>Retromod refuses {@code RULE_DOFIRETICK}, whose boolean contract became a radius, and the
     * three negative-to-positive rules {@code RULE_DISABLE_ELYTRA_MOVEMENT_CHECK},
     * {@code RULE_DISABLE_PLAYER_MOVEMENT_CHECK}, and {@code RULE_DISABLE_RAIDS}. A field rename
     * cannot preserve those values without an inversion adapter.</p>
     */
    public static void registerGameRuleFieldRenames(RetromodTransformer transformer) {
        String owner = "net/minecraft/world/level/gamerules/GameRules";
        String descriptor = "Lnet/minecraft/world/level/gamerules/GameRule;";
        String[][] renames = {
            {"RULE_DAYLIGHT", "ADVANCE_TIME"},
            {"RULE_WEATHER_CYCLE", "ADVANCE_WEATHER"},
            {"RULE_DOBLOCKDROPS", "BLOCK_DROPS"},
            {"RULE_BLOCK_EXPLOSION_DROP_DECAY", "BLOCK_EXPLOSION_DROP_DECAY"},
            {"RULE_COMMANDBLOCKOUTPUT", "COMMAND_BLOCK_OUTPUT"},
            {"RULE_DROWNING_DAMAGE", "DROWNING_DAMAGE"},
            {"RULE_ENDER_PEARLS_VANISH_ON_DEATH", "ENDER_PEARLS_VANISH_ON_DEATH"},
            {"RULE_DOENTITYDROPS", "ENTITY_DROPS"},
            {"RULE_FALL_DAMAGE", "FALL_DAMAGE"},
            {"RULE_FIRE_DAMAGE", "FIRE_DAMAGE"},
            {"RULE_FORGIVE_DEAD_PLAYERS", "FORGIVE_DEAD_PLAYERS"},
            {"RULE_FREEZE_DAMAGE", "FREEZE_DAMAGE"},
            {"RULE_GLOBAL_SOUND_EVENTS", "GLOBAL_SOUND_EVENTS"},
            {"RULE_DO_IMMEDIATE_RESPAWN", "IMMEDIATE_RESPAWN"},
            {"RULE_KEEPINVENTORY", "KEEP_INVENTORY"},
            {"RULE_LAVA_SOURCE_CONVERSION", "LAVA_SOURCE_CONVERSION"},
            {"RULE_LIMITED_CRAFTING", "LIMITED_CRAFTING"},
            {"RULE_LOGADMINCOMMANDS", "LOG_ADMIN_COMMANDS"},
            {"RULE_COMMAND_MODIFICATION_BLOCK_LIMIT", "MAX_BLOCK_MODIFICATIONS"},
            {"RULE_MAX_COMMAND_FORK_COUNT", "MAX_COMMAND_FORKS"},
            {"RULE_MAX_COMMAND_CHAIN_LENGTH", "MAX_COMMAND_SEQUENCE_LENGTH"},
            {"RULE_MAX_ENTITY_CRAMMING", "MAX_ENTITY_CRAMMING"},
            {"RULE_MINECART_MAX_SPEED", "MAX_MINECART_SPEED"},
            {"RULE_SNOW_ACCUMULATION_HEIGHT", "MAX_SNOW_ACCUMULATION_HEIGHT"},
            {"RULE_DOMOBLOOT", "MOB_DROPS"},
            {"RULE_MOB_EXPLOSION_DROP_DECAY", "MOB_EXPLOSION_DROP_DECAY"},
            {"RULE_MOBGRIEFING", "MOB_GRIEFING"},
            {"RULE_NATURAL_REGENERATION", "NATURAL_HEALTH_REGENERATION"},
            {"RULE_PLAYERS_NETHER_PORTAL_CREATIVE_DELAY", "PLAYERS_NETHER_PORTAL_CREATIVE_DELAY"},
            {"RULE_PLAYERS_NETHER_PORTAL_DEFAULT_DELAY", "PLAYERS_NETHER_PORTAL_DEFAULT_DELAY"},
            {"RULE_PLAYERS_SLEEPING_PERCENTAGE", "PLAYERS_SLEEPING_PERCENTAGE"},
            {"RULE_PROJECTILESCANBREAKBLOCKS", "PROJECTILES_CAN_BREAK_BLOCKS"},
            {"RULE_RANDOMTICKING", "RANDOM_TICK_SPEED"},
            {"RULE_REDUCEDDEBUGINFO", "REDUCED_DEBUG_INFO"},
            {"RULE_SPAWN_RADIUS", "RESPAWN_RADIUS"},
            {"RULE_SENDCOMMANDFEEDBACK", "SEND_COMMAND_FEEDBACK"},
            {"RULE_ANNOUNCE_ADVANCEMENTS", "SHOW_ADVANCEMENT_MESSAGES"},
            {"RULE_SHOWDEATHMESSAGES", "SHOW_DEATH_MESSAGES"},
            {"RULE_DOMOBSPAWNING", "SPAWN_MOBS"},
            {"RULE_DO_PATROL_SPAWNING", "SPAWN_PATROLS"},
            {"RULE_DOINSOMNIA", "SPAWN_PHANTOMS"},
            {"RULE_DO_TRADER_SPAWNING", "SPAWN_WANDERING_TRADERS"},
            {"RULE_DO_WARDEN_SPAWNING", "SPAWN_WARDENS"},
            {"RULE_SPECTATORSGENERATECHUNKS", "SPECTATORS_GENERATE_CHUNKS"},
            {"RULE_DO_VINES_SPREAD", "SPREAD_VINES"},
            {"RULE_TNT_EXPLOSION_DROP_DECAY", "TNT_EXPLOSION_DROP_DECAY"},
            {"RULE_UNIVERSAL_ANGER", "UNIVERSAL_ANGER"},
            {"RULE_WATER_SOURCE_CONVERSION", "WATER_SOURCE_CONVERSION"}
        };
        // Registered under both spellings of the owner. A mod built before 26.1 names
        // world/level/GameRules and the class move carries it to gamerules/GameRules, but a field
        // redirect is keyed on the owner it is asked about, and which of the two that is depends on
        // whether the remap has already run on this pass. Keying both means the rename lands either
        // way, and the pre-move entry also repoints the owner itself, so one rewrite does both.
        String legacyOwner = "net/minecraft/world/level/GameRules";
        String legacyDescriptor = "Lnet/minecraft/world/level/GameRules$Key;";
        for (String[] rename : renames) {
            transformer.registerFieldRedirect(
                    owner, rename[0], descriptor,
                    owner, rename[1], descriptor);
            transformer.registerFieldRedirect(
                    legacyOwner, rename[0], legacyDescriptor,
                    owner, rename[1], descriptor);
        }
    }

    /** Registers the official-name EntityType build bridge. Forge and the 1.21.2 shims call it directly. */
    public static void registerOfficialEntityTypeBuildBridge(RetromodTransformer transformer) {
        // 1.21.2 changed EntityType.Builder.build(String) to build(ResourceKey). Fabric mods remapped
        // to official names and Mojang-named loader mods still make the old call, so the embedded
        // bridge rebuilds the key (#162). The key sets the translation key and loot table, so
        // EntityTypeBuildKeyAdapter passes the id the type is registered under.
        ensureSyntheticRegistered(transformer,
                "com/retromod/polyfill/minecraft/RetroEntityTypeBuild");
        transformer.registerMethodRedirect(
                "net/minecraft/world/entity/EntityType$Builder", "build",
                "(Ljava/lang/String;)Lnet/minecraft/world/entity/EntityType;",
                "com/retromod/polyfill/minecraft/RetroEntityTypeBuild", "buildOfficial",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;");
    }

    /**
     * Keeps the legacy inventory-swap call on the container-input side of the 26.1 split.
     *
     * <p>The old {@code ClickType} enum held container operations such as {@code SWAP}. Current
     * Minecraft uses {@code ContainerInput} for those operations and reserves
     * {@code ClickAction} for the unrelated primary and secondary item-click choice. Mapping the
     * old enum to {@code ClickAction} therefore linked neither the {@code SWAP} field nor the
     * multiplayer controller call (#181, Double Hotbar).
     */
    public static void registerLegacyContainerInput(RetromodTransformer transformer) {
        String oldInput = "net/minecraft/world/inventory/ClickType";
        String newInput = "net/minecraft/world/inventory/ContainerInput";
        transformer.registerClassRedirect(oldInput, newInput);
        transformer.registerMethodRedirect(
                "net/minecraft/client/multiplayer/MultiPlayerGameMode",
                "handleInventoryMouseClick",
                "(IIIL" + oldInput + ";Lnet/minecraft/world/entity/player/Player;)V",
                "net/minecraft/client/multiplayer/MultiPlayerGameMode",
                "handleContainerInput",
                "(IIIL" + newInput + ";Lnet/minecraft/world/entity/player/Player;)V");
    }

    /**
     * Vanilla client accessor method renames that landed by 26.1 (verified: 26.1 already has the new
     * name, the old one is gone; corpus-mined from the top-40 NeoForge 1.21.1 mods). Each is an
     * owner+descriptor-scoped rename, so a generic name like {@code getPosition} only rewrites the one
     * overload on the one class:
     * <ul>
     *   <li>{@code Minecraft.getTimer():DeltaTracker} -&gt; {@code getDeltaTracker()} (11 mods)</li>
     *   <li>{@code Camera.getPosition():Vec3} -&gt; {@code position()} (9 mods)</li>
     * </ul>
     * Also called from {@code Forge_1_21_11_to_26_1} so Forge client mods get them. (Fabric mods
     * reference these by intermediary name and go through the intermediary-to-Mojang map instead.)
     */
    public static void registerClientAccessorRenames26_1(RetromodTransformer transformer) {
        transformer.registerMethodRedirect(
                "net/minecraft/client/Minecraft", "getTimer", "()Lnet/minecraft/client/DeltaTracker;",
                "net/minecraft/client/Minecraft", "getDeltaTracker", "()Lnet/minecraft/client/DeltaTracker;");
        transformer.registerMethodRedirect(
                "net/minecraft/client/Camera", "getPosition", "()Lnet/minecraft/world/phys/Vec3;",
                "net/minecraft/client/Camera", "position", "()Lnet/minecraft/world/phys/Vec3;");
        // The pitch and yaw accessors took the same record-style names. Left to the fuzzy
        // resolver, both scored getFov()F at 90, so a camera-aligned overlay rotated by the
        // field of view instead of the view angle (#296, Phantom Shapes).
        transformer.registerMethodRedirect(
                "net/minecraft/client/Camera", "getXRot", "()F",
                "net/minecraft/client/Camera", "xRot", "()F");
        transformer.registerMethodRedirect(
                "net/minecraft/client/Camera", "getYRot", "()F",
                "net/minecraft/client/Camera", "yRot", "()F");
        String cameraCalls = "com/retromod/polyfill/minecraft/LegacyWorldRenderContext";
        ensureSyntheticRegistered(transformer, cameraCalls);
        transformer.registerMethodRedirect(
                "net/minecraft/client/Camera", "getNearPlane", "()Lnet/minecraft/client/Camera$NearPlane;",
                cameraCalls, "nearPlane", "(Ljava/lang/Object;)Ljava/lang/Object;");
        // A 1.21.9+ block-entity renderer already takes a render state; 26.1 only renamed the
        // call render to submit (same parameters). This fixes callers, such as Hold My Items
        // drawing a held bell. A renderer that still declares render needs a real port.
        String blockEntityRenderer = "net/minecraft/client/renderer/blockentity/BlockEntityRenderer";
        String submitDesc = "(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;"
                + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";
        transformer.registerMethodRedirect(blockEntityRenderer, "render", submitDesc,
                blockEntityRenderer, "submit", submitDesc);
        // A mod's own chat line is a client system message, which is what addMessage(text) added.
        String chat = "net/minecraft/client/gui/components/ChatComponent";
        transformer.registerMethodRedirect(chat, "addMessage", "(Lnet/minecraft/network/chat/Component;)V",
                chat, "addClientSystemMessage", "(Lnet/minecraft/network/chat/Component;)V");

        // The vertex API's Matrix4f parameter became Matrix4fc in 1.21.11; that shim shares this.
        JomlParameterWidening.registerVertexMatrixWidening(transformer);
    }

    /**
     * 26.1 renamed the Registry <em>value</em> getter {@code get(Identifier)} to
     * {@code getValue(Identifier)} across the registry hierarchy (verified on the 26.1 and 26.2 jars:
     * {@code getValue(Identifier)} returns {@code T}, while {@code get(Identifier)} now returns
     * {@code Optional<Holder.Reference<T>>}, a different method). A 1.21.1 mod compiled against the
     * old value getter (e.g. {@code BuiltInRegistries.SOUND_EVENT.get(id)}) links against
     * {@code get(Identifier)Ljava/lang/Object;}, which no longer exists, so it dies at construct time
     * with {@code NoSuchMethodError: DefaultedRegistry.get(Identifier)} (YUNG's Better Strongholds,
     * verified). The surviving {@code get(Identifier)} returns {@code Optional}, so the fuzzy resolver
     * leaves the call alone (it "resolves" by name+params) - an explicit descriptor-scoped redirect is
     * required. Keyed on the post-class-remap {@code Identifier} descriptor (ClassRemapper renames
     * {@code ResourceLocation -> Identifier} before the method-redirect lookup runs), scoped to the
     * value-returning ({@code )Ljava/lang/Object;}) overload so the {@code Optional}-returning
     * {@code get} is never touched. Registered for every registry owner a mod might reference by static
     * type. Also called from {@code Forge_1_21_11_to_26_1} so Forge mods get it too.
     */
    /**
     * Corpus-mined 26.x descriptor adaptations (top-50 Fabric+NeoForge 1.21.1 audit): vanilla methods
     * that still exist on 26.1 but changed a primitive type or lost their static form, so a 1.21.1
     * mod links against a descriptor that is gone. Each is owner+descriptor-scoped (a still-present
     * overload sharing the name is untouched) and verified against the 26.1 AND 26.2 jars (old
     * signature gone, new present). Also reached by Forge client mods via
     * {@code Forge_1_21_11_to_26_1}; Fabric mods arrive here after the intermediary->Mojang remap has
     * already produced these Mojang names.
     * <ul>
     *   <li>{@code Mth.cos(F)F}/{@code Mth.sin(F)F} widened their arg to {@code (D)F} in 1.21.11 (16 mods): F2D
     *       the single arg (the last value pushed) and call the double overload.</li>
     *   <li>{@code Window.getGuiScale()D} narrowed its return to {@code ()I} (16 mods): call it, then
     *       I2D the int back to the double the caller expects.</li>
     *   <li>{@code SoundManager.play(SoundInstance)V} returns a {@code SoundEngine$PlayResult} from
     *       1.21.6 (24 mods): call the returning form, then POP the result the void call never had.</li>
     *   <li>{@code CompoundTag.getList(String,int)ListTag} dropped its type-hint int for
     *       {@code getListOrEmpty(String)ListTag} (15 mods): drop the trailing int, rename.</li>
     *   <li>{@code Screen.hasControlDown()/hasShiftDown()/hasAltDown()} moved from static helpers on
     *       {@code Screen} to instance methods on {@code Minecraft} (24 mods): re-express as
     *       {@code Minecraft.getInstance().hasX()}.</li>
     * </ul>
     */
    public static void registerCorpus26xDescriptorAdaptations(RetromodTransformer t) {
        // Mth.cos/sin: (F)F -> (D)F, widen the single arg (F2D).
        registerMthDoubleTrig(t);
        // Window.getGuiScale: ()D -> ()I, widen the result (I2D).
        t.registerConvertingRedirect("com/mojang/blaze3d/platform/Window", "getGuiScale", "()D",
                "com/mojang/blaze3d/platform/Window", "getGuiScale", "()I", 0, Opcodes.I2D);
        // SoundManager.play: (SoundInstance)V -> ()PlayResult, discard the new result (POP).
        registerSoundPlayResult(t);
        // Vec3, Keyframe, PoseStack, and the vertex calls took JOML's read-only interfaces from
        // 1.21.5 to 1.21.11. The 1.21.x shims register the same widenings on their own hosts.
        JomlParameterWidening.registerAll(t);
        // Util.backgroundExecutor()/ioPool()/nonCriticalIoPool() returned java.util.concurrent
        // .ExecutorService and now return net.minecraft.TracingExecutor (a record wrapping the
        // service) in 26.x. Retarget to the TracingExecutor-returning form, then append
        // TracingExecutor.service() to recover the ExecutorService the caller expects. Found in-game
        // on 26.2 Fabric: jade's ClientProxy died NoSuchMethodError Util.backgroundExecutor()
        // ExecutorService. (Util is at net/minecraft/util/Util on 26.x; Fabric mods reach it via the
        // intermediary->Mojang remap, NeoForge/Forge via the class-move.)
        registerUtilExecutorUnwrap(t, "net/minecraft/util/Util");
        // Inventory.setPickedItem(ItemStack) -> addAndPickItem(ItemStack) by 26.1 (the middle-click
        // pick-block method that puts the picked stack into the hotbar; setPickedItem is gone on 26.1,
        // addAndPickItem is the rename, identical (ItemStack)V descriptor and role, pickSlot(int) kept
        // its name). Owner+descriptor-scoped. Corpus-mined from a NeoForge 1.21.1 audit (2 mods @Inject
        // this - inventory/pick-block tweak mixins); the redirect also propagates to those mixin
        // selectors via MixinCompatibilityTransformer.buildMixinRedirects. (Fabric mods reach it after
        // the intermediary->Mojang pass produces the old `setPickedItem` name, then this corrects it.)
        t.registerMethodRedirect(
                "net/minecraft/world/entity/player/Inventory", "setPickedItem",
                "(Lnet/minecraft/world/item/ItemStack;)V",
                "net/minecraft/world/entity/player/Inventory", "addAndPickItem",
                "(Lnet/minecraft/world/item/ItemStack;)V");
        // Particle.getLightColor(F)I became getLightCoords(F)I by 26.1 (1.21.11 still has the old
        // name). Particle classes call it on themselves, so the call often names a subclass.
        String particles = "net/minecraft/client/particle/";
        t.registerInheritedMethodRedirect(particles + "Particle", "getLightColor", "(F)I",
                particles + "Particle", "getLightCoords", "(F)I");
        // An override's super call names the vanilla base it extends and cannot move to Particle,
        // so each base old particle mods extend keeps its own owner. The JVM finds the method above it.
        for (String base : new String[]{"SingleQuadParticle", "SimpleAnimatedParticle",
                "RisingParticle", "WaterDropParticle"}) {
            t.registerMethodRedirect(particles + base, "getLightColor", "(F)I",
                    particles + base, "getLightCoords", "(F)I");
        }
        // BoatModel.createBodyModel() is createBoatModel() since 1.21.2. The class move runs first,
        // so the call arrives under the moved owner.
        registerBoatLayerRename(t, MOVED_BOAT_MODEL);
        registerScreenModifierKeys(t);

        registerNbtApiAdaptations26x(t);
        registerTextEventBridges26x(t);
        registerKeyMappingBridges26x(t);
        registerReloadListenerBridge26x(t);
        registerClientStructureBridges26x(t);
        registerLegacyClientInteractionBridges26x(t);
        registerLegacyGuiCalls26x(t);
        // Item.getName() and getDescription() lost their no-argument forms in 26.1.
        LegacyItemNameBridge.register26x(t);
        // Item stacks need bound components from 26.1, which a world's data binds; old mods build them earlier.
        LegacyEarlyItemStackBridge.register26x(t);
    }

    /**
     * Points the static {@code Screen.hasControlDown/hasShiftDown/hasAltDown} checks that 1.21.9
     * removed at the {@code Minecraft} instance methods that replaced them. The 1.21.9 shims call
     * this too.
     */
    public static void registerScreenModifierKeys(RetromodTransformer t) {
        for (String m : new String[]{"hasControlDown", "hasShiftDown", "hasAltDown"}) {
            t.registerSingletonStaticRedirect(
                    "net/minecraft/client/gui/screens/Screen", m, "()Z",
                    "net/minecraft/client/Minecraft", "getInstance",
                    "()Lnet/minecraft/client/Minecraft;", m, "()Z");
        }
    }

    /** 1.21.11 changed Mth.sin and Mth.cos to take a double. The 1.21.11 shims call this too. */
    public static void registerMthDoubleTrig(RetromodTransformer t) {
        registerMthDoubleTrig(t, "net/minecraft/util/Mth", "sin", "cos");
    }

    /** The same change under other names, so Fabric 1.21.11 can key it on its intermediary names. */
    public static void registerMthDoubleTrig(RetromodTransformer t, String mth, String sin, String cos) {
        // F2D the float the old call pushed last, then call the double form.
        for (String name : new String[]{sin, cos}) {
            t.registerConvertingRedirect(mth, name, "(F)F", mth, name, "(D)F", Opcodes.F2D, 0);
        }
    }

    /** 1.21.6 made SoundManager.play return a PlayResult. The 1.21.6 shims call this too. */
    public static void registerSoundPlayResult(RetromodTransformer t) {
        // Call the returning form, then POP the result the void call never had.
        String soundManager = "net/minecraft/client/sounds/SoundManager";
        String takesInstance = "(Lnet/minecraft/client/resources/sounds/SoundInstance;)";
        t.registerConvertingRedirect(soundManager, "play", takesInstance + "V", soundManager, "play",
                takesInstance + "Lnet/minecraft/client/sounds/SoundEngine$PlayResult;", 0, Opcodes.POP);
    }

    /** 1.21.11 named the buffer's and the id's ResourceLocation members after Identifier. The 1.21.11 and 26.1 shims call this. */
    public static void registerIdentifierMemberRenames(RetromodTransformer t) {
        // A rename reaches method references too, such as FriendlyByteBuf::writeResourceLocation in a stream codec,
        // which a call redirect cannot. A call through RegistryFriendlyByteBuf names that owner.
        for (String buffer : new String[]{"net/minecraft/network/FriendlyByteBuf",
                "net/minecraft/network/RegistryFriendlyByteBuf"}) {
            t.registerMethodRename(buffer, "writeResourceLocation", "writeIdentifier");
            t.registerMethodRename(buffer, "readResourceLocation", "readIdentifier");
        }
        t.registerMethodRename("net/minecraft/resources/ResourceLocation", "isAllowedInResourceLocation",
                "isAllowedInIdentifier");
    }

    /** 1.21.9 removed Listener.setGain, so the old call becomes a no-op. The 1.21.9 and 26.1 shims call this. */
    public static void registerListenerSetGainNoOp(RetromodTransformer t) {
        // The listener lost its gain field with the setter, so a mod that muted through it keeps
        // running but no longer changes the volume.
        t.registerMethodRedirect("com/mojang/blaze3d/audio/Listener", "setGain", "(F)V",
                "com/retromod/shim/fabric/embedded/ItemSafetyShim", "noOp", "(Ljava/lang/Object;F)V", true);
    }

    /** 1.21.6 renamed GameNarrator.sayNow(Component) to saySystemNow. The 1.21.6 and 26.1 shims call this. */
    public static void registerNarratorSayNowRename(RetromodTransformer t) {
        String narrator = "net/minecraft/client/GameNarrator";
        String takesComponent = "(Lnet/minecraft/network/chat/Component;)V";
        t.registerMethodRedirect(narrator, "sayNow", takesComponent, narrator, "saySystemNow", takesComponent);
    }

    /** 1.21.2 made Util's executor getters return a TracingExecutor. The 1.21.2 shims call this. */
    public static void registerUtilExecutorUnwrapOnHost(RetromodTransformer t) {
        // 1.21.11 moved Util into the util package. The class move runs first, so the call
        // arrives under the owner this host declares.
        boolean moved = RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.11") >= 0;
        registerUtilExecutorUnwrap(t, moved ? "net/minecraft/util/Util" : "net/minecraft/Util");
    }

    /** Calls the TracingExecutor form of each executor getter on {@code util}, then unwraps the service. */
    public static void registerUtilExecutorUnwrap(RetromodTransformer t, String util) {
        for (String pool : new String[]{"backgroundExecutor", "ioPool", "nonCriticalIoPool"}) {
            t.registerReturnUnwrapRedirect(util, pool, "()Ljava/util/concurrent/ExecutorService;",
                    "()Lnet/minecraft/TracingExecutor;",
                    "net/minecraft/TracingExecutor", "service", "()Ljava/util/concurrent/ExecutorService;");
        }
    }

    static final String OLD_BOAT_MODEL = "net/minecraft/client/model/BoatModel";
    static final String MOVED_BOAT_MODEL = "net/minecraft/client/model/object/boat/BoatModel";

    /** Points the 1.21.1 boat layer factory at its 1.21.2 name, under the owner this host declares. */
    public static void registerBoatLayerRenameOnHost(RetromodTransformer t) {
        // 1.21.11 moved BoatModel into object/boat. A redirect's target is written as given, so
        // key and target must both name the class the host has.
        boolean moved = RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.11") >= 0;
        registerBoatLayerRename(t, moved ? MOVED_BOAT_MODEL : OLD_BOAT_MODEL);
    }

    private static void registerBoatLayerRename(RetromodTransformer t, String boatModel) {
        // Only the plain boat. ChestBoatModel.createBodyModel() keeps its own owner, which no
        // class redirect folds into BoatModel, so it never reaches this key.
        String layer = "()Lnet/minecraft/client/model/geom/builders/LayerDefinition;";
        t.registerMethodRedirect(boatModel, "createBodyModel", layer, boatModel, "createBoatModel", layer);
    }

    /** Exact client call migrations found by verifying AutoClicky 1.2.1 against 26.1 and 26.2. */
    public static void registerLegacyClientInteractionBridges26x(RetromodTransformer t) {
        LegacyClientInteractionSynthetic.register(t);
    }

    /**
     * GUI calls whose receiver, arguments, and behavior stayed intact through the 26.x extraction
     * rename. These are descriptor-scoped so unrelated mod methods with the same short names are
     * never changed.
     */
    public static void registerLegacyGuiCalls26x(RetromodTransformer t) {
        String graphics = "net/minecraft/client/gui/GuiGraphicsExtractor";
        String renderDesc = "(L" + graphics + ";IIF)V";
        // Old Renderable.render was the public wrapper. Its direct successor is the public
        // extractRenderState wrapper, not a widget's inner extractContents implementation.
        t.registerMethodRedirect(
                "net/minecraft/client/gui/screens/Screen", "render", renderDesc,
                "net/minecraft/client/gui/screens/Screen", "extractRenderState", renderDesc);
        for (String owner : new String[]{
                "net/minecraft/client/gui/components/AbstractWidget",
                "net/minecraft/client/gui/components/AbstractSliderButton",
                "net/minecraft/client/gui/components/Checkbox"}) {
            t.registerMethodRedirect(owner, "render", renderDesc,
                    "net/minecraft/client/gui/components/AbstractWidget",
                    "extractRenderState", renderDesc);
        }
        t.registerMethodRedirect(
                "net/minecraft/client/gui/components/AbstractSliderButton",
                "renderWidget", renderDesc,
                "net/minecraft/client/gui/components/AbstractSliderButton",
                "extractWidgetRenderState", renderDesc);

        String font = "Lnet/minecraft/client/gui/Font;";
        String component = "Lnet/minecraft/network/chat/Component;";
        t.registerConvertingRedirect(
                graphics, "text", "(" + font + component + "IIIZ)I",
                graphics, "text", "(" + font + component + "IIIZ)V",
                0, Opcodes.ICONST_0);
        t.registerMethodRedirect(
                graphics, "extractTooltip", "(" + font + component + "II)V",
                graphics, "setTooltipForNextFrame", "(" + font + component + "II)V");

        // The in-game HUD entry point took the same extract name. This is how a HUD mixin's
        // render selector reaches the 26.1 method; on 26.2 MixinHostDriftRepair follows it onto
        // Hud, where the method moved with the same descriptor.
        String hudDesc = "(L" + graphics + ";Lnet/minecraft/client/DeltaTracker;)V";
        t.registerMethodRedirect("net/minecraft/client/gui/Gui", "render", hudDesc,
                "net/minecraft/client/gui/Gui", "extractRenderState", hudDesc);
        registerEditBoxFilter(t);
    }

    /** {@code EditBox.setFilter} is gone in 26.1, so an embedded helper keeps the filter and enforces it. */
    static void registerEditBoxFilter(RetromodTransformer t) {
        String box = "net/minecraft/client/gui/components/EditBox";
        String helper = "com/retromod/shim/common/embedded/LegacyEditBoxFilter";
        com.retromod.core.SyntheticEmbedder.registerClassResource(t, helper,
                com.retromod.shim.common.embedded.LegacyEditBoxFilter.class);
        // The box has no veto any more, so the helper watches changes through the box's responder
        // and undoes the ones the filter refuses.
        t.registerInheritedMethodRedirect(box, "setFilter", "(Ljava/util/function/Predicate;)V",
                helper, "setFilter", "(Ljava/lang/Object;Ljava/util/function/Predicate;)V");
        // The mod's own responder then runs behind the filter. setResponder still exists, so only
        // mods written before 26.1 take this, and a super call keeps calling the host.
        String responderDesc = "(Ljava/util/function/Consumer;)V";
        t.registerWrappingMethodRedirect(box, "setResponder", responderDesc,
                helper, "setResponder", "(Ljava/lang/Object;Ljava/util/function/Consumer;)V");
        t.limitRedirectToModsBefore(box, "setResponder", responderDesc, "26.1");
    }

    /**
     * {@code Minecraft.ON_OSX} was removed at 26.1 (verified absent on 26.1-snapshot-10); its
     * successor {@code InputQuirks.ON_OSX} is {@code private static final}, so no field-to-field
     * redirect can reach it. The GETSTATIC becomes an {@code INVOKESTATIC} on the embedded, MC-free
     * {@link com.retromod.polyfill.minecraft.RetroClientEnv#isOsx()}, which recomputes the value the
     * way vanilla's {@code Util.getPlatform()} does ({@code os.name} contains "mac"). Corpus: 10
     * mods, 45 sites (framebuffer flip-Y quirks, Cmd-vs-Ctrl key logic), all reads, zero writes, so
     * the opcode-blind field-to-method redirect is safe here.
     */
    public static void registerClientStructureBridges26x(RetromodTransformer t) {
        ensureSyntheticRegistered(t, "com/retromod/polyfill/minecraft/RetroClientEnv");
        t.registerFieldRedirect(
                "net/minecraft/client/Minecraft", "ON_OSX", "Z",
                "com/retromod/polyfill/minecraft/RetroClientEnv", "isOsx", "()Z");
        // The GUI 2D-transform migration's Phase 3 rewrites pose().mulPose(Quaternionf[c]) to the
        // 2D rotate() via this generated helper; embedding is reference-gated, so mods without a
        // migrated mulPose never carry it. Registered here to match the migration's 26.1+ gate.
        Quat2DSynthetic.register(t);

        // PlayerSkin (26.1 restructure, verified on 26.1-snapshot-10): the record moved packages
        // (class-move tsv) AND its texture accessors were renamed with a wrapper type:
        // texture()/capeTexture()/elytraTexture() returning ResourceLocation became
        // body()/cape()/elytra() returning the ClientAsset$Texture INTERFACE, unwrapped back to the
        // Identifier the caller expects via texturePath() (interface-aware unwrap). model()/secure()
        // kept their names ($Model -> PlayerModelType is a class move; SLIM/WIDE unchanged).
        String skin = "net/minecraft/world/entity/player/PlayerSkin";
        String asset = "net/minecraft/core/ClientAsset$Texture";
        String assetRet = "()L" + asset + ";";
        String idRet = "()Lnet/minecraft/resources/Identifier;";
        String rlRet = "()Lnet/minecraft/resources/ResourceLocation;";
        for (String[] r : new String[][]{
                {"texture", "body"}, {"capeTexture", "cape"}, {"elytraTexture", "elytra"}}) {
            // Both pre-move (ResourceLocation) and post-move (Identifier) return spellings: which
            // one the visitor sees depends on where the class-move pass rewrote the desc first.
            t.registerReturnUnwrapRedirect(skin, r[0], rlRet, r[1], assetRet,
                    asset, "texturePath", idRet, true);
            t.registerReturnUnwrapRedirect(skin, r[0], idRet, r[1], assetRet,
                    asset, "texturePath", idRet, true);
            // A Fabric mod's intermediary id maps straight to the 26.x name but keeps the old return.
            t.registerReturnUnwrapRedirect(skin, r[1], idRet, r[1], assetRet,
                    asset, "texturePath", idRet, true);
        }

        registerRenderApiBridges26x(t);
        registerItemInteractionResultBridge(t);
        registerParticleBridge26x(t);
    }


    /**
     * {@code TextureSheetParticle} -> {@code SingleQuadParticle} rebase (6 corpus mods; the 26.1
     * particle rework deleted the old base; the successor exists identically on both 26.x jars with
     * matching fields/protected helpers and a non-abstract {@code getGroup()}, so simple content-mod
     * particles inherit everything). Its constructors gained a trailing {@code TextureAtlasSprite}:
     * the insert-defaults super-ctor redirect appends null (the sprite is set post-construction via
     * {@code setSprite}/{@code SpriteSet}, exactly like the old flow). {@code pickSprite(SpriteSet)}
     * did not survive: bridged receiver-as-arg0 to the reflective
     * {@link com.retromod.polyfill.minecraft.RetroParticleCompat}. A subclass that overrode the old
     * {@code render(VertexConsumer,...)} keeps loading but its override is never called (the render
     * contract became {@code extract(QuadParticleRenderState,...)}): such a particle is invisible,
     * not a crash. Registered on BOTH owner spellings (pre/post rebase).
     */
    public static void registerParticleBridge26x(RetromodTransformer t) {
        String oldBase = "net/minecraft/client/particle/TextureSheetParticle";
        String newBase = "net/minecraft/client/particle/SingleQuadParticle";
        String poly = "com/retromod/polyfill/minecraft/RetroParticleCompat";
        ensureSyntheticRegistered(t, poly);
        t.registerSuperclassRebase(oldBase, newBase);
        LegacyParticleRenderTypeAdapter.register(t);
        // Non-extends references (instanceof, method descs, provider generics).
        t.registerClassRedirect(oldBase, newBase);
        String level = "Lnet/minecraft/client/multiplayer/ClientLevel;";
        String sprite = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;";
        for (String owner : new String[]{oldBase, newBase}) {
            t.registerSuperConstructorRedirect(owner,
                    "(" + level + "DDD)V", "(" + level + "DDD" + sprite + ")V");
            t.registerSuperConstructorRedirect(owner,
                    "(" + level + "DDDDDD)V", "(" + level + "DDDDDD" + sprite + ")V");
            t.registerMethodRedirect(owner, "pickSprite",
                    "(Lnet/minecraft/client/particle/SpriteSet;)V",
                    poly, "pickSprite", "(Ljava/lang/Object;Ljava/lang/Object;)V");
        }
    }

    /**
     * The Mojang-named half of the 1.21.2 {@code ItemInteractionResult} merge, for NeoForge and Forge
     * mods. The class redirect alone left the constants unresolvable: since 1.21.2 each field is
     * typed by its record ({@code InteractionResult$Success} and so on), and three were renamed or
     * removed. {@code PASS_TO_DEFAULT_BLOCK_INTERACTION} crashed Macaw's Bridges on the first right
     * click. The mapping matches {@link #registerItemInteractionResultBridge}. {@code InteractionResult}
     * was an enum until 1.21.2, so its own {@code CONSUME_PARTIAL} and {@code sidedSuccess} are
     * covered by registering both owner spellings.
     */
    public static void registerMojangItemInteractionResultBridge(RetromodTransformer t) {
        String oldOwner = "net/minecraft/world/ItemInteractionResult";
        String newOwner = "net/minecraft/world/InteractionResult";
        t.registerClassRedirect(oldOwner, newOwner);
        String[][] constants = {
                {"SUCCESS", "SUCCESS", "Success"},
                {"CONSUME", "CONSUME", "Success"},
                {"CONSUME_PARTIAL", "CONSUME", "Success"},
                {"PASS_TO_DEFAULT_BLOCK_INTERACTION", "TRY_WITH_EMPTY_HAND", "TryEmptyHandInteraction"},
                {"SKIP_DEFAULT_BLOCK_INTERACTION", "FAIL", "Fail"},
                {"FAIL", "FAIL", "Fail"},
                {"PASS", "PASS", "Pass"},
        };
        String poly = "com/retromod/polyfill/minecraft/RetroItemInteractionResult";
        ensureSyntheticRegistered(t, poly);
        for (String owner : new String[]{oldOwner, newOwner}) {
            for (String[] c : constants) {
                t.registerFieldRedirect(owner, c[0], null,
                        newOwner, c[1], "L" + newOwner + "$" + c[2] + ";");
            }
            t.registerMethodRedirect(owner, "sidedSuccess", "(Z)L" + owner + ";",
                    poly, "sidedSuccess", "(Z)Ljava/lang/Object;");
            // An enum method became an interface method, so the old INVOKEVIRTUAL cannot link.
            t.registerMethodRedirect(owner, "consumesAction", "()Z",
                    poly, "consumesAction", "(Ljava/lang/Object;)Z");
            t.registerMethodRedirect(owner, "result", "()L" + newOwner + ";",
                    poly, "result", "(Ljava/lang/Object;)Ljava/lang/Object;");
        }
    }

    /**
     * {@code ItemInteractionResult} ({@code class_9062}, the 1.20.5-1.21.1 sided item-use result)
     * was merged back into {@code InteractionResult} at 1.21.2 and is ABSENT from the 1.21.4-era
     * intermediary tsv (it died before the harvest), so nothing remapped it: 6 corpus mods carry
     * raw {@code class_9062} references that die {@code NoClassDefFoundError}. Class-redirect it to
     * the merged interface; the intermediary MEMBER names (also unharvested) become polyfill calls:
     * the enum constants are GETSTATIC field-to-method redirects (all-read usage, 105 corpus refs,
     * dominated by {@code field_47731} = {@code PASS_TO_DEFAULT_BLOCK_INTERACTION}, 58), and the
     * instance/static methods go receiver-as-arg0 to {@link
     * com.retromod.polyfill.minecraft.RetroItemInteractionResult} (see its javadoc for the
     * semantic mapping). Registered on BOTH owner spellings (pre/post class-redirect).
     */
    public static void registerItemInteractionResultBridge(RetromodTransformer t) {
        String poly = "com/retromod/polyfill/minecraft/RetroItemInteractionResult";
        ensureSyntheticRegistered(t, poly);
        String oldOwner = "net/minecraft/class_9062";
        String newOwner = "net/minecraft/world/InteractionResult";
        t.registerClassRedirect(oldOwner, newOwner);
        String objRet = "()Ljava/lang/Object;";
        // 1.21.1 ItemInteractionResult constant order: field_47728..field_47733.
        String[][] constants = {
                {"field_47728", "success"},
                {"field_47729", "consume"},
                {"field_47730", "consumePartial"},
                {"field_47731", "passToDefaultBlockInteraction"},
                {"field_47732", "skipDefaultBlockInteraction"},
                {"field_47733", "fail"},
        };
        for (String owner : new String[]{oldOwner, newOwner}) {
            for (String[] c : constants) {
                // Field-to-method form: GETSTATIC owner.field -> INVOKESTATIC poly.method()Object
                // (+ CHECKCAST back to the field's ref type).
                t.registerFieldRedirect(owner, c[0], "L" + newOwner + ";", poly, c[1], objRet);
            }
            // static sidedSuccess(Z) -> SUCCESS (the sided split is gone).
            t.registerMethodRedirect(owner, "method_55644", "(Z)L" + owner + ";",
                    poly, "sidedSuccess", "(Z)Ljava/lang/Object;");
            // instance consumesAction()Z / result() -> receiver-as-arg0 helpers.
            t.registerMethodRedirect(owner, "method_55643", "()Z",
                    poly, "consumesAction", "(Ljava/lang/Object;)Z");
            t.registerMethodRedirect(owner, "method_55645", "()Lnet/minecraft/class_1269;",
                    poly, "result", "(Ljava/lang/Object;)Ljava/lang/Object;");
            t.registerMethodRedirect(owner, "method_55645", "()L" + newOwner + ";",
                    poly, "result", "(Ljava/lang/Object;)Ljava/lang/Object;");
        }
    }

    /**
     * The 26.1 render rewrite's mechanically-bridgeable surface, ground-truthed against BOTH the
     * 26.1-snapshot-10 and 26.2 jars (identical on each, so 26.1 epoch throughout):
     *
     * <p><b>LightTexture</b> (9 corpus mods): the class-move tsv retypes it to {@code Lightmap}
     * (whose static {@code getBrightness} kept the old descriptor, so that call heals by the move
     * alone); the static coord math moved to {@code util/LightCoordsUtil} with IDENTICAL
     * names/descriptors/bit layout, wired here (keyed on the POST-move owner, with the pre-move
     * spelling registered too in case a pass sees it). {@code turnOn/turnOffLightLayer} are truly
     * deleted (the global texture-unit binding no longer exists): neutralized, and the
     * {@code GameRenderer.lightTexture()} accessor mods use to reach them is bridged to a
     * reflective helper so the (now inert) receiver still resolves.
     *
     * <p><b>RenderType static getters</b> (34 corpus mods reference RenderType; translucent 15,
     * cutout 12, entitySolid 10, cutoutMipped 9, entityCutoutNoCull 8, entityCutout 7,
     * entityTranslucent 6): moved to {@code rendertype/RenderTypes}, with a cull-naming FLIP
     * (old {@code entityCutout} = culled -> {@code entityCutoutCull}; old {@code entityCutoutNoCull}
     * -> {@code entityCutout}) and the block-layer getters (solid/cutout/cutoutMipped/translucent/
     * tripwire) approximated by the surviving {@code *MovingBlock} tokens (chunk layers themselves
     * became the ChunkSectionLayer enum). The CompositeState/RenderStateShard builder world is a
     * documented loss (custom render types need re-authoring).
     */
    public static void registerRenderApiBridges26x(RetromodTransformer t) {
        String lightOld = "net/minecraft/client/renderer/LightTexture";
        String lightNew = "net/minecraft/client/renderer/Lightmap";
        String coords = "net/minecraft/util/LightCoordsUtil";
        for (String owner : new String[]{lightOld, lightNew}) {
            t.registerMethodRedirect(owner, "pack", "(II)I", coords, "pack", "(II)I");
            t.registerMethodRedirect(owner, "block", "(I)I", coords, "block", "(I)I");
            t.registerMethodRedirect(owner, "sky", "(I)I", coords, "sky", "(I)I");
            t.registerMethodRedirect(owner, "lightCoordsWithEmission", "(II)I",
                    coords, "lightCoordsWithEmission", "(II)I");
            t.registerRemovedMethodNeutralize(owner, "turnOnLightLayer", "()V");
            t.registerRemovedMethodNeutralize(owner, "turnOffLightLayer", "()V");
        }
        // GameRenderer.lightTexture() -> the private Lightmap instance, via the embedded reflective
        // helper (receiver-as-arg0 auto-devirtualize + CHECKCAST). Both return spellings.
        ensureSyntheticRegistered(t, "com/retromod/polyfill/minecraft/RetroClientEnv");
        for (String ret : new String[]{"()L" + lightOld + ";", "()L" + lightNew + ";"}) {
            t.registerMethodRedirect(
                    "net/minecraft/client/renderer/GameRenderer", "lightTexture", ret,
                    "com/retromod/polyfill/minecraft/RetroClientEnv", "getLightmap",
                    "(Ljava/lang/Object;)Ljava/lang/Object;");
        }

        String rt = "net/minecraft/client/renderer/rendertype/RenderType";
        String rts = "net/minecraft/client/renderer/rendertype/RenderTypes";
        String rtRet = "()L" + rt + ";";
        String idArg = "(Lnet/minecraft/resources/Identifier;)L" + rt + ";";
        String idBoolArg = "(Lnet/minecraft/resources/Identifier;Z)L" + rt + ";";
        registerBlockLayerGetters(t);
        registerRenderTypeFactoryMoves(t);
        // Entity getters: same names, new owner; the cull flip per the javadoc.
        t.registerMethodRedirect(rt, "entitySolid", idArg, rts, "entitySolid", idArg);
        t.registerMethodRedirect(rt, "entityCutout", idArg, rts, "entityCutoutCull", idArg);
        t.registerMethodRedirect(rt, "entityCutoutNoCull", idArg, rts, "entityCutout", idArg);
        t.registerMethodRedirect(rt, "entityCutoutNoCull", idBoolArg, rts, "entityCutout", idBoolArg);
        t.registerMethodRedirect(rt, "entityTranslucent", idArg, rts, "entityTranslucent", idArg);
        t.registerMethodRedirect(rt, "entityTranslucent", idBoolArg, rts, "entityTranslucent", idBoolArg);
        t.registerMethodRedirect(rt, "text", idArg, rts, "text", idArg);
        // Glint getters: same names, new owner. 26.3 renames two of them, which its own shim maps.
        for (String glint : new String[]{"glint", "glintTranslucent", "entityGlint", "armorEntityGlint"}) {
            t.registerMethodRedirect(rt, glint, rtRet, rts, glint, rtRet);
        }

        // ItemBlockRenderTypes deleted in the same rewrite (12 corpus mods): the static
        // block-to-layer table became per-quad model data. Bridged to the embedded reflective
        // RetroItemBlockRenderTypes, which re-derives the layer from the live model (probing
        // getBlockStateModelSet FIRST, the verified 26.1/26.2 probe-order trap) and returns the
        // same RenderTypes.*MovingBlock tokens the RenderType getter redirects above use, so
        // mod-side == comparisons stay consistent. Registered against BOTH the pre-move and
        // post-move RenderType return-desc spellings. The two (BlockState,Z)/(ItemStack,Z)
        // overloads erase to the same (Object,Z) target shape, so they get DISTINCT target names.
        ensureSyntheticRegistered(t, "com/retromod/polyfill/minecraft/RetroItemBlockRenderTypes");
        String ibrt = "net/minecraft/client/renderer/ItemBlockRenderTypes";
        String poly = "com/retromod/polyfill/minecraft/RetroItemBlockRenderTypes";
        String bs = "Lnet/minecraft/world/level/block/state/BlockState;";
        String fs = "Lnet/minecraft/world/level/material/FluidState;";
        String stack = "Lnet/minecraft/world/item/ItemStack;";
        String objRet = "(Ljava/lang/Object;)Ljava/lang/Object;";
        String objBoolRet = "(Ljava/lang/Object;Z)Ljava/lang/Object;";
        for (String rtd : new String[]{"Lnet/minecraft/client/renderer/RenderType;",
                                       "L" + rt + ";"}) {
            t.registerMethodRedirect(ibrt, "getChunkRenderType", "(" + bs + ")" + rtd,
                    poly, "getChunkRenderType", objRet);
            t.registerMethodRedirect(ibrt, "getMovingBlockRenderType", "(" + bs + ")" + rtd,
                    poly, "getMovingBlockRenderType", objRet);
            t.registerMethodRedirect(ibrt, "getRenderLayer", "(" + fs + ")" + rtd,
                    poly, "getRenderLayer", objRet);
            t.registerMethodRedirect(ibrt, "getRenderType", "(" + bs + "Z)" + rtd,
                    poly, "getRenderTypeBlock", objBoolRet);
            t.registerMethodRedirect(ibrt, "getRenderType", "(" + stack + "Z)" + rtd,
                    poly, "getRenderTypeItem", objBoolRet);
            // Forge's and NeoForge's setters, called from client setup by most block mods.
            for (String target : new String[]{"Lnet/minecraft/world/level/block/Block;",
                                              "Lnet/minecraft/world/level/material/Fluid;"}) {
                t.registerMethodRedirect(ibrt, "setRenderLayer", "(" + target + rtd + ")V",
                        poly, "setRenderLayer", "(Ljava/lang/Object;Ljava/lang/Object;)V");
            }
        }
        t.registerMethodRedirect(ibrt, "setRenderLayer",
                "(Lnet/minecraft/world/level/block/Block;Ljava/util/function/Predicate;)V",
                poly, "setRenderLayer", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    }

    /**
     * {@code ClickEvent}/{@code HoverEvent} constructor bridges for the 1.21.5 text-component rework
     * (14+19 mods across the corpus audits; text interactions are ubiquitous). Both became sealed
     * INTERFACES with per-action record subtypes, so the old {@code new ClickEvent(Action,String)} /
     * {@code new HoverEvent(Action,Object)} constructors are gone. A constructor-to-factory redirect
     * rewrites those {@code new}s to {@link com.retromod.polyfill.minecraft.RetroTextEvents}, which
     * dispatches on the action to the right subtype (the dispatch the old constructor did internally).
     * The factory is registered as a synthetic so the Forge/NeoForge per-mod embedder relocates a
     * JPMS-split-package-safe copy into any mod that references it (Fabric injects it directly). The
     * 1.21.5 and 26.1 shims call this. Both types are interfaces from 1.21.5 with the same subtype
     * constructors through 26.3, so the old constructor is gone there; before 1.21.5 the redirect
     * must not fire, and those shims never register on such a host.
     */
    public static void registerTextEventBridges26x(RetromodTransformer t) {
        ensureSyntheticRegistered(t, "com/retromod/polyfill/minecraft/RetroTextEvents");
        t.registerConstructorRedirect(
                "net/minecraft/network/chat/ClickEvent",
                "(Lnet/minecraft/network/chat/ClickEvent$Action;Ljava/lang/String;)V",
                "com/retromod/polyfill/minecraft/RetroTextEvents", "clickEvent",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;");
        t.registerConstructorRedirect(
                "net/minecraft/network/chat/HoverEvent",
                "(Lnet/minecraft/network/chat/HoverEvent$Action;Ljava/lang/Object;)V",
                "com/retromod/polyfill/minecraft/RetroTextEvents", "hoverEvent",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    }

    /** Keybind constructors and quad loops whose 1.21.x shapes are gone on 26.x. */
    public static void registerKeyMappingBridges26x(RetromodTransformer t) {
        // Every keybind-adding mod hits this on 26.x (Jade, found in-game on 26.2 Fabric).
        registerStringCategoryKeyMappings(t);
        // Quad loops a mod wrote itself: material accessors and putBulkData (see the synthetic).
        LegacyBakedQuadSynthetic.register(t);
    }

    /** Rebuilds the String-category keybind constructors that 1.21.9 replaced with a Category record. */
    public static void registerStringCategoryKeyMappings(RetromodTransformer t) {
        // RetroKeyMapping turns the string into a Category: a vanilla constant, else a registered
        // retromod_compat: category, else MISC. The 1.21.9 shims call this too, and every key below
        // is an old descriptor that a mod built for 1.21.9 or newer never emits.
        ensureSyntheticRegistered(t, "com/retromod/polyfill/minecraft/RetroKeyMapping");
        // 4-arg: KeyMapping(String, InputConstants$Type, int, String) -> create(...). The type is
        // passed as Object (Retromod has no compile-time InputConstants$Type); a Type IS-A Object, so
        // the arg is assignable and newInstance's runtime check binds it to the real ctor param.
        t.registerConstructorRedirect(
                "net/minecraft/client/KeyMapping",
                "(Ljava/lang/String;Lcom/mojang/blaze3d/platform/InputConstants$Type;ILjava/lang/String;)V",
                "com/retromod/polyfill/minecraft/RetroKeyMapping", "create",
                "(Ljava/lang/String;Ljava/lang/Object;ILjava/lang/String;)Ljava/lang/Object;");
        // 3-arg: KeyMapping(String, int, String) -> createDefault(...).
        t.registerConstructorRedirect(
                "net/minecraft/client/KeyMapping",
                "(Ljava/lang/String;ILjava/lang/String;)V",
                "com/retromod/polyfill/minecraft/RetroKeyMapping", "createDefault",
                "(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/Object;");

        // NeoForge's and Forge's conflict-context constructors lost their String category at 1.21.9
        // as well, so a keybind naming an IKeyConflictContext died with NoSuchMethodError. Both
        // loaders patch the same four shapes into KeyMapping, each with its own settings package.
        String type = "Lcom/mojang/blaze3d/platform/InputConstants$Type;";
        String key = "Lcom/mojang/blaze3d/platform/InputConstants$Key;";
        for (String settings : new String[]{"Lnet/neoforged/neoforge/client/settings/",
                "Lnet/minecraftforge/client/settings/"}) {
            String context = settings + "IKeyConflictContext;";
            String modifier = settings + "KeyModifier;";
            String[][] conflictForms = {
                {"(Ljava/lang/String;" + context + type + "ILjava/lang/String;)V", "createConflict",
                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;ILjava/lang/String;)"},
                {"(Ljava/lang/String;" + context + key + "Ljava/lang/String;)V", "createConflictKey",
                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)"},
                {"(Ljava/lang/String;" + context + modifier + type + "ILjava/lang/String;)V",
                    "createConflictModifier",
                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I"
                        + "Ljava/lang/String;)"},
                {"(Ljava/lang/String;" + context + modifier + key + "Ljava/lang/String;)V",
                    "createConflictModifierKey",
                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
                        + "Ljava/lang/String;)"},
            };
            for (String[] form : conflictForms) {
                t.registerConstructorRedirect("net/minecraft/client/KeyMapping", form[0],
                        "com/retromod/polyfill/minecraft/RetroKeyMapping", form[1],
                        form[2] + "Ljava/lang/Object;");
            }
        }
        registerStringCategoryKeyMappingSubclasses(t);
    }

    /**
     * A mod's own KeyMapping subclass calls the old String-category constructor as {@code super(...)}, which no
     * redirect to a factory can reach, so Carry On's keybinds stopped the game while keys registered. The category
     * string, on top of the stack, becomes a Category, and the key code is translated as the factories translate it.
     */
    static void registerStringCategoryKeyMappingSubclasses(RetromodTransformer t) {
        String keyMapping = "net/minecraft/client/KeyMapping";
        String category = "net/minecraft/client/KeyMapping$Category";
        String type = "Lcom/mojang/blaze3d/platform/InputConstants$Type;";
        String helper = "com/retromod/polyfill/minecraft/RetroKeyMapping";
        // (name, code, category): convert the category, then the code beneath it.
        t.registerSuperConstructorAdapter(keyMapping, "(Ljava/lang/String;ILjava/lang/String;)V",
                "(Ljava/lang/String;IL" + category + ";)V", mv -> {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, helper, "category", "(Ljava/lang/String;)Ljava/lang/Object;", false);
                    mv.visitInsn(Opcodes.SWAP);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, helper, "hostKeyCode", "(I)I", false);
                    mv.visitInsn(Opcodes.SWAP);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, category);
                });
        // (name, type, code, category): the code is translated by its input type, which sits two below the category.
        t.registerSuperConstructorAdapter(keyMapping, "(Ljava/lang/String;" + type + "ILjava/lang/String;)V",
                "(Ljava/lang/String;" + type + "IL" + category + ";)V", mv -> {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, helper, "category", "(Ljava/lang/String;)Ljava/lang/Object;", false);
                    // name, type, code, cat -> name, cat, type, code
                    mv.visitInsn(Opcodes.DUP_X2);
                    mv.visitInsn(Opcodes.POP);
                    // -> name, cat, type, type, code
                    mv.visitInsn(Opcodes.SWAP);
                    mv.visitInsn(Opcodes.DUP_X1);
                    mv.visitInsn(Opcodes.SWAP);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, helper, "hostCode", "(Ljava/lang/Object;I)I", false);
                    // name, cat, type, code -> name, type, code, cat
                    mv.visitInsn(Opcodes.DUP2_X1);
                    mv.visitInsn(Opcodes.POP2);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, category);
                });
    }

    /**
     * {@code SimpleJsonResourceReloadListener(Gson, String)} bridge for the 1.21.5 resource-reload
     * refactor. The Gson-based constructor was deleted (the class went Codec-based), so a 1.21.x mod
     * that EXTENDS it to load a directory of raw JSON dies {@code NoSuchMethodError} on
     * {@code super(gson, dir)} at init (jade's `ThemeHelper`, found in-game on 26.2 Fabric). A
     * superclass rebase repoints such a subclass at the synthesized
     * {@link ReloadListenerSynthetic RetroSimpleJsonReloadListener} (which re-implements the old
     * Gson scan on 26.x's {@code SimplePreparableReloadListener} via
     * {@link com.retromod.polyfill.minecraft.RetroReloadScan}) and rewrites the {@code super(...)}
     * call to it. Broadly applicable: any mod extending the old Gson listener for custom JSON data.
     */
    public static void registerReloadListenerBridge26x(RetromodTransformer t) {
        if (t == null) return;
        // the reflective scan helper (a compiled polyfill) and the generated superclass, both as
        // embeddable synthetics so the Forge/NeoForge per-mod embedder relocates them.
        ensureSyntheticRegistered(t, "com/retromod/polyfill/minecraft/RetroReloadScan");
        if (!t.getSyntheticClasses().containsKey(ReloadListenerSynthetic.INTERNAL)) {
            t.registerSyntheticClass(ReloadListenerSynthetic.INTERNAL, ReloadListenerSynthetic.generate());
        }
        t.registerSuperclassRebase(
                "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener",
                ReloadListenerSynthetic.INTERNAL);
    }

    /** Register a Retromod class as an embeddable synthetic (idempotent and best-effort). */
    static void ensureSyntheticRegistered(RetromodTransformer t, String internalName) {
        if (t == null || t.getSyntheticClasses().containsKey(internalName)) return;
        try (java.io.InputStream in = Common_1_21_11_to_26_1_ClassMoves.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            if (in != null) t.registerSyntheticClass(internalName, in.readAllBytes());
        } catch (Throwable ignored) {
            // best-effort: the resource is Retromod's own class; a miss just means the ctor redirect
            // resolves against the jar-resident copy on Fabric (and no-ops where the class is absent)
        }
    }

    /**
     * 1.21.11 moved RenderType into the rendertype package and dropped its block-layer getters,
     * whose nearest surviving tokens are the RenderTypes moving-block types. The class move runs
     * first, so the calls arrive under the moved owner. The 1.21.11 shims call this too.
     */
    public static void registerBlockLayerGetters(RetromodTransformer t) {
        String rt = "net/minecraft/client/renderer/rendertype/RenderType";
        String rts = "net/minecraft/client/renderer/rendertype/RenderTypes";
        String rtRet = "()L" + rt + ";";
        t.registerMethodRedirect(rt, "solid", rtRet, rts, "solidMovingBlock", rtRet);
        t.registerMethodRedirect(rt, "cutout", rtRet, rts, "cutoutMovingBlock", rtRet);
        t.registerMethodRedirect(rt, "cutoutMipped", rtRet, rts, "cutoutMovingBlock", rtRet);
        t.registerMethodRedirect(rt, "translucent", rtRet, rts, "translucentMovingBlock", rtRet);
        t.registerMethodRedirect(rt, "tripwire", rtRet, rts, "translucentMovingBlock", rtRet);
    }

    /** The static RenderType factories 1.21.11 moved to RenderTypes under the same names, by descriptor shape. */
    static final String[][] RENDER_TYPE_FACTORIES = {
            {"()", "leash", "waterMask", "armorEntityGlint", "glintTranslucent", "glint", "entityGlint",
                    "textBackground", "textBackgroundSeeThrough", "lightning", "dragonRays", "dragonRaysDepth",
                    "endPortal", "endGateway", "lines", "linesTranslucent", "secondaryBlockOutline",
                    "debugFilledBox", "debugPoint", "debugQuads", "debugTriangleFan"},
            {"(Lnet/minecraft/resources/Identifier;FF)", "breezeWind", "energySwirl"},
            {"(Lnet/minecraft/resources/Identifier;)", "armorCutoutNoCull", "createArmorDecalCutoutNoCull",
                    "armorTranslucent", "entitySolid", "entitySolidZOffsetForward", "entityCutout",
                    "entityCutoutNoCull", "entityCutoutNoCullZOffset", "itemEntityTranslucentCull",
                    "entityTranslucent", "entityTranslucentEmissive", "entitySmoothCutout", "entityDecal",
                    "entityNoOutline", "entityShadow", "dragonExplosionAlpha", "eyes", "breezeEyes", "outline",
                    "crumbling", "text", "textIntensity", "textPolygonOffset", "textIntensityPolygonOffset",
                    "textSeeThrough", "textIntensitySeeThrough", "blockScreenEffect", "fireScreenEffect"},
            {"(Lnet/minecraft/resources/Identifier;Z)", "entityCutoutNoCull", "entityCutoutNoCullZOffset",
                    "entityTranslucent", "entityTranslucentEmissive", "beaconBeam", "weather"},
    };

    /** The factories whose meaning 26.1 flipped; the 26.1 registration maps them by meaning instead. */
    private static final Set<String> CULL_FLIPPED_IN_26_1 = Set.of("entityCutout", "entityCutoutNoCull");

    /**
     * Moves each static RenderType factory to RenderTypes under its own name, as 1.21.11 did.
     * Names 26.1 gave another meaning are left to the explicit 26.1 mapping. A factory a later
     * host removed still ends up missing, as it was before the move.
     */
    public static void registerRenderTypeFactoryMoves(RetromodTransformer t) {
        String rt = "net/minecraft/client/renderer/rendertype/RenderType";
        String rts = "net/minecraft/client/renderer/rendertype/RenderTypes";
        boolean flipped = RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0;
        for (String[] shape : RENDER_TYPE_FACTORIES) {
            String desc = shape[0] + "L" + rt + ";";
            for (int i = 1; i < shape.length; i++) {
                if (flipped && CULL_FLIPPED_IN_26_1.contains(shape[i])) continue;
                t.registerMethodRedirect(rt, shape[i], desc, rts, shape[i], desc);
            }
        }
    }

    /** Every pre-1.21.5 NBT accessor 26.x no longer has, from the 1.21.5, 1.21.6 and 1.21.11 changes. */
    public static void registerNbtApiAdaptations26x(RetromodTransformer t) {
        registerNbtReadRefactor1215(t);
        registerNbtRemoveReturnsTag(t);
        LegacyItemNbtApi.register1215(t);
        LegacyItemNbtApi.register1216(t);
    }

    /** The 1.21.5 NBT read refactor, checked against the 1.21.4, 1.21.5 and 1.21.11 Mojang mappings. */
    public static void registerNbtReadRefactor1215(RetromodTransformer t) {
        // Each redirect is owner and descriptor scoped, so a newer mod's Optional getters are untouched.
        // CompoundTag.contains(String,int) -> contains(String): drop the tag-type-hint int.
        t.registerArgDropMethodRedirect("net/minecraft/nbt/CompoundTag", "contains",
                "(Ljava/lang/String;I)Z", "net/minecraft/nbt/CompoundTag", "contains", "(Ljava/lang/String;)Z");
        // CompoundTag.getList(String,int) -> getListOrEmpty(String): drop the type-hint int.
        t.registerArgDropMethodRedirect(
                "net/minecraft/nbt/CompoundTag", "getList",
                "(Ljava/lang/String;I)Lnet/minecraft/nbt/ListTag;",
                "net/minecraft/nbt/CompoundTag", "getListOrEmpty",
                "(Ljava/lang/String;)Lnet/minecraft/nbt/ListTag;");
        // getCompound -> getCompoundOrEmpty (the plain getter now returns Optional). Descriptor-scoped
        // to the CompoundTag-returning form so the new Optional overload is untouched.
        t.registerMethodRedirect("net/minecraft/nbt/CompoundTag", "getCompound",
                "(Ljava/lang/String;)Lnet/minecraft/nbt/CompoundTag;",
                "net/minecraft/nbt/CompoundTag", "getCompoundOrEmpty",
                "(Ljava/lang/String;)Lnet/minecraft/nbt/CompoundTag;");
        t.registerMethodRedirect("net/minecraft/nbt/ListTag", "getCompound",
                "(I)Lnet/minecraft/nbt/CompoundTag;",
                "net/minecraft/nbt/ListTag", "getCompoundOrEmpty", "(I)Lnet/minecraft/nbt/CompoundTag;");
        // TagParser.parseTag -> parseCompoundFully (same params/return; checked exn not enforced in bytecode).
        t.registerMethodRedirect("net/minecraft/nbt/TagParser", "parseTag",
                "(Ljava/lang/String;)Lnet/minecraft/nbt/CompoundTag;",
                "net/minecraft/nbt/TagParser", "parseCompoundFully",
                "(Ljava/lang/String;)Lnet/minecraft/nbt/CompoundTag;");
        // CompoundTag.getAllKeys() -> keySet(): a plain rename with the same Set return.
        t.registerMethodRedirect("net/minecraft/nbt/CompoundTag", "getAllKeys", "()Ljava/util/Set;",
                "net/minecraft/nbt/CompoundTag", "keySet", "()Ljava/util/Set;");
        registerNbtGetterBridges(t);
    }

    /** The same 1.21.5 refactor in the intermediary names a Fabric 1.21.5 to 1.21.11 host runs. */
    public static void registerNbtReadRefactor1215Intermediary(RetromodTransformer t) {
        String compoundTag = "net/minecraft/class_2487";
        String listTag = "net/minecraft/class_2499";
        String lCompound = "Lnet/minecraft/class_2487;";
        String lList = "Lnet/minecraft/class_2499;";
        // contains(String,int) and getList(String,int) lose the type hint. getAllKeys and keySet
        // share method_10541, so that rename needs no rule here.
        t.registerArgDropMethodRedirect(compoundTag, "method_10573", "(Ljava/lang/String;I)Z",
                compoundTag, "method_10545", "(Ljava/lang/String;)Z");
        t.registerArgDropMethodRedirect(compoundTag, "method_10554", "(Ljava/lang/String;I)" + lList,
                compoundTag, "method_68569", "(Ljava/lang/String;)" + lList);
        // The compound getters moved to getCompoundOrEmpty, and parseTag to parseCompoundFully.
        t.registerMethodRedirect(compoundTag, "method_10562", "(Ljava/lang/String;)" + lCompound,
                compoundTag, "method_68568", "(Ljava/lang/String;)" + lCompound);
        t.registerMethodRedirect(listTag, "method_10602", "(I)" + lCompound,
                listTag, "method_68582", "(I)" + lCompound);
        t.registerMethodRedirect("net/minecraft/class_2522", "method_10718", "(Ljava/lang/String;)" + lCompound,
                "net/minecraft/class_2522", "method_67315", "(Ljava/lang/String;)" + lCompound);
        registerNbtLookupCalls(t, true);
    }

    /** 1.21.11 made CompoundTag.remove(String) return the removed tag; 1.21.5 to 1.21.10 kept void. */
    public static void registerNbtRemoveReturnsTag(RetromodTransformer t) {
        registerNbtRemoveReturnsTag(t, "net/minecraft/nbt/CompoundTag", "remove", "Lnet/minecraft/nbt/Tag;");
    }

    /** The same change in the intermediary names a Fabric 1.21.11 host runs; the method kept method_10551. */
    public static void registerNbtRemoveReturnsTagIntermediary(RetromodTransformer t) {
        registerNbtRemoveReturnsTag(t, "net/minecraft/class_2487", "method_10551", "Lnet/minecraft/class_2520;");
    }

    private static void registerNbtRemoveReturnsTag(RetromodTransformer t, String compoundTag, String remove,
            String lTag) {
        // Call the returning form, then POP the result the void call never had.
        t.registerConvertingRedirect(compoundTag, remove, "(Ljava/lang/String;)V",
                compoundTag, remove, "(Ljava/lang/String;)" + lTag, 0, Opcodes.POP);
    }

    static final String NBT_LOOKUP = "com/retromod/polyfill/registry/NbtCompatLookup";

    /** One old NBT call NbtCompatLookup answers: its Mojang and intermediary names, its old return, and the helper. */
    record NbtLookupCall(String name, String intermediary, String returnDesc, String helper) {}

    /** The CompoundTag getters 1.21.5 turned into Optional getters or removed, each taking a String key. */
    static final List<NbtLookupCall> COMPOUND_LOOKUPS = List.of(
            new NbtLookupCall("getString", "method_10558", "Ljava/lang/String;", "compoundGetString"),
            new NbtLookupCall("getInt", "method_10550", "I", "compoundGetInt"),
            new NbtLookupCall("getLong", "method_10537", "J", "compoundGetLong"),
            new NbtLookupCall("getFloat", "method_10583", "F", "compoundGetFloat"),
            new NbtLookupCall("getDouble", "method_10574", "D", "compoundGetDouble"),
            new NbtLookupCall("getBoolean", "method_10577", "Z", "compoundGetBoolean"),
            new NbtLookupCall("getByte", "method_10571", "B", "compoundGetByte"),
            new NbtLookupCall("getShort", "method_10568", "S", "compoundGetShort"),
            new NbtLookupCall("getByteArray", "method_10547", "[B", "compoundGetByteArray"),
            new NbtLookupCall("getIntArray", "method_10561", "[I", "compoundGetIntArray"),
            new NbtLookupCall("getLongArray", "method_10565", "[J", "compoundGetLongArray"),
            new NbtLookupCall("getUUID", "method_25926", "Ljava/util/UUID;", "compoundGetUUID"),
            new NbtLookupCall("hasUUID", "method_25928", "Z", "compoundHasUUID"),
            new NbtLookupCall("getTagType", "method_10540", "B", "compoundGetTagType"));

    /** The ListTag getters 1.21.5 turned into Optional getters, each taking an int index. */
    static final List<NbtLookupCall> LIST_LOOKUPS = List.of(
            new NbtLookupCall("getString", "method_10608", "Ljava/lang/String;", "listGetString"),
            new NbtLookupCall("getInt", "method_10600", "I", "listGetInt"));

    /** The NumericTag getters 1.21.5 renamed when it made NumericTag an interface; intermediary kept their ids. */
    static final List<NbtLookupCall> NUMERIC_LOOKUPS = List.of(
            new NbtLookupCall("getAsByte", "method_10698", "B", "numericGetAsByte"),
            new NbtLookupCall("getAsShort", "method_10696", "S", "numericGetAsShort"),
            new NbtLookupCall("getAsInt", "method_10701", "I", "numericGetAsInt"),
            new NbtLookupCall("getAsLong", "method_10699", "J", "numericGetAsLong"),
            new NbtLookupCall("getAsFloat", "method_10700", "F", "numericGetAsFloat"),
            new NbtLookupCall("getAsDouble", "method_10697", "D", "numericGetAsDouble"),
            new NbtLookupCall("getAsNumber", "method_10702", "Ljava/lang/Number;", "numericGetAsNumber"));

    /** The tag types a pre-1.21.5 mod can name as the owner of a number getter. */
    static final String[] NUMBER_TAGS = {"NumericTag", "ByteTag", "ShortTag", "IntTag", "LongTag", "FloatTag", "DoubleTag"};

    /** Every tag type a pre-1.21.5 mod can name as the owner of getAsString(), with its intermediary name. */
    static final String[][] TAG_TYPES = {
            {"Tag", "class_2520"}, {"StringTag", "class_2519"}, {"CompoundTag", "class_2487"},
            {"ListTag", "class_2499"}, {"CollectionTag", "class_2483"}, {"NumericTag", "class_2514"},
            {"ByteTag", "class_2481"}, {"ShortTag", "class_2516"}, {"IntTag", "class_2497"},
            {"LongTag", "class_2503"}, {"FloatTag", "class_2494"}, {"DoubleTag", "class_2489"},
            {"ByteArrayTag", "class_2479"}, {"IntArrayTag", "class_2495"}, {"LongArrayTag", "class_2501"},
            {"EndTag", "class_2491"}};

    /** The getters 1.21.5 turned into Optional getters or renamed, and the UUID and text helpers it removed. */
    static void registerNbtGetterBridges(RetromodTransformer t) {
        registerNbtLookupCalls(t, false);
    }

    /** Points each old call at NbtCompatLookup, keyed by Mojang names or by intermediary ones. */
    private static void registerNbtLookupCalls(RetromodTransformer t, boolean intermediary) {
        String compoundTag = intermediary ? "net/minecraft/class_2487" : "net/minecraft/nbt/CompoundTag";
        String listTag = intermediary ? "net/minecraft/class_2499" : "net/minecraft/nbt/ListTag";
        // NbtCompatLookup tries the Optional getter and then the old one, under either name, and
        // returns the old default for a missing key. Forge-family and offline output embed a copy.
        ensureSyntheticRegistered(t, NBT_LOOKUP);

        // Each helper takes the receiver first, since the instance call becomes a static one.
        for (NbtLookupCall call : COMPOUND_LOOKUPS) {
            t.registerMethodRedirect(compoundTag, intermediary ? call.intermediary() : call.name(),
                    "(Ljava/lang/String;)" + call.returnDesc(),
                    NBT_LOOKUP, call.helper(), "(Ljava/lang/Object;Ljava/lang/String;)" + call.returnDesc(), true);
        }
        t.registerMethodRedirect(compoundTag, intermediary ? "method_25927" : "putUUID",
                "(Ljava/lang/String;Ljava/util/UUID;)V",
                NBT_LOOKUP, "compoundPutUUID", "(Ljava/lang/Object;Ljava/lang/String;Ljava/util/UUID;)V", true);
        for (NbtLookupCall call : LIST_LOOKUPS) {
            t.registerMethodRedirect(listTag, intermediary ? call.intermediary() : call.name(),
                    "(I)" + call.returnDesc(),
                    NBT_LOOKUP, call.helper(), "(Ljava/lang/Object;I)" + call.returnDesc(), true);
        }
        // javac names the receiver's own type as the owner, so each tag type needs its own key.
        for (String[] type : TAG_TYPES) {
            String owner = intermediary ? "net/minecraft/" + type[1] : "net/minecraft/nbt/" + type[0];
            t.registerMethodRedirect(owner, intermediary ? "method_10714" : "getAsString", "()Ljava/lang/String;",
                    NBT_LOOKUP, "tagGetAsString", "(Ljava/lang/Object;)Ljava/lang/String;", true);
        }
        // The number getters go through the helper, which also covers NumericTag turning into an
        // interface. Under intermediary ids nothing was renamed, so only that interface needs keys.
        for (String type : intermediary ? new String[]{"class_2514"} : NUMBER_TAGS) {
            String owner = (intermediary ? "net/minecraft/" : "net/minecraft/nbt/") + type;
            for (NbtLookupCall call : NUMERIC_LOOKUPS) {
                t.registerMethodRedirect(owner, intermediary ? call.intermediary() : call.name(), "()" + call.returnDesc(),
                        NBT_LOOKUP, call.helper(), "(Ljava/lang/Object;)" + call.returnDesc(), true);
            }
        }
    }

    public static void registerRegistryValueGetterRename(RetromodTransformer transformer) {
        registerRegistryValueGetterRename(transformer, "net/minecraft/resources/Identifier");
    }

    /** The 1.21.2 renames registryOrThrow to lookupOrThrow and getHolderOrThrow to getOrThrow. */
    public static void registerRegistryLookupRenames(RetromodTransformer transformer) {
        // A server's registryAccess() is typed Frozen, so javac names that owner at the call.
        String lookupDesc = "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Registry;";
        // registry(key) became lookup(key), which now returns the same optional registry.
        String optionalDesc = "(Lnet/minecraft/resources/ResourceKey;)Ljava/util/Optional;";
        for (String owner : new String[]{"net/minecraft/core/RegistryAccess",
                "net/minecraft/core/RegistryAccess$Frozen"}) {
            transformer.registerMethodRedirect(owner, "registryOrThrow", lookupDesc, owner, "lookupOrThrow",
                    lookupDesc);
            transformer.registerMethodRedirect(owner, "registry", optionalDesc, owner, "lookup", optionalDesc);
        }
        // BuiltInRegistries.ITEM and BLOCK are DefaultedRegistry, which their calls name as the owner.
        String holderDesc = "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Holder$Reference;";
        for (String owner : new String[]{"net/minecraft/core/Registry",
                "net/minecraft/core/DefaultedRegistry"}) {
            transformer.registerMethodRedirect(owner, "getHolderOrThrow", holderDesc, owner, "getOrThrow",
                    holderDesc);
        }
    }

    /**
     * 1.21.2 made {@code get(id)} return the holder {@code Optional} and named the value lookup
     * {@code getValue}. Only the old {@code Object}-returning shape is moved, so a newer mod's
     * Optional call is left alone. Hosts from 1.21.2 to 1.21.10 still call the id class
     * {@code ResourceLocation}.
     */
    public static void registerRegistryValueGetterRename(RetromodTransformer transformer, String idClass) {
        String getIdDesc = "(L" + idClass + ";)Ljava/lang/Object;";
        for (String owner : new String[]{
                "net/minecraft/core/Registry",
                "net/minecraft/core/DefaultedRegistry",
                "net/minecraft/core/MappedRegistry",
                "net/minecraft/core/DefaultedMappedRegistry"}) {
            transformer.registerMethodRedirect(owner, "get", getIdDesc, owner, "getValue", getIdDesc);
        }
    }

    /** SoundEvent became a record in 1.21.2, so getLocation() is location() on those hosts; {@code idClass} as for the getter above. */
    public static void registerSoundEventLocation(RetromodTransformer transformer, String idClass) {
        String soundEvent = "net/minecraft/sounds/SoundEvent";
        String desc = "()L" + idClass + ";";
        transformer.registerMethodRedirect(soundEvent, "getLocation", desc, soundEvent, "location", desc);
    }
}
