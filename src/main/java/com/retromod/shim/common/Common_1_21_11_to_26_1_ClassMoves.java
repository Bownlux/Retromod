/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.Opcodes;

/**
 * Loader-agnostic Mojang->Mojang vanilla class moves/renames for the 1.21.11 -> 26.1 jump (#64).
 *
 * <p>Scope is vanilla {@code net/minecraft/**} and {@code com/mojang/blaze3d/**} only; loader-API
 * renames live in each loader's shim. Called from {@code Fabric_1_21_11_to_26_1} and
 * {@code NeoForge_1_21_11_to_26_1}.</p>
 */
public final class Common_1_21_11_to_26_1_ClassMoves {

    private Common_1_21_11_to_26_1_ClassMoves() {}

    public static void register(RetromodTransformer transformer) {
        // 26.1 removed the single-arg is() overloads from BlockState/ItemStack/FluidState
        // (NoSuchMethodError on block placement, tag checks, item comparisons - the Macaw's
        // Bridge_Block.onPlace crash). Bridged by a per-mod synthetic.
        IsOverloadBridgeSynthetic.register(transformer);

        registerOfficialEntityTypeBuildBridge(transformer);
        registerLegacyContainerInput(transformer);

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

        // ChunkPos(long) ctor removed in 26.x; rewrite to the static ChunkPos.unpack(long).
        transformer.registerConstructorRedirect(
            "net/minecraft/world/level/ChunkPos", "(J)V",
            "net/minecraft/world/level/ChunkPos", "unpack",
            "(J)Lnet/minecraft/world/level/ChunkPos;");

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
        // GpuDevice/RenderPipeline refactor (Forge wires this directly instead).
        RemovedRenderStateNeutralize.register(transformer);

        registerRegistryValueGetterRename(transformer);
        registerClientAccessorRenames26_1(transformer);
        registerCorpus26xDescriptorAdaptations(transformer);
    }

    /**
     * Repairs the GameRules constant rename that occurred after the bundled 1.21.4 intermediary
     * mapping was generated. Fabric kept the same field ids, so a current Fabric mod still carries
     * names such as {@code field_19388}; the base map expands those ids to the old {@code RULE_*}
     * spelling before this owner-scoped pass selects the 26.1 field. Every entry below was matched
     * through the 1.21.11 intermediary mapping and verified on the 26.1.2 host class.
     *
     * <p>Retromod refuses {@code RULE_DOFIRETICK}, whose boolean contract became a radius, and the
     * three negative-to-positive rules {@code RULE_DISABLE_ELYTRA_MOVEMENT_CHECK},
     * {@code RULE_DISABLE_PLAYER_MOVEMENT_CHECK}, and {@code RULE_DISABLE_RAIDS}. A field rename
     * cannot preserve those values without an inversion adapter.</p>
     */
    public static void registerFabricGameRuleFieldRenames(RetromodTransformer transformer) {
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
        for (String[] rename : renames) {
            transformer.registerFieldRedirect(
                    owner, rename[0], descriptor,
                    owner, rename[1], descriptor);
        }
    }

