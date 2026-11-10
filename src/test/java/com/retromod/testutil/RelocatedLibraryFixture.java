/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testutil;

import com.retromod.util.HostLibraryNames;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/** Builds a Retromod helper class as the release jar ships it, naming the relocated Gson and SLF4J. */
public final class RelocatedLibraryFixture {

    // Built at run time like the release jar's names. Test classes are never shaded, but this keeps the
    // fixture honest if they ever are.
    public static final String SHADED_ROOT = String.join("/", "com", "retromod", "shaded") + "/";
    public static final String SHADED_SLF4J = SHADED_ROOT + "slf4j/";
    public static final String SHADED_GSON = SHADED_ROOT + "gson/";
    public static final String SHADED_TOML = SHADED_ROOT + "toml4j/";
    public static final String HOST_SLF4J = HostLibraryNames.SLF4J_PACKAGE + "/";
    public static final String HOST_GSON = HostLibraryNames.GSON_PACKAGE + "/";

    /** A Retromod name that is not a library, which must keep its spelling. */
    public static final String OTHER_RETROMOD = "com/retromod/legacyapi/fixture/Other";

    private RelocatedLibraryFixture() {}

    /**
     * A helper whose static {@code warn(String)} logs through {@code slf4j} and whose
     * {@code parse(JsonObject)} reads {@code gson}, naming each library in a field, a generic
     * signature, a call, a constant, and a stack map frame.
     */
    public static byte[] helper(String name, String slf4j, String gson) {
        String logger = slf4j + "Logger";
        String jsonObject = gson + "JsonObject";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, name, null,
                "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "LOG", "L" + logger + ";",
                null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "values", "Ljava/util/List;",
                "Ljava/util/List<L" + jsonObject + ";>;", null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "other", "L" + OTHER_RETROMOD + ";", null, null)
                .visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitLdcInsn("Retromod");
        init.visitMethodInsn(Opcodes.INVOKESTATIC, slf4j + "LoggerFactory", "getLogger",
                "(Ljava/lang/String;)L" + logger + ";", false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, name, "LOG", "L" + logger + ";");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 0);
        init.visitEnd();

        MethodVisitor warn = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "warn",
                "(Ljava/lang/String;)V", null, null);
        warn.visitCode();
        warn.visitFieldInsn(Opcodes.GETSTATIC, name, "LOG", "L" + logger + ";");
        warn.visitVarInsn(Opcodes.ALOAD, 0);
        warn.visitMethodInsn(Opcodes.INVOKEINTERFACE, logger, "warn", "(Ljava/lang/String;)V", true);
        warn.visitInsn(Opcodes.RETURN);
        warn.visitMaxs(2, 1);
        warn.visitEnd();

        MethodVisitor parse = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "parse",
                "(L" + jsonObject + ";)Ljava/lang/Object;", null, null);
        parse.visitCode();
        Label present = new Label();
        parse.visitVarInsn(Opcodes.ALOAD, 0);
        parse.visitJumpInsn(Opcodes.IFNONNULL, present);
        parse.visitInsn(Opcodes.ACONST_NULL);
        parse.visitInsn(Opcodes.ARETURN);
        parse.visitLabel(present);
        parse.visitFrame(Opcodes.F_FULL, 1, new Object[] {jsonObject}, 0, new Object[0]);
        parse.visitLdcInsn(Type.getObjectType(gson + "JsonElement"));
        parse.visitInsn(Opcodes.ARETURN);
        parse.visitMaxs(1, 1);
        parse.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class that names only the given toml4j package, which no game ships. */
    public static byte[] tomlUser(String name, String toml4j) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "toml", "L" + toml4j + "Toml;", null, null)
                .visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Whether any byte of the class file, constant pool included, still names a relocated library. */
    public static boolean namesShadedLibrary(byte[] classBytes) {
        return new String(classBytes, StandardCharsets.ISO_8859_1).contains(SHADED_ROOT);
    }

    /** The class as ASM prints it, for comparing two classes by what they declare and do. */
    public static String text(byte[] classBytes) {
        StringWriter out = new StringWriter();
        new ClassReader(classBytes).accept(new TraceClassVisitor(new PrintWriter(out)), 0);
        return out.toString();
    }

    /**
     * A class whose class loader serves {@code bytes} as the resource {@code name + ".class"}, the way
     * the release jar serves a relocated helper to code that copies it.
     */
    public static Class<?> anchorServing(String name, byte[] bytes) {
        return subclassServing(Object.class, "()V", resource -> resource.equals(name + ".class") ? bytes : null);
    }

    /**
     * A public subclass of {@code parent} whose constructor of {@code constructorDesc} passes its
     * arguments on, defined in a class loader that answers {@code resources} first, so code that
     * reads resources through {@code getClass()} reads them.
     */
    public static Class<?> subclassServing(Class<?> parent, String constructorDesc,
            Function<String, byte[]> resources) {
        String parentName = Type.getInternalName(parent);
        String name = "relocfixture/Serving" + parent.getSimpleName();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, parentName, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", constructorDesc, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        int slot = 1;
        for (Type argument : Type.getArgumentTypes(constructorDesc)) {
            init.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
            slot += argument.getSize();
        }
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, parentName, "<init>", constructorDesc, false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return new ServingLoader(resources).define(name.replace('/', '.'), cw.toByteArray());
    }

    private static final class ServingLoader extends ClassLoader {
        private final Function<String, byte[]> resources;

        ServingLoader(Function<String, byte[]> resources) {
            super(RelocatedLibraryFixture.class.getClassLoader());
            this.resources = resources;
        }

        Class<?> define(String binaryName, byte[] classBytes) {
            return defineClass(binaryName, classBytes, 0, classBytes.length);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            byte[] served = resources.apply(name);
            return served != null ? new ByteArrayInputStream(served) : super.getResourceAsStream(name);
        }
    }
}
