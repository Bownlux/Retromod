/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the pre-26.1 particle render types onto the 26.1 particle groups.
 *
 * <p>Up to 1.21.x {@code ParticleRenderType} was an interface with constants such as
 * {@code PARTICLE_SHEET_OPAQUE} and {@code CUSTOM}, and a mod could implement it for its own sheet.
 * 26.1 turned it into a final record keyed by name, with the constants {@code SINGLE_QUADS},
 * {@code ITEM_PICKUP}, {@code ELDER_GUARDIANS} and {@code NO_RENDER}. A quad particle now picks its
 * texture sheet through the abstract {@code SingleQuadParticle.getLayer()}. Three failures follow:
 *
 * <ul>
 *   <li>A read of a removed constant dies with {@code NoSuchFieldError}. NexusLib reads
 *       {@code CUSTOM} inside its {@code ParticleEngine} static-init mixin, which crashed the client
 *       before the title screen.</li>
 *   <li>A class implementing the old interface cannot load, because it now names a final class
 *       as an interface.</li>
 *   <li>A particle rebased from {@code TextureSheetParticle} has no {@code getLayer()} and throws
 *       {@code AbstractMethodError} the first time it is drawn.</li>
 * </ul>
 *
 * <p>The sheet constants map to {@code SINGLE_QUADS}, which every quad particle uses on 26.1.
 * {@code CUSTOM} maps to {@code ITEM_PICKUP}, the first custom-drawn group in the render order, so
 * a mod that inserts its own type relative to {@code CUSTOM} still finds an anchor. A legacy
 * implementation keeps its class but loses the interface, and the place that builds it hands the
 * engine a record named after its {@code toString()} instead. No particle is ever filed under that
 * record, so its slot in the render order stays empty. A generated {@code getLayer()} picks the
 * opaque or translucent sheet from the particle's sprite, as {@code Layer.bySprite} does.
 *
 * <p>The limit: a custom render type's own drawing code, its {@code begin} method, is never called
 * on 26.1. Its particles render through the standard quad path or not at all.
 */
public final class LegacyParticleRenderTypeAdapter {

    private LegacyParticleRenderTypeAdapter() {}

    static final String RENDER_TYPE = "net/minecraft/client/particle/ParticleRenderType";
    static final String RENDER_TYPE_DESC = "L" + RENDER_TYPE + ";";
    static final String SINGLE_QUAD = "net/minecraft/client/particle/SingleQuadParticle";
    static final String LAYER = SINGLE_QUAD + "$Layer";
    static final String LAYER_DESC = "L" + LAYER + ";";
    static final String SPRITE_DESC =
            "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;";
    public static final String POLY = "com/retromod/polyfill/minecraft/RetroParticleCompat";

    private static final byte[] RENDER_TYPE_BYTES = RENDER_TYPE.getBytes(StandardCharsets.UTF_8);
    private static final byte[] SINGLE_QUAD_BYTES = SINGLE_QUAD.getBytes(StandardCharsets.UTF_8);

    /** Old constant name and the 26.1 constant that takes its place. */
    private static final String[][] CONSTANTS = {
            {"TERRAIN_SHEET", "SINGLE_QUADS"},
            {"PARTICLE_SHEET_OPAQUE", "SINGLE_QUADS"},
            {"PARTICLE_SHEET_TRANSLUCENT", "SINGLE_QUADS"},
            {"PARTICLE_SHEET_LIT", "SINGLE_QUADS"},
            {"CUSTOM", "ITEM_PICKUP"},
    };

    /** Registers the constant redirects. The class repair runs from the transformer's post pass. */
    public static void register(RetromodTransformer transformer) {
        for (String[] constant : CONSTANTS) {
            transformer.registerFieldRedirect(RENDER_TYPE, constant[0], RENDER_TYPE_DESC,
                    RENDER_TYPE, constant[1], RENDER_TYPE_DESC);
        }
    }

