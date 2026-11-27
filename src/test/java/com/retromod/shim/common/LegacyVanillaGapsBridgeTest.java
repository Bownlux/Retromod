/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.retromod.shim.common.LegacyVanillaGapsBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** Old single members reach their rebuilt forms on 26.x hosts, and only where the host lacks them. */
class LegacyVanillaGapsBridgeTest {

    /** One old call: its opcode, owner, name and descriptor. */
    record Call(int opcode, String owner, String name, String desc) {}

    static final String OLD_ADD_CARVER = "(L" + CARVING + ";Lnet/minecraft/resources/ResourceKey;)L" + GEN_BUILDER + ";";

    static final List<Call> OLD_CALLS = List.of(
            new Call(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "getXpRepairRatio", "()F"),
            new Call(Opcodes.INVOKEVIRTUAL, CAMERA, "forwardVector", "()Lorg/joml/Vector3f;"),
            new Call(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "getAttributeModifiers", "(" + L_SLOT + ")" + MULTIMAP),
            new Call(Opcodes.INVOKEVIRTUAL, ITEM, "getDefaultAttributeModifiers", "(" + L_SLOT + ")" + MULTIMAP),
            new Call(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "getEnchantmentTags", "()Lnet/minecraft/nbt/ListTag;"),
            new Call(Opcodes.INVOKESTATIC, ENCHANTMENT_HELPER, "getEnchantmentId", "(" + L_TAG + ")" + L_ID),
            new Call(Opcodes.INVOKEVIRTUAL, FOOD_DATA, "getExhaustionLevel", "()F"),
            new Call(Opcodes.INVOKEVIRTUAL, FOOD_DATA, "setExhaustion", "(F)V"),
            new Call(Opcodes.INVOKEVIRTUAL, ENCHANTMENT, "getFullname", "(I)" + L_COMPONENT),
            new Call(Opcodes.INVOKEVIRTUAL, STACKED_CONTENTS, "accountSimpleStack", "(" + L_STACK + ")V"),
            new Call(Opcodes.INVOKEVIRTUAL, LOOK_CONTROL, "isHasWanted", "()Z"),
            new Call(Opcodes.INVOKEVIRTUAL, PROJECTILE, "mayInteract", "(" + L_LEVEL + L_POS + ")Z"),
            new Call(Opcodes.INVOKESTATIC, TNT, "explode", "(" + L_LEVEL + L_POS + ")V"),
            new Call(Opcodes.INVOKESTATIC, MTH, "sqrt", "(D)F"),
            new Call(Opcodes.INVOKESTATIC, DIRECTION, "fromDelta", "(III)L" + DIRECTION + ";"),
            new Call(Opcodes.INVOKEVIRTUAL, CRASH_REPORT, "getFriendlyReport", "()Ljava/lang/String;"),
            new Call(Opcodes.INVOKESTATIC, IDENTIFIER, "of", "(Ljava/lang/String;C)" + L_ID),
            new Call(Opcodes.INVOKESTATIC, INTERACT_PACKET, "createAttackPacket", ATTACK_PACKET),
            new Call(Opcodes.INVOKEVIRTUAL, SERVER_LISTENER, "handleInteract", "(L" + INTERACT_PACKET + ";)V"),
            new Call(Opcodes.INVOKESTATIC, PACK, "readMetaAndCreate", OLD_READ_META),
            new Call(Opcodes.INVOKEVIRTUAL, GAME_MODE, "getPickRange", "()F"),
            new Call(Opcodes.INVOKEVIRTUAL, LOCAL_PLAYER, "getEntityReach", "()D"),
            new Call(Opcodes.INVOKEVIRTUAL, CUBE, "compile", OLD_CUBE_COMPILE),
            new Call(Opcodes.INVOKEVIRTUAL, GRAPHICS, "blitNineSliced", NINE_SLICED_10),
            new Call(Opcodes.INVOKEINTERFACE, RECIPE_LISTENER, "getRecipeBookComponent",
                    "()Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;"),
            new Call(Opcodes.INVOKESPECIAL, PATH_PACK, "<init>", "(Ljava/lang/String;Ljava/nio/file/Path;Z)V"),
            new Call(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "isFramed", "()Z"),
            new Call(Opcodes.INVOKEVIRTUAL, GEN_BUILDER, "addCarver", OLD_ADD_CARVER),
            new Call(Opcodes.INVOKEVIRTUAL, GEN_SETTINGS, "getCarvers", "(L" + CARVING + ";)Ljava/lang/Iterable;"),
            new Call(Opcodes.INVOKEVIRTUAL, SCREEN, "setTooltipForNextRenderPass", "(Ljava/util/List;)V"),
            new Call(Opcodes.INVOKEVIRTUAL, PLAYER, "playNotifySound", "(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"),
            new Call(Opcodes.INVOKEVIRTUAL, ENTITY, "sendSystemMessage", "(" + L_COMPONENT + ")V"),
            new Call(Opcodes.INVOKEVIRTUAL, WALK_ANIMATION, "update", "(FF)V"),
            new Call(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/CompoundTag", "putIntArray", "(Ljava/lang/String;Ljava/util/List;)V"),
            new Call(Opcodes.INVOKESTATIC, EXTRA_CODECS, "validate",
                    "(Lcom/mojang/serialization/Codec;Ljava/util/function/Function;)Lcom/mojang/serialization/Codec;"),
            new Call(Opcodes.INVOKEVIRTUAL, CLIENT_LEVEL, "disconnect", "()V"),
            new Call(Opcodes.INVOKEVIRTUAL, MINECRAFT, "isSingleplayer", "()Z"),
            new Call(Opcodes.INVOKEVIRTUAL, LOCAL_PLAYER, "sendMessage", "(" + L_COMPONENT + "Ljava/util/UUID;)V"),
            new Call(Opcodes.INVOKESTATIC, NBT_UTILS, "readBlockState", "(" + L_TAG + ")" + L_STATE),
            new Call(Opcodes.INVOKEVIRTUAL, MUTABLE_COMPONENT, "getKey", "()Ljava/lang/String;"),
            new Call(Opcodes.INVOKEVIRTUAL, SPRITE, "getWidth", "()I"),
            new Call(Opcodes.INVOKESTATIC, RENDER_SYSTEM, "pushMatrix", "()V"),
            new Call(Opcodes.INVOKEVIRTUAL, KEYBOARD, "setSendRepeatsToGui", "(Z)V"));


    private String previousTarget;
    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        transformer.setModSourceVersion(null);
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
    }

