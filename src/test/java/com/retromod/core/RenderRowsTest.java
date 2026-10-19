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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every batch R1 row and old color callback, written as an old mod compiled it, links on 26.x. */
class RenderRowsTest {

    private static final String API = "net/fabricmc/fabric/api/";
    private static final String STATIC_LAYERS = API + "client/rendering/v1/BlockRenderLayerMap";
    private static final String BLOCK_COLOR = "net/minecraft/class_322";
    private static final String ITEM_COLOR = "net/minecraft/class_326";
    /** Old callback types with their intermediary method, as a 1.20.1 or 1.21.1 mod names them. */
    private static final Map<String, String> CALLBACKS = Map.of(
            BLOCK_COLOR, "getColor(Lnet/minecraft/class_2680;Lnet/minecraft/class_1920;Lnet/minecraft/class_2338;I)I",
            ITEM_COLOR, "getColor(Lnet/minecraft/class_1799;I)I");
    /** Minecraft types 26.1 removed, which no translated class may still name. */
    private static final Set<String> REMOVED_MC = Set.of("net/minecraft/client/color/block/BlockColor",
            "net/minecraft/client/color/item/ItemColor");
    private static final Pattern INTERMEDIARY = Pattern.compile("(class|method|field)_\\d+");
    private static final String SECTION_LAYER = "net/minecraft/client/renderer/chunk/ChunkSectionLayer";
    /**
     * The section layers 1.21.6 to 1.21.11 mods pass to the static form, by intermediary name:
     * SOLID, CUTOUT_MIPPED (gone in 1.21.11), CUTOUT, TRANSLUCENT and TRIPWIRE, in the order Fabric
     * API 0.128.2+1.21.6 maps them to render types in {@code RenderLayerHelper}.
     */
    private static final List<String> OLD_SECTION_LAYERS = List.of("field_60923", "field_60924",
            "field_60925", "field_60926", "field_60927");
    /** The constants {@code ChunkSectionLayer} has in the 26.1.2, 26.2 and 26.3 client jars. */
    private static final Set<String> HOST_SECTION_LAYERS = Set.of("SOLID", "CUTOUT", "TRANSLUCENT");

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private AutoCloseable runtime;
    private String savedHost;