    /**
     * @return the repaired class, or the same array when nothing in it needed a repair
     */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null
                || indexOf(classBytes, RENDER_TYPE_BYTES) < 0
                        && indexOf(classBytes, SINGLE_QUAD_BYTES) < 0) {
            return classBytes;
        }
        ClassNode cn = new ClassNode();
        try {
            new ClassReader(classBytes).accept(cn, 0);
        } catch (RuntimeException unreadable) {
            return classBytes;
        }

        boolean changed = cn.interfaces.remove(RENDER_TYPE);
        for (MethodNode method : cn.methods) {
            changed |= wrapLegacyConstruction(method);
        }
        changed |= addMissingLayer(cn);
        if (!changed) return classBytes;

        try {
            SafeClassWriter cw = new SafeClassWriter(new ClassReader(classBytes),
                    ClassWriter.COMPUTE_FRAMES);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (RuntimeException failed) {
            return classBytes; // never ship a half-rewritten class
        }
    }

    /**
     * Converts a freshly built legacy implementation into a 26.1 render type record.
     *
     * <p>On 26.1 nothing can be a subtype of the final record, so an object built by {@code new}
     * and stored straight into a {@code ParticleRenderType} field or returned as one can only be an
     * old implementation. Matching only those immediate consumers keeps every other use of the
     * object untouched.
     */
    private static boolean wrapLegacyConstruction(MethodNode method) {
        if (method.instructions == null) return false;
        boolean returnsRenderType = Type.getReturnType(method.desc).getDescriptor()
                .equals(RENDER_TYPE_DESC);
        List<MethodInsnNode> sites = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.name.equals("<init>")
                    || call.owner.equals(RENDER_TYPE)) {
                continue;
            }
            AbstractInsnNode next = nextReal(call);
            if (next == null) continue;
            boolean storesRenderType = next instanceof FieldInsnNode field
                    && (field.getOpcode() == Opcodes.PUTSTATIC
                            || field.getOpcode() == Opcodes.PUTFIELD)
                    && field.desc.equals(RENDER_TYPE_DESC);
            boolean returnsIt = returnsRenderType && next.getOpcode() == Opcodes.ARETURN;
            if (storesRenderType || returnsIt) sites.add(call);
        }
        for (MethodInsnNode site : sites) {
            InsnList convert = new InsnList();
            convert.add(new MethodInsnNode(Opcodes.INVOKESTATIC, POLY, "renderTypeFor",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", false));
            convert.add(new TypeInsnNode(Opcodes.CHECKCAST, RENDER_TYPE));
            method.instructions.insert(site, convert);
        }
        return !sites.isEmpty();
    }

    /**
     * Adds {@code getLayer()} to a direct quad particle subclass that has none.
     *
     * <p>{@code protected Layer getLayer() { return sprite == null ? Layer.OPAQUE
     * : Layer.bySprite(sprite); }}. A subclass that declares its own keeps it.
     */
    private static boolean addMissingLayer(ClassNode cn) {
        if (!SINGLE_QUAD.equals(cn.superName)) return false;
        for (MethodNode m : cn.methods) {
            if (m.name.equals("getLayer") && m.desc.equals("()" + LAYER_DESC)) return false;
        }
        MethodNode getLayer = new MethodNode(Opcodes.ACC_PROTECTED, "getLayer", "()" + LAYER_DESC,
                null, null);
        InsnList code = getLayer.instructions;
        LabelNode noSprite = new LabelNode(new Label());
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, SINGLE_QUAD, "sprite", SPRITE_DESC));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new JumpInsnNode(Opcodes.IFNULL, noSprite));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LAYER, "bySprite",
                "(" + SPRITE_DESC + ")" + LAYER_DESC, false));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(noSprite);
        code.add(new InsnNode(Opcodes.POP));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, LAYER, "OPAQUE", LAYER_DESC));
        code.add(new InsnNode(Opcodes.ARETURN));
        getLayer.maxStack = 2;
        getLayer.maxLocals = 1;
        cn.methods.add(getLayer);
        return true;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
