/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps {@code ResourceManager.listResources} and {@code listResourceStacks} calls from older mods
 * working on 26.3.
 *
 * <p>26.3 replaced their {@code Predicate<Identifier>} filter with {@code ResourceManager.Selector},
 * which has the same shape ({@code boolean isIncluded(Identifier)}). An old call failed with
 * {@code NoSuchMethodError} while data packs loaded, and the server stopped with "Failed to load
 * datapacks". The call now goes through a helper that wraps the mod's predicate in a selector.
 */
public final class LegacyResourceSelectorBridge {

    static final String HELPER = "com/retromod/generated/LegacyResourceSelector";
    static final String MANAGER = "net/minecraft/server/packs/resources/ResourceManager";
    static final String SELECTOR = MANAGER + "$Selector";
    static final String OLD_DESC = "(Ljava/lang/String;Ljava/util/function/Predicate;)Ljava/util/Map;";
    private static final String NEW_DESC = "(Ljava/lang/String;L" + SELECTOR + ";)Ljava/util/Map;";
    private static final String HELPER_DESC =
            "(L" + MANAGER + ";Ljava/lang/String;Ljava/util/function/Predicate;)Ljava/util/Map;";
    private static final String PREDICATE = "java/util/function/Predicate";
    static final String[] METHODS = {"listResources", "listResourceStacks"};

    private LegacyResourceSelectorBridge() {}

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(HELPER, generateHelper());
        for (String method : METHODS) {
            transformer.registerMethodRedirect(MANAGER, method, OLD_DESC, HELPER, method, HELPER_DESC);
        }
    }

    /** A selector over the mod's predicate, plus a static stand-in for each old listing method. */
    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object",
                new String[]{SELECTOR});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "predicate", "L" + PREDICATE + ";", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + PREDICATE + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, HELPER, "predicate", "L" + PREDICATE + ";");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor included = cw.visitMethod(ACC_PUBLIC, "isIncluded",
                "(Lnet/minecraft/resources/Identifier;)Z", null, null);
        included.visitCode();
        included.visitVarInsn(ALOAD, 0);
        included.visitFieldInsn(GETFIELD, HELPER, "predicate", "L" + PREDICATE + ";");
        included.visitVarInsn(ALOAD, 1);
        included.visitMethodInsn(INVOKEINTERFACE, PREDICATE, "test", "(Ljava/lang/Object;)Z", true);
        included.visitInsn(IRETURN);
        included.visitMaxs(0, 0);
        included.visitEnd();

        for (String method : METHODS) {
            MethodVisitor list = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, method, HELPER_DESC, null, null);
            list.visitCode();
            list.visitVarInsn(ALOAD, 0);
            list.visitVarInsn(ALOAD, 1);
            list.visitTypeInsn(NEW, HELPER);
            list.visitInsn(DUP);
            list.visitVarInsn(ALOAD, 2);
            list.visitMethodInsn(INVOKESPECIAL, HELPER, "<init>", "(L" + PREDICATE + ";)V", false);
            list.visitMethodInsn(INVOKEINTERFACE, MANAGER, method, NEW_DESC, true);
            list.visitInsn(ARETURN);
            list.visitMaxs(0, 0);
            list.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
