/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.util.List;
import java.util.function.Function;

import static com.retromod.shim.common.LegacyPackMetadataCodecBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** An old read of {@code PackMetadataSection.CODEC} becomes the rebuilt codec, only on hosts without that field. */
class LegacyPackMetadataCodecBridgeTest {

    private static final String REBUILT_CALL = REBUILT + ".codec()" + CODEC;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A read of CODEC, or of the private name the intermediary remap gives it, calls the rebuilt codec")
    void oldReadsCallTheRebuiltCodec() {
        registerOn("26.3", name -> null);
        assertEquals(List.of(REBUILT_CALL), BridgeHarness.calls(reader("CODEC"), "codec"));
        assertEquals(List.of(REBUILT_CALL), BridgeHarness.calls(reader("FALLBACK_CODEC"), "codec"),
                "a Fabric mod's field_45051 is FALLBACK_CODEC after the remap, which is private on the host");
        assertTrue(transformer.getSyntheticClasses().containsKey(REBUILT), "the rebuilt codec travels with the mod");
    }

    @Test
    @DisplayName("Hosts that still have the public CODEC keep it; offline, targets from 1.21.11 get the bridge")
    void followsTheHost() {
        registerOn("26.3", host(true));
        assertFalse(transformer.getSyntheticClasses().containsKey(REBUILT), "a host with CODEC needs no bridge");
        registerOn("26.3", host(false));
        assertTrue(transformer.getSyntheticClasses().containsKey(REBUILT));
        registerOn("1.21.10", name -> null);
        assertFalse(transformer.getSyntheticClasses().containsKey(REBUILT));
        registerOn("1.21.11", name -> null);
        assertTrue(transformer.getSyntheticClasses().containsKey(REBUILT));
    }

    private void registerOn(String target, Function<String, ClassNode> host) {
        RetromodVersion.TARGET_MC_VERSION = target;
        transformer.clearRedirectsForTesting();
        register(transformer, host);
    }

    /** A host metadata class, with or without a public {@code CODEC}. */
    private static Function<String, ClassNode> host(boolean hasCodec) {
        ClassNode node = new ClassNode();
        node.name = METADATA;
        String name = hasCodec ? FIELD : "FALLBACK_CODEC";
        int access = Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | (hasCodec ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE);
        node.fields.add(new FieldNode(access, name, CODEC, null, null));
        return owner -> owner.equals(METADATA) ? node : null;
    }

    /** {@code mod/Reader}, whose static {@code codec()} returns {@code PackMetadataSection.<field>}, transformed. */
    private byte[] reader(String field) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Reader", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "codec", "()" + CODEC, null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, METADATA, field, CODEC);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return transformer.transformClass(writer.toByteArray(), "mod/Reader");
    }
}
