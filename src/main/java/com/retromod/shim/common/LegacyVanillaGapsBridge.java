/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.HostLibraryNames;
import com.retromod.util.McReflect;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Single vanilla members that kept otherwise clean 1.16.5 to 1.21.1 mods from linking on 26.x, each rebuilt on its replacement. */
public final class LegacyVanillaGapsBridge {

    static final String ROOT = RebuiltApiClasses.ROOT + "minecraft/";
    static final String WORLD = ROOT + "world/LegacyVanillaGaps";
    static final String CLIENT = ROOT + "client/LegacyVanillaClientGaps";
    static final String LAN_SCREEN = ROOT + "client/gui/screens/ShareToLanScreen";
    static final String TOASTS = ROOT + "client/LegacyToastFields";
    static final String FRAMES = ROOT + "client/gui/LegacyFrameTooltips";
    static final String FUELS = ROOT + "world/LegacyFurnaceFuels";

    static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    static final String ITEM = "net/minecraft/world/item/Item";
    static final String FOOD_DATA = "net/minecraft/world/food/FoodData";
    static final String STYLE = "net/minecraft/network/chat/Style";
    static final String ENCHANTMENT = "net/minecraft/world/item/enchantment/Enchantment";
    static final String ENCHANTMENT_HELPER = "net/minecraft/world/item/enchantment/EnchantmentHelper";
    static final String CRASH_REPORT = "net/minecraft/CrashReport";
    static final String MTH = "net/minecraft/util/Mth";
    static final String LOOK_CONTROL = "net/minecraft/world/entity/ai/control/LookControl";
    static final String DIRECTION = "net/minecraft/core/Direction";
    static final String PROJECTILE = "net/minecraft/world/entity/projectile/Projectile";
    static final String BUF = "net/minecraft/network/FriendlyByteBuf";
    static final String SERVER_PLAYER = "net/minecraft/server/level/ServerPlayer";
    static final String TNT = "net/minecraft/world/level/block/TntBlock";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String ENTITY_TYPE = "net/minecraft/world/entity/EntityType";
    static final String STACKED_CONTENTS = "net/minecraft/world/entity/player/StackedContents";
    static final String DISPLAY_INFO = "net/minecraft/advancements/DisplayInfo";
    static final String COMBAT_TRACKER = "net/minecraft/world/damagesource/CombatTracker";
    static final String DAMAGE_SOURCE = "net/minecraft/world/damagesource/DamageSource";
    static final String SECTION_POS = "net/minecraft/core/SectionPos";
    static final String ENTITY_DATA_PACKET = "net/minecraft/network/protocol/game/ClientboundSetEntityDataPacket";
    static final String GSON_HELPER = "net/minecraft/util/GsonHelper";
    static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    static final String UTIL = "net/minecraft/util/Util";
    static final String CONDITION = "net/minecraft/client/renderer/block/dispatch/multipart/Condition";
    static final String INTERACT_PACKET = "net/minecraft/network/protocol/game/ServerboundInteractPacket";
    static final String SERVER_LISTENER = "net/minecraft/server/network/ServerGamePacketListenerImpl";
    static final String CLIENT_LISTENER = "net/minecraft/client/multiplayer/ClientPacketListener";
    static final String PATH_PACK = "net/minecraft/server/packs/PathPackResources";
    static final String PACK = "net/minecraft/server/packs/repository/Pack";
    static final String CHUNK_ACCESS = "net/minecraft/world/level/chunk/ChunkAccess";
    static final String PACKED_TICKS = CHUNK_ACCESS + "$PackedTicks";
    static final String EFFECT_INSTANCE = "net/minecraft/world/effect/MobEffectInstance";
    static final String CARVING = "net/minecraft/world/level/levelgen/GenerationStep$Carving";
    static final String LEGACY_CARVING = ROOT + "world/level/levelgen/LegacyCarvingStep";
    static final String GEN_SETTINGS = "net/minecraft/world/level/biome/BiomeGenerationSettings";
    static final String GEN_BUILDER = GEN_SETTINGS + "$Builder";
    static final String PLAIN_BUILDER = GEN_SETTINGS + "$PlainBuilder";

    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String TOAST_MANAGER = "net/minecraft/client/gui/components/toasts/ToastManager";
    static final String GAME_MODE = "net/minecraft/client/multiplayer/MultiPlayerGameMode";
    static final String LOCAL_PLAYER = "net/minecraft/client/player/LocalPlayer";
    static final String CLIENT_PLAYER = "net/minecraft/client/player/AbstractClientPlayer";
    static final String ENTITY_RENDERER = "net/minecraft/client/renderer/entity/EntityRenderer";
    static final String INPUT_TYPE = "com/mojang/blaze3d/platform/InputConstants$Type";
    static final String CUBE = "net/minecraft/client/model/geom/ModelPart$Cube";
    static final String VIDEO_SETTINGS = "net/minecraft/client/gui/screens/options/VideoSettingsScreen";
    static final String SOUND_MANAGER = "net/minecraft/client/sounds/SoundManager";
    static final String PINGER = "net/minecraft/client/multiplayer/ServerStatusPinger";
    static final String SPRITE = "net/minecraft/client/renderer/texture/TextureAtlasSprite";
    static final String TEXTURE = "net/minecraft/client/renderer/texture/AbstractTexture";
    static final String RECIPE_LISTENER = "net/minecraft/client/gui/screens/recipebook/RecipeUpdateListener";
    static final String GRAPHICS = "net/minecraft/client/gui/GuiGraphicsExtractor";
    static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    static final String DIRT_SCREEN = "net/minecraft/client/gui/screens/GenericDirtMessageScreen";
    static final String MESSAGE_SCREEN = "net/minecraft/client/gui/screens/GenericMessageScreen";
    static final String HOST_LAN_SCREEN = "net/minecraft/client/gui/screens/ShareToLanScreen";
    static final String GL_UTIL = "com/mojang/blaze3d/platform/GlUtil";
    static final String MATERIAL = "net/minecraft/world/level/material/Material";
    static final String NBT_UTILS = "net/minecraft/nbt/NbtUtils";
    static final String SLOT_ACCESS = "net/minecraft/world/entity/SlotAccess";
    static final String MUTABLE_COMPONENT = "net/minecraft/network/chat/MutableComponent";
    static final String BLOCK_POS = "net/minecraft/core/BlockPos";
    static final String POS_CONTAINING = "(Lnet/minecraft/core/Position;)Lnet/minecraft/core/BlockPos;";
    static final String LANGUAGES = "net/minecraft/client/resources/language/LanguageManager";
    static final String LANGUAGE_INFO = "net/minecraft/client/resources/language/LanguageInfo";
    static final String RENDER_SYSTEM = "com/mojang/blaze3d/systems/RenderSystem";
    static final String LIGHTING = "com/mojang/blaze3d/platform/Lighting";
    static final String KEYBOARD = "net/minecraft/client/KeyboardHandler";
    static final String CAMERA = "net/minecraft/client/Camera";
    static final String CAMERA_VECTORS = ROOT + "client/LegacyCameraVectors";
    /** The 1.16 matrix, alpha, lighting, normal and texture switches; the matrix ones take the old and the JOML matrix. */
    static final String[][] REMOVED_GL_STATE = {{"enableTexture", "()V"}, {"disableTexture", "()V"}, {"enableAlphaTest", "()V"},
            {"disableAlphaTest", "()V"}, {"alphaFunc", "(IF)V"}, {"enableRescaleNormal", "()V"}, {"disableRescaleNormal", "()V"},
            {"enableLighting", "()V"}, {"disableLighting", "()V"}, {"pushMatrix", "()V"}, {"popMatrix", "()V"},
            {"translatef", "(FFF)V"}, {"scalef", "(FFF)V"}, {"multMatrix", "(Lorg/joml/Matrix4f;)V"},
            {"multMatrix", "(Lcom/mojang/math/Matrix4f;)V"}};
    static final String LEGACY_MATERIAL = ROOT + "world/level/material/LegacyMaterial";
    static final String STATE = "net/minecraft/world/level/block/state/BlockState";
    static final String STATE_BASE = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
    static final String PLAYER = "net/minecraft/world/entity/player/Player";
    static final String WALK_ANIMATION = "net/minecraft/world/entity/WalkAnimationState";
    static final String BLOCK = "net/minecraft/world/level/block/Block";
    static final String BLOCK_ITEM = "net/minecraft/world/item/BlockItem";
    static final String ITEM_INPUT = "net/minecraft/commands/arguments/item/ItemInput";
    static final String EXTRA_CODECS = "net/minecraft/util/ExtraCodecs";
    static final String BUILTIN_DIMENSIONS = "net/minecraft/world/level/dimension/BuiltinDimensionTypes";
    static final String CLIENT_LEVEL = "net/minecraft/client/multiplayer/ClientLevel";
    static final String WINDOW = "com/mojang/blaze3d/platform/Window";
    static final String GL_UTIL_REBUILT = ROOT + "client/platform/LegacyGlUtil";
    static final String GPU_DEVICE = "com/mojang/blaze3d/systems/GpuDevice";
    static final String OGG_STREAM = "com/mojang/blaze3d/audio/OggAudioStream";
    static final String JORBIS_STREAM = "net/minecraft/client/sounds/JOrbisAudioStream";

