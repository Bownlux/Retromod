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
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Batches C1 and C2: every old content registry row, HoeItem helper and brewing call links on the host. */
class ContentRowsTest {

    private static final String REGISTRY = "net/fabricmc/fabric/api/registry/";
    private static final String HOE_ITEM = "net/minecraft/class_1794";
    // The removed HoeItem helpers, by intermediary name as a 1.20.1 mod calls them.
    private static final List<String[]> HOE_HELPERS = List.of(
            new String[]{"method_36987", "(Lnet/minecraft/class_1838;)Z"},
            new String[]{"method_36988", "(Lnet/minecraft/class_2680;)Ljava/util/function/Consumer;"},
            new String[]{"method_36985", "(Lnet/minecraft/class_2680;Lnet/minecraft/class_1935;)Ljava/util/function/Consumer;"});
    // Vanilla's brewing builder, whose methods 1.20.5 to 1.21.11 mods call in their BUILD callback.
    private static final String BREWING_BUILDER = "net/minecraft/class_1845$class_9665";
    private static final List<String[]> BREWING_CALLS = List.of(
            new String[]{"method_59705", "(Lnet/minecraft/class_6880;Lnet/minecraft/class_1792;Lnet/minecraft/class_6880;)V"},
            new String[]{"method_59704", "(Lnet/minecraft/class_1792;Lnet/minecraft/class_6880;)V"},
            new String[]{"method_59703", "(Lnet/minecraft/class_1792;Lnet/minecraft/class_1792;Lnet/minecraft/class_1792;)V"},
            new String[]{"method_59702", "(Lnet/minecraft/class_1792;)V"},
            // Fabric API's methods, injected into the builder, so call sites name it.
            new String[]{"registerPotionRecipe", "(Lnet/minecraft/class_6880;Lnet/minecraft/class_1856;Lnet/minecraft/class_6880;)V"});
    private static final Set<String> GONE_ON_26_3 = Set.of(
            "net/minecraft/world/item/HoeItem", "com/retromod/generated/LegacyHoeItem",
            "net/minecraft/world/item/alchemy/PotionBrewing$Builder");

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private AutoCloseable runtime;
    private String savedHost;

