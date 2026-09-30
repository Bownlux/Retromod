/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Each removed item base is rebased on exactly the hosts that lost it.
 *
 * <p>The bridge used to be registered from 26.1 only, so on every NeoForge and Forge host from 1.21
 * through 1.21.11 a mod extending one still failed. Report #260: a 1.20.1 mod's music disc died on
 * 1.21.1 with {@code NoClassDefFoundError: RecordItem}. Removal versions come from Mojang's client
 * mappings: {@code RecordItem} went at 1.21, {@code EnchantedBookItem} at 1.21.2, {@code SwordItem}
 * at 1.21.5.
 */
class RemovedItemBaseVersionGateTest {

    private static final String GENERATED = "com/retromod/generated/Legacy";

    private RetromodTransformer transformer;
    private String previousTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
        McReflect.setForceNeoForge(true);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
        McReflect.setForceNeoForge(false);
    }

    @ParameterizedTest(name = "{0} on a {1} host: rebased = {2}")
    @DisplayName("a base is rebased on the host that removed it and left alone before that")
    @CsvSource({
        "RecordItem,        1.20.6, false",
        "RecordItem,        1.21,   true",
        "RecordItem,        1.21.1, true",
        "EnchantedBookItem, 1.21.1, false",
        "EnchantedBookItem, 1.21.2, true",
        "SwordItem,         1.21.4, false",
        "SwordItem,         1.21.5, true",
        "SwordItem,         26.1,   true",
    })
    void rebasedOnlyWhereRemoved(String base, String host, boolean expectRebase) {
        loadShimsFor(host);

        byte[] out = transformer.transformClass(subclassOf(base), "test/mod/ModItem");
        String superName = new ClassReader(out).getSuperName();

        if (expectRebase) {
            assertEquals(GENERATED + base, superName,
                    base + " is gone on " + host + ", so the mod must extend the generated base");
        } else {
            assertEquals("net/minecraft/world/item/" + base, superName,
                    base + " still exists on " + host + ", so the mod must keep extending it");
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a 1.20.1 sword keeps its constructor, instanceof and parameter types on 1.21.5")
    void swordLinksEverywhereItIsNamed() {
        loadShimsFor("1.21.5");
        String sword = "net/minecraft/world/item/SwordItem";
        String tierCtor = "(Lnet/minecraft/world/item/Tier;IFLnet/minecraft/world/item/Item$Properties;)V";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/ModSword", null, sword, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", tierCtor, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ILOAD, 2);
        init.visitVarInsn(FLOAD, 3);
        init.visitVarInsn(ALOAD, 4);
        init.visitMethodInsn(INVOKESPECIAL, sword, "<init>", tierCtor, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor check = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "isSword",
                "(Ljava/lang/Object;L" + sword + ";)Z", null, null);
        check.visitCode();
        check.visitVarInsn(ALOAD, 0);
        check.visitTypeInsn(INSTANCEOF, sword);
        check.visitInsn(IRETURN);
        check.visitMaxs(0, 0);
        check.visitEnd();
        cw.visitEnd();

        org.objectweb.asm.tree.ClassNode out = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/mod/ModSword"))
                .accept(out, 0);
        String generated = GENERATED + "SwordItem";

        assertEquals(generated, out.superName);
        for (org.objectweb.asm.tree.MethodNode m : out.methods) {
            assertFalse(m.desc.contains(sword), "a parameter type still names the removed class: "
                    + m.name + m.desc);
            for (org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions) {
                if (insn instanceof org.objectweb.asm.tree.TypeInsnNode type) {
                    assertEquals(generated, type.desc, "instanceof must name the stand-in");
                }
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.name.equals("<init>")) {
                    assertEquals(generated, call.owner, "super(...) must reach the stand-in");
                }
            }
        }
        byte[] base = transformer.getSyntheticClasses().get(generated);
        org.objectweb.asm.tree.ClassNode baseNode = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(base).accept(baseNode, 0);
        assertTrue(baseNode.methods.stream().anyMatch(m -> m.name.equals("<init>")
                        && m.desc.equals(tierCtor)),
                "the stand-in must absorb the sword's own constructor");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("AxeItem still exists on 26.2, so it is neither rebased nor redirected there")
    void axeStaysOnTwentySixTwo() {
        loadShimsFor("26.2");
        byte[] out = transformer.transformClass(subclassOf("AxeItem"), "test/mod/ModItem");
        assertEquals("net/minecraft/world/item/AxeItem", new ClassReader(out).getSuperName());
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("every base has a removal version")
    @CsvSource({"RecordItem", "EnchantedBookItem", "TieredItem", "SwordItem", "ArmorItem"})
    void everyBaseHasARemovalVersion(String base) {
        assertNotNull(RemovedItemBaseBridge.removedIn(base));
    }

    /** Register what a NeoForge host of this version would, in the in-game order. */
    private void loadShimsFor(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            String loader = shim.getModLoaderType();
            if (!("neoforge".equals(loader) || "forge".equals(loader) || "common".equals(loader))) {
                continue;
            }
            if (ShimRegistry.isAvailableOnHost(shim, host)) shim.registerRedirects(transformer);
        }
    }

    /** A mod item extending {@code base} through the {@code (Item.Properties)} constructor. */
    private static byte[] subclassOf(String base) {
        String owner = "net/minecraft/world/item/" + base;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/ModItem", null, owner, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(Lnet/minecraft/world/item/Item$Properties;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, owner, "<init>",
                "(Lnet/minecraft/world/item/Item$Properties;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
