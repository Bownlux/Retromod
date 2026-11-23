/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Function;

/** Members 1.20.1 mods call that later versions removed, rebuilt on 26.x replacements for every loader. */
public final class Legacy1201MemberBridge {

    static final String ROOT = RebuiltApiClasses.ROOT + "minecraft/";
    static final String SERVER_PLAYER = "net/minecraft/server/level/ServerPlayer";
    static final String PLAYER = "net/minecraft/world/entity/player/Player";
    static final String LEVEL = "net/minecraft/world/level/Level";
    static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    static final String CLIENT_LEVEL = "net/minecraft/client/multiplayer/ClientLevel";
    static final String NBT_UTILS = "net/minecraft/nbt/NbtUtils";
    static final String USER = "net/minecraft/client/User";
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String TRANSITION = "net/minecraft/world/level/portal/TeleportTransition";
    static final String FABRIC_DIMENSIONS = "net/fabricmc/fabric/api/dimension/v1/FabricDimensions";
    static final String BLOCK_ITEM = "net/minecraft/world/item/BlockItem";
    static final String EVENT_LOOP = "net/minecraft/util/thread/BlockableEventLoop";
    static final String EDIT_BOX = "net/minecraft/client/gui/components/EditBox";
    static final String SPRITE = "net/minecraft/client/renderer/texture/TextureAtlasSprite";
    static final String MODEL_PART = "net/minecraft/client/model/geom/ModelPart";
    static final String OS = "net/minecraft/util/Util$OS";
    static final String LEVEL_ACCESSOR = "net/minecraft/world/level/LevelAccessor";
    static final String GAME_EVENT = "net/minecraft/world/level/gameevent/GameEvent";
    static final String PLACE_SETTINGS = "net/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings";
    static final String STRUCTURE_MANAGER = "net/minecraft/world/level/StructureManager";
    static final String STRUCTURE = "net/minecraft/world/level/levelgen/structure/Structure";
    static final String STRUCTURE_START = "net/minecraft/world/level/levelgen/structure/StructureStart";
    static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    static final String BUF = "net/minecraft/network/FriendlyByteBuf";
    static final String NBT_IO = "net/minecraft/nbt/NbtIo";
    static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    static final String OPTIONS = "net/minecraft/client/Options";
    static final String MODEL = "net/minecraft/client/model/Model";
    static final String OLD_MODEL_CTOR = "(Ljava/util/function/Function;)V";
    static final String OLD_MODEL_DRAW = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V";
    static final String NEW_MODEL_CTOR = "(L" + MODEL_PART + ";Ljava/util/function/Function;)V";
    static final String RENDER_SHAPE = "net/minecraft/world/level/block/RenderShape";
    static final String GRAPHICS = "net/minecraft/client/gui/GuiGraphicsExtractor";
    static final String OLD_LIST_CTOR = "(L" + MINECRAFT + ";IIIII)V";
    static final String NEW_LIST_CTOR = "(L" + MINECRAFT + ";IIII)V";
    /** The selection lists whose constructors a mod's list calls up to. */
    static final String[] SELECTION_LISTS = {"net/minecraft/client/gui/components/AbstractSelectionList",
            "net/minecraft/client/gui/components/ObjectSelectionList", "net/minecraft/client/gui/components/ContainerObjectSelectionList"};
    /** The level types mods call level and game events through. */
    static final String[] LEVEL_OWNERS = {LEVEL_ACCESSOR, LEVEL, SERVER_LEVEL, CLIENT_LEVEL,
            "net/minecraft/world/level/WorldGenLevel", "net/minecraft/server/level/WorldGenRegion"};

    static final String RESPAWN = ROOT + "server/level/LegacyRespawnMembers";
    static final String PROFILE_TAGS = ROOT + "nbt/LegacyGameProfileTags";
    static final String CLIENT_MEMBERS = ROOT + "client/Legacy1201ClientMembers";
    static final String OS_OPENERS = ROOT + "util/LegacyOsOpeners";
    static final String LEVEL_EVENTS = ROOT + "world/level/LegacyLevelEvents";
    static final String IDENTIFIER_CHECKS = ROOT + "resources/LegacyIdentifierChecks";
    static final String NBT_READS = ROOT + "network/LegacyNbtReads";
    static final String BUF_COMPONENTS = ROOT + "network/LegacyBufComponents";
    static final String INGREDIENTS = ROOT + "world/item/crafting/LegacyIngredients";
    static final String MODEL_ATLASES = ROOT + "client/resources/model/LegacyModelAtlases";
    static final String INGREDIENT = "net/minecraft/world/item/crafting/Ingredient";
    static final String NBT_FILES = ROOT + "nbt/LegacyNbtFiles";
    static final String STRUCTURE_CALLS = ROOT + "world/level/levelgen/structure/LegacyStructureCalls";
    static final String STRUCTURE_SETTINGS = ROOT + "world/level/levelgen/structure/LegacyStructureSettings";
    static final String PORTAL_INFOS = ROOT + "world/level/portal/LegacyPortalInfos";
    static final String BLOCK_ITEMS = ROOT + "world/item/LegacyBlockItemTags";