    static final String L_STACK = "L" + ITEM_STACK + ";";
    static final String L_LEVEL = "Lnet/minecraft/world/level/Level;";
    static final String L_POS = "Lnet/minecraft/core/BlockPos;";
    static final String L_STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    static final String L_ENTITY = "L" + ENTITY + ";";
    static final String L_SLOT = "Lnet/minecraft/world/entity/EquipmentSlot;";
    static final String L_PLAYER = "Lnet/minecraft/world/entity/player/Player;";
    static final String MULTIMAP = "Lcom/google/common/collect/Multimap;";
    static final String L_TAG = "Lnet/minecraft/nbt/CompoundTag;";
    static final String L_ID = "L" + IDENTIFIER + ";";
    static final String L_COMPONENT = "Lnet/minecraft/network/chat/Component;";
    static final String L_SCREEN = "Lnet/minecraft/client/gui/screens/Screen;";
    static final String L_OPTIONS = "Lnet/minecraft/client/Options;";
    static final String NINE_SLICED_10 = "(" + L_ID + "IIIIIIIIII)V";
    static final String NINE_SLICED_9 = "(" + L_ID + "IIIIIIIII)V";
    static final String NINE_SLICED_12 = "(" + L_ID + "IIIIIIIIIIII)V";
    static final String OLD_CHUNK = "(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/UpgradeData;"
            + "Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/core/Registry;J"
            + "[Lnet/minecraft/world/level/chunk/LevelChunkSection;Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V";
    static final String NEW_CHUNK = OLD_CHUNK.replace("Lnet/minecraft/core/Registry;", "Lnet/minecraft/world/level/chunk/PalettedContainerFactory;");
    static final String OLD_PACKED_TICKS = "(Lnet/minecraft/world/ticks/SerializableTickContainer;Lnet/minecraft/world/ticks/SerializableTickContainer;)V";
    static final String OLD_READ_META = "(Ljava/lang/String;" + L_COMPONENT + "ZLnet/minecraft/server/packs/repository/Pack$ResourcesSupplier;"
            + "Lnet/minecraft/server/packs/PackType;Lnet/minecraft/server/packs/repository/Pack$Position;"
            + "Lnet/minecraft/server/packs/repository/PackSource;)L" + PACK + ";";
    static final String ATTACK_PACKET = "(" + L_ENTITY + "Z)L" + INTERACT_PACKET + ";";
    static final String OLD_FILL_REPORT = "(Lnet/minecraft/client/resources/language/LanguageManager;Ljava/lang/String;" + L_OPTIONS
            + "Lnet/minecraft/CrashReport;)V";
    static final String OLD_CUBE_COMPILE = "(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V";
    static final String OLD_PING = "(Lnet/minecraft/client/multiplayer/ServerData;Ljava/lang/Runnable;Ljava/lang/Runnable;)V";
    static final String SEND = "(Lnet/minecraft/network/protocol/Packet;)V";
    // A chunk super call spills the three values above the biome registry here, past any slot a constructor uses itself.
    static final int SPILL = RetromodTransformer.CONSTRUCTOR_ARG_SPILL_SLOT + 40;

    private LegacyVanillaGapsBridge() {}

    /** Registers each bridge whose old member the host lacks. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        register(t, host, !McReflect.isNeoForge() && !McReflect.isForge() && !McReflect.isForgeTarget());
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host, boolean fabricHost) {
        // Every helper is built on 26.x shapes, so an older target keeps its own members.
        if (!targetAtLeast("26.1")) return;
        registerItems(t, host);
        registerEntities(t, host);
        registerData(t, host, fabricHost);
        registerNetwork(t, host);
        registerClient(t, host);
        registerScreens(t, host);
        registerFurnaces(t, host);
        registerTooltips(t, host);
        registerMaterial(t, host);
        registerOldEra(t, host, fabricHost);
    }

    /** Members mods for 1.16.5 to 1.19.2 call that later versions removed: chat, block state reads, menus, sprites and languages. */
    static void registerOldEra(RetromodTransformer t, Function<String, ClassNode> host, boolean fabricHost) {
        String sendMessage = "(" + L_COMPONENT + "Ljava/util/UUID;)V";
        if (lacksInherited(host, PLAYER, "sendMessage", sendMessage)) {
            for (String owner : List.of(PLAYER, SERVER_PLAYER, LOCAL_PLAYER, CLIENT_PLAYER)) {
                t.registerMethodRedirect(owner, "sendMessage", sendMessage, WORLD, "sendMessage", "(L" + PLAYER + ";" + sendMessage.substring(1), true);
                t.limitRedirectToModsBefore(owner, "sendMessage", sendMessage, "1.19");
            }
        }
        String readState = "(" + L_TAG + ")" + L_STATE;
        if (lacks(host.apply(NBT_UTILS), "readBlockState", readState)) staticCall(t, WORLD, NBT_UTILS, "readBlockState", readState, "1.19.3");
        // On Fabric the pre-1.19 text bridge already reads an old text's key.
        if (!fabricHost && lacksInherited(host, MUTABLE_COMPONENT, "getKey", "()Ljava/lang/String;")) {
            receiverCall(t, WORLD, MUTABLE_COMPONENT, "getKey", "()Ljava/lang/String;", "1.19");
        }
        String vecPos = "(Lnet/minecraft/world/phys/Vec3;)V";
        if (lacks(host.apply(BLOCK_POS), "<init>", vecPos) && !lacks(host.apply(BLOCK_POS), "containing", POS_CONTAINING)) {
            t.registerConstructorRedirect(BLOCK_POS, vecPos, BLOCK_POS, "containing", POS_CONTAINING);
        }
        if (host.apply(MINECRAFT) == null) return;
        boolean used = false;
        for (String size : new String[]{"getWidth", "getHeight"}) {
            if (!lacks(host.apply(SPRITE), size, "()I")) continue;
            receiverCall(t, CLIENT, SPRITE, size, "()I", "1.19.3");
            used = true;
        }
        String info = "()Lnet/minecraft/client/resources/language/LanguageInfo;";
        if (lacks(host.apply(LANGUAGES), "getSelected", info)) {
            receiverCall(t, CLIENT, LANGUAGES, "getSelected", info, "1.19.4");
            receiverCall(t, CLIENT, LANGUAGE_INFO, "getCode", "()Ljava/lang/String;", "1.19.4");
            used = true;
        }
        if (used) RebuiltApiClasses.registerTree(t, CLIENT);
        // Fixed-function GL state 1.17 removed; old screens and overlays set it around their draws, and it has no effect now.
        ClassNode renderSystem = host.apply(RENDER_SYSTEM);
        for (String[] call : REMOVED_GL_STATE) {
            if (lacks(renderSystem, call[0], call[1])) t.registerRemovedMethodNeutralize(RENDER_SYSTEM, call[0], call[1]);
        }
        for (String lighting : new String[]{"turnOff", "turnBackOn"}) {
            if (lacks(host.apply(LIGHTING), lighting, "()V")) t.registerRemovedMethodNeutralize(LIGHTING, lighting, "()V");
        }
        if (lacks(host.apply(KEYBOARD), "setSendRepeatsToGui", "(Z)V")) t.registerRemovedMethodNeutralize(KEYBOARD, "setSendRepeatsToGui", "(Z)V");
    }

