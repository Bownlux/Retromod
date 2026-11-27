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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/** Rebuilds 1.20.1's texture image buttons on hosts from 1.20.2 to 1.21.1, whose ImageButton draws GUI sprites. */
public final class LegacyTextureImageButtonBridge {

    static final String CLASS = "com/retromod/generated/LegacyTextureImageButton";
    static final String IMAGE_BUTTON = "net/minecraft/client/gui/components/ImageButton";
    static final String GRAPHICS = "net/minecraft/client/gui/GuiGraphics";
    static final String SPRITES = "net/minecraft/client/gui/components/WidgetSprites";
    static final String TEXTURE = "net/minecraft/resources/ResourceLocation";
    static final String COMPONENT = "net/minecraft/network/chat/Component";
    static final String ON_PRESS = "Lnet/minecraft/client/gui/components/Button$OnPress;";
    static final String RENDER = "(L" + GRAPHICS + ";IIF)V";
    /** {@code blit(texture, x, y, u, v, width, height, textureWidth, textureHeight)}, which 1.21.2 replaced. */
    static final String BLIT = "(L" + TEXTURE + ";IIFFIIII)V";
    static final String FULL = "(IIIIIIIL" + TEXTURE + ";II" + ON_PRESS + "L" + COMPONENT + ";)V";
    /** The 1.20.1 constructors: without a hover offset, plain, with the texture size, and with a message too. */
    static final String[] OLD_CONSTRUCTORS = {
            "(IIIIIIL" + TEXTURE + ";" + ON_PRESS + ")V",
            "(IIIIIIIL" + TEXTURE + ";" + ON_PRESS + ")V",
            "(IIIIIIIL" + TEXTURE + ";II" + ON_PRESS + ")V",
            FULL};
    /** The host's sprite constructors, for a mod's own button moved onto this class. */
    static final String[] SPRITE_CONSTRUCTORS = {
            "(IIIIL" + SPRITES + ";" + ON_PRESS + ")V",
            "(IIIIL" + SPRITES + ";" + ON_PRESS + "L" + COMPONENT + ";)V",
            "(IIL" + SPRITES + ";" + ON_PRESS + "L" + COMPONENT + ";)V"};

    private LegacyTextureImageButtonBridge() {}

    /** Registers the rebuilt button where the host dropped the texture constructors but still blits a texture region. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostShape(host.apply(IMAGE_BUTTON), host.apply(GRAPHICS))) return;
        t.registerSyntheticClass(CLASS, generate());
        for (String old : OLD_CONSTRUCTORS) {
            t.registerConstructorRedirect(IMAGE_BUTTON, old, CLASS, "create", old.replace(")V", ")L" + IMAGE_BUTTON + ";"));
        }
        // A mod's own image button moves onto the rebuilt one; a mixin keeps its superclass, which Mixin checks.
        t.registerSuperclassRebaseSkippingMixins(IMAGE_BUTTON, CLASS);
    }

    /** Whether the host has sprite image buttons and the texture blit; offline, 1.20.2 to 1.21.1 do. */
    static boolean hostShape(ClassNode imageButton, ClassNode graphics) {
        if (imageButton == null || graphics == null) {
            String host = RetromodVersion.TARGET_MC_VERSION;
            return host != null && RetromodVersion.compareMcVersions(host, "1.20.2") >= 0
                    && RetromodVersion.compareMcVersions(host, "1.21.2") < 0;
        }
        return !has(imageButton, "<init>", OLD_CONSTRUCTORS[1]) && has(imageButton, "<init>", SPRITE_CONSTRUCTORS[1])
                && has(imageButton, "renderWidget", RENDER) && has(graphics, "blit", BLIT);
    }

