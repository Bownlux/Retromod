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
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Every transform path sends an old mod's model calls (getModel, render, the shapers, the block renderer) to embedded copies that link. */
class LegacyBakedItemPathParityTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    // What the draw's calls become where old BakedModel is 26.x's block model: NeoForge, and Fabric through intermediary.
    private static final List<String> NEUTRAL = List.of("embedded LegacyItemStackRenderer.get", "embedded LegacyItemStackRenderer.getModel",
            "embedded LegacyBakedModelCalls.isGui3d", "embedded LegacyItemStackRenderer.render", "embedded LegacyItemStackRenderer.getFoilBuffer",
            "embedded LegacyItemStackRenderer.renderModelLists", "embedded LegacyItemStackRenderer.getItemModelShaper",
            "embedded LegacyItemModelShaper.getItemModel", "embedded LegacyBlockRenderDispatcher.get",
            "embedded LegacyBlockRenderDispatcher.getBlockModel", "embedded LegacyBakedModelCalls.getQuads", "java/util/List.size");
    // On Forge 26.2 and newer the old model is the rebuilt Forge one, so its calls stay on it and the renderers' go to Forge helpers.
    private static final List<String> FORGE = List.of("embedded LegacyItemStackRenderer.get", "embedded LegacyForgeItemDraws.getModel",
            "embedded BakedModel.isGui3d", "embedded LegacyForgeItemDraws.render", "embedded LegacyItemStackRenderer.getFoilBuffer",
            "embedded LegacyForgeItemDraws.renderModelLists", "embedded LegacyItemStackRenderer.getItemModelShaper",
            "embedded LegacyForgeItemDraws.getItemModel", "embedded LegacyBlockRenderDispatcher.get",
            "embedded LegacyForgeItemDraws.getBlockModel", "embedded BakedModel.getQuads", "java/util/List.size");

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
    @DisplayName("Forge: a Forge 1.20.1 mod's model calls reach the Forge helpers on every path, and every embedded call links")
    void forge(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceForge(true);
        assertEveryPathAgrees(host, dir, Fixture.FORGE_1201, FORGE);
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("NeoForge: a NeoForge 1.21.1 mod's model calls reach the rebuilt renderers on every path")
    void neoForge(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceNeoForge(true);
        assertEveryPathAgrees(host, dir, Fixture.NEOFORGE_1211, NEUTRAL);
    }

    @ParameterizedTest(name = "Fabric {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("Fabric: a Fabric 1.21.1 mod's model calls, in intermediary names, reach the rebuilt renderers on every path")
    void fabric(String host, @TempDir Path dir) throws Exception {
        assertEveryPathAgrees(host, dir, Fixture.FABRIC_1211, NEUTRAL);
    }

    private void assertEveryPathAgrees(String host, Path dir, Fixture fixture, List<String> expectedCalls) throws Exception {
        Path input = dir.resolve("models.jar");
        Files.write(input, modJar(fixture));

        Map<String, Path> outputs = new LinkedHashMap<>();
        outputs.put("runtime", runtime(input, dir, host, fixture));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("models.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", LegacyAutoSubscribePathParityTest.onlyJar(batchOut));
        Path transformed = dir.resolve("transformed.jar");
        CliHarness.transform(input, transformed, host);
        outputs.put("CLI transform", transformed);
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", aot);

        for (Map.Entry<String, Path> output : outputs.entrySet()) {
            String where = fixture + " " + host + ", " + output.getKey();
            try (JarFile jar = new JarFile(output.getValue().toFile())) {
                assertEquals(expectedCalls, drawCalls(jar, "mod/"), where);
                assertEquals(List.of(), unresolved(jar), where + ": every call into an embedded class names a member it has");
                Path nested = Files.createTempFile("nested-", ".jar");
                try {
                    Files.write(nested, jar.getInputStream(jar.getJarEntry(fixture.nested)).readAllBytes());
                    try (JarFile lib = new JarFile(nested.toFile())) {
                        assertEquals(expectedCalls, drawCalls(lib, "fixturelib/"), where + ", nested jar");
                        assertEquals(List.of(), unresolved(lib), where + ", nested jar links");
                    }
                } finally {
                    Files.deleteIfExists(nested);
                }
            }
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

    private static List<String> drawCalls(JarFile jar, String pkg) throws Exception {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method(node(jar, pkg + "Holder"), "draw").instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(embedded(jar, call.owner) + "." + call.name);
        }
        return calls;
    }

    /** Each call into an embedded class whose name and descriptor that class, or one of its embedded supertypes, does not declare. */
    static List<String> unresolved(JarFile jar) throws Exception {
        List<String> missing = new ArrayList<>();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (!entry.getName().endsWith(".class")) continue;
            ClassNode node = ModConstructorHooksTest.node(jar.getInputStream(entry).readAllBytes());
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && call.owner.startsWith(SyntheticEmbedder.PREFIX)
                            && !declares(jar, call.owner, call.name, call.desc)) {
                        missing.add(node.name + "." + method.name + " -> " + call.owner + "." + call.name + call.desc);
                    }
                }
            }
        }
        return missing;
    }

    // Walks the embedded class and its embedded supertypes; a supertype outside the jar is the host's, which the link check covers.
    private static boolean declares(JarFile jar, String owner, String name, String desc) throws Exception {
        JarEntry entry = jar.getJarEntry(owner + ".class");
        if (entry == null) return !owner.startsWith(SyntheticEmbedder.PREFIX);
        ClassNode node = ModConstructorHooksTest.node(jar.getInputStream(entry).readAllBytes());
        if (node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc))) return true;
        List<String> parents = new ArrayList<>(node.interfaces);
        if (node.superName != null) parents.add(node.superName);
        for (String parent : parents) {
            if (parent.startsWith(SyntheticEmbedder.PREFIX) && declares(jar, parent, name, desc)) return true;
            if (!parent.startsWith(SyntheticEmbedder.PREFIX) && !parent.equals("java/lang/Object")) return true;
        }
        return name.equals("getClass") || name.equals("equals") || name.equals("hashCode") || name.equals("toString");
    }

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

    /** The names old model calls are written in: SRG for Forge 1.20.1, Mojang for NeoForge 1.21.1, intermediary for Fabric 1.21.1. */
    enum Fixture {
        FORGE_1201("META-INF/jarjar/lib.jar", new String[]{
            "net/minecraft/client/Minecraft", "m_91291_", "m_91289_", "net/minecraft/client/renderer/entity/ItemRenderer", "m_174264_",
            "m_115143_", "m_115211_", "m_115189_", "m_115103_", "net/minecraft/client/renderer/ItemModelShaper", "m_109406_",
            "net/minecraft/client/resources/model/BakedModel", "m_7539_", "m_213637_", "net/minecraft/client/renderer/block/BlockRenderDispatcher",
            "m_110910_", "net/minecraft/world/item/ItemStack", "net/minecraft/world/level/Level", "net/minecraft/world/entity/LivingEntity",
            "net/minecraft/world/item/ItemDisplayContext", "com/mojang/blaze3d/vertex/PoseStack", "net/minecraft/client/renderer/MultiBufferSource",
            "net/minecraft/client/renderer/RenderType", "com/mojang/blaze3d/vertex/VertexConsumer", "net/minecraft/world/level/block/state/BlockState",
            "net/minecraft/core/Direction", "net/minecraft/util/RandomSource"}),
        NEOFORGE_1211("META-INF/jarjar/lib.jar", new String[]{
            "net/minecraft/client/Minecraft", "getItemRenderer", "getBlockRenderer", "net/minecraft/client/renderer/entity/ItemRenderer",
            "getModel", "render", "getFoilBuffer", "renderModelLists", "getItemModelShaper", "net/minecraft/client/renderer/ItemModelShaper",
            "getItemModel", "net/minecraft/client/resources/model/BakedModel", "isGui3d", "getQuads",
            "net/minecraft/client/renderer/block/BlockRenderDispatcher", "getBlockModel", "net/minecraft/world/item/ItemStack",
            "net/minecraft/world/level/Level", "net/minecraft/world/entity/LivingEntity", "net/minecraft/world/item/ItemDisplayContext",
            "com/mojang/blaze3d/vertex/PoseStack", "net/minecraft/client/renderer/MultiBufferSource", "net/minecraft/client/renderer/RenderType",
            "com/mojang/blaze3d/vertex/VertexConsumer", "net/minecraft/world/level/block/state/BlockState", "net/minecraft/core/Direction",
            "net/minecraft/util/RandomSource"}),
        FABRIC_1211("META-INF/jars/lib.jar", new String[]{
            "net/minecraft/class_310", "method_1480", "method_1541", "net/minecraft/class_918", "method_4019", "method_23179", "method_23181",
            "method_23182", "method_4012", "net/minecraft/class_763", "method_3308", "net/minecraft/class_1087", "method_4712", "method_4707",
            "net/minecraft/class_776", "method_3349", "net/minecraft/class_1799", "net/minecraft/class_1937", "net/minecraft/class_1309",
            "net/minecraft/class_811", "net/minecraft/class_4587", "net/minecraft/class_4597", "net/minecraft/class_1921",
            "net/minecraft/class_4588", "net/minecraft/class_2680", "net/minecraft/class_2350", "net/minecraft/class_5819"});

        final String nested;
        final String minecraft, getItemRenderer, getBlockRenderer, itemRenderer, getModel, render, getFoilBuffer, renderModelLists;
        final String getItemModelShaper, itemShaper, getItemModel, model, isGui3d, getQuads, blocks, getBlockModel;
        final String stack, level, living, context, pose, buffers, renderType, consumer, state, direction, random;

        Fixture(String nested, String[] n) {
            this.nested = nested;
            minecraft = n[0]; getItemRenderer = n[1]; getBlockRenderer = n[2]; itemRenderer = n[3]; getModel = n[4]; render = n[5];
            getFoilBuffer = n[6]; renderModelLists = n[7]; getItemModelShaper = n[8]; itemShaper = n[9]; getItemModel = n[10]; model = n[11];
            isGui3d = n[12]; getQuads = n[13]; blocks = n[14]; getBlockModel = n[15]; stack = n[16]; level = n[17]; living = n[18];
            context = n[19]; pose = n[20]; buffers = n[21]; renderType = n[22]; consumer = n[23]; state = n[24]; direction = n[25]; random = n[26];
        }
    }

    /** A mod with the model calls, and a nested library mod with the same. */
    private static byte[] modJar(Fixture fixture) throws Exception {
        Map<String, byte[]> lib = files("fixturelib", "fixturelib/", fixture, null);
        Map<String, byte[]> mod = files("modelsmod", "mod/", fixture, fixture.nested);
        mod.put(fixture.nested, LegacyAutoSubscribePathParityTest.jar(lib));
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
                    + "versionRange=\"[1.21.1,1.21.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8));
        } else {
            files.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml(modId));
        }
        files.put(pkg + "Holder.class", holder(pkg + "Holder", fixture));
        return files;
    }

    // As Farmer's Delight, Sophisticated Backpacks and Carry On draw: the item's model, its 3D flag, the item drawn with it,
    // its quads written into a glint buffer, the shaper's model, and a block model's quads through the block renderer.
    private static byte[] holder(String name, Fixture f) {
        String items = "L" + f.itemRenderer + ";";
        String model = "L" + f.model + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor draw = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "draw", "(L" + f.minecraft + ";L" + f.stack + ";L" + f.pose + ";L"
                + f.buffers + ";L" + f.renderType + ";L" + f.state + ";)V", null, null);
        draw.visitCode();
        draw.visitVarInsn(ALOAD, 0);
        draw.visitMethodInsn(INVOKEVIRTUAL, f.minecraft, f.getItemRenderer, "()" + items, false);
        draw.visitVarInsn(ASTORE, 6);
        draw.visitVarInsn(ALOAD, 6);
        draw.visitVarInsn(ALOAD, 1);
        draw.visitInsn(ACONST_NULL);
        draw.visitInsn(ACONST_NULL);
        draw.visitInsn(ICONST_0);
        draw.visitMethodInsn(INVOKEVIRTUAL, f.itemRenderer, f.getModel, "(L" + f.stack + ";L" + f.level + ";L" + f.living + ";I)" + model, false);
        draw.visitVarInsn(ASTORE, 7);
        draw.visitVarInsn(ALOAD, 7);
        draw.visitMethodInsn(INVOKEINTERFACE, f.model, f.isGui3d, "()Z", true);
        draw.visitInsn(POP);
        draw.visitVarInsn(ALOAD, 6);
        draw.visitVarInsn(ALOAD, 1);
        draw.visitInsn(ACONST_NULL);
        draw.visitInsn(ICONST_0);
        draw.visitVarInsn(ALOAD, 2);
        draw.visitVarInsn(ALOAD, 3);
        draw.visitInsn(ICONST_0);
        draw.visitInsn(ICONST_0);
        draw.visitVarInsn(ALOAD, 7);
        draw.visitMethodInsn(INVOKEVIRTUAL, f.itemRenderer, f.render, "(L" + f.stack + ";L" + f.context + ";ZL" + f.pose + ";L" + f.buffers
                + ";II" + model + ")V", false);
        draw.visitVarInsn(ALOAD, 3);
        draw.visitVarInsn(ALOAD, 4);
        draw.visitInsn(ICONST_1);
        draw.visitInsn(ICONST_0);
        draw.visitMethodInsn(INVOKESTATIC, f.itemRenderer, f.getFoilBuffer, "(L" + f.buffers + ";L" + f.renderType + ";ZZ)L" + f.consumer + ";",
                false);
        draw.visitVarInsn(ASTORE, 8);
        draw.visitVarInsn(ALOAD, 6);
        draw.visitVarInsn(ALOAD, 7);
        draw.visitVarInsn(ALOAD, 1);
        draw.visitInsn(ICONST_0);
        draw.visitInsn(ICONST_0);
        draw.visitVarInsn(ALOAD, 2);
        draw.visitVarInsn(ALOAD, 8);
        draw.visitMethodInsn(INVOKEVIRTUAL, f.itemRenderer, f.renderModelLists, "(" + model + "L" + f.stack + ";IIL" + f.pose + ";L" + f.consumer
                + ";)V", false);
        draw.visitVarInsn(ALOAD, 6);
        draw.visitMethodInsn(INVOKEVIRTUAL, f.itemRenderer, f.getItemModelShaper, "()L" + f.itemShaper + ";", false);
        draw.visitVarInsn(ALOAD, 1);
        draw.visitMethodInsn(INVOKEVIRTUAL, f.itemShaper, f.getItemModel, "(L" + f.stack + ";)" + model, false);
        draw.visitInsn(POP);
        draw.visitVarInsn(ALOAD, 0);
        draw.visitMethodInsn(INVOKEVIRTUAL, f.minecraft, f.getBlockRenderer, "()L" + f.blocks + ";", false);
        draw.visitVarInsn(ALOAD, 5);
        draw.visitMethodInsn(INVOKEVIRTUAL, f.blocks, f.getBlockModel, "(L" + f.state + ";)" + model, false);
        draw.visitVarInsn(ALOAD, 5);
        draw.visitInsn(ACONST_NULL);
        draw.visitInsn(ACONST_NULL);
        draw.visitMethodInsn(INVOKEINTERFACE, f.model, f.getQuads, "(L" + f.state + ";L" + f.direction + ";L" + f.random + ";)Ljava/util/List;",
                true);
        draw.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "size", "()I", true);
        draw.visitInsn(POP);
        draw.visitInsn(RETURN);
        draw.visitMaxs(0, 0);
        draw.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