    /** The block material class 1.20 removed, rebuilt under its old name so every redirect keyed on it still matches. */
    static void registerMaterial(RetromodTransformer t, Function<String, ClassNode> host) {
        if (host.apply(MATERIAL) != null) return;
        // Its nested classes move with it, since they share package-private members.
        RebuiltApiClasses.registerTree(t, LEGACY_MATERIAL, new SimpleRemapper(Map.of(LEGACY_MATERIAL, MATERIAL,
                LEGACY_MATERIAL + "$Builder", MATERIAL + "$Builder", LEGACY_MATERIAL + "$Reactions", MATERIAL + "$Reactions")));
        // getMaterial() is read from what the state is now; the helper takes the state as Object, so a call javac
        // named on the state base passes the verifier too.
        String getMaterial = "()L" + MATERIAL + ";";
        for (String owner : List.of(STATE, STATE_BASE)) {
            t.registerMethodRedirect(owner, "getMaterial", getMaterial, MATERIAL, "fromState", "(Ljava/lang/Object;)L" + MATERIAL + ";", true);
            t.limitRedirectToModsBefore(owner, "getMaterial", getMaterial, "1.20");
        }
        // Properties.of(material, ...) gets the settings that material gave, as 1.20 spelled them out.
        String properties = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
        for (String extra : new String[]{"", "Lnet/minecraft/world/level/material/MapColor;", "Lnet/minecraft/world/item/DyeColor;",
                "Ljava/util/function/Function;"}) {
            String desc = "(L" + MATERIAL + ";" + extra + ")L" + properties + ";";
            t.registerMethodRedirect(properties, "of", desc, MATERIAL, "properties", desc);
        }
    }

    /** An old furnace's burn time override, which the host calls in its fuel values or server level form now. */
    static void registerFurnaces(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode furnace = host.apply(LegacyVanillaGapsAdapters.FURNACE);
        if (furnace == null || !lacks(furnace, "getBurnDuration", LegacyVanillaGapsAdapters.OLD_BURN)) return;
        if (!lacks(furnace, "getBurnDuration", LegacyVanillaGapsAdapters.FUEL_BURN)) {
            RebuiltApiClasses.registerTree(t, FUELS);
            t.enablePostProcess(LegacyVanillaGapsAdapters.FURNACE_FUEL_FLAG);
        } else if (!lacks(furnace, "getBurnDuration", LegacyVanillaGapsAdapters.LEVEL_BURN)) {
            t.enablePostProcess(LegacyVanillaGapsAdapters.FURNACE_LEVEL_FLAG);
        }
    }

    /** The screen tooltip calls 1.21.6 replaced with the GUI frame's own, set on the frame an old render method last saw. */
    static void registerTooltips(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode screen = host.apply(SCREEN);
        if (screen == null || !lacks(screen, "setTooltipForNextRenderPass", "(Ljava/util/List;)V") || host.apply(GRAPHICS) == null) return;
        String positioner = "Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;";
        String[] shapes = {"(Ljava/util/List;)V", "(Ljava/util/List;" + positioner + "Z)V",
                "(Lnet/minecraft/client/gui/components/Tooltip;" + positioner + "Z)V", "(" + L_COMPONENT + ")V"};
        for (String shape : shapes) {
            // The screen is passed as Object, so a call javac named on the mod's own screen class links too.
            String helperShape = "(Ljava/lang/Object;" + shape.substring(1);
            t.registerInheritedMethodRedirect(SCREEN, "setTooltipForNextRenderPass", shape, FRAMES, "setTooltipForNextRenderPass", helperShape);
            t.registerMethodRedirect(SCREEN, "setTooltipForNextRenderPass", shape, FRAMES, "setTooltipForNextRenderPass", helperShape, true);
            t.limitRedirectToModsBefore(SCREEN, "setTooltipForNextRenderPass", shape, LegacyVanillaGapsAdapters.FIRST_FRAME_TOOLTIPS);
        }
        t.enablePostProcess(LegacyVanillaGapsAdapters.GUI_FRAME_FLAG);
        RebuiltApiClasses.registerTree(t, FRAMES);
    }

