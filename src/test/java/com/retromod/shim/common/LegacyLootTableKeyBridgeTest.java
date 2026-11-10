/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyLootTableKeyBridge.Names;
import com.retromod.shim.common.LegacyLootTableKeyBridge.Shape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * 1.20.5 turned loot table ids into registry keys. Sophisticated Backpacks 1.20.1 builds its entity loot
 * mapping from {@code BuiltInLootTables} constants and died with {@code NoSuchFieldError} on Forge 26.2.
 */
class LegacyLootTableKeyBridgeTest {

    private static final String BUILT_IN = "net/minecraft/world/level/storage/loot/BuiltInLootTables";
    private static final String RL = "net/minecraft/resources/ResourceLocation";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String KEY = "net/minecraft/resources/ResourceKey";
    private static final String HELPER = LegacyLootTableKeyBridge.HELPER;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an old constant read on 1.21.1 reads the key and hands back its ResourceLocation")
    void constantReadsTheKeyOn1211() {
        List<AbstractInsnNode> code = transform("1.21.1", "1.20.1",
                mv -> mv.visitFieldInsn(GETSTATIC, BUILT_IN, "DESERT_PYRAMID", "L" + RL + ";"), "L" + RL + ";");
        FieldInsnNode read = field(code, "DESERT_PYRAMID");
        assertEquals("L" + KEY + ";", read.desc, "the host field is a key since 1.20.5");
        MethodInsnNode convert = call(code, HELPER, "lootTableId");
        assertEquals("(L" + KEY + ";)L" + RL + ";", convert.desc, "the code after the read still sees a ResourceLocation");
    }

    @Test
    @DisplayName("on 26.3 the same read hands back an Identifier, which ResourceLocation became")
    void constantReadsTheKeyOn263() {
        List<AbstractInsnNode> code = transform("26.3", "1.20.1",
                mv -> mv.visitFieldInsn(GETSTATIC, BUILT_IN, "DESERT_PYRAMID", "L" + IDENTIFIER + ";"), "L" + IDENTIFIER + ";");
        assertEquals("L" + KEY + ";", field(code, "DESERT_PYRAMID").desc);
        assertEquals("(L" + KEY + ";)L" + IDENTIFIER + ";", call(code, HELPER, "lootTableId").desc);
    }

    @Test
    @DisplayName("EMPTY, which 1.21.2 removed, is answered by the helper instead of a field read")
    void emptyIsAnsweredWhereTheHostRemovedIt() {
        List<AbstractInsnNode> code = transform("26.3", "1.20.1",
                mv -> mv.visitFieldInsn(GETSTATIC, BUILT_IN, "EMPTY", "L" + IDENTIFIER + ";"), "L" + IDENTIFIER + ";");
        assertTrue(code.stream().noneMatch(insn -> insn instanceof FieldInsnNode f && f.owner.equals(BUILT_IN)),
                "26.3 has no BuiltInLootTables.EMPTY to read");
        assertEquals("()L" + IDENTIFIER + ";", call(code, HELPER, "emptyLootTableId").desc);
    }

    @Test
    @DisplayName("EMPTY on 1.21.1 still reads the host constant through the key")
    void emptyReadsTheHostConstantOn1211() {
        List<AbstractInsnNode> code = transform("1.21.1", "1.20.1",
                mv -> mv.visitFieldInsn(GETSTATIC, BUILT_IN, "EMPTY", "L" + RL + ";"), "L" + RL + ";");
        assertEquals("L" + KEY + ";", field(code, "EMPTY").desc);
        call(code, HELPER, "lootTableId");
    }