    static final String L_POS = "Lnet/minecraft/core/BlockPos;";
    static final String L_KEY = "Lnet/minecraft/resources/ResourceKey;";
    static final String L_VEC = "Lnet/minecraft/world/phys/Vec3;";
    static final String L_PROFILE = "Lcom/mojang/authlib/GameProfile;";
    static final String L_COMPOUND = "Lnet/minecraft/nbt/CompoundTag;";
    static final String L_ENTITY = "Lnet/minecraft/world/entity/Entity;";
    static final String L_GAME_EVENT = "Lnet/minecraft/world/level/gameevent/GameEvent;";
    static final String FIND_RESPAWN = "(L" + SERVER_LEVEL + ";" + L_POS + "FZZ)Ljava/util/Optional;";
    static final String SET_RESPAWN = "(" + L_KEY + L_POS + "FZZ)V";
    static final String PORTAL_INFO = "(" + L_VEC + L_VEC + "FF)V";
    static final String TELEPORT = "(" + L_ENTITY + "L" + SERVER_LEVEL + ";L" + TRANSITION + ";)" + L_ENTITY;
    static final String OLD_UPDATE_TAG = "(" + L_POS + "Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;"
            + "Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/block/state/BlockState;)Z";

    /** The ServerPlayer respawn getters 1.21.5 folded into its respawn config, with their 1.20.1 descriptors. */
    static final String[][] RESPAWN_GETTERS = {
            {"getRespawnPosition", "()" + L_POS},
            {"getRespawnDimension", "()" + L_KEY},
            {"getRespawnAngle", "()F"},
            {"isRespawnForced", "()Z"}};

    private Legacy1201MemberBridge() {}