    /** Item, food, enchantment and attribute reads in their 1.20.1 and 1.21.1 shapes. */
    static void registerItems(RetromodTransformer t, Function<String, ClassNode> host) {
        if (lacksInherited(host, ITEM_STACK, "getXpRepairRatio", "()F")) receiverCall(t, WORLD, ITEM_STACK, "getXpRepairRatio", "()F", "1.21");
        if (lacksInherited(host, ITEM_STACK, "getAttributeModifiers", "(" + L_SLOT + ")" + MULTIMAP)) {
            receiverCall(t, WORLD, ITEM_STACK, "getAttributeModifiers", "(" + L_SLOT + ")" + MULTIMAP, "1.20.5");
        }
        if (lacksInherited(host, ITEM, "getDefaultAttributeModifiers", "(" + L_SLOT + ")" + MULTIMAP)) {
            receiverCall(t, WORLD, ITEM, "getDefaultAttributeModifiers", "(" + L_SLOT + ")" + MULTIMAP, "1.20.5");
        }
        if (lacksInherited(host, ITEM_STACK, "getEnchantmentTags", "()Lnet/minecraft/nbt/ListTag;")) {
            receiverCall(t, WORLD, ITEM_STACK, "getEnchantmentTags", "()Lnet/minecraft/nbt/ListTag;", "1.20.5");
        }
        ClassNode helper = host.apply(ENCHANTMENT_HELPER);
        if (lacks(helper, "getEnchantmentId", "(" + L_TAG + ")" + L_ID)) {
            staticCall(t, WORLD, ENCHANTMENT_HELPER, "getEnchantmentId", "(" + L_TAG + ")" + L_ID, "1.20.5");
        }
        if (lacks(helper, "getEnchantmentLevel", "(" + L_TAG + ")I")) {
            staticCall(t, WORLD, ENCHANTMENT_HELPER, "getEnchantmentLevel", "(" + L_TAG + ")I", "1.20.5");
        }
        if (lacksInherited(host, ITEM_STACK, "getUseDuration", "()I")) receiverCall(t, WORLD, ITEM_STACK, "getUseDuration", "()I", "1.21");
        String foodProperties = "(" + L_STACK + "Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/food/FoodProperties;";
        if (lacksInherited(host, ITEM, "getFoodProperties", foodProperties)) receiverCall(t, WORLD, ITEM, "getFoodProperties", foodProperties, "1.20.5");
        String craftedBy = "(" + L_STACK + L_LEVEL + L_PLAYER + ")V";
        if (lacksInherited(host, ITEM, "onCraftedBy", craftedBy)) receiverCall(t, WORLD, ITEM, "onCraftedBy", craftedBy, "1.21.5");
        ClassNode food = host.apply(FOOD_DATA);
        if (lacks(food, "getExhaustionLevel", "()F")) receiverCall(t, WORLD, FOOD_DATA, "getExhaustionLevel", "()F", "1.21.5");
        if (lacks(food, "setExhaustion", "(F)V")) receiverCall(t, WORLD, FOOD_DATA, "setExhaustion", "(F)V", "1.21.5");
        String fullname = "(I)" + L_COMPONENT;
        if (lacks(host.apply(ENCHANTMENT), "getFullname", fullname)) receiverCall(t, WORLD, ENCHANTMENT, "getFullname", fullname, "1.21");
        // 26.1 took the frame an item rests in off the stack; Retromod's item property type knows it while an old function runs.
        if (lacksInherited(host, ITEM_STACK, "isFramed", "()Z")) receiverCall(t, WORLD, ITEM_STACK, "isFramed", "()Z", "26.1");
        String frame = "()Lnet/minecraft/world/entity/decoration/ItemFrame;";
        if (lacksInherited(host, ITEM_STACK, "getFrame", frame)) receiverCall(t, WORLD, ITEM_STACK, "getFrame", frame, "26.1");
        String accountSimple = "(" + L_STACK + ")V";
        if (lacks(host.apply(STACKED_CONTENTS), "accountSimpleStack", accountSimple)) {
            // The helper takes Object, since the host's StackedContents is a generic counter now.
            t.registerMethodRedirect(STACKED_CONTENTS, "accountSimpleStack", accountSimple, WORLD, "accountSimpleStack",
                    "(Ljava/lang/Object;" + L_STACK + ")V", true);
            t.limitRedirectToModsBefore(STACKED_CONTENTS, "accountSimpleStack", accountSimple, "1.21.2");
        }
        RebuiltApiClasses.registerTree(t, WORLD);
    }

    /** Entity, projectile, look control, damage and block members in their old shapes. */
    static void registerEntities(RetromodTransformer t, Function<String, ClassNode> host) {
        if (lacks(host.apply(LOOK_CONTROL), "isHasWanted", "()Z")) {
            t.registerMethodRedirect(LOOK_CONTROL, "isHasWanted", "()Z", LOOK_CONTROL, "isLookingAtTarget", "()Z");
            t.limitRedirectToModsBefore(LOOK_CONTROL, "isHasWanted", "()Z", "1.17");
        }
        String mayInteract = "(" + L_LEVEL + L_POS + ")Z";
        if (lacksInherited(host, PROJECTILE, "mayInteract", mayInteract)) receiverCall(t, WORLD, PROJECTILE, "mayInteract", mayInteract, "1.21.2");
        if (lacksInherited(host, ENTITY, "getPassengersRidingOffset", "()D")) {
            // Mobs call their parent's offset from their own override, so the call may name any entity class.
            t.registerInheritedMethodRedirect(ENTITY, "getPassengersRidingOffset", "()D", WORLD, "getPassengersRidingOffset", "(L" + ENTITY + ";)D");
            receiverCall(t, WORLD, ENTITY, "getPassengersRidingOffset", "()D", "1.20.5");
        }
        String customTag = "(" + L_LEVEL + L_PLAYER + L_ENTITY + L_TAG + ")V";
        if (lacks(host.apply(ENTITY_TYPE), "updateCustomEntityTag", customTag)) {
            staticCall(t, WORLD, ENTITY_TYPE, "updateCustomEntityTag", customTag, "1.20.5");
        }
        // The server player's latency moved to its connection in 1.20.2.
        if (lacksField(host.apply(SERVER_PLAYER), "latency")) {
            t.registerFieldStaticBridge(SERVER_PLAYER, "latency", WORLD, "latency", "(Ljava/lang/Object;)I",
                    "setLatency", "(Ljava/lang/Object;I)V");
        }
        if (lacks(host.apply(TNT), "explode", "(" + L_LEVEL + L_POS + ")V")) {
            staticCall(t, WORLD, TNT, "explode", "(" + L_LEVEL + L_POS + ")V", "1.21.2");
        }
        String record = "(L" + DAMAGE_SOURCE + ";FF)V";
        if (lacks(host.apply(COMBAT_TRACKER), "recordDamage", record)) receiverCall(t, WORLD, COMBAT_TRACKER, "recordDamage", record, "1.19.4");
        String thrown = "(" + L_ENTITY + L_ENTITY + ")L" + DAMAGE_SOURCE + ";";
        if (lacks(host.apply(DAMAGE_SOURCE), "thrown", thrown)) staticCall(t, WORLD, DAMAGE_SOURCE, "thrown", thrown, "1.19.4");
        // SectionPos.of(entity) takes the entity access interface every entity implements.
        String sectionOf = "(" + L_ENTITY + ")L" + SECTION_POS + ";";
        if (lacks(host.apply(SECTION_POS), "of", sectionOf)) {
            t.registerMethodRedirect(SECTION_POS, "of", sectionOf, SECTION_POS, "of",
                    "(Lnet/minecraft/world/level/entity/EntityAccess;)L" + SECTION_POS + ";");
        }
        String notify = "(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V";
        if (lacksInherited(host, PLAYER, "playNotifySound", notify)) receiverCall(t, WORLD, PLAYER, "playNotifySound", notify, "1.21.11");
        String systemMessage = "(" + L_COMPONENT + ")V";
        if (lacksInherited(host, ENTITY, "sendSystemMessage", systemMessage)) {
            receiverCall(t, WORLD, ENTITY, "sendSystemMessage", systemMessage, "1.21.5");
        }
        if (lacksInherited(host, WALK_ANIMATION, "update", "(FF)V")) receiverCall(t, WORLD, WALK_ANIMATION, "update", "(FF)V", "1.21.5");
        String clone = "(Lnet/minecraft/world/level/BlockGetter;" + L_POS + L_STATE + ")" + L_STACK;
        if (lacksInherited(host, BLOCK, "getCloneItemStack", clone)) registerCloneStack(t);
        if (lacksField(host.apply(SLOT_ACCESS), "NULL") && host.apply(SLOT_ACCESS) != null) {
            t.registerFieldRedirect(SLOT_ACCESS, "NULL", "L" + SLOT_ACCESS + ";", WORLD, "nullSlot", "()L" + SLOT_ACCESS + ";");
        }
        if (lacks(host.apply(EFFECT_INSTANCE), "setNoCounter", "(Z)V")) {
            t.registerRemovedMethodNeutralize(EFFECT_INSTANCE, "setNoCounter", "(Z)V");
        }
    }

