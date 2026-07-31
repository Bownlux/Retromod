/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;

/**
 * 1.12.2 (MCP) -> 26.1 (Mojang) class-move baseline (#103/#108). The dominant gap for old Forge
 * mods is class moves (308 of 344 issues on Metallurgy 4); this verifies the bundled table is
 * loaded by the shim and remaps the core content classes. Targets were validated against the 26.1
 * jar at harvest time; here we confirm a 1.12.2-named class comes out with 26.1 names.
 *
 * <p>This is the class-move layer only. Constructor-signature changes (1.12.2 {@code Block(Material)}
 * -> {@code Block(Properties)}), the 1.12.2 registry/event idiom, and the redesigned systems
 * (entity AI, rendering, networking) are separate layers; complex mods need those too.
 */
class Forge1122ClassMovesTest {

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.1";
    }

    @Test
    @DisplayName("1.12.2 core classes remap to their 26.1 names on a 26.1 host")
    void mapsCoreClassesTo26_1() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new Forge_1_12_2_to_1_13_2().registerRedirects(t);

        byte[] out = t.transformClass(fixture(), "test/Old1122");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        Set<String> descs = new HashSet<>();
        for (FieldNode f : cn.fields) descs.add(f.desc);

        assertTrue(descs.contains("Lnet/minecraft/core/BlockPos;"), "BlockPos -> core: " + descs);
        assertTrue(descs.contains("Lnet/minecraft/world/level/Level;"), "World -> Level: " + descs);
        assertTrue(descs.contains("Lnet/minecraft/world/item/ItemStack;"), "ItemStack -> world/item: " + descs);
        assertTrue(descs.contains("Lnet/minecraft/world/level/block/Block;"), "Block -> world/level/block: " + descs);
        assertTrue(descs.contains("Lnet/minecraft/resources/Identifier;"), "ResourceLocation -> Identifier: " + descs);
        assertTrue(descs.contains("Lnet/minecraft/client/resources/sounds/SoundInstance;"),
                "ISound -> SoundInstance (#113 The Betweenlands): " + descs);

        for (String d : descs) {
            assertFalse(d.contains("util/math/BlockPos"), "leftover 1.12.2 BlockPos: " + d);
            assertFalse(d.equals("Lnet/minecraft/world/World;"), "leftover 1.12.2 World: " + d);
            assertFalse(d.equals("Lnet/minecraft/item/ItemStack;"), "leftover 1.12.2 ItemStack: " + d);
        }
    }

    @Test
    @DisplayName("on a 1.20.1 host the 1.20.x-target table applies (NOT the 26.1 names)")
    void mapsCoreClassesTo1_20_1() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new Forge_1_12_2_to_1_13_2().registerRedirects(t);
        byte[] out = t.transformClass(fixture(), "test/Old1122");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        Set<String> descs = new HashSet<>();
        for (FieldNode f : cn.fields) descs.add(f.desc);

        assertTrue(descs.contains("Lnet/minecraft/core/BlockPos;"), "BlockPos -> core: " + descs);
        assertTrue(descs.contains("Lnet/minecraft/world/level/Level;"), "World -> Level: " + descs);
        // 1.20.1 kept ResourceLocation; the 26.1-only Identifier name must not appear
        assertTrue(descs.contains("Lnet/minecraft/resources/ResourceLocation;"),
                "ResourceLocation -> resources (1.20.1 name): " + descs);
        assertFalse(descs.stream().anyMatch(d -> d.contains("resources/Identifier")),
                "26.1-only Identifier must not be applied on a 1.20.1 host: " + descs);
        // the whole point of the 1.20.x table: families 26.1 removed but 1.20.x still has
        assertTrue(descs.contains("Lnet/minecraft/world/item/SwordItem;"),
                "ItemSword -> SwordItem (26.1-removed, 1.20.x-present): " + descs);
    }

    @Test
    @DisplayName("no class-move table on a 1.21.x host (none validated against those jars)")
    void noTableOn1_21() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new Forge_1_12_2_to_1_13_2().registerRedirects(t);
        byte[] out = t.transformClass(fixture(), "test/Old1122");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        for (FieldNode f : cn.fields) {
            assertFalse(f.desc.contains("resources/Identifier"),
                    "26.1 table must not apply on 1.21.x: " + f.desc);
            assertFalse(f.desc.contains("world/item/SwordItem"),
                    "1.20.x table must not apply on 1.21.x: " + f.desc);
        }
    }

    @Test
    @DisplayName("the ctor bridge applies on a 1.20.1 host too (Material gone since 1.20)")
    void blockCtorBridgeOn1_20_1() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new Forge_1_12_2_to_1_13_2().registerRedirects(t);

        byte[] out = t.transformClass(customBlock(), "test/MyBlock");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        MethodNode init = cn.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
        boolean superTakesProps = Arrays.stream(init.instructions.toArray())
                .filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i)
                .anyMatch(mi -> mi.name.equals("<init>")
                        && mi.desc.equals("(Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;)V"));
        assertTrue(superTakesProps,
                "super(Material) must become super(BlockBehaviour.Properties) on 1.20.1 "
                + "(Properties.of() verified present in Mojang's 1.20.1 mappings)");
        boolean refsMaterial = Arrays.stream(init.instructions.toArray())
                .filter(i -> i instanceof FieldInsnNode).map(i -> (FieldInsnNode) i)
                .anyMatch(fi -> fi.owner.contains("block/material/Material"));
        assertFalse(refsMaterial, "Material is gone on 1.20 too; its GETSTATIC must be nulled");
    }

    @Test
    @DisplayName("1.12.2 custom item: super() -> super(new Item.Properties()) on 26.1 (#80)")
    void itemCtorBridge() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new Forge_1_12_2_to_1_13_2().registerRedirects(t);

        byte[] out = t.transformClass(customItem(), "test/MyItem");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);

        assertEquals("net/minecraft/world/item/Item", cn.superName, "superclass rebased to world/item/Item");
        MethodNode init = cn.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();

        // the super(...) call now takes an Item.Properties (was no-arg)
        boolean superTakesProps = Arrays.stream(init.instructions.toArray())
                .filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i)
                .anyMatch(mi -> mi.name.equals("<init>")
                        && mi.owner.equals("net/minecraft/world/item/Item")
                        && mi.desc.equals("(Lnet/minecraft/world/item/Item$Properties;)V"));
        assertTrue(superTakesProps, "super() must become super(Item.Properties)");

        // a real default Item.Properties is constructed (not null, which would NPE in the ctor)
        boolean constructsProps = Arrays.stream(init.instructions.toArray())
                .anyMatch(i -> i instanceof TypeInsnNode tn && tn.getOpcode() == Opcodes.NEW
                        && tn.desc.equals("net/minecraft/world/item/Item$Properties"));
        assertTrue(constructsProps, "must construct a default Item.Properties");

        // and no leftover no-arg super Item.<init>() (exact owner, so the Item$Properties.<init>()V
        // emitted to build the default Properties is not mistaken for a leftover super call)
        boolean leftoverNoArg = Arrays.stream(init.instructions.toArray())
                .filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i)
                .anyMatch(mi -> mi.name.equals("<init>") && mi.desc.equals("()V")
                        && mi.owner.equals("net/minecraft/world/item/Item"));
        assertFalse(leftoverNoArg, "no leftover no-arg Item.<init>()");
    }

    @Test
    @DisplayName("1.12.2 custom block: super(Material.IRON) -> super(Properties.of()) on 26.1 (#80)")
    void blockCtorBridge() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new Forge_1_12_2_to_1_13_2().registerRedirects(t);

        byte[] out = t.transformClass(customBlock(), "test/MyBlock");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);

        assertEquals("net/minecraft/world/level/block/Block", cn.superName, "superclass rebased");
        MethodNode init = cn.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();

        // the super(...) call now takes a BlockBehaviour.Properties (was Material)
        boolean superTakesProps = Arrays.stream(init.instructions.toArray())
                .filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i)
                .anyMatch(mi -> mi.name.equals("<init>")
                        && mi.owner.equals("net/minecraft/world/level/block/Block")
                        && mi.desc.equals("(Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;)V"));
        assertTrue(superTakesProps, "super(Material) must become super(BlockBehaviour.Properties)");

        // a default Properties is constructed via the static factory
        boolean callsPropsOf = Arrays.stream(init.instructions.toArray())
                .filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i)
                .anyMatch(mi -> mi.name.equals("of")
                        && mi.owner.equals("net/minecraft/world/level/block/state/BlockBehaviour$Properties"));
        assertTrue(callsPropsOf, "must construct default via BlockBehaviour.Properties.of()");

        // the removed Material class is no longer referenced (its GETSTATIC was nulled)
        boolean refsMaterial = Arrays.stream(init.instructions.toArray())
                .filter(i -> i instanceof FieldInsnNode).map(i -> (FieldInsnNode) i)
                .anyMatch(fi -> fi.owner.contains("block/material/Material"));
        assertFalse(refsMaterial, "no leftover GETSTATIC of removed Material");

        // and no leftover super Block.<init>(Material)
        boolean leftoverMaterialCtor = Arrays.stream(init.instructions.toArray())
                .filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i)
                .anyMatch(mi -> mi.name.equals("<init>") && mi.desc.contains("block/material/Material"));
        assertFalse(leftoverMaterialCtor, "no leftover Block.<init>(Material)");
    }

    /** A 1.12.2 custom block: {@code extends net/minecraft/block/Block}, ctor does super(Material.IRON). */
    private static byte[] customBlock() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/MyBlock", null, "net/minecraft/block/Block", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/block/material/Material", "IRON",
                "Lnet/minecraft/block/material/Material;");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/block/Block", "<init>",
                "(Lnet/minecraft/block/material/Material;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A 1.12.2 custom item: {@code extends net/minecraft/item/Item}, ctor does a no-arg super(). */
    private static byte[] customItem() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/MyItem", null, "net/minecraft/item/Item", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/item/Item", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class with fields typed by 1.12.2 (MCP) class names. */
    private static byte[] fixture() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Old1122", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "pos", "Lnet/minecraft/util/math/BlockPos;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "world", "Lnet/minecraft/world/World;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "stack", "Lnet/minecraft/item/ItemStack;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "block", "Lnet/minecraft/block/Block;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "id", "Lnet/minecraft/util/ResourceLocation;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "sound", "Lnet/minecraft/client/audio/ISound;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "sword", "Lnet/minecraft/item/ItemSword;", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