    /** Registers each bridge whose old member the host lacks. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // Every helper here is built on 26.x shapes, so an older target keeps its own members.
        if (!targetAtLeast("26.1")) return;
        registerRespawn(t, host);
        registerProfiles(t, host);
        registerClient(t, host);
        registerLevel(t, host);
        registerData(t, host);
        registerPortals(t, host);
        // 26.3 dropped the instance placement hook a block item's override called up to; the static form stays.
        if (lacks(host.apply(BLOCK_ITEM), "updateCustomBlockEntityTag", OLD_UPDATE_TAG, "26.3")) {
            RebuiltApiClasses.registerTree(t, BLOCK_ITEMS);
            String helperDesc = "(L" + BLOCK_ITEM + ";" + OLD_UPDATE_TAG.substring(1);
            t.registerMethodRedirect(BLOCK_ITEM, "updateCustomBlockEntityTag", OLD_UPDATE_TAG, BLOCK_ITEMS,
                    "updateCustomBlockEntityTag", helperDesc, true);
            // A super call names the mod's direct parent, which may be any block item class.
            t.registerInheritedMethodRedirect(BLOCK_ITEM, "updateCustomBlockEntityTag", OLD_UPDATE_TAG, BLOCK_ITEMS,
                    "updateCustomBlockEntityTag", helperDesc);
        }
    }

    /** Respawn points and the world spawn, which 1.21.5 and 1.21.9 moved into records. */
    static void registerRespawn(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode serverPlayer = host.apply(SERVER_PLAYER);
        for (String[] getter : RESPAWN_GETTERS) {
            if (!lacks(serverPlayer, getter[0], getter[1], "1.21.5")) continue;
            RebuiltApiClasses.registerTree(t, RESPAWN);
            String helperDesc = "(L" + SERVER_PLAYER + ";" + getter[1].substring(1);
            t.registerMethodRedirect(SERVER_PLAYER, getter[0], getter[1], RESPAWN, getter[0], helperDesc, true);
            t.registerInheritedMethodRedirect(SERVER_PLAYER, getter[0], getter[1], RESPAWN, getter[0], helperDesc);
        }
        if (lacks(serverPlayer, "setRespawnPosition", SET_RESPAWN, "1.21.5")) {
            RebuiltApiClasses.registerTree(t, RESPAWN);
            String helperDesc = "(L" + SERVER_PLAYER + ";" + SET_RESPAWN.substring(1);
            t.registerMethodRedirect(SERVER_PLAYER, "setRespawnPosition", SET_RESPAWN, RESPAWN, "setRespawnPosition", helperDesc, true);
            t.registerInheritedMethodRedirect(SERVER_PLAYER, "setRespawnPosition", SET_RESPAWN, RESPAWN, "setRespawnPosition", helperDesc);
        }
        // The shared spawn getters became getRespawnData() in 1.21.9; mods call them through any level type.
        ClassNode level = host.apply(LEVEL);
        for (String[] getter : new String[][]{{"getSharedSpawnPos", "()" + L_POS}, {"getSharedSpawnAngle", "()F"}}) {
            if (!lacks(level, getter[0], getter[1], "1.21.9") || !lacks(host.apply(SERVER_LEVEL), getter[0], getter[1], "1.21.9")) continue;
            RebuiltApiClasses.registerTree(t, RESPAWN);
            String helperDesc = "(L" + LEVEL + ";" + getter[1].substring(1);
            for (String owner : new String[]{LEVEL, SERVER_LEVEL, CLIENT_LEVEL}) {
                t.registerMethodRedirect(owner, getter[0], getter[1], RESPAWN, getter[0], helperDesc, true);
            }
            t.registerInheritedMethodRedirect(LEVEL, getter[0], getter[1], RESPAWN, getter[0], helperDesc);
        }
        // The static respawn search was a ServerPlayer method taking a respawn config by 1.21.1.
        if (lacks(host.apply(PLAYER), "findRespawnPositionAndUseSpawnBlock", FIND_RESPAWN, "1.21")) {
            RebuiltApiClasses.registerTree(t, RESPAWN);
            // javac names the class the call is written through, which server code often makes ServerPlayer.
            for (String owner : new String[]{PLAYER, SERVER_PLAYER}) {
                t.registerMethodRedirect(owner, "findRespawnPositionAndUseSpawnBlock", FIND_RESPAWN, RESPAWN,
                        "findRespawnPositionAndUseSpawnBlock", FIND_RESPAWN);
            }
        }
    }

    /** Game profile tags, the client user's profile, and the client profiler. */
    static void registerProfiles(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode nbtUtils = host.apply(NBT_UTILS);
        String read = "(" + L_COMPOUND + ")" + L_PROFILE;
        String write = "(" + L_COMPOUND + L_PROFILE + ")" + L_COMPOUND;
        if (lacks(nbtUtils, "readGameProfile", read, "1.21") || lacks(nbtUtils, "writeGameProfile", write, "1.21")) {
            RebuiltApiClasses.registerTree(t, PROFILE_TAGS);
            t.registerMethodRedirect(NBT_UTILS, "readGameProfile", read, PROFILE_TAGS, "readGameProfile", read);
            t.registerMethodRedirect(NBT_UTILS, "writeGameProfile", write, PROFILE_TAGS, "writeGameProfile", write);
        }
        if (lacks(host.apply(USER), "getGameProfile", "()" + L_PROFILE, "1.21")) {
            RebuiltApiClasses.registerTree(t, CLIENT_MEMBERS);
            t.registerMethodRedirect(USER, "getGameProfile", "()" + L_PROFILE, CLIENT_MEMBERS, "getGameProfile",
                    "(L" + USER + ";)" + L_PROFILE, true);
        }
        String profiler = "()Lnet/minecraft/util/profiling/ProfilerFiller;";
        if (lacks(host.apply(MINECRAFT), "getProfiler", profiler, "1.21.2")) {
            RebuiltApiClasses.registerTree(t, CLIENT_MEMBERS);
            t.registerMethodRedirect(MINECRAFT, "getProfiler", profiler, CLIENT_MEMBERS, "getProfiler",
                    "(L" + MINECRAFT + ";)" + profiler.substring(2), true);
        }
    }