    /** 1.20.1's {@code block.getCloneItemStack(getter, pos, state)}, answered by the state the host asks from 1.21.5. */
    static void registerCloneStack(RetromodTransformer t) {
        String clone = "(Lnet/minecraft/world/level/BlockGetter;" + L_POS + L_STATE + ")" + L_STACK;
        // LegacyBlockOverridesAdapter turns an override's super call into the host form, so it keeps its INVOKESPECIAL;
        // sent to the helper, it would reach the override again through the state.
        t.registerWrappingMethodRedirect(BLOCK, "getCloneItemStack", clone, WORLD, "getCloneItemStack", "(L" + BLOCK + ";" + clone.substring(1));
        t.limitRedirectToModsBefore(BLOCK, "getCloneItemStack", clone, "1.21");
    }

    /** Math, direction, codec, crash report, JSON, chunk and pack members in their old shapes. */
    static void registerData(RetromodTransformer t, Function<String, ClassNode> host, boolean fabricHost) {
        if (lacks(host.apply(MTH), "sqrt", "(D)F")) staticCall(t, WORLD, MTH, "sqrt", "(D)F", "1.17");
        String fromDelta = "(III)L" + DIRECTION + ";";
        if (lacks(host.apply(DIRECTION), "fromDelta", fromDelta)) staticCall(t, WORLD, DIRECTION, "fromDelta", fromDelta, "1.21.2");
        if (lacksField(host.apply(STYLE), "FORMATTING_CODEC")) {
            t.registerFieldRedirect(STYLE, "FORMATTING_CODEC", "Lcom/mojang/serialization/Codec;", WORLD, "formattingCodec",
                    "()Lcom/mojang/serialization/Codec;");
        }
        if (lacks(host.apply(CRASH_REPORT), "getFriendlyReport", "()Ljava/lang/String;")) {
            receiverCall(t, WORLD, CRASH_REPORT, "getFriendlyReport", "()Ljava/lang/String;", "1.20.5");
        }
        ClassNode gson = host.apply(GSON_HELPER);
        for (String input : new String[]{"Ljava/io/Reader;", "Ljava/lang/String;"}) {
            // The Gson package is built at runtime, since the release jar's shading would rename a literal one.
            String lenient = "(" + input + "Z)L" + HostLibraryNames.GSON_PACKAGE + "/JsonObject;";
            if (lacks(gson, "parse", lenient)) staticCall(t, WORLD, GSON_HELPER, "parse", lenient, "1.21.6");
        }
        String bySeparator = "(Ljava/lang/String;C)" + L_ID;
        if (lacks(host.apply(IDENTIFIER), "of", bySeparator)) {
            t.registerMethodRedirect(IDENTIFIER, "of", bySeparator, IDENTIFIER, "bySeparator", bySeparator);
            t.limitRedirectToModsBefore(IDENTIFIER, "of", bySeparator, "1.21");
        }
        String identity = "()Lit/unimi/dsi/fastutil/Hash$Strategy;";
        if (lacks(host.apply(UTIL), "identityStrategy", identity)) staticCall(t, WORLD, UTIL, "identityStrategy", identity, "1.21.2");
        String predicate = "(Lnet/minecraft/world/level/block/state/StateDefinition;)Ljava/util/function/Predicate;";
        if (lacks(host.apply(CONDITION), "getPredicate", predicate) && !lacks(host.apply(CONDITION), "instantiate", predicate)) {
            t.registerMethodRedirect(CONDITION, "getPredicate", predicate, CONDITION, "instantiate", predicate);
        }
        registerChunk(t, host);
        registerPacks(t, host);
        registerCarving(t, host, fabricHost);
        String blockEntityData = "(" + L_STACK + "Lnet/minecraft/world/level/block/entity/BlockEntityType;" + L_TAG + ")V";
        if (lacks(host.apply(BLOCK_ITEM), "setBlockEntityData", blockEntityData)) {
            staticCall(t, WORLD, BLOCK_ITEM, "setBlockEntityData", blockEntityData, "1.21.11");
        }
        if (lacks(host.apply("net/minecraft/nbt/CompoundTag"), "putIntArray", "(Ljava/lang/String;Ljava/util/List;)V")) {
            receiverCall(t, WORLD, "net/minecraft/nbt/CompoundTag", "putIntArray", "(Ljava/lang/String;Ljava/util/List;)V", "1.21.5");
        }
        String createStack = "(IZ)" + L_STACK;
        if (lacks(host.apply(ITEM_INPUT), "createItemStack", createStack)) receiverCall(t, WORLD, ITEM_INPUT, "createItemStack", createStack, "26.1");
        ClassNode extraCodecs = host.apply(EXTRA_CODECS);
        for (String codec : new String[]{"Lcom/mojang/serialization/Codec;", "Lcom/mojang/serialization/MapCodec;"}) {
            String validate = "(" + codec + "Ljava/util/function/Function;)" + codec;
            if (lacks(extraCodecs, "validate", validate)) staticCall(t, WORLD, EXTRA_CODECS, "validate", validate, "1.20.5");
        }
        String lazy = "(Ljava/util/function/Supplier;)Lcom/mojang/serialization/Codec;";
        if (lacks(extraCodecs, "lazyInitializedCodec", lazy)) staticCall(t, WORLD, EXTRA_CODECS, "lazyInitializedCodec", lazy, "1.20.5");
        // The overworld, nether and end sky ids became environment attributes in 26.1; the ids themselves are kept.
        ClassNode dimensions = host.apply(BUILTIN_DIMENSIONS);
        String[][] effects = {{"OVERWORLD_EFFECTS", "overworldEffects"}, {"NETHER_EFFECTS", "netherEffects"}, {"END_EFFECTS", "endEffects"}};
        for (String[] effect : effects) {
            if (!lacksField(dimensions, effect[0])) continue;
            t.registerFieldRedirect(BUILTIN_DIMENSIONS, effect[0], L_ID, WORLD, effect[1], "()" + L_ID);
        }
    }

