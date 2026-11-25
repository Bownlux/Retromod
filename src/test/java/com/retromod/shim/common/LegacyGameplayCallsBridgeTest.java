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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.retromod.shim.common.LegacyGameplayCallsBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** Old gameplay, registry, data and network calls reach their rebuilt forms on 26.x hosts, and only where the host lacks them. */
class LegacyGameplayCallsBridgeTest {

    /** One old call: its opcode, owner, name and descriptor. */
    record Call(int opcode, String owner, String name, String desc) {}

    static final List<Call> OLD_CALLS = List.of(
            new Call(Opcodes.INVOKEINTERFACE, DEFAULTED_REGISTRY, "getOrCreateTag", TAG),
            new Call(Opcodes.INVOKEINTERFACE, REGISTRY, "holderOwner", OWNER),
            new Call(Opcodes.INVOKEVIRTUAL, STRUCTURE_MANAGER, "getStructureWithPieceAt", PIECE_AT_KEY),
            new Call(Opcodes.INVOKEVIRTUAL, STATE, "isPathfindable", PATHFINDABLE),
            new Call(Opcodes.INVOKEVIRTUAL, CLIENT_LEVEL, "addParticle", PARTICLE),
            new Call(Opcodes.INVOKEVIRTUAL, LEVEL, "neighborChanged", NEIGHBOR),
            new Call(Opcodes.INVOKESTATIC, CLEARABLE, "tryClear", "(Ljava/lang/Object;)V"),
            new Call(Opcodes.INVOKEVIRTUAL, TEMPLATE, "fillFromWorld", FILL),
            new Call(Opcodes.INVOKEVIRTUAL, SKULL, "getOwnerProfile", "()Lcom/mojang/authlib/GameProfile;"),
            new Call(Opcodes.INVOKEVIRTUAL, PROFESSION, "name", "()Ljava/lang/String;"),
            new Call(Opcodes.INVOKEVIRTUAL, SERVER_LEVEL, "getStructureManager", TEMPLATE_MANAGER),
            new Call(Opcodes.INVOKESTATIC, BLOCK_ITEM, "updateState", UPDATE_STATE),
            new Call(Opcodes.INVOKEVIRTUAL, ITEM, "canAttackBlock", ATTACK_BLOCK),
            new Call(Opcodes.INVOKEVIRTUAL, OPERATION, "toValue", "()I"),
            new Call(Opcodes.INVOKESTATIC, ENCHANTMENT_HELPER, "hasVanishingCurse", CURSE),
            new Call(Opcodes.INVOKEVIRTUAL, CUSTOM_DATA, "contains", "(Ljava/lang/String;)Z"),
            new Call(Opcodes.INVOKEVIRTUAL, CONTAINER, "stream", STREAM),
            new Call(Opcodes.INVOKEVIRTUAL, BUNDLE, "itemCopyStream", STREAM),
            new Call(Opcodes.INVOKEVIRTUAL, BUNDLE, "items", "()Ljava/lang/Iterable;"),
            new Call(Opcodes.INVOKEVIRTUAL, DISPLAY_INFO, "getIcon", ICON),
            new Call(Opcodes.INVOKEVIRTUAL, SERVER, "getProfilePermissions", PROFILE_PERMISSIONS),
            new Call(Opcodes.INVOKEVIRTUAL, CHUNK_HOLDER, "getLastAvailableStatus", STATUS),
            new Call(Opcodes.INVOKEVIRTUAL, CHUNK_HOLDER, "getOrScheduleFuture", SCHEDULE),
            new Call(Opcodes.INVOKEVIRTUAL, STORAGE_ACCESS, "getDataTag", DATA_TAG),
            new Call(Opcodes.INVOKEVIRTUAL, STORAGE_ACCESS, "getDataTag", DATA_TAG_FALLBACK),
            new Call(Opcodes.INVOKESTATIC, TEXT_COLOR, "parseColor", PARSE_COLOR),
            new Call(Opcodes.INVOKEVIRTUAL, CHAT_BOUND, "chatType", "()Lnet/minecraft/network/chat/ChatType;"),
            new Call(Opcodes.INVOKESTATIC, BUF, "getVarIntSize", "(I)I"),
            new Call(Opcodes.INVOKEVIRTUAL, LEVEL, "playSound", SHORT_SOUND),
            new Call(Opcodes.INVOKEINTERFACE, LEVEL_ACCESSOR, "getCurrentDifficultyAt", DIFFICULTY),
            new Call(Opcodes.INVOKEVIRTUAL, LEVEL, "getShade", SHADE),
            new Call(Opcodes.INVOKEVIRTUAL, CLIENT_LEVEL, "getShade", SHADE),
            new Call(Opcodes.INVOKEVIRTUAL, STATE, "getFaceOcclusionShape", OCCLUSION),
            new Call(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "onCraftedBy", CRAFTED_BY),
            new Call(Opcodes.INVOKEVIRTUAL, SPAWN_EGG, "getType", EGG_TYPE),
            new Call(Opcodes.INVOKEVIRTUAL, ADVANCEMENT, "getDisplay", "()L" + DISPLAY_INFO + ";"),
            new Call(Opcodes.INVOKEVIRTUAL, SERVER_PLAYER, "playNotifySound", NOTIFY_SOUND),
            new Call(Opcodes.INVOKEVIRTUAL, REGISTRY_BUF, "readById", READ_BY_ID),
            new Call(Opcodes.INVOKEVIRTUAL, BUF, "writeId", WRITE_ID),
            new Call(Opcodes.INVOKEVIRTUAL, DIMENSION_TYPE, "ultraWarm", "()Z"),
            new Call(Opcodes.INVOKEVIRTUAL, DIMENSION_TYPE, "natural", "()Z"),
            new Call(Opcodes.INVOKESPECIAL, ADD_ENTITY, "<init>", "(Lnet/minecraft/world/entity/Entity;I)V"),
            new Call(Opcodes.INVOKESPECIAL, SPAWN_DATA, "<init>", OLD_SPAWN_DATA));

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
        LegacyGameplayCallsBridge.register(transformer, host263()::get);

