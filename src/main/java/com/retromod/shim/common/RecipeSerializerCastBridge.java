/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Lets a mod cast a registered recipe serializer back to its own class, though the registry holds an adapter. */
public final class RecipeSerializerCastBridge {

    // Types that can never be a mod's own serializer class.
    private static final String[] PLATFORM = {"java/", "javax/", "jdk/", "sun/", "net/minecraft/", "com/mojang/",
            "com/retromod/", "net/fabricmc/", "net/neoforged/", "net/minecraftforge/", "org/"};

    // The old serializer interface; a mod class keeps it in the jar as shipped, which the hierarchy check reads.
    private static final String SERIALIZER = "net/minecraft/world/item/crafting/RecipeSerializer";

    private RecipeSerializerCastBridge() {}

    /**
     * Unwraps a value read from a generic getter, such as a registry entry's {@code get()}, before the mod
     * casts or tests it against one of its own classes. Other values pass through unchanged.
     */
    public static byte[] apply(byte[] classBytes) {
        return apply(classBytes, RecipeSerializerCastBridge::serializerType);
    }

    /** A serializer type of this jar, or one of another mod the jar cannot see, such as a library's, named as one. */
    static boolean serializerType(String type) {
        if (com.retromod.core.RetromodTransformer.currentJarClassInheritsFrom(type, SERIALIZER)) return true;
        // A library is often not loaded yet when its users are translated, so only its name is known.
        return com.retromod.core.RetromodTransformer.readCurrentJarClassForAdapter(type) == null
                && type.substring(type.lastIndexOf('/') + 1).endsWith("Serializer");
    }

    /** The same, inserting only before casts to a type {@code isSerializer} accepts. */
    static byte[] apply(byte[] classBytes, java.util.function.Predicate<String> isSerializer) {
        if (classBytes == null) return null;
        ClassReader reader;
        try {
            reader = new ClassReader(classBytes);
        } catch (RuntimeException unreadable) {
            return classBytes;
        }
        if (com.retromod.core.SyntheticEmbedder.isRetromodClass(reader.getClassName())
                || reader.getClassName().startsWith("net/minecraft/")) {
            return classBytes;
        }
        boolean[] changed = new boolean[1];
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new CastSite(super.visitMethod(access, name, desc, signature, exceptions), changed, isSerializer);
            }
        }, 0);
        return changed[0] ? writer.toByteArray() : classBytes;
    }

    /** Whether a cast to this type may be a cast to a mod's own serializer class. */
    static boolean modType(String type) {
        if (type.startsWith("[")) return false;
        for (String prefix : PLATFORM) {
            if (type.startsWith(prefix)) return false;
        }
        return true;
    }

    /** Tracks whether the value on top of the stack came straight from a call that returns Object. */
    private static final class CastSite extends MethodVisitor {
        private final boolean[] changed;
        private final java.util.function.Predicate<String> isSerializer;
        private boolean genericValue;

        CastSite(MethodVisitor next, boolean[] changed, java.util.function.Predicate<String> isSerializer) {
            super(Opcodes.ASM9, next);
            this.changed = changed;
            this.isSerializer = isSerializer;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            super.visitMethodInsn(opcode, owner, name, desc, itf);
            genericValue = desc.endsWith(")Ljava/lang/Object;");
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            // Only a cast to one of the mod's own serializer types can need the original back.
            if (genericValue && (opcode == Opcodes.CHECKCAST || opcode == Opcodes.INSTANCEOF) && modType(type)
                    && isSerializer.test(type)) {
                // Stack neutral: Object in, Object out, so the frames stay valid.
                super.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyRecipeSerializerShim.BRIDGE, "original",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                changed[0] = true;
            }
            genericValue = false;
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitInsn(int opcode) {
            genericValue = false;
            super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            genericValue = false;
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            genericValue = false;
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            genericValue = false;
            super.visitFieldInsn(opcode, owner, name, desc);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bootstrap, Object... args) {
            genericValue = false;
            super.visitInvokeDynamicInsn(name, desc, bootstrap, args);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            genericValue = false;
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLabel(Label label) {
            genericValue = false;
            super.visitLabel(label);
        }

        @Override
        public void visitLdcInsn(Object value) {
            genericValue = false;
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            genericValue = false;
            super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            genericValue = false;
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            genericValue = false;
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(String desc, int dims) {
            genericValue = false;
            super.visitMultiANewArrayInsn(desc, dims);
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            genericValue = false;
            super.visitFrame(type, numLocal, local, numStack, stack);
        }
    }
}
