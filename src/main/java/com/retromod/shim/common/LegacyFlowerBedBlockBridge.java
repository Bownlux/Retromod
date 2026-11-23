/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/** Keeps old flower beds constructible on 26.3, whose FlowerBedBlock constructor is protected and takes a shape height. */
public final class LegacyFlowerBedBlockBridge {

    public static final String INTERNAL = "com/retromod/generated/LegacyFlowerBedBlock";

    static final String FLOWER_BED = "net/minecraft/world/level/block/FlowerBedBlock";
    static final String PROPS = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    static final String LEGACY_CTOR = "(L" + PROPS + ";)V";
    static final String MODERN_CTOR = "(L" + PROPS + ";I)V";
    static final String FACTORY_DESC = "(L" + PROPS + ";)L" + FLOWER_BED + ";";

    /** The height in pixels of the old flower bed shape, which 26.3 passes for its own flower beds too. */
    static final int OLD_SHAPE_HEIGHT = 3;

    private LegacyFlowerBedBlockBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostDroppedIt(host.apply(FLOWER_BED)) || t.getSyntheticClasses().containsKey(INTERNAL)) return;
        t.registerSyntheticClass(INTERNAL, generate());
        // Subclasses keep their super(props) call; new FlowerBedBlock(props) becomes a factory call.
        t.registerSuperclassRebase(FLOWER_BED, INTERNAL);
        t.registerConstructorRedirect(FLOWER_BED, LEGACY_CTOR, INTERNAL, "create", FACTORY_DESC);
    }

    /** Whether the host lacks the old one-argument constructor; offline or unreadable, a target from 26.3. */
    static boolean hostDroppedIt(ClassNode flowerBed) {
        if (flowerBed == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        for (MethodNode method : flowerBed.methods) {
            if (method.name.equals("<init>") && method.desc.equals(LEGACY_CTOR)) return false;
        }
        return true;
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, INTERNAL, null, FLOWER_BED, null);
        constructor(cw, LEGACY_CTOR, false);
        // A subclass written for 26.3 passes its own height; it reaches the host constructor unchanged.
        constructor(cw, MODERN_CTOR, true);

        MethodVisitor create = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create", FACTORY_DESC, null, null);
        create.visitCode();
        create.visitTypeInsn(NEW, INTERNAL);
        create.visitInsn(DUP);
        create.visitVarInsn(ALOAD, 0);
        create.visitMethodInsn(INVOKESPECIAL, INTERNAL, "<init>", LEGACY_CTOR, false);
        create.visitInsn(ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void constructor(ClassWriter cw, String desc, boolean passesHeight) {
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", desc, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        if (passesHeight) {
            init.visitVarInsn(ILOAD, 2);
        } else {
            init.visitLdcInsn(OLD_SHAPE_HEIGHT);
        }
        init.visitMethodInsn(INVOKESPECIAL, FLOWER_BED, "<init>", MODERN_CTOR, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }
}