    @AfterEach
    void tearDown() throws Exception {
        if (runtime != null) runtime.close();
        transformer.clearRedirectsForTesting();
        if (savedHost != null) RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    // Shapes are calls, field reads, type uses, implementing classes, lambdas and mod interfaces
    // extending the old callbacks, with calls through those subtypes; R1 has no injected
    // interfaces. Each class then loads on the fake host, so the JVM verifies it, and every shared
    // callback method it implements runs.
    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"26.1", "26.3", "26.4"})
    @DisplayName("Every R1 shape translates to verifiable code that links on the host")
    void everyShapeLinks(String host) throws Exception {
        prepare(host);
        HostSurface surface = HostSurface.forHost(host);
        List<String> problems = new ArrayList<>();
        Map<String, byte[]> translated = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> fixture : fixtures(rows()).entrySet()) {
            byte[] out = transformer.transformClass(fixture.getValue(), fixture.getKey());
            problems.addAll(check(fixture.getKey(), out, surface));
            translated.put(fixture.getKey(), out);
        }
        problems.addAll(NetworkingRowsTest.runOnFakeHost(translated, transformer.getSyntheticClasses()));
        assertEquals(List.of(), problems, "on " + host);
    }

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("The runtime declares every R1 row and both old color callbacks")
    void everyRowIsDeclared(String host) throws Exception {
        prepare(host);
        Set<RedirectKey> declared = new HashSet<>();
        LoaderApiRuntime.ledgers(transformer).forEach(ledger -> declared.addAll(ledger.declared()));
        List<String> undeclared = new ArrayList<>();
        for (StatusRow row : rows()) {
            if (!declared.contains(RedirectKey.type(row.owner()))) undeclared.add(row.key());
        }
        for (String callback : List.of("net/minecraft/client/color/block/BlockColor",
                "net/minecraft/client/color/item/ItemColor")) {
            if (!declared.contains(RedirectKey.type(callback))) undeclared.add(callback);
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
            if ("R1".equals(row.batch())) rows.add(row);
        }
        return rows;
    }

    // ---- Fixtures ---------------------------------------------------------------------------

    /** One small class per shape, keyed by its internal name. */
    static Map<String, byte[]> fixtures(List<StatusRow> rows) {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        int n = 0;
        Set<String> owners = new TreeSet<>();
        for (StatusRow row : rows) {
            String name = "fixture/r1/Shape" + (n++);
            switch (row.kind()) {
                case "C" -> classes.put(name, typeUse(name, row.owner()));
                case "F" -> classes.put(name, fieldRead(name, row.owner(), row.name(), row.oldDescriptor()));
                default -> classes.put(name, call(name, row.owner(), row.name(), row.oldDescriptor()));
            }
            if ("M".equals(row.kind()) && !row.owner().equals(STATIC_LAYERS)) owners.add(row.owner());
        }
        // The static form with each section layer those versions had, some of which 26.x removed.
        for (String layer : OLD_SECTION_LAYERS) {
            String name = "fixture/r1/Layer" + (n++);
            classes.put(name, staticLayerPut(name, layer));
        }
        // A mod class that implements an old registry interface itself, with its typed methods.
        for (String owner : owners) {
            List<String> methods = new ArrayList<>();
            for (StatusRow row : rows) {
                if (row.owner().equals(owner) && "M".equals(row.kind())) methods.add(row.name() + row.oldDescriptor());
            }
            String name = "fixture/r1/Impl" + (n++);
            classes.put(name, override(name, owner, methods));
        }
        // Each old color callback: an override, a lambda, a typed call on the old type, a mod
        // interface that extends it with its own lambda and override, and calls through both subtypes.
        for (Map.Entry<String, String> callback : CALLBACKS.entrySet()) {
            String iface = callback.getKey();
            String sam = callback.getValue().substring(0, callback.getValue().indexOf('('));
            String desc = callback.getValue().substring(callback.getValue().indexOf('('));
            String name = "fixture/r1/Color" + (n++);
            classes.put(name + "Override", override(name + "Override", iface, List.of(sam + desc)));
            classes.put(name + "Lambda", lambda(name + "Lambda", iface, sam, desc));
            classes.put(name + "Call", call(name + "Call", iface, sam, desc));
            classes.put(name + "Sub", subInterface(name + "Sub", iface));
            classes.put(name + "SubLambda", lambda(name + "SubLambda", name + "Sub", sam, desc));
            classes.put(name + "SubOverride", override(name + "SubOverride", name + "Sub", List.of(sam + desc)));
            classes.put(name + "ViaSub", call(name + "ViaSub", name + "Sub", sam, desc));
            classes.put(name + "ViaClass", virtualCall(name + "ViaClass", name + "Override", sam, desc));
        }
        return classes;
    }

    /** A static method whose parameters feed one call to {@code owner.name(desc)}. */
    private static byte[] call(String className, String owner, String name, String desc) {
        boolean isStatic = owner.equals(STATIC_LAYERS);
        return invoke(className, owner, name, desc, isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEINTERFACE,
                !isStatic);
    }

    /** A call to {@code name} on a mod class, as javac names a call on that class's own type. */
    private static byte[] virtualCall(String className, String owner, String name, String desc) {
        return invoke(className, owner, name, desc, Opcodes.INVOKEVIRTUAL, false);
    }

    private static byte[] invoke(String className, String owner, String name, String desc, int opcode,
            boolean isInterface) {
        List<Type> own = new ArrayList<>();
        if (opcode != Opcodes.INVOKESTATIC) own.add(Type.getObjectType(owner));
        own.addAll(List.of(Type.getArgumentTypes(desc)));
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
        mv.visitMethodInsn(opcode, owner, name, desc, isInterface);
        mv.visitInsn(result.getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A static method that reads one static field. */
    private static byte[] fieldRead(String className, String owner, String name, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "()" + desc, null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, name, desc);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code putBlock(block, class_11515.<layer>)}, as a 1.21.6 to 1.21.11 mod calls it. */
    private static byte[] staticLayerPut(String className, String layer) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call",
                "(Lnet/minecraft/class_2248;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/class_11515", layer, "Lnet/minecraft/class_11515;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, STATIC_LAYERS, "putBlock",
                "(Lnet/minecraft/class_2248;Lnet/minecraft/class_11515;)V", false);
        mv.visitInsn(Opcodes.RETURN);
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

    /** A class that implements {@code iface} with each typed method, returning zero or null. */
    private static byte[] override(String className, String iface, List<String> methods) {
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
        for (String method : methods) {
            String desc = method.substring(method.indexOf('('));
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method.substring(0, method.indexOf('(')),
                    desc, null, null);
            mv.visitCode();
            returnZero(mv, Type.getReturnType(desc));
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
        returnZero(body, Type.getReturnType(samDesc));
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

    private static void returnZero(MethodVisitor mv, Type type) {
        switch (type.getSort()) {
            case Type.VOID -> mv.visitInsn(Opcodes.RETURN);
            case Type.OBJECT, Type.ARRAY -> {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
            }
            default -> {
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
            }
        }
    }

    // ---- Checks -----------------------------------------------------------------------------

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
                    if (field.owner.startsWith("com/retromod/") && !retromodFieldExists(field.owner, field.name)) {
                        problems.add(name + ": reads " + field.owner + "." + field.name + ", which Retromod lacks");
                    }
                    if (field.owner.equals(SECTION_LAYER) && !HOST_SECTION_LAYERS.contains(field.name)) {
                        problems.add(name + ": reads ChunkSectionLayer." + field.name + ", which 26.x lacks");
                    }
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
            if (REMOVED_MC.contains(type)) problems.add(name + ": names " + type + ", which 26.1 removed");
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

    private boolean retromodFieldExists(String owner, String name) {
        ClassNode node = classNode(owner);
        return node != null && node.fields.stream().anyMatch(field -> field.name.equals(name));
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
