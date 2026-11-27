/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/** Wraps a Mojang-named mod's pack supplier lambda, which implements 1.20.1's {@code open(String)}, in a supplier of the host's shape. */
public final class LegacyPackSupplierLambdas {

    static final String SUPPLIER = "net/minecraft/server/packs/repository/Pack$ResourcesSupplier";
    static final String LOCATION = "net/minecraft/server/packs/PackLocationInfo";
    static final String RESOURCES = "net/minecraft/server/packs/PackResources";
    static final String OLD_OPEN = "(Ljava/lang/String;)L" + RESOURCES + ";";
    static final String OPEN_PRIMARY = "(L" + LOCATION + ";)L" + RESOURCES + ";";
    static final String OPEN_FULL = "(L" + LOCATION + ";Lnet/minecraft/server/packs/repository/Pack$Metadata;)L" + RESOURCES + ";";
    static final String ADAPTER = "com/retromod/generated/LegacyPackResourcesSupplier";
    static final String WRAP_DESC = "(Ljava/util/function/Function;)L" + SUPPLIER + ";";

    /** One host's names for the supplier, its pack location and the two methods that open a pack. */
    public record SupplierNames(String adapter, String supplier, String location, String locationId, String resources,
            String openPrimary, String openPrimaryDesc, String openFull, String openFullDesc) {}

    static final SupplierNames MOJANG = new SupplierNames(ADAPTER, SUPPLIER, LOCATION, "id", RESOURCES,
            "openPrimary", OPEN_PRIMARY, "openFull", OPEN_FULL);

    private LegacyPackSupplierLambdas() {}

    /** Registers where the host's supplier opens packs by location, as 1.20.5 to 26.2 do, instead of by id. */
    public static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode supplier = host.apply(SUPPLIER);
        if (supplier == null || !declares(supplier, "openPrimary", OPEN_PRIMARY) || !declares(supplier, "openFull", OPEN_FULL)
                || declares(supplier, "open", OLD_OPEN)) {
            return;
        }
        if (!t.getSyntheticClasses().containsKey(ADAPTER)) t.registerSyntheticClass(ADAPTER, generateAdapter(MOJANG, true));
        // The lambda is built as a Function of the pack id, and the adapter answers both new methods with it.
        t.registerLambdaAdapter(SUPPLIER, "open", OLD_OPEN, new RetromodTransformer.LambdaAdapter("java/util/function/Function", "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;", ADAPTER, "supplier", WRAP_DESC));
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    /** A supplier that opens the primary and the full pack alike through the old function of the pack id, and its factory when asked. */
    public static byte[] generateAdapter(SupplierNames names, boolean factory) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, names.adapter(), null, "java/lang/Object", new String[]{names.supplier()});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "open", "Ljava/util/function/Function;", null, null).visitEnd();
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/util/function/Function;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitVarInsn(ALOAD, 1);
        ctor.visitFieldInsn(PUTFIELD, names.adapter(), "open", "Ljava/util/function/Function;");
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        if (factory) {
            MethodVisitor wrap = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "supplier",
                    "(Ljava/util/function/Function;)L" + names.supplier() + ";", null, null);
            wrap.visitCode();
            wrap.visitTypeInsn(NEW, names.adapter());
            wrap.visitInsn(DUP);
            wrap.visitVarInsn(ALOAD, 0);
            wrap.visitMethodInsn(INVOKESPECIAL, names.adapter(), "<init>", "(Ljava/util/function/Function;)V", false);
            wrap.visitInsn(ARETURN);
            wrap.visitMaxs(0, 0);
            wrap.visitEnd();
        }
        // Packs written before 1.20.2 carry no overlays, so the full pack opens the same way as the primary one.
        openById(cw, names, names.openPrimary(), names.openPrimaryDesc(), 2);
        openById(cw, names, names.openFull(), names.openFullDesc(), 3);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code open.apply(location.id())}; a missing class or member in the old lambda ends that pack only. */
    private static void openById(ClassWriter cw, SupplierNames names, String name, String desc, int freeLocal) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        Label start = new Label();
        Label end = new Label();
        Label failed = new Label();
        mv.visitCode();
        mv.visitTryCatchBlock(start, end, failed, "java/lang/LinkageError");
        mv.visitLabel(start);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, names.adapter(), "open", "Ljava/util/function/Function;");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, names.location(), names.locationId(), "()Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, names.resources());
        mv.visitLabel(end);
        mv.visitInsn(ARETURN);
        // Pack discovery catches exceptions, not errors, so the host skips this pack with its own warning and goes on.
        mv.visitLabel(failed);
        mv.visitFrame(F_SAME1, 0, null, 1, new Object[]{"java/lang/LinkageError"});
        mv.visitVarInsn(ASTORE, freeLocal);
        mv.visitTypeInsn(NEW, "java/lang/IllegalStateException");
        mv.visitInsn(DUP);
        mv.visitLdcInsn("Could not open the old pack ");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, names.location(), names.locationId(), "()Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ALOAD, freeLocal);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;Ljava/lang/Throwable;)V", false);
        mv.visitInsn(ATHROW);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
