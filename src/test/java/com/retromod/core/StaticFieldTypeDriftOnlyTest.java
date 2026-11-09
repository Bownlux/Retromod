/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A transformer whose only registration is a static field type drift or a library field reader still rewrites the reads it covers. */
class StaticFieldTypeDriftOnlyTest {

    private static final String CONSTANTS = "probe/Constants";

    @Test
    @DisplayName("A drift alone reaches the class pass: the read takes the new type and passes through the converter")
    void aDriftAloneRewritesTheRead() throws Exception {
        Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RetromodTransformer transformer = constructor.newInstance();
        transformer.registerStaticFieldTypeDrift(CONSTANTS, "Lprobe/Holder;", "Lprobe/Key;",
                "probe/Convert", "toHolder", "(Lprobe/Key;)Lprobe/Holder;");

        byte[] translated = transformer.transformClass(reader(), "mod/Reader");

        assertEquals(List.of("GETSTATIC probe/Constants.PATCH_GRASS:Lprobe/Key;", "CALL probe/Convert.toHolder"),
                instructions(translated), "the 1.19.3 Holder-to-ResourceKey drift, with no other registration");
    }

    @Test
    @DisplayName("A library field reader alone reaches the class pass: another mod's field is read by name")
    void aLibraryReaderAloneRewritesTheRead() throws Exception {
        Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RetromodTransformer transformer = constructor.newInstance();
        transformer.registerLibraryStaticFieldReader("Lprobe/Holder;", "probe/Reader", "read");

        byte[] translated;
        // The jar being translated ships only mod/Reader, so probe/Constants is another mod's class.
        try (var scope = transformer.pushJarClassBytesProvider(name -> null)) {
            translated = transformer.transformClass(reader(), "mod/Reader");
        }

        assertEquals(List.of("CALL probe/Reader.read"), instructions(translated),
                "a Forge library's retyped RegistryObject field, with no other registration");
    }

    /** {@code read()} returns {@code Constants.PATCH_GRASS} as the old Holder type. */
    private static byte[] reader() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "mod/Reader", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "()Lprobe/Holder;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, CONSTANTS, "PATCH_GRASS", "Lprobe/Holder;");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> instructions(byte[] bytes) {
        List<String> seen = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (!name.equals("read")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                        seen.add("GETSTATIC " + owner + "." + field + ":" + desc);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        seen.add("CALL " + owner + "." + method);
                    }
                };
            }
        }, 0);
        return seen;
    }
}