    private static boolean has(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    /** {@code class LegacyTextureImageButton extends ImageButton}: a texture region, the hovered image one offset down. */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, CLASS, null, IMAGE_BUTTON, null);
        // The 1.20.1 fields under their names, protected, since a mod's own button reads them. The texture is null
        // for a button built with sprites, which draws as the host's ImageButton does.
        cw.visitField(ACC_PROTECTED | ACC_FINAL, "resourceLocation", "L" + TEXTURE + ";", null, null).visitEnd();
        for (String field : new String[] {"xTexStart", "yTexStart", "yDiffTex", "textureWidth", "textureHeight"}) {
            cw.visitField(ACC_PROTECTED | ACC_FINAL, field, "I", null, null).visitEnd();
        }
        emitFullConstructor(cw);
        // The shorter old forms fill in what 1.20.1 did: the button height as the offset, a 256 pixel texture, no message.
        emitDelegate(cw, OLD_CONSTRUCTORS[0], 6, true, true);
        emitDelegate(cw, OLD_CONSTRUCTORS[1], 8, false, true);
        emitDelegate(cw, OLD_CONSTRUCTORS[2], 10, false, false);
        for (String sprites : SPRITE_CONSTRUCTORS) emitSpriteConstructor(cw, sprites);
        for (String old : OLD_CONSTRUCTORS) emitFactory(cw, old);
        emitRender(cw);
        emitUpdateTooltip(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 1.20.1's {@code updateTooltip()}, which a mod's override calls as super; the host's tooltip holder does that work now. */
    private static void emitUpdateTooltip(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "updateTooltip", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(RETURN);
        end(mv);
    }

    /** The full texture constructor: the sprites are never drawn, the texture region below takes their place. */
    private static void emitFullConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", FULL, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        for (int slot = 1; slot <= 4; slot++) mv.visitVarInsn(ILOAD, slot);
        mv.visitTypeInsn(NEW, SPRITES);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitMethodInsn(INVOKESPECIAL, SPRITES, "<init>", "(L" + TEXTURE + ";L" + TEXTURE + ";)V", false);
        mv.visitVarInsn(ALOAD, 11);
        mv.visitVarInsn(ALOAD, 12);
        mv.visitMethodInsn(INVOKESPECIAL, IMAGE_BUTTON, "<init>", SPRITE_CONSTRUCTORS[1], false);
        putField(mv, "resourceLocation", "L" + TEXTURE + ";", ALOAD, 8);
        putField(mv, "xTexStart", "I", ILOAD, 5);
        putField(mv, "yTexStart", "I", ILOAD, 6);
        putField(mv, "yDiffTex", "I", ILOAD, 7);
        putField(mv, "textureWidth", "I", ILOAD, 9);
        putField(mv, "textureHeight", "I", ILOAD, 10);
        mv.visitInsn(RETURN);
        end(mv);
    }

    /** A shorter old constructor, which calls the full one with the values 1.20.1 filled in. */
    private static void emitDelegate(ClassWriter cw, String desc, int leading, boolean heightOffset, boolean defaultSize) {
        Type[] arguments = Type.getArgumentTypes(desc);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        // The first leading arguments, then the button height as the hover offset and the texture where it has no
        // offset, a 256 pixel size where it gives none, the press handler, and no message.
        for (int i = 0; i < leading; i++) mv.visitVarInsn(arguments[i].getOpcode(ILOAD), i + 1);
        if (heightOffset) {
            mv.visitVarInsn(ILOAD, 4);
            mv.visitVarInsn(ALOAD, leading + 1);
        }
        if (defaultSize) {
            mv.visitIntInsn(SIPUSH, 256);
            mv.visitIntInsn(SIPUSH, 256);
        }
        // Everything after the leading arguments and the texture, through the press handler, follows in order.
        for (int i = heightOffset ? leading + 1 : leading; i < arguments.length; i++) {
            mv.visitVarInsn(arguments[i].getOpcode(ILOAD), i + 1);
        }
        mv.visitFieldInsn(GETSTATIC, "net/minecraft/network/chat/CommonComponents", "EMPTY", "L" + COMPONENT + ";");
        mv.visitMethodInsn(INVOKESPECIAL, CLASS, "<init>", FULL, false);
        mv.visitInsn(RETURN);
        end(mv);
    }

