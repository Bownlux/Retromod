/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

/**
 * Small stand-ins for the Minecraft classes a generated helper calls, so a test can run the
 * helper instead of only reading its bytecode. Everything is defined in this isolated loader,
 * never on the test classpath, where a fake Minecraft class would change what host probes see.
 *
 * <p>Any other Minecraft class a signature names, such as a parameter type a test passes as
 * {@code null}, is defined as an empty class on first use.
 */
final class HostStubs extends ClassLoader {

    private final Map<String, byte[]> classes = new HashMap<>();
    private final Map<String, Class<?>> defined = new HashMap<>();

    HostStubs() {
        super(HostStubs.class.getClassLoader());
    }

    HostStubs add(byte[] bytes) {
        return add(new ClassReader(bytes).getClassName(), bytes);
    }

    HostStubs add(String internalName, byte[] bytes) {
        classes.put(internalName.replace('/', '.'), bytes);
        return this;
    }

    Class<?> load(String internalName) throws ClassNotFoundException {
        return loadClass(internalName.replace('/', '.'));
    }

    /** The first public method of that name on the loaded class. */
    Method method(String internalName, String name) throws ClassNotFoundException {
        for (Method method : load(internalName).getMethods()) {
            if (method.getName().equals(name)) return method;
        }
        throw new AssertionError("no " + name + " on " + internalName);
    }

    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        byte[] bytes = classes.get(name);
        if (bytes == null && name.startsWith("net.minecraft.")) {
            bytes = plainClass(name.replace('.', '/'), "java/lang/Object");
            classes.put(name, bytes);
        }
        if (bytes == null) return super.loadClass(name, resolve);
        Class<?> type = defined.get(name);
        if (type == null) {
            type = defineClass(name, bytes, 0, bytes.length);
            defined.put(name, type);
        }
        return type;
    }

    static byte[] iface(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A public class with a public no-argument constructor. */
    static byte[] plainClass(String name, String superName, String... interfaces) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, interfaces);
        defaultConstructor(cw, superName);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class with a {@code DEFAULT} instance, like {@code SwingAnimation} or {@code TooltipDisplay}. */
    static byte[] singleton(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "DEFAULT", "L" + name + ";", null, null).visitEnd();
        defaultConstructor(cw, "java/lang/Object");
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, name);
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, name, "<init>", "()V", false);
        clinit.visitFieldInsn(PUTSTATIC, name, "DEFAULT", "L" + name + ";");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A class whose one instance method stores its boxed arguments in the public {@code call}
     * field and returns {@code true}, itself (as a builder does), {@code null}, or nothing, as
     * its descriptor asks.
     */
    static byte[] recorder(String name, String method, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC, "call", "[Ljava/lang/Object;", null, null).visitEnd();
        defaultConstructor(cw, "java/lang/Object");
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, method, desc, null, null);
        mv.visitCode();
        Type[] args = Type.getArgumentTypes(desc);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(args.length);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        int local = 1;
        for (int i = 0; i < args.length; i++) {
            mv.visitInsn(DUP);
            mv.visitLdcInsn(i);
            if (args[i].getSort() == Type.BOOLEAN) {
                mv.visitVarInsn(ILOAD, local);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
            } else {
                mv.visitVarInsn(ALOAD, local);
            }
            mv.visitInsn(AASTORE);
            local += args[i].getSize();
        }
        mv.visitFieldInsn(PUTFIELD, name, "call", "[Ljava/lang/Object;");
        Type result = Type.getReturnType(desc);
        if (result.getSort() == Type.BOOLEAN) {
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IRETURN);
        } else if (result.getSort() == Type.VOID) {
            mv.visitInsn(RETURN);
        } else {
            if (result.getInternalName().equals(name)) {
                mv.visitVarInsn(ALOAD, 0);
            } else {
                mv.visitInsn(ACONST_NULL);
            }
            mv.visitInsn(ARETURN);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    static void defaultConstructor(ClassWriter cw, String superName) {
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }
}
