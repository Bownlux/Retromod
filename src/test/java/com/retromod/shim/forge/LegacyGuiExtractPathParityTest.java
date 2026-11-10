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

import java.nio.charset.StandardCharsets;
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

/** Every transform path gives an old screen's draws, list entry, renderable and button the 26.x GUI shapes. */
class LegacyGuiExtractPathParityTest {

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

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("NeoForge: the runtime, CLI batch, CLI transform and AOT paths, and a nested jar, bridge a NeoForge 1.21.1 screen")
    void neoForge(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceNeoForge(true);
        assertEveryPathAgrees(host, dir, Fixture.NEOFORGE_1211);
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Forge: every path bridges a Forge 1.21.1 screen")
    void forge(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceForge(true);
        assertEveryPathAgrees(host, dir, Fixture.FORGE_1211);
    }

    @ParameterizedTest(name = "Fabric {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("Fabric: every path bridges a Fabric 1.21.1 screen, written in intermediary names")
    void fabric(String host, @TempDir Path dir) throws Exception {
        assertEveryPathAgrees(host, dir, Fixture.FABRIC_1211);
    }

    private void assertEveryPathAgrees(String host, Path dir, Fixture fixture) throws Exception {
        Path input = dir.resolve("screen.jar");
        Files.write(input, modJar(fixture));

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("runtime", summary(runtime(input, dir, host, fixture), fixture));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("screen.jar"));
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
            expected.put(prefix + "draw", String.join(", ", new TreeSet<>(Set.of("embedded LegacyGuiDraws.pose",
                    "embedded LegacyGuiDraws.guiRenderType", "embedded LegacyGuiDraws.fill", "embedded LegacyGuiDraws.hLine",
                    "embedded LegacyGuiDraws.renderTooltip", "embedded RetroKeyMapping.matches", "Screenish.shift"))));
            expected.put(prefix + "overrides", "Badge.extractRenderState, Row.extractContent, Toggle.onPress(input)");
        }
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), fixture + " " + host + ", " + output.getKey());
        }
    }

    /** What the loader's entry point registers on the host, mappings and shims, then one runtime transform. */
    private Path runtime(Path input, Path dir, String host, Fixture fixture) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        Path out = Files.createDirectory(dir.resolve("runtime"));
        if (fixture == Fixture.FABRIC_1211) {
            IntermediaryToMojangMapper.applyTo(transformer);
            for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
                if (Set.of("fabric", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                    ShimRegistration.register(shim, transformer);
                }
            }
            return new FabricModTransformer(host).transformMod(input, out);
        }
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
        found.put(prefix + "draw", String.join(", ", calls(jar, method(node(jar, pkg + "Screenish"), "draw"))));
        List<String> overrides = new java.util.ArrayList<>();
        if (declares(node(jar, pkg + "Badge"), "extractRenderState", "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V")) {
            overrides.add("Badge.extractRenderState");
        }
        if (declares(node(jar, pkg + "Row"), "extractContent", "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIZF)V")) {
            overrides.add("Row.extractContent");
        }
        if (declares(node(jar, pkg + "Toggle"), "onPress", "(Lnet/minecraft/client/input/InputWithModifiers;)V")) {
            overrides.add("Toggle.onPress(input)");
        }
        found.put(prefix + "overrides", String.join(", ", overrides));
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

    // An embedded class by its simple name, so each jar's own relocated copy reads the same.
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

    /** The names a 1.21.1 screen's calls are written in: Mojang for Forge and NeoForge, intermediary for Fabric. */
    enum Fixture {
        NEOFORGE_1211(mojang("META-INF/jarjar/lib.jar")),
        FORGE_1211(mojang("META-INF/jarjar/lib.jar")),
        FABRIC_1211(Map.ofEntries(Map.entry("graphics", "net/minecraft/class_332"), Map.entry("pose", "method_51448"),
                Map.entry("poseStack", "net/minecraft/class_4587"), Map.entry("renderType", "net/minecraft/class_1921"),
                Map.entry("gui", "method_51784"), Map.entry("fill", "method_51739"), Map.entry("hLine", "method_25292"),
                Map.entry("font", "net/minecraft/class_327"), Map.entry("positioner", "net/minecraft/class_8000"),
                Map.entry("tooltip", "method_51436"), Map.entry("keyMapping", "net/minecraft/class_304"),
                Map.entry("matches", "method_1417"), Map.entry("entry", "net/minecraft/class_350$class_351"),
                Map.entry("entryRender", "method_25343"), Map.entry("renderable", "net/minecraft/class_4068"),
                Map.entry("render", "method_25394"), Map.entry("button", "net/minecraft/class_4264"),
                Map.entry("onPress", "method_25306"), Map.entry("nested", "META-INF/jars/lib.jar")));

        private final Map<String, String> names;

        Fixture(Map<String, String> names) {
            this.names = names;
        }

        String get(String key) {
            return names.get(key);
        }

        private static Map<String, String> mojang(String nested) {
            return Map.ofEntries(Map.entry("graphics", "net/minecraft/client/gui/GuiGraphics"), Map.entry("pose", "pose"),
                    Map.entry("poseStack", "com/mojang/blaze3d/vertex/PoseStack"), Map.entry("renderType", "net/minecraft/client/renderer/RenderType"),
                    Map.entry("gui", "gui"), Map.entry("fill", "fill"), Map.entry("hLine", "hLine"),
                    Map.entry("font", "net/minecraft/client/gui/Font"),
                    Map.entry("positioner", "net/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner"),
                    Map.entry("tooltip", "renderTooltip"), Map.entry("keyMapping", "net/minecraft/client/KeyMapping"),
                    Map.entry("matches", "matches"), Map.entry("entry", "net/minecraft/client/gui/components/AbstractSelectionList$Entry"),
                    Map.entry("entryRender", "render"), Map.entry("renderable", "net/minecraft/client/gui/components/Renderable"),
                    Map.entry("render", "render"), Map.entry("button", "net/minecraft/client/gui/components/AbstractButton"),
                    Map.entry("onPress", "onPress"), Map.entry("nested", nested));
        }
    }

    /** A 1.21.1 mod with the old screen, entry, renderable and button, and a nested library mod with the same. */
    private static byte[] modJar(Fixture fixture) throws Exception {
        Map<String, byte[]> lib = files("fixturelib", "fixturelib/", fixture, null);
        Map<String, byte[]> mod = files("screenmod", "mod/", fixture, fixture.get("nested"));
        mod.put(fixture.get("nested"), LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    private static Map<String, byte[]> files(String modId, String pkg, Fixture fixture, String nestedJar) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (fixture == Fixture.FABRIC_1211) {
            files.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0\","
                    + "\"depends\":{\"minecraft\":\"1.21.1\"}" + (nestedJar == null ? "" : ",\"jars\":[{\"file\":\"" + nestedJar + "\"}]")
                    + "}").getBytes(StandardCharsets.UTF_8));
        } else if (fixture == Fixture.NEOFORGE_1211) {
            files.put("META-INF/neoforge.mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\""
                    + modId + "\"\nversion=\"1.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\ntype=\"required\"\n"
                    + "versionRange=\"[1.21.1,1.22)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8));
        } else {
            files.put("META-INF/mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[52,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\""
                    + modId + "\"\nversion=\"1.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                    + "versionRange=\"[1.21.1,1.22)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8));
        }
        files.put(pkg + "Screenish.class", screen(pkg + "Screenish", fixture));
        files.put(pkg + "Row.class", subclass(pkg + "Row", fixture.get("entry"), null, fixture.get("entryRender"),
                "(L" + fixture.get("graphics") + ";IIIIIIIZF)V"));
        files.put(pkg + "Badge.class", subclass(pkg + "Badge", "java/lang/Object", fixture.get("renderable"), fixture.get("render"),
                "(L" + fixture.get("graphics") + ";IIF)V"));
        files.put(pkg + "Toggle.class", subclass(pkg + "Toggle", fixture.get("button"), null, fixture.get("onPress"), "()V"));
        return files;
    }

    // draw(graphics, keyMapping, font, lines, positioner): a pose stack passed on, a typed fill, a line, a tooltip and a key check.
    private static byte[] screen(String name, Fixture f) {
        String g = f.get("graphics");
        String type = "L" + f.get("renderType") + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "draw", "(L" + g + ";L" + f.get("keyMapping") + ";L" + f.get("font")
                + ";Ljava/util/List;L" + f.get("positioner") + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, g, f.get("pose"), "()L" + f.get("poseStack") + ";", false);
        mv.visitMethodInsn(INVOKESTATIC, name, "shift", "(L" + f.get("poseStack") + ";)V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, f.get("renderType"), f.get("gui"), "()" + type, false);
        for (int i = 0; i < 5; i++) mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKEVIRTUAL, g, f.get("fill"), "(" + type + "IIIII)V", false);
        mv.visitVarInsn(ALOAD, 0);
        for (int i = 0; i < 4; i++) mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKEVIRTUAL, g, f.get("hLine"), "(IIII)V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKEVIRTUAL, g, f.get("tooltip"), "(L" + f.get("font") + ";Ljava/util/List;L" + f.get("positioner") + ";II)V",
                false);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKEVIRTUAL, f.get("keyMapping"), f.get("matches"), "(II)Z", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        MethodVisitor shift = cw.visitMethod(ACC_STATIC, "shift", "(L" + f.get("poseStack") + ";)V", null, null);
        shift.visitCode();
        shift.visitInsn(RETURN);
        shift.visitMaxs(0, 0);
        shift.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // An abstract class over superName (implementing iface when given) with one empty override, as an old widget had.
    private static byte[] subclass(String name, String superName, String iface, String method, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT, name, null, superName, iface == null ? null : new String[]{iface});
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, method, desc, null, null);
        mv.visitCode();
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