    @AfterEach
    void tearDown() throws Exception {
        if (runtime != null) runtime.close();
        transformer.clearRedirectsForTesting();
        if (savedHost != null) RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    // Shapes are calls, field reads, type uses and method references. Each class then loads on
    // the fake host, so the JVM verifies it. On 26.1 the host still serves every class but the
    // old FuelRegistry, so the same shapes must link through the host's own Fabric API there.
    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"26.1", "26.3", "26.4"})
    @DisplayName("Every C1 shape translates to verifiable code that links on the host")
    void everyShapeLinks(String host) throws Exception {
        prepare(host);
        HostSurface surface = HostSurface.forHost(host);
        List<String> problems = new ArrayList<>();
        Map<String, byte[]> translated = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> fixture : fixtures(rows(), host).entrySet()) {
            byte[] out = transformer.transformClass(fixture.getValue(), fixture.getKey());
            problems.addAll(check(fixture.getKey(), out, surface, host));
            translated.put(fixture.getKey(), out);
        }
        problems.addAll(NetworkingRowsTest.runOnFakeHost(translated, transformer.getSyntheticClasses()));
        assertEquals(List.of(), problems, "on " + host);
    }

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"26.3", "26.4"})
    @DisplayName("The runtime declares every C1 row on the hosts that removed them")
    void everyRowIsDeclared(String host) throws Exception {
        prepare(host);
        Set<RedirectKey> declared = new HashSet<>();
        LoaderApiRuntime.ledgers(transformer).forEach(ledger -> declared.addAll(ledger.declared()));
        List<String> undeclared = new ArrayList<>();
        for (StatusRow row : rows()) {
            RedirectKey key = switch (row.kind()) {
                case "C" -> RedirectKey.type(row.owner());
                case "F" -> RedirectKey.field(row.owner(), row.name());
                default -> RedirectKey.method(row.owner(), row.name(), mojang(row.oldDescriptor()));
            };
            if (!declared.contains(key) && !declared.contains(RedirectKey.type(row.owner()))) {
                undeclared.add(row.key());
            }
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
        for (StatusRow row : LoaderApiCoverage.readStatus(Path.of("docs-dev/loader-api/fabric-26x-status.tsv"))) {
            if ("C1".equals(row.batch()) || "C2".equals(row.batch())) rows.add(row);
        }
        return rows;
    }

    /** A descriptor with its intermediary classes named as the 26.x host names them. */
    private static String mojang(String descriptor) {
        return com.retromod.mapping.IntermediaryToMojangMapper.getInstance().remapDescriptor(descriptor);
    }

    // ---- Fixtures ---------------------------------------------------------------------------

    /** One small class per shape, keyed by its internal name. */
    private static Map<String, byte[]> fixtures(List<StatusRow> rows, String host) {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        int n = 0;
        for (StatusRow row : rows) {
            String name = "fixture/c1/Shape" + (n++);
            boolean registryInterface = row.owner().endsWith("ChanceRegistry") || row.owner().endsWith("FuelRegistry")
                    || row.owner().endsWith("RegistryBuilder") || row.owner().endsWith("$BuildCallback");
            switch (row.kind()) {
                case "C" -> classes.put(name, typeUse(name, row.owner()));
                case "F" -> classes.put(name, fieldRead(name, row.owner(), row.name(), row.oldDescriptor()));
                default -> classes.put(name, invoke(name, row.owner(), row.name(), row.oldDescriptor(),
                        registryInterface ? Opcodes.INVOKEINTERFACE
                                : row.owner().endsWith("$Entry") ? Opcodes.INVOKEVIRTUAL : Opcodes.INVOKESTATIC));
            }
        }
        // 26.3 removed HoeItem, whose helpers old mods hand to the tilling registry, usually as a
        // method reference. 26.1 still has HoeItem, so those shapes are left to the host there.
        if (!host.startsWith("26.1")) {
            for (String[] helper : HOE_HELPERS) {
                String name = "fixture/c1/Hoe" + (n++);
                classes.put(name, invoke(name, HOE_ITEM, helper[0], helper[1], Opcodes.INVOKESTATIC));
            }
            classes.put("fixture/c1/HoeReference", hoeReference("fixture/c1/HoeReference"));
            // 26.3 also removed vanilla's brewing builder.
            for (String[] call : BREWING_CALLS) {
                String name = "fixture/c1/Brewing" + (n++);
                classes.put(name, invoke(name, BREWING_BUILDER, call[0], call[1], Opcodes.INVOKEVIRTUAL));
            }
        }
        return classes;
    }

    /** A static method whose parameters feed one call to {@code owner.name(desc)}. */
    private static byte[] invoke(String className, String owner, String name, String desc, int opcode) {
        boolean instance = opcode != Opcodes.INVOKESTATIC;
        Type method = Type.getMethodType(desc);
        List<Type> params = new ArrayList<>();
        if (instance) params.add(Type.getObjectType(owner));
        params.addAll(List.of(method.getArgumentTypes()));
        String callDesc = Type.getMethodDescriptor(method.getReturnType(), params.toArray(new Type[0]));
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", callDesc, null, null);
        mv.visitCode();
        int slot = 0;
        for (Type param : params) {
            mv.visitVarInsn(param.getOpcode(Opcodes.ILOAD), slot);
            slot += param.getSize();
        }
        mv.visitMethodInsn(opcode, owner, name, desc, opcode == Opcodes.INVOKEINTERFACE);
        mv.visitInsn(method.getReturnType().getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] fieldRead(String className, String owner, String name, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, name, desc);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class literal of the old type, the way a mod names a class it never instantiates. */
    private static byte[] typeUse(String className, String type) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitLdcInsn(Type.getObjectType(type));
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code HoeItem::onlyIfAirAbove} as a {@code Predicate}, the shape tilling registrations pass. */
    private static byte[] hoeReference(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        mv.visitInvokeDynamicInsn("test", "()Ljava/util/function/Predicate;", metafactory,
                Type.getType("(Ljava/lang/Object;)Z"),
                new Handle(Opcodes.H_INVOKESTATIC, HOE_ITEM, HOE_HELPERS.get(0)[0], HOE_HELPERS.get(0)[1], false),
                Type.getType("(Lnet/minecraft/class_1838;)Z"));
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ---- Checks -----------------------------------------------------------------------------

    /** Problems with one translated class: verifier errors, removed names and members the host lacks. */
    private List<String> check(String name, byte[] out, HostSurface surface, String host) {
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
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    types.add(call.owner);
                    String missing = missingMember(call.owner, call.name, call.desc, surface);
                    if (missing != null) problems.add(name + ": " + missing);
                } else if (insn instanceof FieldInsnNode field) {
                    types.add(field.owner);
                    if (field.owner.startsWith("com/retromod/") && fieldOf(field.owner, field.name) == null) {
                        problems.add(name + ": reads " + field.owner + "." + field.name + ", which Retromod lacks");
                    }
                } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) {
                    types.add(type.getInternalName());
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle handle) {
                            types.add(handle.getOwner());
                            String missing = missingMember(handle.getOwner(), handle.getName(), handle.getDesc(), surface);
                            if (missing != null) problems.add(name + ": " + missing);
                        }
                    }
                }
            }
        }
        for (String type : types) {
            if (type.startsWith("net/fabricmc/fabric/") && !surface.hasClass(type)) {
                problems.add(name + ": names " + type + ", which the host lacks");
            }
            if (!host.startsWith("26.1") && GONE_ON_26_3.contains(type)) {
                problems.add(name + ": names " + type + ", which 26.3 removed");
            }
            if (type.startsWith("com/retromod/") && !type.startsWith("com/retromod/generated/")
                    && classNode(type) == null) {
                problems.add(name + ": names " + type + ", which Retromod does not ship");
            }
        }
        return problems;
    }

    private String missingMember(String owner, String name, String desc, HostSurface surface) {
        if (owner.startsWith("net/fabricmc/fabric/")) {
            return surface.member(owner, name, desc) == HostSurface.Presence.MISSING
                    ? "calls " + owner + "." + name + desc + ", which the host lacks" : null;
        }
        if (owner.startsWith("com/retromod/") && !owner.startsWith("com/retromod/generated/")) {
            ClassNode node = classNode(owner);
            boolean found = node != null && node.methods.stream()
                    .anyMatch(method -> method.name.equals(name) && method.desc.equals(desc));
            return found ? null : "calls " + owner + "." + name + desc + ", which Retromod does not ship";
        }
        return null;
    }

    private static Object fieldOf(String owner, String name) {
        ClassNode node = classNode(owner);
        return node == null ? null : node.fields.stream().filter(field -> field.name.equals(name)).findFirst().orElse(null);
    }

    private static ClassNode classNode(String internalName) {
        try (InputStream in = ContentRowsTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        } catch (IOException e) {
            return null;
        }
    }
}