    /** Client calls whose shapes changed between 1.20.1 and 26.3: the event loop, widgets, sprites, model parts and links. */
    static void registerClient(RetromodTransformer t, Function<String, ClassNode> host) {
        String realms = "()Z";
        if (lacks(host.apply(MINECRAFT), "isConnectedToRealms", realms, "1.20.5")) {
            RebuiltApiClasses.registerTree(t, CLIENT_MEMBERS);
            t.registerMethodRedirect(MINECRAFT, "isConnectedToRealms", realms, CLIENT_MEMBERS, "isConnectedToRealms",
                    "(L" + MINECRAFT + ";)Z", true);
        }
        // The event loop's tell became schedule, with the same queued meaning.
        String runnable = "(Ljava/lang/Runnable;)V";
        if (lacks(host.apply(EVENT_LOOP), "tell", runnable, "1.21")) {
            for (String owner : new String[]{MINECRAFT, "net/minecraft/server/MinecraftServer", EVENT_LOOP,
                    "net/minecraft/util/thread/ReentrantBlockableEventLoop"}) {
                t.registerMethodRedirect(owner, "tell", runnable, owner, "schedule", runnable);
            }
        }
        // The debug overlay switch moved off the options after 1.20.1; reads go to the overlay's state.
        ClassNode options = host.apply(OPTIONS);
        if (options == null ? targetAtLeast("1.20.2") : !hasField(options, "renderDebug")) {
            RebuiltApiClasses.registerTree(t, CLIENT_MEMBERS);
            // Reads and writes take different calls, so a write is not turned into a read.
            t.registerFieldStaticBridge(OPTIONS, "renderDebug", CLIENT_MEMBERS, "renderDebug", "(L" + OPTIONS + ";)Z",
                    "setRenderDebug", "(L" + OPTIONS + ";Z)V");
        }
        // 26.1 removed the client's camera entity field; its getter and setter remain.
        ClassNode minecraft = host.apply(MINECRAFT);
        if (minecraft == null ? targetAtLeast("26.1") : !hasField(minecraft, "cameraEntity") && !lacks(minecraft, "getCameraEntity", "()" + L_ENTITY, "26.1")) {
            t.registerFieldAccessorRedirect(MINECRAFT, "cameraEntity", "getCameraEntity", "()" + L_ENTITY,
                    "setCameraEntity", "(" + L_ENTITY + ")V");
        }
        if (lacks(host.apply(EDIT_BOX), "tick", "()V", "1.20.2")) {
            RebuiltApiClasses.registerTree(t, CLIENT_MEMBERS);
            t.registerMethodRedirect(EDIT_BOX, "tick", "()V", CLIENT_MEMBERS, "tick", "(Ljava/lang/Object;)V", true);
        }
        ClassNode sprite = host.apply(SPRITE);
        for (String name : new String[]{"getU", "getV"}) {
            if (!lacks(sprite, name, "(D)F", "1.20.2")) continue;
            RebuiltApiClasses.registerTree(t, CLIENT_MEMBERS);
            t.registerMethodRedirect(SPRITE, name, "(D)F", CLIENT_MEMBERS, name, "(L" + SPRITE + ";D)F", true);
        }
        String oldRender = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V";
        if (lacks(host.apply(MODEL_PART), "render", oldRender, "1.21")) {
            RebuiltApiClasses.registerTree(t, CLIENT_MEMBERS);
            t.registerMethodRedirect(MODEL_PART, "render", oldRender, CLIENT_MEMBERS, "render",
                    "(L" + MODEL_PART + ";" + oldRender.substring(1), true);
        }
        // The background takes the mouse and partial tick now; the 1.20.1 call arrives under the 26.x name.
        String background = "(L" + GRAPHICS + ";)V";
        if (lacks(host.apply(SCREEN), "extractBackground", background, "1.20.2")) {
            RebuiltApiClasses.registerTree(t, CLIENT_MEMBERS);
            String helperDesc = "(L" + SCREEN + ";L" + GRAPHICS + ";)V";
            t.registerMethodRedirect(SCREEN, "extractBackground", background, CLIENT_MEMBERS, "renderBackground", helperDesc, true);
            t.registerInheritedMethodRedirect(SCREEN, "extractBackground", background, CLIENT_MEMBERS, "renderBackground", helperDesc);
        }
        // A list takes its own height and top now, instead of the screen height and its top and bottom.
        for (String list : SELECTION_LISTS) {
            if (!lacks(host.apply(list), "<init>", OLD_LIST_CTOR, "1.20.3")) continue;
            t.registerSuperConstructorAdapter(list, OLD_LIST_CTOR, NEW_LIST_CTOR, Legacy1201MemberBridge::listBounds);
        }
        // The pose matrix multiply lost its own name and takes any matrix now.
        String pose = "com/mojang/blaze3d/vertex/PoseStack";
        ClassNode poseStack = host.apply(pose);
        if (lacks(poseStack, "mulPoseMatrix", "(Lorg/joml/Matrix4f;)V", "1.21")
                && (poseStack == null || !lacks(poseStack, "mulPose", "(Lorg/joml/Matrix4fc;)V", "1.21"))) {
            t.registerMethodRedirect(pose, "mulPoseMatrix", "(Lorg/joml/Matrix4f;)V", pose, "mulPose", "(Lorg/joml/Matrix4fc;)V");
        }
        // The old draw took the color as four floats. Any model type names it, as EntityModel does, so the hierarchy is followed.
        ClassNode model = host.apply(MODEL);
        if (lacks(model, "renderToBuffer", OLD_MODEL_DRAW, "1.21")) {
            RebuiltApiClasses.registerTree(t, CLIENT_MEMBERS);
            String helperDesc = "(L" + MODEL + ";" + OLD_MODEL_DRAW.substring(1);
            t.registerMethodRedirect(MODEL, "renderToBuffer", OLD_MODEL_DRAW, CLIENT_MEMBERS, "renderToBuffer", helperDesc, true);
            t.registerInheritedMethodRedirect(MODEL, "renderToBuffer", OLD_MODEL_DRAW, CLIENT_MEMBERS, "renderToBuffer", helperDesc);
        }
        // From 1.21.2 a model is built on its root part; an old model draws its own parts, so it gets an empty root.
        if (lacks(model, "<init>", OLD_MODEL_CTOR, "1.21.2")) {
            t.registerSuperConstructorAdapter(MODEL, OLD_MODEL_CTOR, NEW_MODEL_CTOR, Legacy1201MemberBridge::emptyRoot);
        }
        // The client's atlas lookup went to the atlas manager in 1.21.9.
        String atlas = "(L" + IDENTIFIER + ";)Ljava/util/function/Function;";
        if (lacks(host.apply(MINECRAFT), "getTextureAtlas", atlas, "1.21.9")) {
            RebuiltApiClasses.registerTree(t, MODEL_ATLASES);
            t.registerMethodRedirect(MINECRAFT, "getTextureAtlas", atlas, MODEL_ATLASES, "getTextureAtlas",
                    "(L" + MINECRAFT + ";" + atlas.substring(1), true);
        }
        // 26.3 moved the link and folder openers to Blaze3D.
        ClassNode os = host.apply(OS);
        String[][] openers = {{"openUri", "(Ljava/lang/String;)V"}, {"openUri", "(Ljava/net/URI;)V"},
                {"openFile", "(Ljava/io/File;)V"}, {"openPath", "(Ljava/nio/file/Path;)V"}};
        for (String[] opener : openers) {
            if (!lacks(os, opener[0], opener[1], "26.3")) continue;
            RebuiltApiClasses.registerTree(t, OS_OPENERS);
            t.registerMethodRedirect(OS, opener[0], opener[1], OS_OPENERS, opener[0], "(L" + OS + ";" + opener[1].substring(1), true);
        }
    }

