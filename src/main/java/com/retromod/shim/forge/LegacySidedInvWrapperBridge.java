/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.forge.capability.LegacyCapabilitySynthetics;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/** Rebuilds Forge's {@code SidedInvWrapper.create}, which NeoForge dropped with {@code LazyOptional}, on NeoForge hosts. */
public final class LegacySidedInvWrapperBridge {

    static final String HELPER = "com/retromod/generated/LegacySidedInvWrappers";
    static final String CONTAINER = "net/minecraft/world/WorldlyContainer";
    static final String DIRECTION = "net/minecraft/core/Direction";
    static final String LAZY = LegacyCapabilitySynthetics.LAZY_OPTIONAL;
    // NeoForge's wrappers; on a host that removed them the class moves point the helper at the rebuilt ones.
    static final String SIDED = "net/neoforged/neoforge/items/wrapper/SidedInvWrapper";
    static final String CREATE = "(L" + CONTAINER + ";[L" + DIRECTION + ";)[L" + LAZY + ";";
    /** The owners a mod's call can carry: Forge's, NeoForge's, and the rebuilt class on 26.3. */
    static final String[] OWNERS = {"net/minecraftforge/items/wrapper/SidedInvWrapper", SIDED,
            "com/retromod/legacyapi/neoforge/items/wrapper/SidedInvWrapper"};

    private LegacySidedInvWrapperBridge() {}

    /** Registers the rebuilt {@code create}, which every MCreator block entity with an inventory calls in a field initializer. */
    public static void register(RetromodTransformer t) {
        if (!LegacyCapabilitySynthetics.isRegistered(t)) return;
        t.registerSyntheticClass(HELPER, generate());
        for (String owner : OWNERS) t.registerMethodRedirect(owner, "create", CREATE, HELPER, "create", CREATE);
    }

    /** {@code final class LegacySidedInvWrappers implements Supplier}: one side's wrapper, built when the capability is first read. */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object",
                new String[] {"java/util/function/Supplier"});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "inventory", "L" + CONTAINER + ";", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "side", "L" + DIRECTION + ";", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PRIVATE, "<init>", "(L" + CONTAINER + ";L" + DIRECTION + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, HELPER, "inventory", "L" + CONTAINER + ";");
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 2);
        init.visitFieldInsn(PUTFIELD, HELPER, "side", "L" + DIRECTION + ";");
        init.visitInsn(RETURN);
        end(init);

        // get(): the side's wrapper, a null side included, as Forge's create built it.
        MethodVisitor get = cw.visitMethod(ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null);
        get.visitCode();
        get.visitTypeInsn(NEW, SIDED);
        get.visitInsn(DUP);
        get.visitVarInsn(ALOAD, 0);
        get.visitFieldInsn(GETFIELD, HELPER, "inventory", "L" + CONTAINER + ";");
        get.visitVarInsn(ALOAD, 0);
        get.visitFieldInsn(GETFIELD, HELPER, "side", "L" + DIRECTION + ";");
        get.visitMethodInsn(INVOKESPECIAL, SIDED, "<init>", "(L" + CONTAINER + ";L" + DIRECTION + ";)V", false);
        get.visitInsn(ARETURN);
        end(get);

        // create(inventory, sides): a lazy handler per side, in the order given.
        MethodVisitor create = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create", CREATE, null, null);
        create.visitCode();
        create.visitVarInsn(ALOAD, 1);
        create.visitInsn(ARRAYLENGTH);
        create.visitTypeInsn(ANEWARRAY, LAZY);
        create.visitVarInsn(ASTORE, 2);
        create.visitInsn(ICONST_0);
        create.visitVarInsn(ISTORE, 3);
        Label loop = new Label();
        Label done = new Label();
        create.visitLabel(loop);
        create.visitVarInsn(ILOAD, 3);
        create.visitVarInsn(ALOAD, 2);
        create.visitInsn(ARRAYLENGTH);
        create.visitJumpInsn(IF_ICMPGE, done);
        create.visitVarInsn(ALOAD, 2);
        create.visitVarInsn(ILOAD, 3);
        create.visitTypeInsn(NEW, HELPER);
        create.visitInsn(DUP);
        create.visitVarInsn(ALOAD, 0);
        create.visitVarInsn(ALOAD, 1);
        create.visitVarInsn(ILOAD, 3);
        create.visitInsn(AALOAD);
        create.visitMethodInsn(INVOKESPECIAL, HELPER, "<init>", "(L" + CONTAINER + ";L" + DIRECTION + ";)V", false);
        create.visitMethodInsn(INVOKESTATIC, LAZY, "of", "(Ljava/util/function/Supplier;)L" + LAZY + ";", false);
        create.visitInsn(AASTORE);
        create.visitIincInsn(3, 1);
        create.visitJumpInsn(GOTO, loop);
        create.visitLabel(done);
        create.visitVarInsn(ALOAD, 2);
        create.visitInsn(ARETURN);
        end(create);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void end(MethodVisitor mv) {
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