    @Test
    @DisplayName("on a 26.3 host each old call reaches its helper or the member that replaced it")
    void everyCallIsBridgedOn263() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyVanillaGapsBridge.register(transformer, host263()::get, false);

        List<String> refs = refs(transformer.transformClass(oldCalls(), "test/gaps/Old"));

        assertTrue(refs.contains(WORLD + ".getXpRepairRatio(" + L_STACK + ")F"), refs.toString());
        assertTrue(refs.contains(CAMERA_VECTORS + ".getLookVector(L" + CAMERA + ";)Lorg/joml/Vector3f;"), refs.toString());
        assertTrue(refs.contains(WORLD + ".getAttributeModifiers(" + L_STACK + L_SLOT + ")" + MULTIMAP), refs.toString());
        assertTrue(refs.contains(WORLD + ".getDefaultAttributeModifiers(L" + ITEM + ";" + L_SLOT + ")" + MULTIMAP), refs.toString());
        assertTrue(refs.contains(WORLD + ".getEnchantmentTags(" + L_STACK + ")Lnet/minecraft/nbt/ListTag;"), refs.toString());
        assertTrue(refs.contains(WORLD + ".getEnchantmentId(" + L_TAG + ")" + L_ID), refs.toString());
        assertTrue(refs.contains(WORLD + ".getExhaustionLevel(L" + FOOD_DATA + ";)F"), refs.toString());
        assertTrue(refs.contains(WORLD + ".setExhaustion(L" + FOOD_DATA + ";F)V"), refs.toString());
        assertTrue(refs.contains(WORLD + ".getFullname(L" + ENCHANTMENT + ";I)" + L_COMPONENT), refs.toString());
        assertTrue(refs.contains(WORLD + ".accountSimpleStack(Ljava/lang/Object;" + L_STACK + ")V"), refs.toString());
        assertTrue(refs.contains(LOOK_CONTROL + ".isLookingAtTarget()Z"), refs.toString());
        assertTrue(refs.contains(WORLD + ".mayInteract(L" + PROJECTILE + ";" + L_LEVEL + L_POS + ")Z"), refs.toString());
        assertTrue(refs.contains(WORLD + ".explode(" + L_LEVEL + L_POS + ")V"), refs.toString());
        assertTrue(refs.contains(WORLD + ".sqrt(D)F"), refs.toString());
        assertTrue(refs.contains(WORLD + ".fromDelta(III)L" + DIRECTION + ";"), refs.toString());
        assertTrue(refs.contains(WORLD + ".getFriendlyReport(L" + CRASH_REPORT + ";)Ljava/lang/String;"), refs.toString());
        assertTrue(refs.contains(IDENTIFIER + ".bySeparator(Ljava/lang/String;C)" + L_ID), refs.toString());
        assertTrue(refs.contains(WORLD + ".createAttackPacket" + ATTACK_PACKET), refs.toString());
        assertTrue(refs.contains(WORLD + ".handleInteract(L" + SERVER_LISTENER + ";L" + INTERACT_PACKET + ";)V"), refs.toString());
        assertTrue(refs.contains(WORLD + ".readMetaAndCreate" + OLD_READ_META), refs.toString());
        assertTrue(refs.contains(CLIENT + ".getPickRange(L" + GAME_MODE + ";)F"), refs.toString());
        assertTrue(refs.contains(LOCAL_PLAYER + ".entityInteractionRange()D"), refs.toString());
        assertTrue(refs.contains(CLIENT + ".compile(L" + CUBE + ";" + OLD_CUBE_COMPILE.substring(1)), refs.toString());
        assertTrue(refs.contains(CLIENT + ".blitNineSliced(L" + GRAPHICS + ";" + NINE_SLICED_10.substring(1)), refs.toString());
        assertTrue(refs.contains(CLIENT + ".getRecipeBookComponent(Ljava/lang/Object;)"
                + "Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;"), refs.toString());
        assertTrue(refs.contains(WORLD + ".pathPackResources(Ljava/lang/String;Ljava/nio/file/Path;Z)L" + PATH_PACK + ";"), refs.toString());
        assertTrue(refs.contains(WORLD + ".isFramed(" + L_STACK + ")Z"), refs.toString());
        assertTrue(refs.contains(WORLD + ".addCarver(L" + GEN_BUILDER + ";Ljava/lang/Object;Lnet/minecraft/resources/ResourceKey;)L"
                + GEN_BUILDER + ";"), refs.toString());
        assertTrue(refs.contains(WORLD + ".getCarvers(L" + GEN_SETTINGS + ";Ljava/lang/Object;)Ljava/lang/Iterable;"), refs.toString());
        assertTrue(refs.contains(FRAMES + ".setTooltipForNextRenderPass(Ljava/lang/Object;Ljava/util/List;)V"), refs.toString());
        assertTrue(refs.contains(WORLD + ".playNotifySound(L" + PLAYER + ";Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"),
                refs.toString());
        assertTrue(refs.contains(WORLD + ".sendSystemMessage(" + L_ENTITY + L_COMPONENT + ")V"), refs.toString());
        assertTrue(refs.contains(WORLD + ".update(L" + WALK_ANIMATION + ";FF)V"), refs.toString());
        assertTrue(refs.contains(WORLD + ".putIntArray(Lnet/minecraft/nbt/CompoundTag;Ljava/lang/String;Ljava/util/List;)V"), refs.toString());
        assertTrue(refs.contains(WORLD + ".validate(Lcom/mojang/serialization/Codec;Ljava/util/function/Function;)Lcom/mojang/serialization/Codec;"),
                refs.toString());
        assertTrue(refs.contains(CLIENT + ".disconnect(L" + CLIENT_LEVEL + ";)V"), refs.toString());
        assertTrue(refs.contains(MINECRAFT + ".hasSingleplayerServer()Z"), refs.toString());
        assertTrue(refs.contains(WORLD + ".sendMessage(L" + PLAYER + ";" + L_COMPONENT + "Ljava/util/UUID;)V"), "a local player too: " + refs);
        assertTrue(refs.contains(WORLD + ".readBlockState(" + L_TAG + ")" + L_STATE), refs.toString());
        assertTrue(refs.contains(WORLD + ".getKey(L" + MUTABLE_COMPONENT + ";)Ljava/lang/String;"), refs.toString());
        assertTrue(refs.contains(CLIENT + ".getWidth(L" + SPRITE + ";)I"), refs.toString());
        assertFalse(refs.contains(RENDER_SYSTEM + ".pushMatrix()V"), "the removed GL switch is dropped: " + refs);
        assertFalse(refs.contains(KEYBOARD + ".setSendRepeatsToGui(Z)V"), refs.toString());
        for (String helper : List.of(WORLD, CLIENT, FRAMES, LEGACY_CARVING)) {
            assertTrue(transformer.getSyntheticClasses().containsKey(helper), helper + " must ship with the mod");
        }
    }

    @Test
    @DisplayName("a 1.21.1 mod's carving step constant reads the stand-in and its carver lands in the one list; a Fabric 1.19.2 mod keeps the step")
    void carvingStepMoves() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyVanillaGapsBridge.register(transformer, host263()::get, true);

        transformer.setModSourceVersion("1.19.2");
        byte[] older = transformer.transformClass(carvingStep(), "test/gaps/Carvers");
        assertTrue(fields(older).contains(CARVING + ".AIR"), "a Fabric mod before 1.19.3 keeps the step: " + fields(older));

        transformer.setModSourceVersion("1.21.1");
        byte[] transformed = transformer.transformClass(carvingStep(), "test/gaps/Carvers");
        assertTrue(fields(transformed).contains(LEGACY_CARVING + ".AIR"), fields(transformed).toString());
        assertTrue(refs(transformed).contains(WORLD + ".addCarver(L" + GEN_BUILDER + ";Ljava/lang/Object;Lnet/minecraft/resources/ResourceKey;)L"
                + GEN_BUILDER + ";"), refs(transformed).toString());
    }

    @Test
    @DisplayName("removed fields read through helpers: the player's latency, the culling flag and the toast manager")
    void removedFieldsReadThroughHelpers() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyVanillaGapsBridge.register(transformer, host263()::get);

        byte[] transformed = transformer.transformClass(oldFieldReads(), "test/gaps/Fields");
        List<String> refs = refs(transformed);
        List<String> fields = fields(transformed);

        assertTrue(refs.contains(WORLD + ".latency(Ljava/lang/Object;)I"), refs.toString());
        assertTrue(refs.contains(CLIENT + ".noCulling(Ljava/lang/Object;)Z"), refs.toString());
        assertTrue(refs.contains(CLIENT + ".setNoCulling(Ljava/lang/Object;Z)V"), refs.toString());
        assertTrue(refs.contains(TOASTS + ".toastManager(Ljava/lang/Object;)L" + TOAST_MANAGER + ";"), refs.toString());
        assertTrue(fields.contains(INPUT_TYPE + ".KEYBOARD"), "26.3 named both keyboard types KEYBOARD: " + fields);
        assertTrue(refs.contains(WORLD + ".nullSlot()L" + SLOT_ACCESS + ";"), refs.toString());
    }

    @Test
    @DisplayName("a mod's own chunk, toast manager and video settings screen call the constructors 26.x has")
    void superConstructorsTakeTheNewShapes() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyVanillaGapsBridge.register(transformer, host263()::get);

        List<String> chunk = refs(transformer.transformClass(subclass("test/gaps/Chunk", CHUNK_ACCESS, OLD_CHUNK), "test/gaps/Chunk"));
        assertTrue(chunk.contains(CHUNK_ACCESS + ".<init>" + NEW_CHUNK), chunk.toString());
        assertTrue(chunk.contains(WORLD + ".containerFactory(Ljava/lang/Object;)Lnet/minecraft/world/level/chunk/PalettedContainerFactory;"),
                chunk.toString());

        List<String> toasts = refs(transformer.transformClass(subclass("test/gaps/Toasts", TOAST_MANAGER, "(L" + MINECRAFT + ";)V"),
                "test/gaps/Toasts"));
        assertTrue(toasts.contains(TOAST_MANAGER + ".<init>(L" + MINECRAFT + ";" + L_OPTIONS + ")V"), toasts.toString());

        List<String> video = refs(transformer.transformClass(subclass("test/gaps/Video", VIDEO_SETTINGS, "(" + L_SCREEN + L_OPTIONS + ")V"),
                "test/gaps/Video"));
        assertTrue(video.contains(VIDEO_SETTINGS + ".<init>(" + L_SCREEN + "L" + MINECRAFT + ";" + L_OPTIONS + ")V"), video.toString());
        assertTrue(video.contains(MINECRAFT + ".getInstance()L" + MINECRAFT + ";"), video.toString());
    }

    @Test
    @DisplayName("a listener subclass's super call to handleInteract keeps reaching the host's own method")
    void superCallsStayOnTheHost() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyVanillaGapsBridge.register(transformer, host263()::get);

        ClassNode node = node(transformer.transformClass(superHandleInteract(), "test/gaps/Listener"));

        MethodInsnNode call = calls(node).stream().filter(c -> c.name.equals("handleInteract")).findFirst().orElseThrow();
        assertEquals(Opcodes.INVOKESPECIAL, call.getOpcode());
        assertEquals(SERVER_LISTENER, call.owner);
    }

    @Test
    @DisplayName("the dirt message screen becomes the message screen, and the LAN screen a stand-in from 26.2")
    void screensMove() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        LegacyVanillaGapsBridge.register(transformer, host263()::get);

        List<String> types = types(transformer.transformClass(screenUses(), "test/gaps/Screens"));

        assertTrue(types.contains(MESSAGE_SCREEN), types.toString());
        assertTrue(types.contains(LAN_SCREEN), types.toString());
        assertTrue(transformer.getSyntheticClasses().containsKey(LAN_SCREEN));
    }

    @Test
    @DisplayName("a 1.19.2 mod's materials are real again: its constants, its builder and getMaterial() reach the rebuilt class")
    void materialIsRebuilt() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyVanillaGapsBridge.register(transformer, host263()::get);

        byte[] transformed = transformer.transformClass(materialUses(), "test/gaps/Materials");
        List<String> refs = refs(transformed);

        assertTrue(refs.contains(MATERIAL + ".fromState(Ljava/lang/Object;)L" + MATERIAL + ";"), refs.toString());
        assertTrue(fields(transformed).contains(MATERIAL + ".STONE"), "the constant is real, not nulled: " + fields(transformed));
        assertTrue(refs.contains(MATERIAL + "$Builder.build()L" + MATERIAL + ";"), refs.toString());
        assertTrue(refs.contains(MATERIAL + ".properties(L" + MATERIAL + ";)Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;"),
                "the block gets the settings its material gave: " + refs);
        ClassNode rebuilt = node(transformer.getSyntheticClasses().get(MATERIAL));
        assertEquals(MATERIAL, rebuilt.name, "registered under the old name");
        assertTrue(rebuilt.fields.stream().anyMatch(f -> f.name.equals("STONE") && f.desc.equals("L" + MATERIAL + ";")));
        assertTrue(transformer.getSyntheticClasses().containsKey(MATERIAL + "$Builder"));
        assertTrue(transformer.getSyntheticClasses().containsKey(MATERIAL + "$Reactions"), "nested classes share its package");
    }

    @Test
    @DisplayName("GlUtil reads the rebuilt helper, the Ogg stream the JOrbis one, and destroy() is dropped on 26.3")
    void platformMoves() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        Map<String, ClassNode> host = host263();
        host.put(JORBIS_STREAM, new ClassNode());
        LegacyVanillaGapsBridge.register(transformer, host::get);

        byte[] transformed = transformer.transformClass(platformUses(), "test/gaps/Platform");
        List<String> refs = refs(transformed);

        assertTrue(refs.contains(GL_UTIL_REBUILT + ".getVendor()Ljava/lang/String;"), refs.toString());
        assertTrue(types(transformed).contains(JORBIS_STREAM), types(transformed).toString());
        assertFalse(refs.contains(MINECRAFT + ".destroy()V"), refs.toString());
        assertTrue(transformer.getSyntheticClasses().containsKey(GL_UTIL_REBUILT));
    }

    @Test
    @DisplayName("on 26.1, whose GPU device answers its vendor itself, GlUtil reads the 26.1 helper")
    void glUtilOn261() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        Map<String, ClassNode> host = host263();
        host.put(GPU_DEVICE, withMethods(new String[][]{{"getVendor", "()Ljava/lang/String;"}}));
        LegacyVanillaGapsBridge.register(transformer, host::get);

        assertTrue(refs(transformer.transformClass(platformUses(), "test/gaps/Platform")).contains(
                GL_UTIL_REBUILT + "261.getVendor()Ljava/lang/String;"));
    }

    @Test
    @DisplayName("a member a host's extension interface still declares is left alone, as NeoForge keeps getXpRepairRatio")
    void inheritedHostMembersAreKept() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        Map<String, ClassNode> host = host263();
        ClassNode stack = host.get(ITEM_STACK);
        stack.interfaces.add("test/host/IItemStackExtension");
        host.put("test/host/IItemStackExtension", withMethods(new String[][]{{"getXpRepairRatio", "()F"}}));
        LegacyVanillaGapsBridge.register(transformer, host::get);

        List<String> refs = refs(transformer.transformClass(oldCalls(), "test/gaps/Old"));

        assertTrue(refs.contains(ITEM_STACK + ".getXpRepairRatio()F"), refs.toString());
    }

    @Test
    @DisplayName("a mod built for a version that no longer had the member keeps its call")
    void limitedToOlderMods() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyVanillaGapsBridge.register(transformer, host263()::get);
        transformer.setModSourceVersion("26.1");

        List<String> refs = refs(transformer.transformClass(oldCalls(), "test/gaps/Newer"));

        assertTrue(refs.contains(SERVER_LISTENER + ".handleInteract(L" + INTERACT_PACKET + ";)V"), "the host still has it: " + refs);
        assertTrue(refs.contains(MTH + ".sqrt(D)F"), refs.toString());
    }

    @Test
    @DisplayName("an older target registers nothing, since the helpers are built on 26.x shapes")
    void olderTargetsRegisterNothing() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        LegacyVanillaGapsBridge.register(transformer, host263()::get);

        List<String> refs = refs(transformer.transformClass(oldCalls(), "test/gaps/Old"));

        for (Call call : OLD_CALLS) {
            assertTrue(refs.contains(call.owner() + "." + call.name() + call.desc()), call + " in " + refs);
        }
    }

    /** The 26.3 host shapes: every old member is gone, and each replacement the bridge checks for is there. */
    private static Map<String, ClassNode> host263() {
        Map<String, ClassNode> host = new HashMap<>();
        for (Call call : OLD_CALLS) host.putIfAbsent(call.owner(), new ClassNode());
        host.put(SERVER_LISTENER, withMethods(new String[][]{{"handleInteract", "(L" + INTERACT_PACKET + ";)V"}}));
        host.put(CHUNK_ACCESS, withMethods(new String[][]{{"<init>", NEW_CHUNK}}));
        host.put(TOAST_MANAGER, withMethods(new String[][]{{"<init>", "(L" + MINECRAFT + ";" + L_OPTIONS + ")V"}}));
        host.put(VIDEO_SETTINGS, withMethods(new String[][]{{"<init>", "(" + L_SCREEN + "L" + MINECRAFT + ";" + L_OPTIONS + ")V"}}));
        host.put(MINECRAFT, withMethods(new String[][]{{"hasSingleplayerServer", "()Z"}}));
        host.put(MESSAGE_SCREEN, new ClassNode());
        host.put("net/minecraft/client/gui/Gui", withField("toastManager", "L" + TOAST_MANAGER + ";"));
        host.put(INPUT_TYPE, withField("KEYBOARD", "L" + INPUT_TYPE + ";"));
        host.put(SLOT_ACCESS, new ClassNode());
        host.put(CAMERA, withMethods(new String[][]{{"forwardVector", "()Lorg/joml/Vector3fc;"}}));
        host.put(ENTITY, new ClassNode());
        host.put(SERVER_PLAYER, new ClassNode());
        host.put(GEN_BUILDER, withMethods(new String[][]{{"addCarver", "(Lnet/minecraft/resources/ResourceKey;)L" + GEN_BUILDER + ";"}}));
        return host;
    }

    /** A class whose one method makes every old call with default arguments and drops each result. */
    private static byte[] oldCalls() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/gaps/Old", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        for (Call call : OLD_CALLS) {
            boolean constructor = call.name().equals("<init>");
            if (constructor) {
                mv.visitTypeInsn(Opcodes.NEW, call.owner());
                mv.visitInsn(Opcodes.DUP);
            } else if (call.opcode() != Opcodes.INVOKESTATIC) {
                mv.visitInsn(Opcodes.ACONST_NULL);
            }
            for (Type argument : Type.getArgumentTypes(call.desc())) pushDefault(mv, argument);
            mv.visitMethodInsn(call.opcode(), call.owner(), call.name(), call.desc(), call.opcode() == Opcodes.INVOKEINTERFACE);
            Type result = constructor ? Type.getObjectType(call.owner()) : Type.getReturnType(call.desc());
            if (result.getSize() > 0) mv.visitInsn(result.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Reads of the player's latency, the culling flag and the toast manager, a write of the flag, and a key type. */
    private static byte[] oldFieldReads() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/gaps/Fields", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
                "(L" + SERVER_PLAYER + ";" + L_ENTITY + "L" + MINECRAFT + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, SERVER_PLAYER, "latency", "I");
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.GETFIELD, ENTITY, "noCulling", "Z");
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, ENTITY, "noCulling", "Z");
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.GETFIELD, MINECRAFT, "toastManager", "L" + TOAST_MANAGER + ";");
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, INPUT_TYPE, "SCANCODE", "L" + INPUT_TYPE + ";");
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, SLOT_ACCESS, "NULL", "L" + SLOT_ACCESS + ";");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A material constant, a state's material compared to it, a mod's own material, and block properties from a material. */
    private static byte[] materialUses() {
        String props = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/gaps/Materials", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(L" + STATE + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STATE, "getMaterial", "()L" + MATERIAL + ";", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, MATERIAL, "STONE", "L" + MATERIAL + ";");
        mv.visitInsn(Opcodes.POP2);
        mv.visitTypeInsn(Opcodes.NEW, MATERIAL + "$Builder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MATERIAL + "$Builder", "<init>", "(Lnet/minecraft/world/level/material/MapColor;)V", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MATERIAL + "$Builder", "build", "()L" + MATERIAL + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, props, "of", "(L" + MATERIAL + ";)L" + props + ";", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A GPU vendor read, an Ogg stream and the client's destroy step, as old launch and debug mods make them. */
    private static byte[] platformUses() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/gaps/Platform", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(L" + MINECRAFT + ";)V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL_UTIL, "getVendor", "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, OGG_STREAM);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MINECRAFT, "destroy", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code builder.addCarver(GenerationStep.Carving.AIR, key)}, as a 1.20.1 biome adds a cave carver. */
    private static byte[] carvingStep() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/gaps/Carvers", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(L" + GEN_BUILDER + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, CARVING, "AIR", "L" + CARVING + ";");
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GEN_BUILDER, "addCarver", OLD_ADD_CARVER, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A subclass whose constructor passes default arguments to its superclass's old constructor. */
    private static byte[] subclass(String name, String superName, String superDesc) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        for (Type argument : Type.getArgumentTypes(superDesc)) pushDefault(mv, argument);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", superDesc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A packet listener subclass whose handleInteract override calls the host's. */
    private static byte[] superHandleInteract() {
        String handle = "(L" + INTERACT_PACKET + ";)V";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/gaps/Listener", null, SERVER_LISTENER, null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "handleInteract", handle, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SERVER_LISTENER, "handleInteract", handle, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Checks for the dirt message and LAN screens, as InvMove lists screens it leaves alone. */
    private static byte[] screenUses() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/gaps/Screens", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        for (String screen : new String[]{DIRT_SCREEN, HOST_LAN_SCREEN}) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, screen);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void pushDefault(MethodVisitor mv, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> mv.visitInsn(Opcodes.ICONST_0);
            case Type.FLOAT -> mv.visitInsn(Opcodes.FCONST_0);
            case Type.LONG -> mv.visitInsn(Opcodes.LCONST_0);
            case Type.DOUBLE -> mv.visitInsn(Opcodes.DCONST_0);
            default -> mv.visitInsn(Opcodes.ACONST_NULL);
        }
    }

    private static ClassNode withMethods(String[][] methods) {
        ClassNode node = new ClassNode();
        for (String[] method : methods) node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, method[0], method[1], null, null));
        return node;
    }

    private static ClassNode withField(String name, String desc) {
        ClassNode node = new ClassNode();
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, name, desc, null, null));
        return node;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<MethodInsnNode> calls(ClassNode node) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call);
            }
        }
        return calls;
    }

    private static List<String> refs(byte[] bytes) {
        List<String> refs = new ArrayList<>();
        for (MethodInsnNode call : calls(node(bytes))) refs.add(call.owner + "." + call.name + call.desc);
        return refs;
    }

    private static List<String> fields(byte[] bytes) {
        List<String> fields = new ArrayList<>();
        for (MethodNode method : node(bytes).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) fields.add(field.owner + "." + field.name);
            }
        }
        return fields;
    }

    private static List<String> types(byte[] bytes) {
        List<String> types = new ArrayList<>();
        for (MethodNode method : node(bytes).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof TypeInsnNode type) types.add(type.desc);
            }
        }
        return types;
    }
}