    /** Turns (screenHeight, top, bottom, itemHeight) into (bottom - top, top, itemHeight) with the stack above the receiver. */
    static void listBounds(MethodVisitor mv) {
        int itemHeight = RetromodTransformer.CONSTRUCTOR_ARG_SPILL_SLOT + 20;
        int bottom = itemHeight + 1;
        int top = itemHeight + 2;
        mv.visitVarInsn(Opcodes.ISTORE, itemHeight);
        mv.visitVarInsn(Opcodes.ISTORE, bottom);
        mv.visitVarInsn(Opcodes.ISTORE, top);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ILOAD, bottom);
        mv.visitVarInsn(Opcodes.ILOAD, top);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ILOAD, top);
        mv.visitVarInsn(Opcodes.ILOAD, itemHeight);
    }

    /** Puts an empty root part before the render type function the old constructor took. */
    static void emptyRoot(MethodVisitor mv) {
        int function = RetromodTransformer.CONSTRUCTOR_ARG_SPILL_SLOT + 20;
        mv.visitVarInsn(Opcodes.ASTORE, function);
        mv.visitTypeInsn(Opcodes.NEW, MODEL_PART);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/List", "of", "()Ljava/util/List;", true);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Map", "of", "()Ljava/util/Map;", true);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MODEL_PART, "<init>", "(Ljava/util/List;Ljava/util/Map;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, function);
    }

    /** Level events with a player source, game events from before they were holders, and structure lookups. */
    static void registerLevel(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode accessor = host.apply(LEVEL_ACCESSOR);
        String levelEvent = "(L" + PLAYER + ";I" + L_POS + "I)V";
        String gameEvent = "(" + L_ENTITY + L_GAME_EVENT + L_POS + ")V";
        String contextEvent = "(" + L_GAME_EVENT + L_POS + "L" + GAME_EVENT + "$Context;)V";
        String[][] calls = {{"levelEvent", levelEvent}, {"gameEvent", gameEvent}, {"gameEvent", contextEvent}};
        for (String[] call : calls) {
            if (!lacks(accessor, call[0], call[1], "1.20.5")) continue;
            RebuiltApiClasses.registerTree(t, LEVEL_EVENTS);
            String helperDesc = "(L" + LEVEL_ACCESSOR + ";" + call[1].substring(1);
            for (String owner : LEVEL_OWNERS) t.registerMethodRedirect(owner, call[0], call[1], LEVEL_EVENTS, call[0], helperDesc, true);
            t.registerInheritedMethodRedirect(LEVEL_ACCESSOR, call[0], call[1], LEVEL_EVENTS, call[0], helperDesc);
        }
        // A block drawn only by its block entity renderer is an invisible one to the chunk mesher now.
        ClassNode shape = host.apply(RENDER_SHAPE);
        if (shape == null ? targetAtLeast("1.21.4") : !hasField(shape, "ENTITYBLOCK_ANIMATED") && hasField(shape, "INVISIBLE")) {
            t.registerFieldRedirect(RENDER_SHAPE, "ENTITYBLOCK_ANIMATED", RENDER_SHAPE, "INVISIBLE");
        }
        String keepLiquids = "(Z)L" + PLACE_SETTINGS + ";";
        if (lacks(host.apply(PLACE_SETTINGS), "setKeepLiquids", keepLiquids, "1.20.5")) {
            RebuiltApiClasses.registerTree(t, STRUCTURE_SETTINGS);
            t.registerMethodRedirect(PLACE_SETTINGS, "setKeepLiquids", keepLiquids, STRUCTURE_SETTINGS, "setKeepLiquids",
                    "(L" + PLACE_SETTINGS + ";Z)L" + PLACE_SETTINGS + ";", true);
        }
        ClassNode manager = host.apply(STRUCTURE_MANAGER);
        String start = "(Lnet/minecraft/core/SectionPos;L" + STRUCTURE + ";Lnet/minecraft/world/level/chunk/StructureAccess;)L"
                + STRUCTURE_START + ";";
        if (lacks(manager, "getStartForStructure", start, "26.3")) {
            RebuiltApiClasses.registerTree(t, STRUCTURE_CALLS);
            t.registerMethodRedirect(STRUCTURE_MANAGER, "getStartForStructure", start, STRUCTURE_CALLS, "getStartForStructure",
                    "(L" + STRUCTURE_MANAGER + ";" + start.substring(1), true);
        }
        String pieceAt = "(" + L_POS + "L" + STRUCTURE + ";)L" + STRUCTURE_START + ";";
        if (lacks(manager, "getStructureWithPieceAt", pieceAt, "26.3")) {
            RebuiltApiClasses.registerTree(t, STRUCTURE_CALLS);
            t.registerMethodRedirect(STRUCTURE_MANAGER, "getStructureWithPieceAt", pieceAt, STRUCTURE_CALLS,
                    "getStructureWithPieceAt", "(L" + STRUCTURE_MANAGER + ";" + pieceAt.substring(1), true);
        }
    }

    /** Ids, packet tags and NBT files in the shapes 1.20.1 used. */
    static void registerData(RetromodTransformer t, Function<String, ClassNode> host) {
        String valid = "(Ljava/lang/String;)Z";
        if (lacks(host.apply(IDENTIFIER), "isValidResourceLocation", valid, "1.20.5")) {
            RebuiltApiClasses.registerTree(t, IDENTIFIER_CHECKS);
            // A Forge mod still names ResourceLocation when the class move has not reached this call.
            for (String owner : new String[]{IDENTIFIER, "net/minecraft/resources/ResourceLocation"}) {
                t.registerMethodRedirect(owner, "isValidResourceLocation", valid, IDENTIFIER_CHECKS, "isValidResourceLocation", valid);
            }
        }
        String anySize = "()" + L_COMPOUND;
        if (lacks(host.apply(BUF), "readAnySizeNbt", anySize, "1.20.2")) {
            RebuiltApiClasses.registerTree(t, NBT_READS);
            t.registerMethodRedirect(BUF, "readAnySizeNbt", anySize, NBT_READS, "readAnySizeNbt", "(L" + BUF + ";)" + L_COMPOUND, true);
        }
        // A tag ingredient is built on the item registry's named set from 1.21.2.
        String tagIngredient = "(Lnet/minecraft/tags/TagKey;)L" + INGREDIENT + ";";
        if (lacks(host.apply(INGREDIENT), "of", tagIngredient, "1.21.2")) {
            RebuiltApiClasses.registerTree(t, INGREDIENTS);
            t.registerMethodRedirect(INGREDIENT, "of", tagIngredient, INGREDIENTS, "of", tagIngredient);
        }
        // A component on the wire went through a registry-aware codec; the old calls keep the old JSON form.
        ClassNode buf = host.apply(BUF);
        String component = "Lnet/minecraft/network/chat/Component;";
        if (lacks(buf, "readComponent", "()" + component, "1.21")) {
            RebuiltApiClasses.registerTree(t, BUF_COMPONENTS);
            t.registerMethodRedirect(BUF, "readComponent", "()" + component, BUF_COMPONENTS, "readComponent",
                    "(L" + BUF + ";)" + component, true);
        }
        if (lacks(buf, "writeComponent", "(" + component + ")L" + BUF + ";", "1.21")) {
            RebuiltApiClasses.registerTree(t, BUF_COMPONENTS);
            t.registerMethodRedirect(BUF, "writeComponent", "(" + component + ")L" + BUF + ";", BUF_COMPONENTS, "writeComponent",
                    "(L" + BUF + ";" + component + ")L" + BUF + ";", true);
        }
        ClassNode nbtIo = host.apply(NBT_IO);
        String[][] files = {{"read", "(Ljava/io/File;)" + L_COMPOUND}, {"write", "(" + L_COMPOUND + "Ljava/io/File;)V"},
                {"readCompressed", "(Ljava/io/File;)" + L_COMPOUND}, {"readCompressed", "(Ljava/io/InputStream;)" + L_COMPOUND},
                {"writeCompressed", "(" + L_COMPOUND + "Ljava/io/File;)V"}};
        for (String[] file : files) {
            if (!lacks(nbtIo, file[0], file[1], "1.20.3")) continue;
            RebuiltApiClasses.registerTree(t, NBT_FILES);
            t.registerMethodRedirect(NBT_IO, file[0], file[1], NBT_FILES, file[0], file[1]);
        }
    }

    /** The old portal target, which Fabric 1.20.1 mods reach as TeleportTransition, and Fabric API's dimension teleport. */
    static void registerPortals(RetromodTransformer t, Function<String, ClassNode> host) {
        if (lacks(host.apply(TRANSITION), "<init>", PORTAL_INFO, "1.21")) {
            RebuiltApiClasses.registerTree(t, PORTAL_INFOS);
            t.registerConstructorRedirect(TRANSITION, PORTAL_INFO, PORTAL_INFOS, "create",
                    "(" + L_VEC + L_VEC + "FF)L" + TRANSITION + ";");
        }
        // Fabric API dropped FabricDimensions when vanilla took a transition; a host that still has it keeps it.
        if (host.apply(FABRIC_DIMENSIONS) == null) {
            RebuiltApiClasses.registerTree(t, PORTAL_INFOS);
            t.registerMethodRedirect(FABRIC_DIMENSIONS, "teleport", TELEPORT, PORTAL_INFOS, "teleport", TELEPORT);
        }
    }

    private static boolean lacks(ClassNode owner, String name, String desc, String since) {
        if (owner == null) return targetAtLeast(since);
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return false;
        }
        return true;
    }

    private static boolean hasField(ClassNode owner, String name) {
        return owner.fields.stream().anyMatch(field -> field.name.equals(name));
    }

    private static boolean targetAtLeast(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) >= 0;
    }
}