    /** The host's own sprite constructor, for a subclass that already uses sprites: it draws as the host does. */
    private static void emitSpriteConstructor(ClassWriter cw, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        int slot = 1;
        for (Type argument : Type.getArgumentTypes(desc)) {
            mv.visitVarInsn(argument.getOpcode(ILOAD), slot);
            slot += argument.getSize();
        }
        mv.visitMethodInsn(INVOKESPECIAL, IMAGE_BUTTON, "<init>", desc, false);
        mv.visitInsn(RETURN);
        end(mv);
    }

    /** {@code static ImageButton create(...)}: the factory a {@code new ImageButton(...)} with old arguments calls. */
    private static void emitFactory(ClassWriter cw, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create", desc.replace(")V", ")L" + IMAGE_BUTTON + ";"), null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, CLASS);
        mv.visitInsn(DUP);
        int slot = 0;
        for (Type argument : Type.getArgumentTypes(desc)) {
            mv.visitVarInsn(argument.getOpcode(ILOAD), slot);
            slot += argument.getSize();
        }
        mv.visitMethodInsn(INVOKESPECIAL, CLASS, "<init>", desc, false);
        mv.visitInsn(ARETURN);
        end(mv);
    }

    /** {@code renderWidget}: the texture region, one offset down when hovered or focused and two when inactive. */
    private static void emitRender(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "renderWidget", RENDER, null, null);
        mv.visitCode();
        Label textured = new Label();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, CLASS, "resourceLocation", "L" + TEXTURE + ";");
        mv.visitJumpInsn(IFNONNULL, textured);
        mv.visitVarInsn(ALOAD, 0);
        for (int slot = 1; slot <= 3; slot++) mv.visitVarInsn(slot == 1 ? ALOAD : ILOAD, slot);
        mv.visitVarInsn(FLOAD, 4);
        mv.visitMethodInsn(INVOKESPECIAL, IMAGE_BUTTON, "renderWidget", RENDER, false);
        mv.visitInsn(RETURN);
        mv.visitLabel(textured);
        // The row: v, plus the offset when hovered or focused, plus two offsets when the button is inactive.
        int row = 5;
        Label active = new Label();
        Label chosen = new Label();
        getField(mv, "yTexStart");
        mv.visitVarInsn(ISTORE, row);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, CLASS, "isActive", "()Z", false);
        mv.visitJumpInsn(IFNE, active);
        mv.visitVarInsn(ILOAD, row);
        getField(mv, "yDiffTex");
        mv.visitInsn(ICONST_2);
        mv.visitInsn(IMUL);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ISTORE, row);
        mv.visitJumpInsn(GOTO, chosen);
        mv.visitLabel(active);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, CLASS, "isHoveredOrFocused", "()Z", false);
        mv.visitJumpInsn(IFEQ, chosen);
        mv.visitVarInsn(ILOAD, row);
        getField(mv, "yDiffTex");
        mv.visitInsn(IADD);
        mv.visitVarInsn(ISTORE, row);
        mv.visitLabel(chosen);
        // graphics.blit(texture, x, y, u, row, width, height, textureWidth, textureHeight)
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, CLASS, "resourceLocation", "L" + TEXTURE + ";");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, CLASS, "getX", "()I", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, CLASS, "getY", "()I", false);
        getField(mv, "xTexStart");
        mv.visitInsn(I2F);
        mv.visitVarInsn(ILOAD, row);
        mv.visitInsn(I2F);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, CLASS, "getWidth", "()I", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, CLASS, "getHeight", "()I", false);
        getField(mv, "textureWidth");
        getField(mv, "textureHeight");
        mv.visitMethodInsn(INVOKEVIRTUAL, GRAPHICS, "blit", BLIT, false);
        mv.visitInsn(RETURN);
        end(mv);
    }

    private static void getField(MethodVisitor mv, String name) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, CLASS, name, "I");
    }

    private static void putField(MethodVisitor mv, String name, String desc, int load, int slot) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(load, slot);
        mv.visitFieldInsn(PUTFIELD, CLASS, name, desc);
    }

    private static void end(MethodVisitor mv) {
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