    /** The carving step 1.21.2 removed: AIR and LIQUID come from a stand-in, and each carver call drops the step. */
    static void registerCarving(RetromodTransformer t, Function<String, ClassNode> host, boolean fabricHost) {
        ClassNode builder = host.apply(GEN_BUILDER);
        String byKey = "(Lnet/minecraft/resources/ResourceKey;)L" + GEN_BUILDER + ";";
        if (host.apply(CARVING) != null || builder == null || lacks(builder, "addCarver", byKey)) return;
        // The class moves first, so each call below is also keyed under the stand-in the class remap writes.
        RebuiltApiClasses.registerTree(t, LEGACY_CARVING);
        t.registerClassRedirect(CARVING, LEGACY_CARVING);
        t.limitClassRedirectToModsBefore(CARVING, "1.21.2");
        // A Fabric mod before 1.19.3 passed the step to carver calls of older shapes, which this bridge does not cover.
        if (fabricHost) t.limitClassRedirectToModsFrom(CARVING, "1.19.3");
        String oldAdd = "(L" + CARVING + ";Lnet/minecraft/resources/ResourceKey;)L" + GEN_BUILDER + ";";
        t.registerMethodRedirect(GEN_BUILDER, "addCarver", oldAdd, WORLD, "addCarver",
                "(L" + GEN_BUILDER + ";Ljava/lang/Object;Lnet/minecraft/resources/ResourceKey;)L" + GEN_BUILDER + ";", true);
        t.limitRedirectToModsBefore(GEN_BUILDER, "addCarver", oldAdd, "1.21.2");
        String oldPlain = "(L" + CARVING + ";Lnet/minecraft/core/Holder;)L" + PLAIN_BUILDER + ";";
        t.registerMethodRedirect(PLAIN_BUILDER, "addCarver", oldPlain, WORLD, "addCarver",
                "(L" + PLAIN_BUILDER + ";Ljava/lang/Object;Lnet/minecraft/core/Holder;)L" + PLAIN_BUILDER + ";", true);
        t.limitRedirectToModsBefore(PLAIN_BUILDER, "addCarver", oldPlain, "1.21.2");
        String oldCarvers = "(L" + CARVING + ";)Ljava/lang/Iterable;";
        t.registerMethodRedirect(GEN_SETTINGS, "getCarvers", oldCarvers, WORLD, "getCarvers",
                "(L" + GEN_SETTINGS + ";Ljava/lang/Object;)Ljava/lang/Iterable;", true);
        t.limitRedirectToModsBefore(GEN_SETTINGS, "getCarvers", oldCarvers, "1.21.2");
    }

    /** A mod's own chunk built with the biome registry, and its packed ticks, as 1.21.1 shaped them. */
    static void registerChunk(RetromodTransformer t, Function<String, ClassNode> host) {
        if (lacks(host.apply(CHUNK_ACCESS), "<init>", OLD_CHUNK) && !lacks(host.apply(CHUNK_ACCESS), "<init>", NEW_CHUNK)) {
            t.registerSuperConstructorAdapter(CHUNK_ACCESS, OLD_CHUNK, NEW_CHUNK, LegacyVanillaGapsBridge::chunkFactory);
        }
        if (lacks(host.apply(PACKED_TICKS), "<init>", OLD_PACKED_TICKS)) {
            t.registerConstructorRedirect(PACKED_TICKS, OLD_PACKED_TICKS, WORLD, "packedTicks",
                    "(Ljava/lang/Object;Ljava/lang/Object;)L" + PACKED_TICKS + ";");
        }
    }

    /** Spills the three values above the biome registry, turns it into a container factory, and puts them back. */
    static void chunkFactory(org.objectweb.asm.MethodVisitor mv) {
        mv.visitVarInsn(Opcodes.ASTORE, SPILL);
        mv.visitVarInsn(Opcodes.ASTORE, SPILL + 1);
        mv.visitVarInsn(Opcodes.LSTORE, SPILL + 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, WORLD, "containerFactory",
                "(Ljava/lang/Object;)Lnet/minecraft/world/level/chunk/PalettedContainerFactory;", false);
        mv.visitVarInsn(Opcodes.LLOAD, SPILL + 2);
        mv.visitVarInsn(Opcodes.ALOAD, SPILL + 1);
        mv.visitVarInsn(Opcodes.ALOAD, SPILL);
    }

    /** The 1.20.1 pack resource constructor and pack factory, built on the pack location 1.20.5 added. */
    static void registerPacks(RetromodTransformer t, Function<String, ClassNode> host) {
        String oldPath = "(Ljava/lang/String;Ljava/nio/file/Path;Z)V";
        if (lacks(host.apply(PATH_PACK), "<init>", oldPath)) {
            t.registerConstructorRedirect(PATH_PACK, oldPath, WORLD, "pathPackResources",
                    "(Ljava/lang/String;Ljava/nio/file/Path;Z)L" + PATH_PACK + ";");
            t.limitRedirectToModsBefore(PATH_PACK, "<init>", oldPath, "1.20.5");
        }
        if (lacks(host.apply(PACK), "readMetaAndCreate", OLD_READ_META)) {
            staticCall(t, WORLD, PACK, "readMetaAndCreate", OLD_READ_META, "1.20.5");
        }
        // The supplier a mod hands it is a lambda of 1.20.1's open(String); 26.1 and 26.2 open packs by location.
        LegacyPackSupplierLambdas.register(t, host);
    }

    /** The attack packet, which 26.1 split from the interaction packet, and the calls that hand one on. */
    static void registerNetwork(RetromodTransformer t, Function<String, ClassNode> host) {
        String writeVector = "(Lio/netty/buffer/ByteBuf;Lorg/joml/Vector3f;)V";
        if (lacks(host.apply(BUF), "writeVector3f", writeVector)) {
            t.registerMethodRedirect(BUF, "writeVector3f", writeVector, BUF, "writeVector3f", "(Lio/netty/buffer/ByteBuf;Lorg/joml/Vector3fc;)V");
        }
        if (!lacks(host.apply(INTERACT_PACKET), "createAttackPacket", ATTACK_PACKET)) return;
        staticCall(t, WORLD, INTERACT_PACKET, "createAttackPacket", ATTACK_PACKET, "26.1");
        // A mod that hands the packet to the server's handler or sends it gets the attack 26.1 handles apart.
        // The member still exists, so a listener subclass's super call keeps reaching the host's own method.
        String handle = "(L" + INTERACT_PACKET + ";)V";
        t.registerWrappingMethodRedirect(SERVER_LISTENER, "handleInteract", handle, WORLD, "handleInteract",
                "(L" + SERVER_LISTENER + ";L" + INTERACT_PACKET + ";)V");
        t.limitRedirectToModsBefore(SERVER_LISTENER, "handleInteract", handle, "26.1");
        // A class that builds an attack also sends it; only its own client sends take the attack form.
        RebuiltApiClasses.registerTree(t, CLIENT);
        t.enablePostProcess(LegacyVanillaGapsAdapters.ATTACK_FLAG);
    }

