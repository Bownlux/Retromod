/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.shim.fabric.Fabric_1_21_3_to_1_21_4;
import com.retromod.shim.forge.Forge_1_21_3_to_1_21_4;
import com.retromod.shim.neoforge.NeoForge_1_21_3_to_1_21_4;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 1.21.4 renamed the furnace timers, so Jade 1.21.1's furnace progress accessor found no field on 26.3. */
class Mc1_21_3To1_21_4CoreMovesTest {

    private static final String FURNACE = Mc1_21_3To1_21_4CoreMoves.FURNACE;
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Jade's implicit furnace accessors name the 1.21.4 fields on every loader")
    void accessorsFollowTheRenamedFields() {
        for (Runnable shim : List.<Runnable>of(
                () -> new NeoForge_1_21_3_to_1_21_4().registerRedirects(transformer()),
                () -> new Forge_1_21_3_to_1_21_4().registerRedirects(transformer()),
                () -> new Fabric_1_21_3_to_1_21_4().registerRedirects(transformer()))) {
            transformer().clearRedirectsForTesting();
            shim.run();

            ClassNode node = read(new MixinCompatibilityTransformer(transformer()).transformMixinClass(jadeAccessor()));

            assertEquals("cookingTimer", accessorValue(node, "getCookingProgress"));
            assertNull(accessorValue(node, "getCookingTotalTime"), "cookingTotalTime kept its name");
        }
    }

    @Test
    @DisplayName("a direct read of an old timer reads the renamed field")
    void directFieldReadsAreRenamed() {
        Mc1_21_3To1_21_4CoreMoves.register(transformer());

        List<String> reads = fieldReads(transformer().transformClass(
                reader(FURNACE, "litTime", "litDuration", "cookingProgress"), "test/mod/FurnaceReader"));

        assertEquals(List.of(FURNACE + ".litTimeRemaining", FURNACE + ".litTotalTime", FURNACE + ".cookingTimer"), reads);
    }

    @Test
    @DisplayName("Fabric mods on an intermediary host read the 1.21.4 intermediary fields")
    void intermediaryReadsAreRenamed() {
        String furnace = Mc1_21_3To1_21_4CoreMoves.FURNACE_INTERMEDIARY;
        Mc1_21_3To1_21_4CoreMoves.register(transformer());

        List<String> reads = fieldReads(transformer().transformClass(
                reader(furnace, "field_11981", "field_11980", "field_11989"), "test/mod/FurnaceReader"));

        assertEquals(List.of(furnace + ".field_55574", furnace + ".field_55575", furnace + ".field_55576"), reads);
    }

    private static RetromodTransformer transformer() {
        return RetromodTransformer.getInstance();
    }

    /** Jade 1.21.1's accessor: {@code @Accessor int getCookingProgress()} and {@code getCookingTotalTime()}. */
    private static byte[] jadeAccessor() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "snownee/jade/mixin/AbstractFurnaceBlockEntityAccess", null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(FURNACE));
        targets.visitEnd();
        mixin.visitEnd();
        for (String name : new String[] {"getCookingProgress", "getCookingTotalTime"}) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, "()I", null, null);
            method.visitAnnotation(ACCESSOR, true).visitEnd();
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A class whose one method reads each named int field of the owner, in order. */
    private static byte[] reader(String owner, String... fields) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/mod/FurnaceReader", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "(L" + owner + ";)I", null, null);
        method.visitCode();
        for (String field : fields) {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, owner, field, "I");
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> fieldReads(byte[] bytes) {
        List<String> reads = new ArrayList<>();
        for (MethodNode method : read(bytes).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) reads.add(field.owner + "." + field.name);
            }
        }
        return reads;
    }

    /** The accessor's explicit field name, or null when it still infers the name from the method. */
    private static String accessorValue(ClassNode node, String methodName) {
        MethodNode method = node.methods.stream().filter(m -> m.name.equals(methodName)).findFirst().orElseThrow();
        for (AnnotationNode annotation : method.visibleAnnotations) {
            if (!ACCESSOR.equals(annotation.desc) || annotation.values == null) continue;
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if ("value".equals(annotation.values.get(i))) return (String) annotation.values.get(i + 1);
            }
        }
        return null;
    }
}
