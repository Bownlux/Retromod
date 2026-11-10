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
import org.objectweb.asm.tree.FieldInsnNode;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Every transform path gives Sophisticated Backpacks' layer pattern the embedded skin map, renderer map, layer submit and AddLayers lookups. */
class LegacyPlayerLayersPathParityTest {

    private static final String DISPATCHER = "net/minecraft/client/renderer/entity/EntityRenderDispatcher";
    private static final String RENDER_LAYER = "net/minecraft/client/renderer/entity/layers/RenderLayer";
    private static final String PARENT = "net/minecraft/client/renderer/entity/RenderLayerParent";
    private static final String LIVING = "net/minecraft/client/renderer/entity/LivingEntityRenderer";
    private static final String FORGE_EVENT = "net/minecraftforge/client/event/EntityRenderersEvent$AddLayers";
    private static final String SUBMIT_DESC = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
            + "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FF)V";

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
    @DisplayName("Forge: the runtime, CLI batch, CLI transform and AOT paths bridge a Forge 1.20.1 mod's player layers")
    void forge(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceForge(true);
        assertEveryPathAgrees(host, dir, Fixture.FORGE_1201, "forge");
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("NeoForge: every path bridges a Forge 1.20.1 mod's player layers")
    void neoForge(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceNeoForge(true);
        assertEveryPathAgrees(host, dir, Fixture.FORGE_1201, "neoforge");
    }

    @ParameterizedTest(name = "Fabric {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("Fabric: every path gives a Fabric 1.20.1 layer its render back and a submit, and leaves its mixin alone")
    void fabric(String host, @TempDir Path dir) throws Exception {
        assertEveryPathAgrees(host, dir, Fixture.FABRIC_1201, "fabric");
    }

    private void assertEveryPathAgrees(String host, Path dir, Fixture fixture, String loader) throws Exception {
        Path input = dir.resolve("layers.jar");
        Files.write(input, modJar(fixture));

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("runtime", summary(runtime(input, dir, host, fixture), fixture));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("layers.jar"));
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
            expected.put(prefix + "layer", "extends " + RENDER_LAYER + ", submits through the embedded LegacyRenderLayers, keeps its old render");
            // Fabric's map gives a mixin's old render the name submit, and the mixin is left exactly as that remap leaves it.
            expected.put(prefix + "mixin", "extends " + RENDER_LAYER + ", implements [], declares [<init>, "
                    + (fixture == Fixture.FABRIC_1201 ? "submit" : "render") + "]");
            if (fixture == Fixture.FABRIC_1201) continue;
            // Forge makes the renderer map public, so a Forge mod keeps reading it; NeoForge's is private.
            String renderers = loader.equals("forge") ? DISPATCHER + ".renderers" : "embedded LegacyEntityRendererMap.of";
            Set<String> install = new TreeSet<>(Set.of("embedded LegacySkinMap.of", renderers, LIVING + ".addLayer"));
            expected.put(prefix + "install", String.join(", ", install));
            expected.put(prefix + "addLayers", "embedded " + loader + " LegacyAddLayers getRenderer, getSkin, getSkins");
        }
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), loader + " " + host + ", " + output.getKey());
        }
    }

    /** What the loader's entry point registers on the host, mappings and shims, then one runtime transform. */
    private Path runtime(Path input, Path dir, String host, Fixture fixture) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        Path out = Files.createDirectory(dir.resolve("runtime"));
        if (fixture == Fixture.FABRIC_1201) {
            IntermediaryToMojangMapper.applyTo(transformer);
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
            describe(file, "mod/", "", found, fixture);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry(fixture.nested)).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, "fixturelib/", "nested:", found, fixture);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    // What the layer and the mixin became, then for a Forge mod what the install code and the AddLayers listener call.
    private static void describe(JarFile jar, String pkg, String prefix, Map<String, String> found, Fixture fixture) throws Exception {
        ClassNode layer = node(jar, pkg + "Pack");
        boolean oldLayer = layer.interfaces.stream().anyMatch(i -> i.startsWith(SyntheticEmbedder.PREFIX)
                && i.endsWith("/LegacyRenderLayers$OldLayer") && jar.getJarEntry(i + ".class") != null);
        boolean submits = layer.methods.stream().filter(m -> m.name.equals("submit") && m.desc.equals(SUBMIT_DESC)).anyMatch(m -> {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.endsWith("/LegacyRenderLayers") && jar.getJarEntry(call.owner + ".class") != null) {
                    return true;
                }
            }
            return false;
        });
        boolean keepsRender = layer.methods.stream().anyMatch(m -> m.name.equals("render") && m.desc.contains("Lnet/minecraft/world/entity/Entity;FFFFFF)V"));
        found.put(prefix + "layer", "extends " + layer.superName + (oldLayer && submits && keepsRender
                ? ", submits through the embedded LegacyRenderLayers, keeps its old render"
                : ", not bridged: interface " + oldLayer + ", submit " + submits + ", render " + keepsRender));
        ClassNode mixin = node(jar, pkg + "ArmorMixin");
        Set<String> declared = new TreeSet<>();
        for (MethodNode m : mixin.methods) declared.add(m.name);
        found.put(prefix + "mixin", "extends " + mixin.superName + ", implements " + mixin.interfaces + ", declares " + declared);
        if (fixture == Fixture.FABRIC_1201) return;

        Set<String> install = new TreeSet<>();
        for (AbstractInsnNode insn : method(node(jar, pkg + "Install"), "install").instructions) {
            if (insn instanceof MethodInsnNode call && !call.name.equals("<init>")) install.add(shortName(call.owner, call.name));
            if (insn instanceof FieldInsnNode field) install.add(field.owner + "." + field.name);
        }
        found.put(prefix + "install", String.join(", ", install));

        Set<String> lookups = new TreeSet<>();
        String loader = "";
        for (AbstractInsnNode insn : method(node(jar, pkg + "Layers"), "onAddLayers").instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.endsWith("/LegacyAddLayers") && jar.getJarEntry(call.owner + ".class") != null) {
                lookups.add(call.name);
                loader = call.owner.contains("/legacyapi/neoforge/") ? "neoforge" : call.owner.contains("/legacyapi/forge/") ? "forge" : "?";
            } else if (insn instanceof MethodInsnNode call) {
                lookups.add(call.owner + "." + call.name);
            }
        }
        found.put(prefix + "addLayers", "embedded " + loader + " LegacyAddLayers " + String.join(", ", lookups));
    }

    // An embedded rebuilt class by its simple name, so each jar's own relocated copy reads the same.
    private static String shortName(String owner, String name) {
        if (owner.startsWith(SyntheticEmbedder.PREFIX)) return "embedded " + owner.substring(owner.lastIndexOf('/') + 1) + "." + name;
        return owner + "." + name;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static ClassNode node(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return ModConstructorHooksTest.node(jar.getInputStream(entry).readAllBytes());
    }

    /** The names an old layer is written in: SRG for Forge 1.20.1, intermediary for Fabric 1.20.1. */
    enum Fixture {
        FORGE_1201(RENDER_LAYER, PARENT, "m_6494_", "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;"
                + "ILnet/minecraft/world/entity/Entity;", "META-INF/jarjar/lib.jar"),
        FABRIC_1201("net/minecraft/class_3887", "net/minecraft/class_3883", "method_4199", "Lnet/minecraft/class_4587;Lnet/minecraft/class_4597;"
                + "ILnet/minecraft/class_1297;", "META-INF/jars/lib.jar");

        final String layer;
        final String parent;
        final String render;
        final String renderDesc;
        final String nested;

        Fixture(String layer, String parent, String render, String renderArgs, String nested) {
            this.layer = layer;
            this.parent = parent;
            this.render = render;
            this.renderDesc = "(" + renderArgs + "FFFFFF)V";
            this.nested = nested;
        }
    }

    /** A 1.20.1 mod with the layer pattern and an armor-layer mixin, and a nested library mod with the same. */
    private static byte[] modJar(Fixture fixture) throws Exception {
        Map<String, byte[]> lib = files("fixturelib", "fixturelib/", fixture, null);
        Map<String, byte[]> mod = files("layersmod", "mod/", fixture, fixture.nested);
        mod.put(fixture.nested, LegacyAutoSubscribePathParityTest.jar(lib));
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
            files.put(pkg + "Install.class", install(pkg + "Install", pkg + "Pack"));
            files.put(pkg + "Layers.class", addLayers(pkg + "Layers"));
        }
        files.put(pkg + "Pack.class", layer(pkg + "Pack", fixture, false));
        files.put(pkg + "ArmorMixin.class", layer(pkg + "ArmorMixin", fixture, true));
        return files;
    }

    // As Sophisticated Backpacks' layer: super(parent), and the old render bridge taking an Entity; or the same as an armor-layer mixin.
    private static byte[] layer(String name, Fixture fixture, boolean mixin) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER | (mixin ? ACC_ABSTRACT : 0), name, null, fixture.layer, null);
        if (mixin) cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + fixture.parent + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitMethodInsn(INVOKESPECIAL, fixture.layer, "<init>", "(L" + fixture.parent + ";)V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor render = cw.visitMethod(ACC_PUBLIC, fixture.render, fixture.renderDesc, null, null);
        render.visitCode();
        render.visitInsn(RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // As its reload listener: the skin map's renderers, then the public renderer map, each given the layer.
    private static byte[] install(String name, String layer) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "install", "(L" + DISPATCHER + ";)V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, DISPATCHER, "getSkinMap", "()Ljava/util/Map;", false);
        m.visitInsn(POP);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, DISPATCHER, "f_114362_", "Ljava/util/Map;");
        m.visitInsn(POP);
        m.visitInsn(ACONST_NULL);
        m.visitTypeInsn(CHECKCAST, LIVING);
        m.visitTypeInsn(NEW, layer);
        m.visitInsn(DUP);
        m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKESPECIAL, layer, "<init>", "(L" + PARENT + ";)V", false);
        m.visitMethodInsn(INVOKEVIRTUAL, LIVING, "m_115326_", "(L" + RENDER_LAYER + ";)Z", false);
        m.visitInsn(POP);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // As Curios' AddLayers listener: a skin by name, every skin name, and a mob's renderer.
    private static byte[] addLayers(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onAddLayers", "(L" + FORGE_EVENT + ";)V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitLdcInsn("slim");
        m.visitMethodInsn(INVOKEVIRTUAL, FORGE_EVENT, "getSkin", "(Ljava/lang/String;)L" + LIVING + ";", false);
        m.visitInsn(POP);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, FORGE_EVENT, "getSkins", "()Ljava/util/Set;", false);
        m.visitInsn(POP);
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKEVIRTUAL, FORGE_EVENT, "getRenderer", "(Lnet/minecraft/world/entity/EntityType;)L" + LIVING + ";", false);
        m.visitInsn(POP);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
