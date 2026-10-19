/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.LoaderApiContext;
import com.retromod.loaderapi.LoaderApiRuntime;
import com.retromod.loaderapi.LoaderApiTestSupport;
import com.retromod.loaderapi.coverage.LoaderApiCoverage;
import com.retromod.loaderapi.coverage.LoaderApiCoverage.StatusRow;
import com.retromod.loaderapi.fabric.v26.HostSurface;
import com.retromod.loaderapi.fabric.v26.net.FakeHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every batch N1 row, written the way an old mod compiled it, links and verifies on 26.x. */
class NetworkingRowsTest {

    private static final String API = "net/fabricmc/fabric/api/";
    private static final Set<String> STATIC_OWNERS = Set.of(
            API + "networking/v1/ServerPlayNetworking",
            API + "client/networking/v1/ClientPlayNetworking",
            API + "networking/v1/ServerConfigurationNetworking",
            API + "client/networking/v1/ClientConfigurationNetworking");
    private static final String PACKET_TYPE = API + "networking/v1/PacketType";
    private static final String SEND_LISTENER = "net/minecraft/class_7648";
    private static final Pattern INTERMEDIARY = Pattern.compile("(class|method|field)_\\d+");

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private AutoCloseable runtime;
    private String savedHost;

    @AfterEach
    void tearDown() throws Exception {
        if (runtime != null) runtime.close();
        transformer.clearRedirectsForTesting();
        if (savedHost != null) RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    // Shapes are calls (also through a subtype owner), lambdas, overrides, type uses and subtypes
    // of the old handler types; N1 has no field rows or injected interfaces. Each class then loads
    // on the fake host, so the JVM verifies it, and each shared handler method it implements runs.
    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"26.1", "26.3", "26.4"})
    @DisplayName("Every N1 shape translates to verifiable code that links on the host")
    void everyShapeLinks(String host) throws Exception {
        prepare(host);
        HostSurface surface = HostSurface.forHost(host);
        Map<String, byte[]> fixtures = fixtures(rows());
        List<String> problems = new ArrayList<>();
        Map<String, byte[]> translated = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> fixture : fixtures.entrySet()) {
            byte[] out = transformer.transformClass(fixture.getValue(), fixture.getKey());
            problems.addAll(check(fixture.getKey(), out, surface));
            translated.put(fixture.getKey(), out);
        }
        problems.addAll(runOnFakeHost(translated, transformer.getSyntheticClasses()));
        assertEquals(List.of(), problems, "on " + host);
    }

    @Test
    @DisplayName("Loading on the fake host catches a helper result that reaches a class type without a cast")
    void hostRunCatchesAMissingCast() throws Exception {
        String name = "fixture/n1/NoCast";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "listener",
                "(Ljava/lang/Runnable;)Lnet/minecraft/network/PacketSendListener;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/retromod/loaderapi/fabric/v26/net/SendListeners",
                "thenRun", "(Ljava/lang/Runnable;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        byte[] missingCast = cw.toByteArray();

        new ClassReader(missingCast).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        List<String> problems = runOnFakeHost(Map.of(name, missingCast), Map.of());
        assertEquals(1, problems.size(), "CheckClassAdapter passes this class, so only loading it can tell");
        assertTrue(problems.get(0).contains("VerifyError"), problems.get(0));
    }

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("The runtime declares every N1 row it serves")
    void everyRowIsDeclared(String host) throws Exception {
        prepare(host);
        Set<RedirectKey> declared = new HashSet<>();
        LoaderApiRuntime.ledgers(transformer).forEach(ledger -> declared.addAll(ledger.declared()));
        Map<String, String> classes = transformer.getClassRedirects();
        List<String> undeclared = new ArrayList<>();
        for (StatusRow row : rows()) {
            boolean owned = declared.contains(RedirectKey.type(row.owner()));
            if ("M".equals(row.kind())) {
                String desc = finalDescriptor(row.oldDescriptor(), classes);
                owned |= declared.contains(RedirectKey.method(row.owner(), row.name(), desc));
            }
            if (!owned) undeclared.add(row.key());
        }
        assertEquals(List.of(), undeclared, "rows the runtime does not own on " + host);
    }

    // ---- Setup ------------------------------------------------------------------------------

    private void prepare(String host) {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        runtime = LoaderApiTestSupport.runtimeOn(LoaderApiTestSupport.fabricProvider());
        RetromodPreLaunch.registerShimsForTransform(host);
        LoaderApiRuntime.prepare(transformer, new LoaderApiContext("fabric", host,
                LoaderApiContext.TransformPath.TEST, null));
    }

    static List<StatusRow> rows() throws IOException {
        List<StatusRow> rows = new ArrayList<>();
        for (StatusRow row : LoaderApiCoverage.readStatus(
                Path.of("docs-dev/loader-api/fabric-26x-status.tsv"))) {
            if ("N1".equals(row.batch())) rows.add(row);
        }
        return rows;
    }

    private static String finalDescriptor(String desc, Map<String, String> classes) {
        return Type.getMethodDescriptor(mapType(Type.getReturnType(desc), classes),
                java.util.Arrays.stream(Type.getArgumentTypes(desc))
                        .map(type -> mapType(type, classes)).toArray(Type[]::new));
    }

    private static Type mapType(Type type, Map<String, String> classes) {
        if (type.getSort() != Type.OBJECT) return type;
        String name = type.getInternalName();
        for (int hops = 0; hops < 8 && classes.containsKey(name); hops++) name = classes.get(name);
        return Type.getObjectType(name);
    }

    // ---- Fixtures ---------------------------------------------------------------------------

    /** One small class per shape, keyed by its internal name. */
    static Map<String, byte[]> fixtures(List<StatusRow> rows) {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        Map<String, String> samOf = new LinkedHashMap<>();
        for (StatusRow row : rows) {
            if ("M".equals(row.kind()) && isSam(row)) samOf.put(row.owner(), row.name() + row.oldDescriptor());
        }
        int n = 0;
        for (StatusRow row : rows) {
            String name = "fixture/n1/Shape" + (n++);
            if ("C".equals(row.kind())) {
                classes.put(name, typeUse(name, row.owner()));
            } else {
                classes.put(name, call(name, row.owner(), row.name(), row.oldDescriptor()));
            }
        }
        // Each old interface method a mod implements: an override, a lambda, and both through a
        // mod interface that extends the old one.
        for (Map.Entry<String, String> sam : samOf.entrySet()) {
            String name = "fixture/n1/Sam" + (n++);
            String samName = sam.getValue().substring(0, sam.getValue().indexOf('('));
            String samDesc = sam.getValue().substring(sam.getValue().indexOf('('));
            classes.put(name + "Override", override(name + "Override", sam.getKey(), samName, samDesc));
            if (sam.getKey().endsWith("/FabricPacket")) continue;
            classes.put(name + "Lambda", lambda(name + "Lambda", sam.getKey(), samName, samDesc));
            classes.put(name + "Sub", subInterface(name + "Sub", sam.getKey()));
            classes.put(name + "SubLambda", lambda(name + "SubLambda", name + "Sub", samName, samDesc));
            classes.put(name + "SubOverride", override(name + "SubOverride", name + "Sub", samName, samDesc));
        }
        // A call through each subtype owner a row lists, as javac names it for a subtype receiver.
        for (StatusRow row : rows) {
            if (!"M".equals(row.kind())) continue;
            for (String owner : row.callSiteOwners()) {
                String name = "fixture/n1/Via" + (n++);
                classes.put(name, call(name, owner, row.name(), row.oldDescriptor()));
            }
        }
        // One class that implements the server and the client typed handler.
        classes.put("fixture/n1/DualPacketHandler", dualPacketHandler(samOf));
        // The old listener factories the PacketSender listener rows rely on.
        classes.put("fixture/n1/ThenRun", call("fixture/n1/ThenRun", SEND_LISTENER, "method_45084",
                "(Ljava/lang/Runnable;)L" + SEND_LISTENER + ";"));
        classes.put("fixture/n1/ExceptionallySend", call("fixture/n1/ExceptionallySend", SEND_LISTENER,
                "method_45085", "(Ljava/util/function/Supplier;)L" + SEND_LISTENER + ";"));
        return classes;
    }

    private static boolean isSam(StatusRow row) {
        return row.owner().contains("$") && row.name().equals("receive")
                || row.owner().endsWith("/FabricPacket") && row.name().equals("write");
    }

    /** A static method whose parameters feed one call to {@code owner.name(desc)}. */
    static byte[] call(String className, String owner, String name, String desc) {
        boolean isStatic = STATIC_OWNERS.contains(owner)
                || owner.equals(PACKET_TYPE) && name.equals("create")
                || owner.equals(SEND_LISTENER);
        boolean isInterface = !STATIC_OWNERS.contains(owner) && !owner.equals(PACKET_TYPE);
        Type[] params = Type.getArgumentTypes(desc);
        List<Type> own = new ArrayList<>();
        if (!isStatic) own.add(Type.getObjectType(owner));
        own.addAll(List.of(params));
        Type result = Type.getReturnType(desc);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call",
                Type.getMethodDescriptor(result, own.toArray(Type[]::new)), null, null);
        mv.visitCode();
        int slot = 0;
        for (Type type : own) {
            mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), slot);
            slot += type.getSize();
        }
        int opcode = isStatic ? Opcodes.INVOKESTATIC : isInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
        mv.visitMethodInsn(opcode, owner, name, desc, isInterface);
        mv.visitInsn(result.getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A field, a parameter and a checkcast of {@code type}. */
    private static byte[] typeUse(String className, String type) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "L" + type + ";", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "cast",
                "(Ljava/lang/Object;)L" + type + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, className, "value", "L" + type + ";");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class that implements {@code iface} with the old typed method. */
    private static byte[] override(String className, String iface, String sam, String samDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object",
                new String[]{iface});
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, sam, samDesc, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        if (iface.endsWith("/FabricPacket")) {
            MethodVisitor type = cw.visitMethod(Opcodes.ACC_PUBLIC, "getType", "()L" + PACKET_TYPE + ";", null, null);
            type.visitCode();
            type.visitInsn(Opcodes.ACONST_NULL);
            type.visitInsn(Opcodes.ARETURN);
            type.visitMaxs(0, 0);
            type.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class that implements both old typed play handlers, each with its own typed method. */
    private static byte[] dualPacketHandler(Map<String, String> samOf) {
        String server = API + "networking/v1/ServerPlayNetworking$PlayPacketHandler";
        String client = API + "client/networking/v1/ClientPlayNetworking$PlayPacketHandler";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "fixture/n1/DualPacketHandler", null,
                "java/lang/Object", new String[]{server, client});
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        for (String iface : List.of(server, client)) {
            String sam = samOf.get(iface);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "receive", sam.substring(sam.indexOf('(')),
                    null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A static method returning a lambda that implements {@code iface} with a static body. */
    private static byte[] lambda(String className, String iface, String sam, String samDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make",
                "()L" + iface + ";", null, null);
        mv.visitCode();
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
                + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        mv.visitInvokeDynamicInsn(sam, "()L" + iface + ";", metafactory, Type.getType(samDesc),
                new Handle(Opcodes.H_INVOKESTATIC, className, "body", samDesc, false), Type.getType(samDesc));
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        MethodVisitor body = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "body", samDesc, null, null);
        body.visitCode();
        body.visitInsn(Opcodes.RETURN);
        body.visitMaxs(0, 0);
        body.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mod interface that only extends {@code iface}. */
    private static byte[] subInterface(String className, String iface) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, className, null,
                "java/lang/Object", new String[]{iface});
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ---- Checks -----------------------------------------------------------------------------

    /**
     * Loads each class on the fake 26.x host, so the JVM verifier checks every assignment against
     * the host's real class and interface kinds, which {@link CheckClassAdapter} does not. Each
     * lambda fixture is built, and every shared handler method a built or constructed object has is
     * called with empty arguments, so a conflicting or missing bridge fails here.
     */
    static List<String> runOnFakeHost(Map<String, byte[]> classes, Map<String, byte[]> synthetics)
            throws IOException {
        Map<String, byte[]> all = new HashMap<>(synthetics);
        all.putAll(classes);
        List<String> problems = new ArrayList<>();
        try (FakeHost fake = FakeHost.start()) {
            ClassLoader mod = fake.defineMod(all);
            for (String name : classes.keySet()) {
                try {
                    Class<?> type = Class.forName(name.replace('/', '.'), true, mod);
                    Object instance = instanceOf(type);
                    if (instance != null) callSharedMethods(instance);
                } catch (InvocationTargetException e) {
                    problems.add(name + ": " + e.getCause());
                } catch (ReflectiveOperationException | LinkageError e) {
                    problems.add(name + ": " + e);
                }
            }
        }
        return problems;
    }

    /** The lambda a fixture's {@code make()} builds, a new instance of a handler class, or null. */
    private static Object instanceOf(Class<?> type) throws ReflectiveOperationException {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals("make") && method.getParameterCount() == 0) return method.invoke(null);
        }
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers()) || sharedTypesOf(type).isEmpty()) {
            return null;
        }
        return type.getConstructor().newInstance();
    }

    /** Calls every abstract method of each shared type {@code instance} implements. */
    private static void callSharedMethods(Object instance) throws ReflectiveOperationException {
        for (Class<?> shared : sharedTypesOf(instance.getClass())) {
            for (Method method : shared.getMethods()) {
                if (!Modifier.isAbstract(method.getModifiers())) continue;
                Object[] args = new Object[method.getParameterCount()];
                Class<?>[] params = method.getParameterTypes();
                for (int i = 0; i < args.length; i++) {
                    args[i] = params[i] == int.class ? (Object) 0 : params[i] == boolean.class ? (Object) false : null;
                }
                method.invoke(instance, args);
            }
        }
    }

    private static Set<Class<?>> sharedTypesOf(Class<?> type) {
        Set<Class<?>> shared = new LinkedHashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            pending.addAll(List.of(current.getInterfaces()));
        }
        while (!pending.isEmpty()) {
            Class<?> iface = pending.pop();
            if (LoaderApiRuntime.isSharedTypeName(iface.getName().replace('.', '/'))) shared.add(iface);
            pending.addAll(List.of(iface.getInterfaces()));
        }
        return shared;
    }

    /** Problems with one translated class: verifier errors, stale names and members the host lacks. */
    private List<String> check(String name, byte[] out, HostSurface surface) {
        List<String> problems = new ArrayList<>();
        try {
            new ClassReader(out).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        } catch (RuntimeException e) {
            problems.add(name + ": does not verify: " + e.getMessage());
            return problems;
        }
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);
        Set<String> types = new TreeSet<>();
        if (node.superName != null) types.add(node.superName);
        types.addAll(node.interfaces);
        for (FieldNode field : node.fields) collectTypes(Type.getType(field.desc), types);
        for (MethodNode method : node.methods) {
            collectTypes(Type.getMethodType(method.desc), types);
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    types.add(call.owner);
                    collectTypes(Type.getMethodType(call.desc), types);
                    String missing = missingMember(call.owner, call.name, call.desc, surface);
                    if (missing != null) problems.add(name + ": " + missing);
                } else if (insn instanceof FieldInsnNode field) {
                    types.add(field.owner);
                    collectTypes(Type.getType(field.desc), types);
                } else if (insn instanceof TypeInsnNode type) {
                    collectTypes(Type.getObjectType(type.desc), types);
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    collectTypes(Type.getMethodType(indy.desc), types);
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Type type) collectTypes(type, types);
                        if (arg instanceof Handle handle) types.add(handle.getOwner());
                    }
                }
            }
        }
        for (String type : types) {
            if (INTERMEDIARY.matcher(type).find()) problems.add(name + ": keeps intermediary " + type);
            if (type.startsWith("net/fabricmc/fabric/") && !surface.hasClass(type)) {
                problems.add(name + ": names " + type + ", which the host lacks");
            }
            if (type.startsWith("com/retromod/") && !retromodClassExists(type)) {
                problems.add(name + ": names " + type + ", which Retromod does not ship");
            }
        }
        return problems;
    }

    private static void collectTypes(Type type, Set<String> into) {
        switch (type.getSort()) {
            case Type.OBJECT -> into.add(type.getInternalName());
            case Type.ARRAY -> collectTypes(type.getElementType(), into);
            case Type.METHOD -> {
                collectTypes(type.getReturnType(), into);
                for (Type arg : type.getArgumentTypes()) collectTypes(arg, into);
            }
            default -> { }
        }
    }

    /** Why a call does not link, or null when it does. */
    private String missingMember(String owner, String name, String desc, HostSurface surface) {
        if (owner.startsWith("net/fabricmc/fabric/")) {
            return surface.member(owner, name, desc) == HostSurface.Presence.MISSING
                    ? "calls " + owner + "." + name + desc + ", which the host lacks" : null;
        }
        if (owner.startsWith("com/retromod/")) {
            return retromodMethodExists(owner, name, desc) ? null
                    : "calls " + owner + "." + name + desc + ", which Retromod does not ship";
        }
        return null;
    }

    private boolean retromodClassExists(String type) {
        return transformer.getSyntheticClasses().containsKey(type) || classNode(type) != null;
    }

    /** Whether a compiled or synthetic Retromod class or one of its supertypes declares the method. */
    private boolean retromodMethodExists(String owner, String name, String desc) {
        ClassNode node = classNode(owner);
        if (node == null) return false;
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        for (String iface : node.interfaces) {
            if (iface.startsWith("com/retromod/") && retromodMethodExists(iface, name, desc)) return true;
        }
        return false;
    }

    private ClassNode classNode(String type) {
        byte[] synthetic = transformer.getSyntheticClasses().get(type);
        try (InputStream in = synthetic != null ? new java.io.ByteArrayInputStream(synthetic)
                : getClass().getClassLoader().getResourceAsStream(type + ".class")) {
            if (in == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
            return node;
        } catch (IOException e) {
            return null;
        }
    }
}