    @Test
    @DisplayName("a 1.21.1 mod's key read and all() call stay as written")
    void newerModIsUntouched() {
        List<AbstractInsnNode> read = transform("26.3", "1.21.1",
                mv -> mv.visitFieldInsn(GETSTATIC, BUILT_IN, "DESERT_PYRAMID", "L" + KEY + ";"), "L" + KEY + ";");
        assertTrue(read.stream().noneMatch(insn -> insn instanceof MethodInsnNode), "a key read needs no conversion");
        List<AbstractInsnNode> all = transform("26.3", "1.21.1",
                mv -> mv.visitMethodInsn(INVOKESTATIC, BUILT_IN, "all", "()Ljava/util/Set;", false), "Ljava/util/Set;");
        assertEquals(BUILT_IN, call(all, BUILT_IN, "all").owner, "a 1.21.1 mod expects the key set");
    }

    @Test
    @DisplayName("all() from a 1.20.1 mod returns the helper's id set")
    void allReturnsIdsForAnOldMod() {
        List<AbstractInsnNode> code = transform("26.3", "1.20.1",
                mv -> mv.visitMethodInsn(INVOKESTATIC, BUILT_IN, "all", "()Ljava/util/Set;", false), "Ljava/util/Set;");
        assertEquals("()Ljava/util/Set;", call(code, HELPER, "allLootTableIds").desc);
    }

