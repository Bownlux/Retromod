/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Keeps a mod's {@code Style} font calls working where 1.21.9 made the font a {@code FontDescription}. */
public final class LegacyStyleFontBridge {

    /** The names one helper is written in: Mojang names, or a Fabric host's intermediary. */
    record Names(String helper, String style, String withFont, String getFont, String id, String description,
            String resource, String resourceId, String defaultFont) {
        String withFontOld() {
            return "(L" + id + ";)L" + style + ";";
        }

        String withFontNew() {
            return "(L" + description + ";)L" + style + ";";
        }

        String helperWithFont() {
            return "(L" + style + ";L" + id + ";)L" + style + ";";
        }

        String helperGetFont() {
            return "(L" + style + ";)L" + id + ";";
        }
    }

    static final String STYLE = "net/minecraft/network/chat/Style";
    static final String FONT_DESCRIPTION = "net/minecraft/network/chat/FontDescription";
    static final String RESOURCE_LOCATION = "net/minecraft/resources/ResourceLocation";
    static final String IDENTIFIER = "net/minecraft/resources/Identifier";

    /** The first version whose {@code Style} takes a {@code FontDescription}. */
    static final String CHANGED_IN = "1.21.9";

    // A Fabric 1.21.9 to 1.21.11 host keeps both Style ids and changes their descriptors. Checked
    // against the 1.21.8, 1.21.9 and 1.21.11 tables.
    static final Names INTERMEDIARY = new Names("com/retromod/generated/LegacyIntermediaryStyleFont",
            "net/minecraft/class_2583", "method_27704", "method_27708", "net/minecraft/class_2960",
            "net/minecraft/class_11719", "net/minecraft/class_11719$class_11721", "comp_4590", "field_61972");

    private LegacyStyleFontBridge() {}

    /** The Mojang names on {@code host}, whose identifier class 1.21.11 renamed. */
    static Names mojang(String host) {
        String id = RetromodVersion.compareMcVersions(host, "1.21.11") >= 0 ? IDENTIFIER : RESOURCE_LOCATION;
        return new Names("com/retromod/generated/LegacyStyleFont", STYLE, "withFont", "getFont", id,
                FONT_DESCRIPTION, FONT_DESCRIPTION + "$Resource", "id", "DEFAULT");
    }

    /** Registers the bridge in Mojang names on a host that made the change. */
    public static void register(RetromodTransformer transformer) {
        register(transformer, mojang(RetromodVersion.TARGET_MC_VERSION));
    }

    /** The same bridge in the intermediary names a Fabric 1.21.9 to 1.21.11 host runs. */
    public static void registerIntermediary(RetromodTransformer transformer) {
        register(transformer, INTERMEDIARY);
    }

    private static void register(RetromodTransformer transformer, Names names) {
        ClassNode style = ClassResourceInspector.read(names.style());
        // Offline, where the host class cannot be read, the host version decides.
        boolean changed = style == null
                ? RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, CHANGED_IN) >= 0
                : takesDescription(style, names);
        if (changed) registerRedirects(transformer, names);
    }

    static void registerRedirects(RetromodTransformer transformer, Names names) {
        transformer.registerSyntheticClass(names.helper(), generateHelper(names));
        transformer.registerMethodRedirect(names.style(), names.withFont(), names.withFontOld(),
                names.helper(), "withFont", names.helperWithFont());
        transformer.registerMethodRedirect(names.style(), names.getFont(), "()L" + names.id() + ";",
                names.helper(), "getFont", names.helperGetFont());
    }

    /** Whether the host's {@code withFont} takes a font description instead of an identifier. */
    static boolean takesDescription(ClassNode style, Names names) {
        boolean oldForm = false;
        boolean newForm = false;
        for (MethodNode method : style.methods) {
            if (!method.name.equals(names.withFont())) continue;
            oldForm |= method.desc.equals(names.withFontOld());
            newForm |= method.desc.equals(names.withFontNew());
        }
        return !oldForm && newForm;
    }

    static byte[] generateHelper(Names names) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String left, String right) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, names.helper(), null,
                "java/lang/Object", null);
        emitWithFont(cw, names);
        emitGetFont(cw, names);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code withFont(style, id)}: the font file named by {@code id}, which is what the old call set. */
    private static void emitWithFont(ClassWriter cw, Names names) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "withFont",
                names.helperWithFont(), null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        // A null id cleared the font before, and a null description still does.
        Label wrap = new Label();
        Label call = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IFNONNULL, wrap);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitJumpInsn(Opcodes.GOTO, call);
        mv.visitLabel(wrap);
        mv.visitTypeInsn(Opcodes.NEW, names.resource());
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, names.resource(), "<init>", "(L" + names.id() + ";)V", false);
        mv.visitLabel(call);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.style(), names.withFont(), names.withFontNew(), false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code getFont(style)}: the font file's id, or the default font's id for a sprite font. */
    private static void emitGetFont(ClassWriter cw, Names names) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getFont",
                names.helperGetFont(), null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.style(), names.getFont(), "()L" + names.description() + ";",
                false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        // The host already reads an unset font as the default one, as the old getter did.
        Label other = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, names.resource());
        mv.visitJumpInsn(Opcodes.IFEQ, other);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, names.resource());
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.resource(), names.resourceId(), "()L" + names.id() + ";",
                false);
        mv.visitInsn(Opcodes.ARETURN);
        // A sprite font has no font file, so it answers with the default font as an unset one did.
        mv.visitLabel(other);
        mv.visitFieldInsn(Opcodes.GETSTATIC, names.description(), names.defaultFont(), "L" + names.resource() + ";");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.resource(), names.resourceId(), "()L" + names.id() + ";",
                false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