    /** Client members: the toast manager, reach, culling, key types, model cubes, settings, pings, sprites and textures. */
    static void registerClient(RetromodTransformer t, Function<String, ClassNode> host) {
        boolean used = false;
        ClassNode minecraft = host.apply(MINECRAFT);
        if (lacks(minecraft, "fillReport", OLD_FILL_REPORT)) {
            staticCall(t, CLIENT, MINECRAFT, "fillReport", OLD_FILL_REPORT, "1.17");
            used = true;
        }
        // 26.2 moved the toast manager to the GUI; 26.1 still keeps the field.
        if (lacksField(minecraft, "toastManager") && !lacksField(host.apply("net/minecraft/client/gui/Gui"), "toastManager")) {
            String toasts = "L" + TOAST_MANAGER + ";";
            RebuiltApiClasses.registerTree(t, TOASTS);
            t.registerFieldStaticBridge(MINECRAFT, "toastManager", TOASTS, "toastManager", "(Ljava/lang/Object;)" + toasts,
                    "setToastManager", "(Ljava/lang/Object;" + toasts + ")V");
        }
        String oldToasts = "(L" + MINECRAFT + ";)V";
        String newToasts = "(L" + MINECRAFT + ";" + L_OPTIONS + ")V";
        if (lacks(host.apply(TOAST_MANAGER), "<init>", oldToasts) && !lacks(host.apply(TOAST_MANAGER), "<init>", newToasts)) {
            // minecraft -> minecraft, minecraft.options
            t.registerSuperConstructorAdapter(TOAST_MANAGER, oldToasts, newToasts, mv -> {
                mv.visitInsn(Opcodes.DUP);
                mv.visitFieldInsn(Opcodes.GETFIELD, MINECRAFT, "options", L_OPTIONS);
            });
        }
        if (lacksInherited(host, GAME_MODE, "getPickRange", "()F")) {
            receiverCall(t, CLIENT, GAME_MODE, "getPickRange", "()F", "1.20.5");
            used = true;
        }
        // Forge's reach getter became the vanilla interaction range attribute in 1.20.5.
        if (lacksInherited(host, LOCAL_PLAYER, "getEntityReach", "()D")) {
            t.registerMethodRedirect(LOCAL_PLAYER, "getEntityReach", "()D", LOCAL_PLAYER, "entityInteractionRange", "()D");
            t.limitRedirectToModsBefore(LOCAL_PLAYER, "getEntityReach", "()D", "1.20.5");
        }
        String offset = "(" + L_ENTITY + "F)Lnet/minecraft/world/phys/Vec3;";
        if (lacks(host.apply(ENTITY_RENDERER), "getRenderOffset", offset)) {
            receiverCall(t, CLIENT, ENTITY_RENDERER, "getRenderOffset", offset, "1.21.2");
            used = true;
        }
        if (lacksField(host.apply(ENTITY), "noCulling")) {
            t.registerFieldStaticBridge(ENTITY, "noCulling", CLIENT, "noCulling", "(Ljava/lang/Object;)Z",
                    "setNoCulling", "(Ljava/lang/Object;Z)V");
            used = true;
        }
        // 26.3 named both keyboard key types KEYBOARD.
        ClassNode inputType = host.apply(INPUT_TYPE);
        if (lacksField(inputType, "SCANCODE") && !lacksField(inputType, "KEYBOARD")) {
            for (String old : new String[]{"SCANCODE", "KEYSYM"}) t.registerFieldRedirect(INPUT_TYPE, old, INPUT_TYPE, "KEYBOARD");
        }
        if (lacks(host.apply(CUBE), "compile", OLD_CUBE_COMPILE)) {
            receiverCall(t, CLIENT, CUBE, "compile", OLD_CUBE_COMPILE, "1.21");
            used = true;
        }
        used |= registerSettings(t, host);
        if (used) RebuiltApiClasses.registerTree(t, CLIENT);
        registerCameraVectors(t, host);
        registerPlatform(t, host);
    }

    /** The camera's vectors that an old Fabric mod reads under the names 1.21.11 gave them, which now hand out read-only views. */
    static void registerCameraVectors(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode camera = host.apply(CAMERA);
        String[][] vectors = {{"forwardVector", "getLookVector"}, {"upVector", "getUpVector"}, {"leftVector", "getLeftVector"}};
        for (String[] vector : vectors) {
            if (!lacks(camera, vector[0], "()Lorg/joml/Vector3f;") || lacks(camera, vector[0], "()Lorg/joml/Vector3fc;")) continue;
            RebuiltApiClasses.registerTree(t, CAMERA_VECTORS);
            t.registerMethodRedirect(CAMERA, vector[0], "()Lorg/joml/Vector3f;", CAMERA_VECTORS, vector[1], "(L" + CAMERA + ";)Lorg/joml/Vector3f;", true);
        }
    }

    /** GlUtil, the Ogg stream, the client's destroy step and the entity data and advancement frame reads. */
    static void registerPlatform(RetromodTransformer t, Function<String, ClassNode> host) {
        // GlUtil's memory and device reads left com.mojang.blaze3d.platform; 26.1's device still answers them itself.
        if (host.apply(GL_UTIL) == null && host.apply(MINECRAFT) != null) {
            boolean deviceInfo = !lacks(host.apply(GPU_DEVICE), "getDeviceInfo", "()Lcom/mojang/blaze3d/systems/DeviceInfo;")
                    || host.apply(GPU_DEVICE) == null;
            String rebuilt = deviceInfo ? GL_UTIL_REBUILT : GL_UTIL_REBUILT + "261";
            RebuiltApiClasses.registerTree(t, rebuilt);
            t.registerClassRedirect(GL_UTIL, rebuilt);
        }
        // The Ogg stream took the JOrbis name with the same constructor and reads.
        if (host.apply(OGG_STREAM) == null && host.apply(JORBIS_STREAM) != null) t.registerClassRedirect(OGG_STREAM, JORBIS_STREAM);
        // 26.2's run() shuts the client down itself, so the separate destroy step a mod's own launch makes is dropped.
        if (host.apply(MINECRAFT) != null && lacks(host.apply(MINECRAFT), "destroy", "()V")) {
            t.registerRemovedMethodNeutralize(MINECRAFT, "destroy", "()V");
        }
        if (lacks(host.apply(ENTITY_DATA_PACKET), "getUnpackedData", "()Ljava/util/List;")) {
            t.registerMethodRedirect(ENTITY_DATA_PACKET, "getUnpackedData", "()Ljava/util/List;", ENTITY_DATA_PACKET, "packedItems",
                    "()Ljava/util/List;");
            t.limitRedirectToModsBefore(ENTITY_DATA_PACKET, "getUnpackedData", "()Ljava/util/List;", "1.19.3");
        }
        // 1.20.2 renamed the advancement frame type, and 26.3 its getter.
        String frame = "()Lnet/minecraft/advancements/AdvancementType;";
        ClassNode display = host.apply(DISPLAY_INFO);
        if (display != null && lacks(display, "getFrame", frame)) {
            String getter = !lacks(display, "type", frame) ? "type" : !lacks(display, "getType", frame) ? "getType" : null;
            if (getter != null) {
                t.registerMethodRedirect(DISPLAY_INFO, "getFrame", frame, DISPLAY_INFO, getter, frame);
                t.limitRedirectToModsBefore(DISPLAY_INFO, "getFrame", frame, "1.20.2");
            }
        }
    }