    @Test
    @DisplayName("Block.getLootTable() and EntityType.getDefaultLootTable() keep returning an id")
    void blockAndEntityGettersReturnIds() {
        String block = "net/minecraft/world/level/block/Block";
        String type = "net/minecraft/world/entity/EntityType";
        List<AbstractInsnNode> blockCode = transform("26.3", "1.20.1", mv -> {
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKEVIRTUAL, block, "getLootTable", "()L" + IDENTIFIER + ";", false);
        }, "L" + IDENTIFIER + ";");
        assertEquals("(Lnet/minecraft/world/level/block/state/BlockBehaviour;)L" + IDENTIFIER + ";",
                call(blockCode, HELPER, "blockLootTable").desc);
        List<AbstractInsnNode> typeCode = transform("1.21.1", "1.20.1", mv -> {
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKEVIRTUAL, type, "getDefaultLootTable", "()L" + RL + ";", false);
        }, "L" + RL + ";");
        assertEquals("(L" + type + ";)L" + RL + ";", call(typeCode, HELPER, "entityLootTable").desc);
    }

    @Test
    @DisplayName("suspicious sand, chest minecart and container vehicle setters take the id's key")
    void settersTakeTheKey() {
        String brushable = "net/minecraft/world/level/block/entity/BrushableBlockEntity";
        String cart = "net/minecraft/world/entity/vehicle/minecart/MinecartChest";
        String container = "net/minecraft/world/entity/vehicle/ContainerEntity";
        String id = "L" + IDENTIFIER + ";";
        assertEquals("(L" + brushable + ";" + id + "J)V", call(transform("26.3", "1.20.1",
                mv -> seededSet(mv, brushable, id), "V"), HELPER, "setBrushableLootTable").desc);
        assertEquals("(Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecartContainer;" + id + "J)V",
                call(transform("26.3", "1.20.1", mv -> seededSet(mv, cart, id), "V"), HELPER, "setMinecartLootTable").desc);
        List<AbstractInsnNode> get = transform("26.3", "1.20.1", mv -> {
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKEINTERFACE, container, "getLootTable", "()" + id, true);
        }, id);
        MethodInsnNode containerGet = call(get, HELPER, "containerLootTable");
        assertEquals(INVOKESTATIC, containerGet.getOpcode());
        assertFalse(containerGet.itf, "the helper is a class");
    }

    @Test
    @DisplayName("LootContext.getResolver().getLootTable(id) looks the table up in the context's registries")
    void resolverLookupGoesToTheHelper() {
        String context = "net/minecraft/world/level/storage/loot/LootContext";
        String resolver = "net/minecraft/world/level/storage/loot/LootDataResolver";
        String provider = "net/minecraft/core/HolderGetter$Provider";
        String table = "Lnet/minecraft/world/level/storage/loot/LootTable;";
        List<AbstractInsnNode> code = transform("26.2", "1.20.1", mv -> {
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKEVIRTUAL, context, "getResolver", "()L" + resolver + ";", false);
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKEINTERFACE, resolver, "getLootTable", "(L" + IDENTIFIER + ";)" + table, true);
        }, table);
        assertEquals("()L" + provider + ";", call(code, context, "getResolver").desc,
                "getResolver returns a HolderGetter.Provider since 1.20.5");
        assertEquals("(L" + provider + ";L" + IDENTIFIER + ";)" + table, call(code, HELPER, "resolveLootTable").desc);
    }

    @Test
    @DisplayName("LootTableReference.lootTableReference(id) builds a nested entry for the key until 26.3")
    void nestedTableReferenceTakesTheKey() {
        String reference = "net/minecraft/world/level/storage/loot/entries/LootTableReference";
        String builder = "Lnet/minecraft/world/level/storage/loot/entries/LootPoolSingletonContainer$Builder;";
        Consumer<MethodVisitor> body = mv -> {
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKESTATIC, reference, "lootTableReference", "(L" + IDENTIFIER + ";)" + builder, false);
        };
        assertEquals("(L" + IDENTIFIER + ";)" + builder, call(transform("26.2", "1.20.1", body, builder),
                HELPER, "lootTableReference").desc);
        // 26.3 takes a holder, which only a method with the registries in hand can look up.
        assertEquals(reference, call(transform("26.3", "1.20.1", body, builder), reference, "lootTableReference").owner);
    }

    @Test
    @DisplayName("a host before 1.20.5 gets no bridge, offline or running")
    void olderHostGetsNothing() {
        assertNull(LegacyLootTableKeyBridge.offlineShape("1.20.4"));
        RetromodVersion.TARGET_MC_VERSION = "1.20.4";
        transformer.clearRedirectsForTesting();
        LegacyLootTableKeyBridge.register(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(HELPER), "1.20.4 still has id-typed loot tables");
    }

    @Test
    @DisplayName("the offline shape follows the host: key accessor, id class, optional tables and EMPTY")
    void offlineShapeFollowsTheHost() {
        Shape v1211 = LegacyLootTableKeyBridge.offlineShape("1.21.1");
        assertEquals("location", v1211.names().keyId());
        assertEquals(RL, v1211.names().id());
        assertFalse(v1211.optionalTables());
        assertTrue(v1211.hostHasEmpty());
        assertEquals("getLootTable", v1211.names().hostContainerGet());
        Shape v263 = LegacyLootTableKeyBridge.offlineShape("26.3");
        assertEquals("identifier", v263.names().keyId());
        assertEquals(IDENTIFIER, v263.names().id());
        assertTrue(v263.optionalTables());
        assertFalse(v263.hostHasEmpty());
        assertEquals("getContainerLootTable", v263.names().hostContainerGet());
        assertEquals("net/minecraft/world/entity/vehicle/minecart/AbstractMinecartContainer", v263.names().minecart());
    }

    @Test
    @DisplayName("the helper reads each key's id with the host's accessor and the host's getter return types")
    void helperUsesHostShapes() {
        MethodNode v1211 = helperMethod(LegacyLootTableKeyBridge.offlineShape("1.21.1"), "blockLootTable");
        assertEquals("()L" + KEY + ";", findCall(v1211.instructions, "getLootTable").desc);
        MethodNode v263 = helperMethod(LegacyLootTableKeyBridge.offlineShape("26.3"), "blockLootTable");
        assertEquals("()Ljava/util/Optional;", findCall(v263.instructions, "getLootTable").desc);
        assertEquals("identifier", findCall(helperMethod(LegacyLootTableKeyBridge.offlineShape("26.3"),
                "lootTableId").instructions, "identifier").name);
        assertEquals("method_29177", findCall(helperMethod(new Shape(Names.INTERMEDIARY, false, true, true),
                "lootTableId").instructions, "method_29177").name);
    }

    @Test
    @DisplayName("an intermediary Fabric mod on a 1.21.1 Fabric host reads class_39 constants through the key")
    void intermediaryConstantRead() {
        transformer.clearRedirectsForTesting();
        LegacyLootTableKeyBridge.registerUnchecked(transformer, new Shape(Names.INTERMEDIARY, false, true, true));
        transformer.setModSourceVersion("1.20.1");
        String id = "Lnet/minecraft/class_2960;";
        List<AbstractInsnNode> code = transformed(mv -> mv.visitFieldInsn(GETSTATIC, "net/minecraft/class_39", "field_885", id), id);
        assertEquals("Lnet/minecraft/class_5321;", field(code, "field_885").desc);
        assertEquals("(Lnet/minecraft/class_5321;)" + id, call(code, HELPER, "lootTableId").desc);
        // class_39 names one class, so class_3901's identifier constant is another class's field and stays.
        List<AbstractInsnNode> other = transformed(mv -> mv.visitFieldInsn(GETSTATIC, "net/minecraft/class_3901", "field_1", id), id);
        assertEquals(id, field(other, "field_1").desc);
        assertTrue(other.stream().noneMatch(insn -> insn instanceof MethodInsnNode), "no conversion for another class");
    }

    // ---- fixtures ----

    private List<AbstractInsnNode> transform(String host, String modVersion, Consumer<MethodVisitor> body, String returns) {
        transformer.clearRedirectsForTesting();
        LegacyLootTableKeyBridge.registerUnchecked(transformer, LegacyLootTableKeyBridge.offlineShape(host));
        transformer.setModSourceVersion(modVersion);
        return transformed(body, returns);
    }

    private List<AbstractInsnNode> transformed(Consumer<MethodVisitor> body, String returns) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/mod/LootUser", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()" + returns, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(returns.equals("V") ? RETURN : ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/mod/LootUser")).accept(out, 0);
        List<AbstractInsnNode> code = new ArrayList<>();
        out.methods.stream().filter(m -> m.name.equals("run")).findFirst().orElseThrow().instructions.forEach(code::add);
        return code;
    }

    private static void seededSet(MethodVisitor mv, String owner, String id) {
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(LCONST_0);
        mv.visitMethodInsn(INVOKEVIRTUAL, owner, "setLootTable", "(" + id + "J)V", false);
    }

    private static FieldInsnNode field(List<AbstractInsnNode> code, String name) {
        return code.stream().filter(insn -> insn instanceof FieldInsnNode f && f.name.equals(name))
                .map(FieldInsnNode.class::cast).findFirst()
                .orElseThrow(() -> new AssertionError("no read of " + name + " in " + describe(code)));
    }

    private static MethodInsnNode call(List<AbstractInsnNode> code, String owner, String name) {
        return code.stream().filter(insn -> insn instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name))
                .map(MethodInsnNode.class::cast).findFirst()
                .orElseThrow(() -> new AssertionError("no call to " + owner + "." + name + " in " + describe(code)));
    }

    private static String describe(List<AbstractInsnNode> code) {
        List<String> parts = new ArrayList<>();
        for (AbstractInsnNode insn : code) {
            if (insn instanceof MethodInsnNode c) parts.add(c.owner + "." + c.name + c.desc);
            if (insn instanceof FieldInsnNode f) parts.add(f.owner + "." + f.name + ":" + f.desc);
        }
        return parts.toString();
    }

    private static MethodNode helperMethod(Shape shape, String name) {
        ClassNode helper = new ClassNode();
        new ClassReader(LegacyLootTableKeyBridge.generate(shape)).accept(helper, 0);
        return helper.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static MethodInsnNode findCall(org.objectweb.asm.tree.InsnList code, String name) {
        for (AbstractInsnNode insn : code) {
            if (insn instanceof MethodInsnNode c && c.name.equals(name)) return c;
        }
        return fail("no call to " + name);
    }
}
