/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.forge.Forge_1_21_1_to_1_21_2;
import com.retromod.shim.neoforge.NeoForge_1_21_1_to_1_21_2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A 1.20.1 armor material reading its equip sound, on a host where that constant is a holder. */
class LegacyHolderConstantBridgeTest {

    private static final String SOUNDS = "net/minecraft/sounds/SoundEvents";
    private static final String SOUND = "Lnet/minecraft/sounds/SoundEvent;";
    private static final String REFERENCE = "Lnet/minecraft/core/Holder$Reference;";

    private RetromodTransformer transformer;
    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = previousHost;
    }

    @Test
    @DisplayName("Only the host's holder fields are bridged")
    void holderFieldsComeFromTheHost() {
        ClassNode host = new ClassNode();
        host.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "ARMOR_EQUIP_IRON", REFERENCE, null, null));
        host.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "ITEM_PICKUP", SOUND, null, null));
        assertEquals(Map.of("ARMOR_EQUIP_IRON", REFERENCE), LegacyHolderConstantBridge.holderFields(host),
                "a constant that is still a bare sound event needs no bridge");
    }

    @Test
    @DisplayName("An old bare read unwraps the holder, and a holder read is left alone")
    void oldReadUnwrapsTheHolder() {
        LegacyHolderConstantBridge.registerRedirects(transformer,
                Map.of(SOUNDS, Map.of("ARMOR_EQUIP_IRON", REFERENCE)));
        assertTrue(readsThroughGetter(armorMaterials("ARMOR_EQUIP_IRON")),
                "the SoundEvent read must go through the unwrapping getter");
    }

    @Test
    @DisplayName("26.3's tool sound holders are bridged from the 26.3 shim, offline too")
    void toolSoundsFrom26Point3() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        Mc26_2To26_3CoreMoves.register(transformer);
        assertFalse(readsThroughGetter(armorMaterials("HOE_TILL")), "26.2 keeps HOE_TILL a sound event");

        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        Mc26_2To26_3CoreMoves.register(transformer);
        for (String sound : LegacyHolderConstantBridge.TOOL_SOUNDS_HELD_FROM_26_3) {
            assertTrue(readsThroughGetter(armorMaterials(sound)), sound + " must unwrap its 26.3 holder");
        }
    }

    @Test
    @DisplayName("The Forge and NeoForge 1.21.2 shims bridge the eat sound a 1.21.1 mod reads")
    void forgeFamily1212ShimsBridgeTheEatSound() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "1.21.4";
        Path host = indexedHost();
        for (VersionShim shim : List.of(new Forge_1_21_1_to_1_21_2(), new NeoForge_1_21_1_to_1_21_2())) {
            RetromodTransformer fresh = freshTransformer();
            fresh.initFuzzyResolver(host);
            shim.registerRedirects(fresh);
            assertTrue(readsThroughGetter(fresh, armorMaterials("GENERIC_EAT")),
                    shim.getShimName() + " must unwrap GENERIC_EAT, a holder since 1.21.2");
            assertFalse(readsThroughGetter(fresh, armorMaterials("ITEM_PICKUP")),
                    shim.getShimName() + " must leave a bare sound event alone");
        }
    }

    @Test
    @DisplayName("Offline, with no readable host class, the holder fields come from the indexed jar")
    void indexedJarStandsInForTheHost() throws Exception {
        RetromodTransformer fresh = freshTransformer();
        fresh.initFuzzyResolver(indexedHost());
        assertEquals(Map.of("GENERIC_EAT", REFERENCE),
                LegacyHolderConstantBridge.indexedHolderFields(fresh.getFuzzyResolver(), SOUNDS));
        assertEquals(Map.of("WATER", "Lnet/minecraft/core/Holder;"),
                LegacyHolderConstantBridge.indexedHolderFields(fresh.getFuzzyResolver(), POTIONS));
        assertNull(LegacyHolderConstantBridge.indexedHolderFields(null, SOUNDS),
                "with no index nothing is known, so the offline 26.3 list decides");
    }

    private static final String POTIONS = "net/minecraft/world/item/alchemy/Potions";

    @TempDir
    Path dir;

    /** A target jar whose eat sound and water potion are holders and whose pickup sound is not. */
    private Path indexedHost() throws Exception {
        Path jar = dir.resolve("host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            writeConstants(out, SOUNDS, Map.of("GENERIC_EAT", REFERENCE, "ITEM_PICKUP", SOUND));
            writeConstants(out, POTIONS, Map.of("WATER", "Lnet/minecraft/core/Holder;"));
        }
        return jar;
    }

    private static void writeConstants(JarOutputStream out, String owner, Map<String, String> fields)
            throws Exception {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, owner, null, "java/lang/Object", null);
        fields.forEach((name, desc) ->
                cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, name, desc, null, null).visitEnd());
        cw.visitEnd();
        out.putNextEntry(new JarEntry(owner + ".class"));
        out.write(cw.toByteArray());
        out.closeEntry();
    }

    private static RetromodTransformer freshTransformer() throws Exception {
        Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private boolean readsThroughGetter(byte[] classBytes) {
        return readsThroughGetter(transformer, classBytes);
    }

    private static boolean readsThroughGetter(RetromodTransformer transformer, byte[] classBytes) {
        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(classBytes, "test/ModArmorMaterials")).accept(out, 0);
        List<AbstractInsnNode> insns = new ArrayList<>();
        out.methods.forEach(m -> m.instructions.forEach(insns::add));
        return insns.stream().anyMatch(i -> i instanceof MethodInsnNode call
                && call.owner.equals(LegacyHolderConstantBridge.HELPER) && call.desc.equals("()" + SOUND));
    }

    /** A class that reads {@code SoundEvents.<field>} as a bare sound event. */
    private static byte[] armorMaterials(String field) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/ModArmorMaterials", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "sounds", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, SOUNDS, field, SOUND);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("A holder read is left alone")
    void holderReadStaysAFieldRead() {
        LegacyHolderConstantBridge.registerRedirects(transformer,
                Map.of(SOUNDS, Map.of("ARMOR_EQUIP_IRON", REFERENCE)));
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/ModArmorMaterials", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "sounds", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, SOUNDS, "ARMOR_EQUIP_IRON", SOUND);
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, SOUNDS, "ARMOR_EQUIP_IRON", REFERENCE);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/ModArmorMaterials")).accept(out, 0);
        List<AbstractInsnNode> insns = new ArrayList<>();
        out.methods.forEach(m -> m.instructions.forEach(insns::add));
        assertTrue(insns.stream().anyMatch(i -> i instanceof FieldInsnNode field
                        && field.owner.equals(SOUNDS) && field.desc.equals(REFERENCE)),
                "a 1.20.5+ mod's holder read must stay a field read");
    }
}
