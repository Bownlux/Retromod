/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.FabricModTransformer;
import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.CliHarness;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.objectweb.asm.Opcodes.*;

/** Every transform path gives an old mod's vertex calls, its own consumer, its entity renderer and its sky reads the rebuilt APIs. */
class LegacyVertexApiPathParityTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void reset() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(false);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Forge: the runtime, CLI batch, CLI transform and AOT paths, and a nested jar, bridge a Forge 1.20.1 mod")
    void forge(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceForge(true);
        assertEveryPathAgrees(host, dir, Fixture.FORGE_1201, mojangCalls());
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("NeoForge: every path bridges a Forge 1.20.1 mod")
    void neoForge(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceNeoForge(true);
        assertEveryPathAgrees(host, dir, Fixture.FORGE_1201, mojangCalls());
    }

    @ParameterizedTest(name = "Fabric {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("Fabric: every path bridges a Fabric 1.20.1 mod, written in intermediary names")
    void fabric(String host, @TempDir Path dir) throws Exception {
        // The ids that kept their shape became 1.21's setters, which the host has.
        Set<String> calls = new TreeSet<>(Set.of("VertexConsumer.addVertex", "VertexConsumer.setColor", "VertexConsumer.setUv",
                "embedded LegacyVertexCalls.endVertex", "embedded LegacyVertexCalls.normal", "embedded LegacyVertexCalls.overlayCoords",
                "embedded LegacyVertexCalls.uv2", "embedded LegacyVertexCalls.vertex"));
        assertEveryPathAgrees(host, dir, Fixture.FABRIC_1201, calls);
    }

    @ParameterizedTest(name = "Fabric {0}")
    @ValueSource(strings = {"1.21.1"})
    @DisplayName("Fabric 1.21.x: every path bridges the calls in the intermediary names the host runs")
    void fabricIntermediary(String host, @TempDir Path dir) throws Exception {
        Set<String> calls = new TreeSet<>(Set.of("class_4588.method_1336", "class_4588.method_22913", "class_4588.method_22918",
                "embedded LegacyVertexCalls.endVertex", "embedded LegacyVertexCalls.normal", "embedded LegacyVertexCalls.overlayCoords",
                "embedded LegacyVertexCalls.uv2", "embedded LegacyVertexCalls.vertex"));
        assertEveryPathAgrees(host, dir, Fixture.FABRIC_1201, calls);
    }

    private static Set<String> mojangCalls() {
        return new TreeSet<>(Set.of("embedded LegacyVertexCalls.color", "embedded LegacyVertexCalls.endVertex",
                "embedded LegacyVertexCalls.normal", "embedded LegacyVertexCalls.overlayCoords", "embedded LegacyVertexCalls.uv",
                "embedded LegacyVertexCalls.uv2", "embedded LegacyVertexCalls.vertex"));
    }

    private void assertEveryPathAgrees(String host, Path dir, Fixture fixture, Set<String> drawCalls) throws Exception {
        Path input = dir.resolve("vertex.jar");
        Files.write(input, modJar(fixture));
        boolean states = RetromodVersion.compareMcVersions(host, "26.1") >= 0;

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("runtime", summary(runtime(input, dir, host, fixture), fixture));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("vertex.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(LegacyAutoSubscribePathParityTest.onlyJar(batchOut), fixture));
        Path transformed = dir.resolve("transformed.jar");
        CliHarness.transform(input, transformed, host);
        outputs.put("CLI transform", summary(transformed, fixture));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot, fixture));

        Map<String, String> expected = new TreeMap<>();
        for (String prefix : List.of("", "nested:")) {
            expected.put(prefix + "draw", String.join(", ", drawCalls));
            expected.put(prefix + "own consumer", "implements embedded LegacyVertexCalls$OldConsumer; starts and ends vertices");
            if (states) {
                // The old offset call stays on the class, which now declares the old base offset; the name rule is the class's own.
                expected.put(prefix + "entity renderer", "renderFinal calls Geo.getRenderOffset, embedded LegacyEntityRenderers.superRender;"
                        + " declares getRenderOffset(entity), shouldShowName(entity, distance)");
                expected.put(prefix + "sky", "embedded LegacyLevelTime.getMoonPhase");
            }
        }
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            Map<String, String> found = new TreeMap<>(output.getValue());
            if (!states) {
                found.remove("entity renderer");
                found.remove("nested:entity renderer");
                found.remove("sky");
                found.remove("nested:sky");
            }
            assertEquals(expected, found, fixture + " " + host + ", " + output.getKey());
        }
    }

    /** What the loader's entry point registers on the host, mappings and shims, then one runtime transform. */
    private Path runtime(Path input, Path dir, String host, Fixture fixture) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        Path out = Files.createDirectory(dir.resolve("runtime"));
        if (fixture == Fixture.FABRIC_1201) {
            if (RetromodVersion.isUnobfuscatedTarget(host)) IntermediaryToMojangMapper.applyTo(transformer);
            for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
                if (Set.of("fabric", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                    ShimRegistration.register(shim, transformer);
                }
            }
            return new FabricModTransformer(host).transformMod(input, out);
        }
        // RetromodForge and RetromodNeoForge decode the mod's SRG names on Mojang-named hosts.
        SrgToMojangMapper.getInstance().applyTo(transformer);
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            Set<String> loaders = McReflect.isNeoForge() ? Set.of("neoforge", "forge", "common") : Set.of("forge", "common");
            if (loaders.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        Path written = new ForgeModTransformer(host).transformMod(input, out);
        assertNotNull(written, "the runtime wrote a jar");
        return written;
    }

    private static Map<String, String> summary(Path jar, Fixture fixture) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            describe(file, "mod/", "", found);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry(fixture.get("nested"))).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, "fixturelib/", "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static void describe(JarFile jar, String pkg, String prefix, Map<String, String> found) throws Exception {
        found.put(prefix + "draw", String.join(", ", calls(jar, method(node(jar, pkg + "Quad"), "draw"))));
        found.put(prefix + "own consumer", consumer(jar, node(jar, pkg + "Tint")));
        ClassNode geo = node(jar, pkg + "Geo");
        found.put(prefix + "entity renderer", "renderFinal calls " + String.join(", ", calls(jar, method(geo, "renderFinal")))
                + "; declares " + rendererMembers(geo));
        found.put(prefix + "sky", String.join(", ", calls(jar, method(node(jar, pkg + "Sky"), "phase"))));
    }

    // Whether the old consumer took the embedded interface, a 1.21 addVertex and the end Retromod calls.
    private static String consumer(JarFile jar, ClassNode node) {
        String implemented = node.interfaces.stream().map(i -> embedded(jar, i)).filter(i -> i.startsWith("embedded ")).findFirst()
                .orElse("nothing");
        boolean starts = node.methods.stream().anyMatch(m -> m.desc.startsWith("(FFF)L") && (m.name.equals("addVertex")
                || m.name.equals("method_22912")));
        boolean ends = node.methods.stream().anyMatch(m -> m.name.equals("retromod$endVertex"));
        return "implements " + implemented + (starts && ends ? "; starts and ends vertices" : "; not adapted");
    }

    private static String rendererMembers(ClassNode node) {
        List<String> members = new java.util.ArrayList<>();
        if (declares(node, "getRenderOffset", "(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/world/phys/Vec3;")) {
            members.add("getRenderOffset(entity)");
        }
        if (declares(node, "getRenderOffset",
                "(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)Lnet/minecraft/world/phys/Vec3;")) {
            members.add("getRenderOffset(state)");
        }
        if (declares(node, "shouldShowName", "(Lnet/minecraft/world/entity/Entity;D)Z")) members.add("shouldShowName(entity, distance)");
        return String.join(", ", members);
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    private static Set<String> calls(JarFile jar, MethodNode method) {
        Set<String> calls = new TreeSet<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && !call.name.equals("<init>")) {
                String owner = embedded(jar, call.owner);
                calls.add((owner.startsWith("embedded ") ? owner : owner.substring(owner.lastIndexOf('/') + 1)) + "." + call.name);
            }
        }
        return calls;
    }

    // An embedded rebuilt class by its simple name, so each jar's own relocated copy reads the same.
    private static String embedded(JarFile jar, String owner) {
        if (owner.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(owner + ".class") != null) {
            return "embedded " + owner.substring(owner.lastIndexOf('/') + 1);
        }
        return owner;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static ClassNode node(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return ModConstructorHooksTest.node(jar.getInputStream(entry).readAllBytes());
    }

    /** The names the old calls are written in: SRG for Forge 1.20.1, intermediary for Fabric 1.20.1. */
    enum Fixture {
        FORGE_1201(Map.ofEntries(Map.entry("consumer", "com/mojang/blaze3d/vertex/VertexConsumer"),
                Map.entry("builder", "com/mojang/blaze3d/vertex/BufferBuilder"), Map.entry("vertex", "m_5483_"),
                Map.entry("color", "m_6122_"), Map.entry("uv", "m_7421_"), Map.entry("overlay", "m_7122_"), Map.entry("uv2", "m_7120_"),
                Map.entry("normal", "m_5601_"), Map.entry("end", "m_5752_"), Map.entry("whole", "m_5954_"), Map.entry("default", "m_7404_"),
                Map.entry("unset", "m_141991_"), Map.entry("uv2Packed", "m_85969_"), Map.entry("normalMatrix", "m_252939_"),
                Map.entry("vertexMatrix", "m_252986_"), Map.entry("pose", "com/mojang/blaze3d/vertex/PoseStack"),
                Map.entry("buffers", "net/minecraft/client/renderer/MultiBufferSource"), Map.entry("entity", "net/minecraft/world/entity/Entity"),
                Map.entry("renderer", "net/minecraft/client/renderer/entity/EntityRenderer"), Map.entry("render", "m_7392_"),
                Map.entry("offset", "m_7860_"), Map.entry("showName", "m_6512_"), Map.entry("vec3", "net/minecraft/world/phys/Vec3"),
                Map.entry("context", "net/minecraft/client/renderer/entity/EntityRendererProvider$Context"),
                Map.entry("level", "net/minecraft/client/multiplayer/ClientLevel"), Map.entry("moonPhase", "m_46941_"),
                Map.entry("nested", "META-INF/jarjar/lib.jar"))),
        FABRIC_1201(Map.ofEntries(Map.entry("consumer", "net/minecraft/class_4588"), Map.entry("builder", "net/minecraft/class_287"),
                Map.entry("vertex", "method_22912"), Map.entry("color", "method_1336"), Map.entry("uv", "method_22913"),
                Map.entry("overlay", "method_22917"), Map.entry("uv2", "method_22921"), Map.entry("normal", "method_22914"),
                Map.entry("end", "method_1344"), Map.entry("whole", "method_23919"), Map.entry("default", "method_22901"),
                Map.entry("unset", "method_35666"), Map.entry("uv2Packed", "method_22916"), Map.entry("normalMatrix", "method_23763"),
                Map.entry("vertexMatrix", "method_22918"), Map.entry("pose", "net/minecraft/class_4587"),
                Map.entry("buffers", "net/minecraft/class_4597"), Map.entry("entity", "net/minecraft/class_1297"),
                Map.entry("renderer", "net/minecraft/class_897"), Map.entry("render", "method_3936"), Map.entry("offset", "method_23169"),
                Map.entry("showName", "method_3921"), Map.entry("vec3", "net/minecraft/class_243"),
                Map.entry("context", "net/minecraft/class_5617$class_5618"), Map.entry("level", "net/minecraft/class_638"),
                Map.entry("moonPhase", "method_30273"), Map.entry("nested", "META-INF/jars/lib.jar")));

        private final Map<String, String> names;

        Fixture(Map<String, String> names) {
            this.names = names;
        }

        String get(String key) {
            return names.get(key);
        }

        String ret() {
            return "L" + get("consumer") + ";";
        }
    }

    /** A 1.20.1 mod with the old calls, and a nested library mod with the same. */
    private static byte[] modJar(Fixture fixture) throws Exception {
        Map<String, byte[]> lib = files("fixturelib", "fixturelib/", fixture, null);
        Map<String, byte[]> mod = files("vertexmod", "mod/", fixture, fixture.get("nested"));
        mod.put(fixture.get("nested"), LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    private static Map<String, byte[]> files(String modId, String pkg, Fixture fixture, String nestedJar) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (fixture == Fixture.FABRIC_1201) {
            files.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0\","
                    + "\"depends\":{\"minecraft\":\"1.20.1\"}" + (nestedJar == null ? "" : ",\"jars\":[{\"file\":\"" + nestedJar + "\"}]")
                    + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } else {
            files.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml(modId));
        }
        files.put(pkg + "Quad.class", quad(pkg + "Quad", fixture));
        files.put(pkg + "Tint.class", tint(pkg + "Tint", fixture));
        files.put(pkg + "Geo.class", geo(pkg + "Geo", fixture));
        files.put(pkg + "Sky.class", sky(pkg + "Sky", fixture));
        return files;
    }

    // draw(consumer, builder, pose, normal): the old chain, a whole vertex, a call named on BufferBuilder and the matrix forms.
    private static byte[] quad(String name, Fixture f) {
        String vc = f.get("consumer");
        String ret = f.ret();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "draw",
                "(" + ret + "L" + f.get("builder") + ";Lorg/joml/Matrix4f;Lorg/joml/Matrix3f;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(DCONST_1);
        mv.visitInsn(DCONST_1);
        mv.visitInsn(DCONST_1);
        mv.visitMethodInsn(INVOKEINTERFACE, vc, f.get("vertex"), "(DDD)" + ret, true);
        ints(mv, 4);
        mv.visitMethodInsn(INVOKEINTERFACE, vc, f.get("color"), "(IIII)" + ret, true);
        floats(mv, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, vc, f.get("uv"), "(FF)" + ret, true);
        ints(mv, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, vc, f.get("overlay"), "(II)" + ret, true);
        ints(mv, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, vc, f.get("uv2Packed"), "(I)" + ret, true);
        mv.visitVarInsn(ALOAD, 3);
        floats(mv, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, vc, f.get("normalMatrix"), "(Lorg/joml/Matrix3f;FFF)" + ret, true);
        mv.visitMethodInsn(INVOKEINTERFACE, vc, f.get("end"), "()V", true);
        mv.visitVarInsn(ALOAD, 0);
        floats(mv, 9);
        ints(mv, 2);
        floats(mv, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, vc, f.get("whole"), "(FFFFFFFFFIIFFF)V", true);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(DCONST_0);
        mv.visitInsn(DCONST_0);
        mv.visitInsn(DCONST_0);
        mv.visitMethodInsn(INVOKEVIRTUAL, f.get("builder"), f.get("vertex"), "(DDD)" + ret, false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        floats(mv, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, vc, f.get("vertexMatrix"), "(Lorg/joml/Matrix4f;FFF)" + ret, true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // A mod's own consumer with the old abstract methods, each returning itself.
    private static byte[] tint(String name, Fixture f) {
        String ret = f.ret();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", new String[]{f.get("consumer")});
        constructor(cw, "java/lang/Object", "()V");
        String[][] members = {{"vertex", "(DDD)" + ret}, {"color", "(IIII)" + ret}, {"uv", "(FF)" + ret}, {"overlay", "(II)" + ret},
                {"uv2", "(II)" + ret}, {"normal", "(FFF)" + ret}};
        for (String[] member : members) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, f.get(member[0]), member[1], null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        for (String[] member : new String[][]{{"end", "()V"}, {"default", "(IIII)V"}, {"unset", "()V"}}) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, f.get(member[0]), member[1], null, null);
            mv.visitCode();
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    // As GeckoLib 4's GeoEntityRenderer: render calls renderFinal, which asks for the base draw and reads the old offset.
    private static byte[] geo(String name, Fixture f) {
        String desc = "(L" + f.get("entity") + ";FFL" + f.get("pose") + ";L" + f.get("buffers") + ";I)V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, f.get("renderer"), null);
        constructor(cw, f.get("renderer"), "(L" + f.get("context") + ";)V");
        MethodVisitor render = cw.visitMethod(ACC_PUBLIC, f.get("render"), desc, null, null);
        render.visitCode();
        loadRenderArgs(render);
        render.visitMethodInsn(INVOKEVIRTUAL, name, "renderFinal", desc, false);
        render.visitInsn(RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        MethodVisitor last = cw.visitMethod(ACC_PUBLIC, "renderFinal", desc, null, null);
        last.visitCode();
        last.visitVarInsn(ALOAD, 0);
        last.visitVarInsn(ALOAD, 1);
        last.visitVarInsn(FLOAD, 3);
        last.visitMethodInsn(INVOKEVIRTUAL, name, f.get("offset"), "(L" + f.get("entity") + ";F)L" + f.get("vec3") + ";", false);
        last.visitInsn(POP);
        loadRenderArgs(last);
        last.visitMethodInsn(INVOKESPECIAL, f.get("renderer"), f.get("render"), desc, false);
        last.visitInsn(RETURN);
        last.visitMaxs(0, 0);
        last.visitEnd();
        // Its own name rule, as GeckoLib's; it has no offset of its own, so the host's stays.
        MethodVisitor rule = cw.visitMethod(ACC_PUBLIC, f.get("showName"), "(L" + f.get("entity") + ";)Z", null, null);
        rule.visitCode();
        rule.visitInsn(ICONST_0);
        rule.visitInsn(IRETURN);
        rule.visitMaxs(0, 0);
        rule.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // phase(level): the moon phase GeckoLib reads for its molang queries.
    private static byte[] sky(String name, Fixture f) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "phase", "(L" + f.get("level") + ";)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, f.get("level"), f.get("moonPhase"), "()I", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void loadRenderArgs(MethodVisitor mv) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(FLOAD, 2);
        mv.visitVarInsn(FLOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitVarInsn(ILOAD, 6);
    }

    private static void ints(MethodVisitor mv, int count) {
        for (int i = 0; i < count; i++) mv.visitInsn(ICONST_1);
    }

    private static void floats(MethodVisitor mv, int count) {
        for (int i = 0; i < count; i++) mv.visitInsn(FCONST_1);
    }

    private static void constructor(ClassWriter cw, String superName, String desc) {
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", desc, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        if (!desc.equals("()V")) init.visitVarInsn(ALOAD, 1);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", desc, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }
}
