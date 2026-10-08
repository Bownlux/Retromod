/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A Forge 1.20.1 particle type built with the removed {@code ParticleOptions.Deserializer}, as
 * Wither Storm registers its tractor beam particle, on a 1.20.5+ NeoForge host.
 */
class Pre1_20_5MojangParticleTypeBridgeTest {

    private static final String TYPE = Pre1_20_5MojangParticleTypeBridge.PARTICLE_TYPE;
    private static final String OLD_DESERIALIZER = Pre1_20_5MojangParticleTypeBridge.OLD_DESERIALIZER;
    private static final Pattern INTERMEDIARY = Pattern.compile("(class|method|field|comp)_\\d+");

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The generated particle classes name only Mojang members")
    void generatedClassesUseMojangNames() {
        Pre1_20_5MojangParticleTypeBridge.registerRedirects(transformer, true);
        for (String name : List.of(Pre1_20_5ParticleTypeBridge.SUPPORT, Pre1_20_5ParticleTypeBridge.DESERIALIZER,
                Pre1_20_5ParticleTypeBridge.DESERIALIZER_VIEW, Pre1_20_5ParticleTypeBridge.LEGACY_TYPE,
                Pre1_20_5ParticleTypeBridge.LEGACY_SIMPLE_TYPE)) {
            byte[] bytes = transformer.getSyntheticClasses().get(name);
            assertNotNull(bytes, name + " must be registered");
            assertFalse(INTERMEDIARY.matcher(new String(bytes, StandardCharsets.ISO_8859_1)).find(),
                    name + " still names an intermediary member");
            assertDoesNotThrow(() -> new ClassReader(bytes).accept(
                    new CheckClassAdapter(new ClassWriter(0), false), 0));
        }
        ClassNode base = read(transformer.getSyntheticClasses().get(Pre1_20_5ParticleTypeBridge.LEGACY_TYPE));
        assertEquals(TYPE, base.superName);
        assertTrue(base.methods.stream().anyMatch(m -> m.name.equals("codec")
                        && m.desc.equals("()Lcom/mojang/serialization/MapCodec;")),
                "the base supplies the 1.20.5 MapCodec codec() from the mod's old Codec codec()");
        assertTrue(base.methods.stream().anyMatch(m -> m.name.equals("streamCodec")));
    }

    @Test
    @DisplayName("A mod particle type moves onto the generated base and deserializer")
    void legacyParticleTypeIsRebased() {
        Pre1_20_5MojangParticleTypeBridge.registerRedirects(transformer, true);
        String oldCtor = "(ZL" + OLD_DESERIALIZER + ";)V";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_SUPER, "test/ModParticleTypes$1", null, TYPE, null);
        MethodVisitor ctor = cw.visitMethod(0, "<init>", oldCtor, null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitVarInsn(ILOAD, 1);
        ctor.visitVarInsn(ALOAD, 2);
        ctor.visitMethodInsn(INVOKESPECIAL, TYPE, "<init>", oldCtor, false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(3, 3);
        ctor.visitEnd();
        cw.visitEnd();

        ClassNode out = read(transformer.transformClass(cw.toByteArray(), "test/ModParticleTypes$1"));
        assertEquals(Pre1_20_5ParticleTypeBridge.LEGACY_TYPE, out.superName);
        MethodInsnNode superCall = (MethodInsnNode) java.util.Arrays.stream(out.methods.get(0).instructions.toArray())
                .filter(MethodInsnNode.class::isInstance).findFirst().orElseThrow();
        assertEquals(Pre1_20_5ParticleTypeBridge.LEGACY_TYPE, superCall.owner);
        assertEquals("(ZL" + Pre1_20_5ParticleTypeBridge.DESERIALIZER + ";)V", superCall.desc,
                "the removed Deserializer must become the generated interface");
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