        List<String> refs = refs(transformer.transformClass(oldCalls(), "test/gameplay/Old"));

        assertTrue(refs.contains(REGISTRY_TAGS + ".getOrCreateTag(L" + REGISTRY + ";Lnet/minecraft/tags/TagKey;)Lnet/minecraft/core/HolderSet$Named;"), refs.toString());
        assertTrue(refs.contains(REGISTRY_TAGS + ".holderOwner(L" + REGISTRY + ";)Lnet/minecraft/core/HolderOwner;"), refs.toString());
        assertTrue(refs.contains(LEVEL_CALLS + ".getStructureWithPieceAt(L" + STRUCTURE_MANAGER + ";" + PIECE_AT_KEY.substring(1)), refs.toString());
        assertTrue(refs.contains(LEVEL_CALLS + ".isPathfindable(Ljava/lang/Object;" + PATHFINDABLE.substring(1)), refs.toString());
        assertTrue(refs.contains(LEVEL_CALLS + ".addParticle(L" + LEVEL + ";" + PARTICLE.substring(1)), "a client level call too: " + refs);
        assertTrue(refs.contains(LEVEL_CALLS + ".neighborChanged(L" + LEVEL + ";" + NEIGHBOR.substring(1)), refs.toString());
        assertTrue(refs.contains(LEVEL_CALLS + ".tryClear(Ljava/lang/Object;)V"), refs.toString());
        assertTrue(refs.contains(LEVEL_CALLS + ".fillFromWorld(L" + TEMPLATE + ";" + FILL.substring(1)), refs.toString());
        assertTrue(refs.contains(LEVEL_CALLS + ".getOwnerProfile(L" + SKULL + ";)Lcom/mojang/authlib/GameProfile;"), refs.toString());
        assertTrue(refs.contains(LEVEL_CALLS + ".professionName(L" + PROFESSION + ";)Ljava/lang/String;"), refs.toString());
        assertTrue(refs.contains(SERVER_LEVEL + ".getStructureTemplateManager" + TEMPLATE_MANAGER), "26.3 renamed it: " + refs);
        assertTrue(refs.contains(ITEM_CALLS + ".updateState" + UPDATE_STATE), refs.toString());
        assertTrue(refs.contains(ITEM_CALLS + ".canAttackBlock(L" + ITEM + ";" + ATTACK_BLOCK.substring(1)), refs.toString());
        assertTrue(refs.contains(OPERATION + ".id()I"), refs.toString());
        assertTrue(refs.contains(ITEM_CALLS + ".hasVanishingCurse" + CURSE), refs.toString());
        assertTrue(refs.contains(ITEM_CALLS + ".contains(L" + CUSTOM_DATA + ";Ljava/lang/String;)Z"), refs.toString());
        assertTrue(refs.contains(CONTAINER + ".itemCopies" + STREAM), refs.toString());
        assertTrue(refs.contains(BUNDLE + ".itemCopies" + STREAM), refs.toString());
        assertTrue(refs.contains(ITEM_CALLS + ".items(L" + BUNDLE + ";)Ljava/lang/Iterable;"), refs.toString());
        assertTrue(refs.contains(ITEM_CALLS + ".getIcon(L" + DISPLAY_INFO + ";)" + L_STACK), refs.toString());
        assertTrue(refs.contains(SERVER_CALLS + ".getProfilePermissions(L" + SERVER + ";Lcom/mojang/authlib/GameProfile;)I"), refs.toString());
        assertTrue(refs.contains(CHUNK_HOLDER + ".getLatestStatus" + STATUS), refs.toString());
        assertTrue(refs.contains(SERVER_CALLS + ".getOrScheduleFuture(L" + GENERATION_HOLDER + ";" + SCHEDULE.substring(1)), refs.toString());
        assertTrue(refs.contains(SERVER_CALLS + ".getDataTag(L" + STORAGE_ACCESS + ";)" + DATA_TAG.substring(2)), refs.toString());
        assertTrue(refs.contains(SERVER_CALLS + ".getDataTag(L" + STORAGE_ACCESS + ";Z)" + DATA_TAG.substring(2)),
                "the fallback read is upgraded too, not only renamed: " + refs);
        assertTrue(refs.contains(CHAT_CALLS + ".parseColor" + PARSE_COLOR), refs.toString());
        assertTrue(refs.contains(CHAT_CALLS + ".chatType(L" + CHAT_BOUND + ";)Lnet/minecraft/network/chat/ChatType;"), refs.toString());
        assertTrue(refs.contains(VAR_INT + ".getByteSize(I)I"), refs.toString());
        assertTrue(refs.contains(LEVEL_CALLS + ".playSound(L" + LEVEL + ";" + SHORT_SOUND.substring(1)), refs.toString());
        assertTrue(refs.contains(LEVEL_CALLS + ".getCurrentDifficultyAt(Ljava/lang/Object;" + DIFFICULTY.substring(1)), refs.toString());
        assertTrue(refs.contains(BLOCK_SHADES + ".getLevelShade(Ljava/lang/Object;" + SHADE.substring(1)), refs.toString());
        assertTrue(refs.contains(BLOCK_SHADES + ".getShade(L" + TINT_GETTER + ";" + SHADE.substring(1)), "a client level is a block getter: " + refs);
        assertTrue(refs.contains(LEVEL_CALLS + ".getFaceOcclusionShape(Ljava/lang/Object;" + OCCLUSION.substring(1)), refs.toString());
        assertTrue(refs.contains(ITEM_CALLS + ".onCraftedBy(L" + ITEM_STACK + ";" + CRAFTED_BY.substring(1)), refs.toString());
        assertTrue(refs.contains(ITEM_CALLS + ".spawnEggType(L" + SPAWN_EGG + ";" + EGG_TYPE.substring(1)), refs.toString());
        assertTrue(refs.contains(ITEM_CALLS + ".getDisplay(L" + ADVANCEMENT + ";)L" + DISPLAY_INFO + ";"), refs.toString());
        assertTrue(refs.contains(SERVER_CALLS + ".playNotifySound(L" + SERVER_PLAYER + ";" + NOTIFY_SOUND.substring(1)), refs.toString());
        assertTrue(refs.contains(NETWORK_CALLS + ".readById(L" + BUF + ";" + READ_BY_ID.substring(1)), "a registry buffer call too: " + refs);
        assertTrue(refs.contains(NETWORK_CALLS + ".writeId(L" + BUF + ";" + WRITE_ID.substring(1)), refs.toString());
        assertTrue(refs.contains(LEVEL_CALLS + ".ultraWarm(L" + DIMENSION_TYPE + ";)Z"), refs.toString());
        assertTrue(refs.contains(LEVEL_CALLS + ".natural(L" + DIMENSION_TYPE + ";)Z"), refs.toString());
        assertTrue(refs.contains(NETWORK_CALLS + ".addEntity(Lnet/minecraft/world/entity/Entity;I)L" + ADD_ENTITY + ";"), refs.toString());
        assertTrue(refs.contains(LEVEL_CALLS + ".spawnData(Lnet/minecraft/nbt/CompoundTag;Ljava/util/Optional;)L" + SPAWN_DATA + ";"), refs.toString());
        for (String helper : List.of(REGISTRY_TAGS, LEVEL_CALLS, ITEM_CALLS, SERVER_CALLS, CHAT_CALLS, NETWORK_CALLS, BLOCK_SHADES)) {
            assertTrue(transformer.getSyntheticClasses().containsKey(helper), helper + " must ship with the mod");
        }
    }

    @Test
    @DisplayName("on 26.1 the template getter keeps its name, the container stream takes the 26.1 name and the icon helper reads getIcon")
    void hostShapesOf261() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        Map<String, ClassNode> host = host263();
        host.put(SERVER_LEVEL, withMethods(new String[][]{{"getStructureManager", TEMPLATE_MANAGER}}));
        host.put(CONTAINER, withMethods(new String[][]{{"allItemsCopyStream", STREAM}, {"nonEmptyItemCopyStream", STREAM}}));
        host.put(BUNDLE, withMethods(new String[][]{{"itemCopyStream", STREAM}}));
        host.put(DISPLAY_INFO, withMethods(new String[][]{{"getIcon", TEMPLATE_ICON}}));
        LegacyGameplayCallsBridge.register(transformer, host::get);

        List<String> refs = refs(transformer.transformClass(oldCalls(), "test/gameplay/Old"));

        assertTrue(refs.contains(SERVER_LEVEL + ".getStructureManager" + TEMPLATE_MANAGER), refs.toString());
        assertTrue(refs.contains(CONTAINER + ".allItemsCopyStream" + STREAM), refs.toString());
        assertTrue(refs.contains(BUNDLE + ".itemCopyStream" + STREAM), "26.1 still has it: " + refs);
        List<String> helperRefs = refs(transformer.getSyntheticClasses().get(ITEM_CALLS));
        assertTrue(helperRefs.contains(DISPLAY_INFO + ".getIcon" + TEMPLATE_ICON), helperRefs.toString());
        assertFalse(helperRefs.contains(DISPLAY_INFO + ".icon" + TEMPLATE_ICON), helperRefs.toString());
    }

    @Test
    @DisplayName("a member the host still has is left alone, as NeoForge 26.3 keeps CustomData.contains")
    void hostMembersAreKept() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        Map<String, ClassNode> host = host263();
        host.put(CUSTOM_DATA, withMethods(new String[][]{{"contains", "(Ljava/lang/String;)Z"}}));
        LegacyGameplayCallsBridge.register(transformer, host::get);

        List<String> refs = refs(transformer.transformClass(oldCalls(), "test/gameplay/Old"));

        assertTrue(refs.contains(CUSTOM_DATA + ".contains(Ljava/lang/String;)Z"), refs.toString());
    }

    @Test
    @DisplayName("a mod built for a version that no longer had the member keeps its call")
    void limitedToOlderMods() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyGameplayCallsBridge.register(transformer, host263()::get);
        transformer.setModSourceVersion("1.21.3");

        List<String> refs = refs(transformer.transformClass(oldCalls(), "test/gameplay/Newer"));

        assertTrue(refs.contains(DEFAULTED_REGISTRY + ".getOrCreateTag" + TAG), "removed in 1.21.2: " + refs);
        assertTrue(refs.contains(LEVEL_CALLS + ".addParticle(L" + LEVEL + ";" + PARTICLE.substring(1)), "removed in 1.21.4: " + refs);
    }

    @Test
    @DisplayName("an older target registers nothing, since the helpers are built on 26.x shapes")
    void olderTargetsRegisterNothing() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        LegacyGameplayCallsBridge.register(transformer, host263()::get);

        List<String> refs = refs(transformer.transformClass(oldCalls(), "test/gameplay/Old"));

        for (Call call : OLD_CALLS) {
            assertTrue(refs.contains(call.owner() + "." + call.name() + call.desc()), call + " in " + refs);
        }
    }

    /** The 26.3 host shapes: every old member is gone, and the renamed getters and streams carry their new names. */
    private static Map<String, ClassNode> host263() {
        Map<String, ClassNode> host = new HashMap<>();
        for (Call call : OLD_CALLS) host.putIfAbsent(call.owner(), new ClassNode());
        host.put(SERVER_LEVEL, withMethods(new String[][]{{"getStructureTemplateManager", TEMPLATE_MANAGER}}));
        host.put(SERVER, withMethods(new String[][]{{"getStructureTemplateManager", TEMPLATE_MANAGER}}));
        host.put(CONTAINER, withMethods(new String[][]{{"itemCopies", STREAM}, {"nonEmptyItemCopyStream", STREAM}}));
        host.put(BUNDLE, withMethods(new String[][]{{"itemCopies", STREAM}}));
        host.put(DISPLAY_INFO, withMethods(new String[][]{{"icon", TEMPLATE_ICON}}));
        host.put(GENERATION_HOLDER, withMethods(new String[][]{{"getLatestStatus", STATUS}}));
        host.put(STORAGE_ACCESS, withMethods(new String[][]{{"getUnfixedDataTag", "(Z)Lcom/mojang/serialization/Dynamic;"}}));
        return host;
    }

    /** A class whose one method makes every old call with default arguments and drops each result. */
    private static byte[] oldCalls() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/gameplay/Old", null, "java/lang/Object", null);
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

    private static List<String> refs(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
            }
        }
        return refs;
    }
}