    /**
     * Register the official-name half of the EntityType builder descriptor bridge. Kept as a
     * focused entry point because Forge mirrors the common vanilla table rather than calling
     * {@link #register(RetromodTransformer)} wholesale.
     */
    public static void registerOfficialEntityTypeBuildBridge(RetromodTransformer transformer) {
        // EntityType.Builder.build(String) changed to build(ResourceKey) before 26.1. Fabric mods
        // remapped into the official namespace and Mojang-named loader mods still carry the old
        // call, so reconstruct the key through the embedded reflective bridge (#162).
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

        // JOML const-interface widening: the vertex API's Matrix4f parameter became the immutable
        // Matrix4fc interface by 26.1 (verified present on 26.1+26.2). A 1.21.1 mod links against the
        // concrete Matrix4f overload, which no longer exists; the concrete Matrix4f IS-A Matrix4fc, so
        // rewriting just the parameter descriptor keeps the call valid. addVertex(Matrix4f,FFF) is the
        // hottest of the cluster (VertexConsumer 12 mods, BufferBuilder 7). BufferBuilder inherits the
        // widened default from VertexConsumer, so the same-owner rewrite resolves fine.
        transformer.registerMethodRedirect(
                "com/mojang/blaze3d/vertex/VertexConsumer", "addVertex",
                "(Lorg/joml/Matrix4f;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
                "com/mojang/blaze3d/vertex/VertexConsumer", "addVertex",
                "(Lorg/joml/Matrix4fc;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        transformer.registerMethodRedirect(
                "com/mojang/blaze3d/vertex/BufferBuilder", "addVertex",
                "(Lorg/joml/Matrix4f;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
                "com/mojang/blaze3d/vertex/BufferBuilder", "addVertex",
                "(Lorg/joml/Matrix4fc;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
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
     *   <li>{@code Mth.cos(F)F}/{@code Mth.sin(F)F} widened their arg to {@code (D)F} (16 mods): F2D
     *       the single arg (the last value pushed) and call the double overload.</li>
     *   <li>{@code Window.getGuiScale()D} narrowed its return to {@code ()I} (16 mods): call it, then
     *       I2D the int back to the double the caller expects.</li>
     *   <li>{@code SoundManager.play(SoundInstance)V} now returns a {@code SoundEngine$PlayResult}
     *       (24 mods): call the returning form, then POP the result the void call never had.</li>
     *   <li>{@code CompoundTag.getList(String,int)ListTag} dropped its type-hint int for
     *       {@code getListOrEmpty(String)ListTag} (15 mods): drop the trailing int, rename.</li>
     *   <li>{@code Screen.hasControlDown()/hasShiftDown()/hasAltDown()} moved from static helpers on
     *       {@code Screen} to instance methods on {@code Minecraft} (24 mods): re-express as
     *       {@code Minecraft.getInstance().hasX()}.</li>
     * </ul>
     */
    public static void registerCorpus26xDescriptorAdaptations(RetromodTransformer t) {
        // Mth.cos/sin: (F)F -> (D)F, widen the single arg (F2D).
        t.registerConvertingRedirect("net/minecraft/util/Mth", "cos", "(F)F",
                "net/minecraft/util/Mth", "cos", "(D)F", Opcodes.F2D, 0);
        t.registerConvertingRedirect("net/minecraft/util/Mth", "sin", "(F)F",
                "net/minecraft/util/Mth", "sin", "(D)F", Opcodes.F2D, 0);
        // Window.getGuiScale: ()D -> ()I, widen the result (I2D).
        t.registerConvertingRedirect("com/mojang/blaze3d/platform/Window", "getGuiScale", "()D",
                "com/mojang/blaze3d/platform/Window", "getGuiScale", "()I", 0, Opcodes.I2D);
        // SoundManager.play: (SoundInstance)V -> ()PlayResult, discard the new result (POP).
        t.registerConvertingRedirect(
                "net/minecraft/client/sounds/SoundManager", "play",
                "(Lnet/minecraft/client/resources/sounds/SoundInstance;)V",
                "net/minecraft/client/sounds/SoundManager", "play",
                "(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
                0, Opcodes.POP);
        // Vec3.<init>(org.joml.Vector3f) widened its param to the Vector3fc INTERFACE in 26.x (the
        // joml concrete->interface modernization; the concrete-typed ctor is gone). Vector3f
        // implements Vector3fc, so the value on the stack is already assignable (no checkcast
        // needed); just widen the INVOKESPECIAL descriptor, no arg/return adaptation. Found in-game
        // on 26.2 Fabric: jade's CommonProxy died NoSuchMethodError Vec3.<init>(Vector3f).
        t.registerConvertingRedirect(
                "net/minecraft/world/phys/Vec3", "<init>", "(Lorg/joml/Vector3f;)V",
                "net/minecraft/world/phys/Vec3", "<init>", "(Lorg/joml/Vector3fc;)V",
                0, 0);
        // PoseStack.mulPose(Quaternionf)/(Matrix4f) widened their arg to the joml INTERFACE
        // (Quaternionfc/Matrix4fc) in 26.x, same concrete->interface modernization as Vec3 above and
        // the addVertex(Matrix4f) rename. The concrete-typed overloads are gone, so a rendering mod
        // calling them dies NoSuchMethodError. The value on the stack already implements the
        // interface, so just widen the descriptor (no cast). PoseStack.mulPose is ubiquitous in
        // rendering mods (found via CERBON's Better Beacons, issue #159).
        t.registerConvertingRedirect(
                "com/mojang/blaze3d/vertex/PoseStack", "mulPose", "(Lorg/joml/Quaternionf;)V",
                "com/mojang/blaze3d/vertex/PoseStack", "mulPose", "(Lorg/joml/Quaternionfc;)V",
                0, 0);
        t.registerConvertingRedirect(
                "com/mojang/blaze3d/vertex/PoseStack", "mulPose", "(Lorg/joml/Matrix4f;)V",
                "com/mojang/blaze3d/vertex/PoseStack", "mulPose", "(Lorg/joml/Matrix4fc;)V",
                0, 0);
        // Util.backgroundExecutor()/ioPool()/nonCriticalIoPool() returned java.util.concurrent
        // .ExecutorService and now return net.minecraft.TracingExecutor (a record wrapping the
        // service) in 26.x. Retarget to the TracingExecutor-returning form, then append
        // TracingExecutor.service() to recover the ExecutorService the caller expects. Found in-game
        // on 26.2 Fabric: jade's ClientProxy died NoSuchMethodError Util.backgroundExecutor()
        // ExecutorService. (Util is at net/minecraft/util/Util on 26.x; Fabric mods reach it via the
        // intermediary->Mojang remap, NeoForge/Forge via the class-move.)
        for (String pool : new String[]{"backgroundExecutor", "ioPool", "nonCriticalIoPool"}) {
            t.registerReturnUnwrapRedirect(
                    "net/minecraft/util/Util", pool, "()Ljava/util/concurrent/ExecutorService;",
                    "()Lnet/minecraft/TracingExecutor;",
                    "net/minecraft/TracingExecutor", "service", "()Ljava/util/concurrent/ExecutorService;");
        }
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
        // CompoundTag.getList(String,int) -> getListOrEmpty(String): drop the type-hint int.
        t.registerArgDropMethodRedirect(
                "net/minecraft/nbt/CompoundTag", "getList",
                "(Ljava/lang/String;I)Lnet/minecraft/nbt/ListTag;",
                "net/minecraft/nbt/CompoundTag", "getListOrEmpty",
                "(Ljava/lang/String;)Lnet/minecraft/nbt/ListTag;");
        // Screen.hasControlDown/hasShiftDown/hasAltDown static -> Minecraft.getInstance().hasX().
        for (String m : new String[]{"hasControlDown", "hasShiftDown", "hasAltDown"}) {
            t.registerSingletonStaticRedirect(
                    "net/minecraft/client/gui/screens/Screen", m, "()Z",
                    "net/minecraft/client/Minecraft", "getInstance",
                    "()Lnet/minecraft/client/Minecraft;", m, "()Z");
        }

        registerNbtApiAdaptations26x(t);
        registerTextEventBridges26x(t);
        registerKeyMappingBridges26x(t);
        registerReloadListenerBridge26x(t);
        registerClientStructureBridges26x(t);
        registerLegacyClientInteractionBridges26x(t);
        registerLegacyGuiCalls26x(t);
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
        // Block-layer getters: nearest surviving RenderType-typed tokens.
        t.registerMethodRedirect(rt, "solid", rtRet, rts, "solidMovingBlock", rtRet);
        t.registerMethodRedirect(rt, "cutout", rtRet, rts, "cutoutMovingBlock", rtRet);
        t.registerMethodRedirect(rt, "cutoutMipped", rtRet, rts, "cutoutMovingBlock", rtRet);
        t.registerMethodRedirect(rt, "translucent", rtRet, rts, "translucentMovingBlock", rtRet);
        t.registerMethodRedirect(rt, "tripwire", rtRet, rts, "translucentMovingBlock", rtRet);
        // Entity getters: same names, new owner; the cull flip per the javadoc.
        t.registerMethodRedirect(rt, "entitySolid", idArg, rts, "entitySolid", idArg);
        t.registerMethodRedirect(rt, "entityCutout", idArg, rts, "entityCutoutCull", idArg);
        t.registerMethodRedirect(rt, "entityCutoutNoCull", idArg, rts, "entityCutout", idArg);
        t.registerMethodRedirect(rt, "entityCutoutNoCull", idBoolArg, rts, "entityCutout", idBoolArg);
        t.registerMethodRedirect(rt, "entityTranslucent", idArg, rts, "entityTranslucent", idArg);
        t.registerMethodRedirect(rt, "entityTranslucent", idBoolArg, rts, "entityTranslucent", idBoolArg);
        t.registerMethodRedirect(rt, "text", idArg, rts, "text", idArg);

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
        }
    }

    /**
     * {@code ClickEvent}/{@code HoverEvent} constructor bridges for the 1.21.5 text-component rework
     * (14+19 mods across the corpus audits; text interactions are ubiquitous). Both became sealed
     * INTERFACES with per-action record subtypes, so the old {@code new ClickEvent(Action,String)} /
     * {@code new HoverEvent(Action,Object)} constructors are gone. A constructor-to-factory redirect
     * rewrites those {@code new}s to {@link com.retromod.polyfill.minecraft.RetroTextEvents}, which
     * dispatches on the action to the right subtype (the dispatch the old constructor did internally).
     * The factory is registered as a synthetic so the Forge/NeoForge per-mod embedder relocates a
     * JPMS-split-package-safe copy into any mod that references it (Fabric injects it directly). Gated
     * 26.1+ by the caller (these are interfaces on every 26.1+ host, so the old constructor is
     * genuinely gone; on a pre-1.21.5 host the redirect must not fire, and this shim never runs there).
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

    /**
     * {@code KeyMapping} (keybind) constructor bridges for the 26.x category refactor. The keybind
     * category changed from a {@code String} translation key to a {@code KeyMapping.Category} record,
     * so the old {@code new KeyMapping(name, [type,] code, categoryString)} constructors are gone and
     * ANY mod that adds a keybind dies {@code NoSuchMethodError} at client init (Jade, found in-game
     * on 26.2 Fabric). A constructor-to-factory redirect rewrites both the 4-arg (with an
     * {@code InputConstants.Type}) and 3-arg (KEYSYM default) forms to
     * {@link com.retromod.polyfill.minecraft.RetroKeyMapping}, which resolves the category string to
     * a {@code Category} (vanilla constants, else a registered {@code retromod:} category, else MISC)
     * and calls the real constructor. Broadly applicable: every keybind-adding mod hits this on 26.x.
     */
    public static void registerKeyMappingBridges26x(RetromodTransformer t) {
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
     * The 1.21.5 NBT read refactor (verified landed by 26.1: old signature gone, new present on the
     * 26.1 AND 26.2 jars). A 1.20.1/1.21.1 mod's save/load code links against the pre-refactor NBT
     * accessors, so these break broadly across content mods on a 26.x host (top-60 Fabric 1.20.1 audit
     * frequencies noted). Each is owner+descriptor-scoped, so the co-existing {@code Optional}-returning
     * overloads (e.g. the new {@code getCompound(String):Optional}) are never touched.
     * <ul>
     *   <li>{@code CompoundTag.contains(String,int)Z} dropped its tag-type-hint int for
     *       {@code contains(String)Z} (12 mods): drop the trailing int.</li>
     *   <li>{@code CompoundTag.getCompound(String)CompoundTag} -> {@code getCompoundOrEmpty(String)}
     *       and {@code ListTag.getCompound(int)CompoundTag} -> {@code getCompoundOrEmpty(int)} (10+9
     *       mods): plain renames (the plain getters now return {@code Optional}).</li>
     *   <li>{@code CompoundTag.remove(String)V} now returns the removed {@code Tag} (9 mods): call it,
     *       then POP the result the void call never had.</li>
     *   <li>{@code TagParser.parseTag(String)CompoundTag} -> {@code parseCompoundFully(String)} (9
     *       mods): plain rename (same params/return; the checked exception is not enforced in
     *       bytecode).</li>
     * </ul>
     */
    public static void registerNbtApiAdaptations26x(RetromodTransformer t) {
        // CompoundTag.contains(String,int) -> contains(String): drop the tag-type-hint int.
        t.registerArgDropMethodRedirect("net/minecraft/nbt/CompoundTag", "contains",
                "(Ljava/lang/String;I)Z", "net/minecraft/nbt/CompoundTag", "contains", "(Ljava/lang/String;)Z");
        // getCompound -> getCompoundOrEmpty (the plain getter now returns Optional). Descriptor-scoped
        // to the CompoundTag-returning form so the new Optional overload is untouched.
        t.registerMethodRedirect("net/minecraft/nbt/CompoundTag", "getCompound",
                "(Ljava/lang/String;)Lnet/minecraft/nbt/CompoundTag;",
                "net/minecraft/nbt/CompoundTag", "getCompoundOrEmpty",
                "(Ljava/lang/String;)Lnet/minecraft/nbt/CompoundTag;");
        t.registerMethodRedirect("net/minecraft/nbt/ListTag", "getCompound",
                "(I)Lnet/minecraft/nbt/CompoundTag;",
                "net/minecraft/nbt/ListTag", "getCompoundOrEmpty", "(I)Lnet/minecraft/nbt/CompoundTag;");
        // CompoundTag.remove(String)V -> remove(String)Tag, discard the now-returned removed tag (POP).
        t.registerConvertingRedirect("net/minecraft/nbt/CompoundTag", "remove", "(Ljava/lang/String;)V",
                "net/minecraft/nbt/CompoundTag", "remove", "(Ljava/lang/String;)Lnet/minecraft/nbt/Tag;",
                0, Opcodes.POP);
        // TagParser.parseTag -> parseCompoundFully (same params/return; checked exn not enforced in bytecode).
        t.registerMethodRedirect("net/minecraft/nbt/TagParser", "parseTag",
                "(Ljava/lang/String;)Lnet/minecraft/nbt/CompoundTag;",
                "net/minecraft/nbt/TagParser", "parseCompoundFully",
                "(Ljava/lang/String;)Lnet/minecraft/nbt/CompoundTag;");
    }

    public static void registerRegistryValueGetterRename(RetromodTransformer transformer) {
        String getIdDesc = "(Lnet/minecraft/resources/Identifier;)Ljava/lang/Object;";
        for (String owner : new String[]{
                "net/minecraft/core/Registry",
                "net/minecraft/core/DefaultedRegistry",
                "net/minecraft/core/MappedRegistry",
                "net/minecraft/core/DefaultedMappedRegistry"}) {
            transformer.registerMethodRedirect(owner, "get", getIdDesc, owner, "getValue", getIdDesc);
        }
    }
}