    /** The video settings screen, field of view, sound volume, server pings, sprite names and texture binds. */
    static boolean registerSettings(RetromodTransformer t, Function<String, ClassNode> host) {
        boolean used = false;
        String oldVideo = "(" + L_SCREEN + L_OPTIONS + ")V";
        String newVideo = "(" + L_SCREEN + "L" + MINECRAFT + ";" + L_OPTIONS + ")V";
        if (lacks(host.apply(VIDEO_SETTINGS), "<init>", oldVideo) && !lacks(host.apply(VIDEO_SETTINGS), "<init>", newVideo)) {
            t.registerConstructorRedirect(VIDEO_SETTINGS, oldVideo, CLIENT, "videoSettings", "(" + L_SCREEN + L_OPTIONS + ")L" + VIDEO_SETTINGS + ";");
            // parent, options -> parent, the running client, options
            t.registerSuperConstructorAdapter(VIDEO_SETTINGS, oldVideo, newVideo, mv -> {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, MINECRAFT, "getInstance", "()L" + MINECRAFT + ";", false);
                mv.visitInsn(Opcodes.SWAP);
            });
            used = true;
        }
        if (lacksInherited(host, CLIENT_PLAYER, "getFieldOfViewModifier", "()F")) {
            for (String owner : List.of(CLIENT_PLAYER, LOCAL_PLAYER)) {
                t.registerMethodRedirect(owner, "getFieldOfViewModifier", "()F", CLIENT, "getFieldOfViewModifier",
                        "(L" + CLIENT_PLAYER + ";)F", true);
                t.limitRedirectToModsBefore(owner, "getFieldOfViewModifier", "()F", "1.21.2");
            }
            used = true;
        }
        String volume = "(Lnet/minecraft/sounds/SoundSource;F)V";
        if (lacks(host.apply(SOUND_MANAGER), "updateSourceVolume", volume)) {
            t.registerMethodRedirect(SOUND_MANAGER, "updateSourceVolume", volume, SOUND_MANAGER, "updateCategoryVolume", volume);
        }
        if (lacks(host.apply(PINGER), "pingServer", OLD_PING)) {
            receiverCall(t, CLIENT, PINGER, "pingServer", OLD_PING, "1.21.6");
            used = true;
        }
        if (lacks(host.apply(SPRITE), "getName", "()" + L_ID)) {
            receiverCall(t, CLIENT, SPRITE, "getName", "()" + L_ID, "1.19.3");
            used = true;
        }
        if (lacks(host.apply(TEXTURE), "bind", "()V")) t.registerRemovedMethodNeutralize(TEXTURE, "bind", "()V");
        ClassNode minecraft = host.apply(MINECRAFT);
        if (minecraft != null && lacks(minecraft, "isSingleplayer", "()Z") && !lacks(minecraft, "hasSingleplayerServer", "()Z")) {
            t.registerMethodRedirect(MINECRAFT, "isSingleplayer", "()Z", MINECRAFT, "hasSingleplayerServer", "()Z");
            t.limitRedirectToModsBefore(MINECRAFT, "isSingleplayer", "()Z", "26.2");
        }
        if (lacks(host.apply(CLIENT_LEVEL), "disconnect", "()V")) {
            receiverCall(t, CLIENT, CLIENT_LEVEL, "disconnect", "()V", "1.21.6");
            used = true;
        }
        if (host.apply(WINDOW) != null && lacks(host.apply(WINDOW), "isFullscreen", "()Z")) {
            t.registerMethodRedirect(WINDOW, "isFullscreen", "()Z", CLIENT, "isFullscreen", "(Ljava/lang/Object;)Z", true);
            t.limitRedirectToModsBefore(WINDOW, "isFullscreen", "()Z", "26.3");
            used = true;
        }
        // 1.20.5 dropped the dirt background; the menu background is what screens draw behind themselves now.
        if (lacksField(host.apply(SCREEN), "BACKGROUND_LOCATION") && !lacksField(host.apply(SCREEN), "MENU_BACKGROUND")) {
            t.registerFieldRedirect(SCREEN, "BACKGROUND_LOCATION", SCREEN, "MENU_BACKGROUND");
        }
        return used;
    }

    /** The recipe book read, the nine-slice draw, the dirt message screen and the LAN screen. */
    static void registerScreens(RetromodTransformer t, Function<String, ClassNode> host) {
        String recipeBook = "()Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;";
        boolean used = false;
        if (lacks(host.apply(RECIPE_LISTENER), "getRecipeBookComponent", recipeBook)) {
            t.registerMethodRedirect(RECIPE_LISTENER, "getRecipeBookComponent", recipeBook, CLIENT, "getRecipeBookComponent",
                    "(Ljava/lang/Object;)Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;", true);
            t.limitRedirectToModsBefore(RECIPE_LISTENER, "getRecipeBookComponent", recipeBook, "1.21.2");
            used = true;
        }
        ClassNode graphics = host.apply(GRAPHICS);
        for (String nineSliced : new String[]{NINE_SLICED_9, NINE_SLICED_10, NINE_SLICED_12}) {
            if (!lacks(graphics, "blitNineSliced", nineSliced)) continue;
            receiverCall(t, CLIENT, GRAPHICS, "blitNineSliced", nineSliced, "1.20.2");
            used = true;
        }
        if (used) RebuiltApiClasses.registerTree(t, CLIENT);
        // 1.20.5 dropped the dirt background, and the message screen kept its job under a new name.
        if (host.apply(DIRT_SCREEN) == null && host.apply(MESSAGE_SCREEN) != null) {
            t.registerClassRedirect(DIRT_SCREEN, MESSAGE_SCREEN);
            t.limitClassRedirectToModsBefore(DIRT_SCREEN, "1.20.5");
        }
        // 26.2 removed the LAN screen; a stand-in keeps the type that mods name.
        if (host.apply(HOST_LAN_SCREEN) == null && host.apply(MINECRAFT) != null) {
            RebuiltApiClasses.registerTree(t, LAN_SCREEN);
            t.registerClassRedirect(HOST_LAN_SCREEN, LAN_SCREEN);
            t.limitClassRedirectToModsBefore(HOST_LAN_SCREEN, "26.2");
        }
    }

    /** An instance call answered by a helper that takes the receiver first, for mods built before {@code firstWithout}. */
    private static void receiverCall(RetromodTransformer t, String helper, String owner, String name, String desc, String firstWithout) {
        t.registerMethodRedirect(owner, name, desc, helper, name, "(L" + owner + ";" + desc.substring(1), true);
        t.limitRedirectToModsBefore(owner, name, desc, firstWithout);
    }

    private static void staticCall(RetromodTransformer t, String helper, String owner, String name, String desc, String firstWithout) {
        t.registerMethodRedirect(owner, name, desc, helper, name, desc);
        t.limitRedirectToModsBefore(owner, name, desc, firstWithout);
    }

    /** Whether the host class lacks the method; an unreadable class counts as lacking it, since this bridge starts at 26.1. */
    static boolean lacks(ClassNode owner, String name, String desc) {
        if (owner == null) return true;
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return false;
        }
        return true;
    }

    /** Whether nothing the class is or extends on the host declares the method, NeoForge's extension interfaces included. */
    static boolean lacksInherited(Function<String, ClassNode> host, String owner, String name, String desc) {
        java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>(List.of(owner));
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (!todo.isEmpty()) {
            String next = todo.poll();
            if (!seen.add(next) || next.equals("java/lang/Object")) continue;
            ClassNode node = host.apply(next);
            if (node == null) continue;
            if (!lacks(node, name, desc)) return false;
            if (node.superName != null) todo.add(node.superName);
            todo.addAll(node.interfaces);
        }
        return true;
    }

    static boolean lacksField(ClassNode owner, String name) {
        if (owner == null) return true;
        for (FieldNode field : owner.fields) {
            if (field.name.equals(name)) return false;
        }
        return true;
    }

    private static boolean targetAtLeast(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) >= 0;
    }
}
