/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.shim.fabric.FabricLegacyRegistrationApiShim;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.objectweb.asm.Opcodes.*;

/**
 * Moonlight registers its own {@code fabric:false} resource condition, which Fabric API for 26.1 and newer ships
 * itself, so Fabric API threw "Duplicate resource condition registered with id fabric:false".
 */
class FabricDuplicateConditionTest {

    private static final String CONDITIONS = "net/fabricmc/fabric/api/resource/conditions/v1/ResourceConditions";
    private static final String TYPE = "net/fabricmc/fabric/api/resource/conditions/v1/ResourceConditionType";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";

    @Test
    @DisplayName("a condition id Fabric API already registered is kept as Fabric API's")
    void fabricOwnedIdIsNotRegisteredTwice() throws Exception {
        assertEquals(0, registrations("fabric", true));
    }

    @Test
    @DisplayName("a mod's own condition, and a fabric id Fabric API lacks, register as before")
    void otherIdsRegister() throws Exception {
        assertEquals(1, registrations("moonlight", true), "a clash outside Fabric API's namespace still reaches Fabric API");
        assertEquals(1, registrations("fabric", false), "an older Fabric API without the id gets the mod's type");
    }

    /** Runs the bridge's register for a type in {@code namespace} and returns how often Fabric API's register ran. */
    private static int registrations(String namespace, boolean fabricHasId) throws Exception {
        HostStubs host = new HostStubs()
                .add(identifier())
                .add(typeInterface())
                .add(conditionType())
                .add(conditions())
                .add(FabricLegacyRegistrationApiShim.CONDITION_LOOKUPS, FabricLegacyRegistrationApiShim.generateConditionLookups());
        Object type = host.load("test/Type").getConstructor(String.class).newInstance(namespace);
        Class<?> conditions = host.load(CONDITIONS);
        if (fabricHasId) conditions.getField("known").set(null, type);
        host.load(FabricLegacyRegistrationApiShim.CONDITION_LOOKUPS).getMethod("register", host.load(TYPE)).invoke(null, type);
        Field count = conditions.getField("registered");
        return count.getInt(null);
    }

    /** {@code class Identifier { String ns; Identifier(String ns); String getNamespace(); }} */
    private static byte[] identifier() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, IDENTIFIER, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC, "ns", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, IDENTIFIER, "ns", "Ljava/lang/String;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor ns = cw.visitMethod(ACC_PUBLIC, "getNamespace", "()Ljava/lang/String;", null, null);
        ns.visitCode();
        ns.visitVarInsn(ALOAD, 0);
        ns.visitFieldInsn(GETFIELD, IDENTIFIER, "ns", "Ljava/lang/String;");
        ns.visitInsn(ARETURN);
        ns.visitMaxs(0, 0);
        ns.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] typeInterface() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, TYPE, null, "java/lang/Object", null);
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "id", "()L" + IDENTIFIER + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A condition type whose id has the namespace it was built with. */
    private static byte[] conditionType() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/Type", null, "java/lang/Object", new String[]{TYPE});
        cw.visitField(ACC_PRIVATE, "id", "L" + IDENTIFIER + ";", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitTypeInsn(NEW, IDENTIFIER);
        init.visitInsn(DUP);
        init.visitVarInsn(ALOAD, 1);
        init.visitMethodInsn(INVOKESPECIAL, IDENTIFIER, "<init>", "(Ljava/lang/String;)V", false);
        init.visitFieldInsn(PUTFIELD, "test/Type", "id", "L" + IDENTIFIER + ";");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor id = cw.visitMethod(ACC_PUBLIC, "id", "()L" + IDENTIFIER + ";", null, null);
        id.visitCode();
        id.visitVarInsn(ALOAD, 0);
        id.visitFieldInsn(GETFIELD, "test/Type", "id", "L" + IDENTIFIER + ";");
        id.visitInsn(ARETURN);
        id.visitMaxs(0, 0);
        id.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Fabric API's registry: {@code getConditionType} answers {@code known}, and {@code register} counts its calls. */
    private static byte[] conditions() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, CONDITIONS, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "known", "L" + TYPE + ";", null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "registered", "I", null, null).visitEnd();
        MethodVisitor get = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getConditionType",
                "(L" + IDENTIFIER + ";)L" + TYPE + ";", null, null);
        get.visitCode();
        get.visitFieldInsn(GETSTATIC, CONDITIONS, "known", "L" + TYPE + ";");
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        MethodVisitor register = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", "(L" + TYPE + ";)V", null, null);
        register.visitCode();
        register.visitFieldInsn(GETSTATIC, CONDITIONS, "registered", "I");
        register.visitInsn(ICONST_1);
        register.visitInsn(IADD);
        register.visitFieldInsn(PUTSTATIC, CONDITIONS, "registered", "I");
        register.visitInsn(RETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
