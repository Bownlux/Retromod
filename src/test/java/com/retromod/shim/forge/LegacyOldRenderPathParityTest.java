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
import org.objectweb.asm.tree.FieldNode;
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

/** Every transform path gives an old mod's draw calls, block entity renderer and entity renderer the embedded rebuilt draws. */
class LegacyOldRenderPathParityTest {

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
    @DisplayName("Forge: the runtime, CLI batch, CLI transform and AOT paths, and a nested jar, bridge a Forge 1.20.1 mod's draws")
    void forge(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceForge(true);
        assertEveryPathAgrees(host, dir, Fixture.FORGE_1201);
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("NeoForge: every path bridges a Forge 1.20.1 mod's draws")
    void neoForge(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceNeoForge(true);
        assertEveryPathAgrees(host, dir, Fixture.FORGE_1201);
    }

    @ParameterizedTest(name = "Fabric {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("Fabric: every path bridges a Fabric 1.20.1 mod's draws, written in intermediary names")
    void fabric(String host, @TempDir Path dir) throws Exception {
        assertEveryPathAgrees(host, dir, Fixture.FABRIC_1201);
    }

    private void assertEveryPathAgrees(String host, Path dir, Fixture fixture) throws Exception {
        Path input = dir.resolve("draws.jar");
        Files.write(input, modJar(fixture));

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("runtime", summary(runtime(input, dir, host, fixture), fixture));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("draws.jar"));
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
            expected.put(prefix + "field", "embedded LegacyItemStackRenderer");
            expected.put(prefix + "getter", "embedded LegacyItemStackRenderer.get");
            expected.put(prefix + "draw", "embedded LegacyEntityDraws.render, embedded LegacyEntityDraws.setRenderShadow,"
                    + " embedded LegacyItemStackRenderer.renderStatic");
            expected.put(prefix + "block renderer", "implements embedded LegacyBlockEntityRenderers$OldRenderer; createRenderState, extractRenderState"
                    + " and submit call embedded LegacyBlockEntityRenderers");
            expected.put(prefix + "entity renderer", "implements embedded LegacyEntityRenderers$OldRenderer; createRenderState, extractRenderState"
                    + " and submit call embedded LegacyEntityRenderers; super.render calls embedded LegacyEntityRenderers.superRender");
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
            describe(file, "mod/", "", found);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry(fixture.nested)).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, "fixturelib/", "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    // What the holder's field, its constructor's getter and its draw calls became, each embedded class checked present.
    private static void describe(JarFile jar, String pkg, String prefix, Map<String, String> found) throws Exception {
        ClassNode holder = node(jar, pkg + "Holder");
        FieldNode field = holder.fields.stream().filter(f -> f.name.equals("items")).findFirst().orElseThrow();
        String type = field.desc.substring(1, field.desc.length() - 1);
        found.put(prefix + "field", embedded(jar, type));
        found.put(prefix + "getter", String.join(", ", calls(jar, method(holder, "<init>"))));
        found.put(prefix + "draw", String.join(", ", calls(jar, method(holder, "draw"))));
        found.put(prefix + "block renderer", renderer(jar, node(jar, pkg + "Plate"), "LegacyBlockEntityRenderers"));
        String entity = renderer(jar, node(jar, pkg + "Ghost"), "LegacyEntityRenderers");
        Set<String> superCalls = calls(jar, method(node(jar, pkg + "Ghost"), "render"));
        found.put(prefix + "entity renderer", entity + "; super.render calls " + String.join(", ", superCalls));
    }

    // Whether the renderer took the old renderer interface and its three host methods each call the embedded helper.
    private static String renderer(JarFile jar, ClassNode node, String helper) {
        String implemented = node.interfaces.stream().map(i -> embedded(jar, i)).filter(i -> i.startsWith("embedded ")).findFirst().orElse("nothing");
        boolean all = true;
        for (String name : List.of("createRenderState", "extractRenderState", "submit")) {
            MethodNode method = node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
            all &= method != null && calls(jar, method).stream().anyMatch(c -> c.startsWith("embedded " + helper + "."));
        }
        return "implements " + implemented + (all ? "; createRenderState, extractRenderState and submit call embedded " + helper
                : "; not bridged");
    }

    private static Set<String> calls(JarFile jar, MethodNode method) {
        Set<String> calls = new TreeSet<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && !call.name.equals("<init>")) calls.add(embedded(jar, call.owner) + "." + call.name);
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

    /** The names old draw calls are written in: SRG for Forge 1.20.1, intermediary for Fabric 1.20.1. */
    enum Fixture {
        FORGE_1201("net/minecraft/client/Minecraft", "m_91291_", "net/minecraft/client/renderer/entity/ItemRenderer", "m_269128_",
                "net/minecraft/world/item/ItemStack", "net/minecraft/world/item/ItemDisplayContext", "com/mojang/blaze3d/vertex/PoseStack",
                "net/minecraft/client/renderer/MultiBufferSource", "net/minecraft/world/level/Level",
                "net/minecraft/client/renderer/entity/EntityRenderDispatcher", "m_114384_", "m_114468_", "net/minecraft/world/entity/Entity",
                "META-INF/jarjar/lib.jar", "net/minecraft/client/renderer/blockentity/BlockEntityRenderer", "m_6922_",
                "net/minecraft/world/level/block/entity/BlockEntity", "net/minecraft/client/renderer/entity/EntityRenderer", "m_7392_",
                "net/minecraft/client/renderer/entity/EntityRendererProvider$Context"),
        FABRIC_1201("net/minecraft/class_310", "method_1480", "net/minecraft/class_918", "method_23178", "net/minecraft/class_1799",
                "net/minecraft/class_811", "net/minecraft/class_4587", "net/minecraft/class_4597", "net/minecraft/class_1937",
                "net/minecraft/class_898", "method_3954", "method_3948", "net/minecraft/class_1297", "META-INF/jars/lib.jar",
                "net/minecraft/class_827", "method_3569", "net/minecraft/class_2586", "net/minecraft/class_897", "method_3936",
                "net/minecraft/class_5617$class_5618");

        final String minecraft;
        final String getItemRenderer;
        final String itemRenderer;
        final String renderStatic;
        final String stack;
        final String context;
        final String pose;
        final String buffers;
        final String level;
        final String dispatcher;
        final String renderEntity;
        final String setRenderShadow;
        final String entity;
        final String nested;
        final String blockRenderer;
        final String blockRender;
        final String blockEntity;
        final String entityRenderer;
        final String entityRender;
        final String entityContext;

        Fixture(String minecraft, String getItemRenderer, String itemRenderer, String renderStatic, String stack, String context, String pose,
                String buffers, String level, String dispatcher, String renderEntity, String setRenderShadow, String entity, String nested,
                String blockRenderer, String blockRender, String blockEntity, String entityRenderer, String entityRender, String entityContext) {
            this.blockRenderer = blockRenderer;
            this.blockRender = blockRender;
            this.blockEntity = blockEntity;
            this.entityRenderer = entityRenderer;
            this.entityRender = entityRender;
            this.entityContext = entityContext;
            this.minecraft = minecraft;
            this.getItemRenderer = getItemRenderer;
            this.itemRenderer = itemRenderer;
            this.renderStatic = renderStatic;
            this.stack = stack;
            this.context = context;
            this.pose = pose;
            this.buffers = buffers;
            this.level = level;
            this.dispatcher = dispatcher;
            this.renderEntity = renderEntity;
            this.setRenderShadow = setRenderShadow;
            this.entity = entity;
            this.nested = nested;
        }
    }

    /** A 1.20.1 mod with the draw calls, and a nested library mod with the same. */
    private static byte[] modJar(Fixture fixture) throws Exception {
        Map<String, byte[]> lib = files("fixturelib", "fixturelib/", fixture, null);
        Map<String, byte[]> mod = files("drawsmod", "mod/", fixture, fixture.nested);
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
        }
        files.put(pkg + "Holder.class", holder(pkg + "Holder", fixture));
        files.put(pkg + "Plate.class", plate(pkg + "Plate", fixture));
        files.put(pkg + "Ghost.class", ghost(pkg + "Ghost", fixture));
        return files;
    }

    // As Farmer's Delight's renderers: the block entity renderer interface and the erased old render.
    private static byte[] plate(String name, Fixture f) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", new String[]{f.blockRenderer});
        constructor(cw, "java/lang/Object", "()V");
        MethodVisitor render = cw.visitMethod(ACC_PUBLIC, f.blockRender, "(L" + f.blockEntity + ";FL" + f.pose + ";L" + f.buffers + ";II)V", null, null);
        render.visitCode();
        render.visitInsn(RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // A mod's own entity renderer directly under EntityRenderer, whose old render ends with super.render.
    private static byte[] ghost(String name, Fixture f) {
        String desc = "(L" + f.entity + ";FFL" + f.pose + ";L" + f.buffers + ";I)V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, f.entityRenderer, null);
        constructor(cw, f.entityRenderer, "(L" + f.entityContext + ";)V");
        MethodVisitor render = cw.visitMethod(ACC_PUBLIC, f.entityRender, desc, null, null);
        render.visitCode();
        render.visitVarInsn(ALOAD, 0);
        render.visitVarInsn(ALOAD, 1);
        render.visitVarInsn(FLOAD, 2);
        render.visitVarInsn(FLOAD, 3);
        render.visitVarInsn(ALOAD, 4);
        render.visitVarInsn(ALOAD, 5);
        render.visitVarInsn(ILOAD, 6);
        render.visitMethodInsn(INVOKESPECIAL, f.entityRenderer, f.entityRender, desc, false);
        render.visitInsn(RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
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

    // As Sophisticated Backpacks' layer: the item renderer kept from the constructor, then an item and an entity drawn.
    private static byte[] holder(String name, Fixture f) {
        String items = "L" + f.itemRenderer + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "items", items, null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + f.minecraft + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitMethodInsn(INVOKEVIRTUAL, f.minecraft, f.getItemRenderer, "()" + items, false);
        init.visitFieldInsn(PUTFIELD, name, "items", items);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor draw = cw.visitMethod(ACC_PUBLIC, "draw", "(L" + f.dispatcher + ";L" + f.pose + ";L" + f.buffers + ";)V", null, null);
        draw.visitCode();
        draw.visitVarInsn(ALOAD, 0);
        draw.visitFieldInsn(GETFIELD, name, "items", items);
        draw.visitInsn(ACONST_NULL);
        draw.visitInsn(ACONST_NULL);
        draw.visitInsn(ICONST_0);
        draw.visitInsn(ICONST_0);
        draw.visitVarInsn(ALOAD, 2);
        draw.visitVarInsn(ALOAD, 3);
        draw.visitInsn(ACONST_NULL);
        draw.visitInsn(ICONST_0);
        draw.visitMethodInsn(INVOKEVIRTUAL, f.itemRenderer, f.renderStatic, "(L" + f.stack + ";L" + f.context + ";IIL" + f.pose + ";L" + f.buffers
                + ";L" + f.level + ";I)V", false);
        draw.visitVarInsn(ALOAD, 1);
        draw.visitInsn(ICONST_0);
        draw.visitMethodInsn(INVOKEVIRTUAL, f.dispatcher, f.setRenderShadow, "(Z)V", false);
        draw.visitVarInsn(ALOAD, 1);
        draw.visitInsn(ACONST_NULL);
        draw.visitInsn(DCONST_0);
        draw.visitInsn(DCONST_0);
        draw.visitInsn(DCONST_0);
        draw.visitInsn(FCONST_0);
        draw.visitInsn(FCONST_0);
        draw.visitVarInsn(ALOAD, 2);
        draw.visitVarInsn(ALOAD, 3);
        draw.visitInsn(ICONST_0);
        draw.visitMethodInsn(INVOKEVIRTUAL, f.dispatcher, f.renderEntity, "(L" + f.entity + ";DDDFFL" + f.pose + ";L" + f.buffers + ";I)V", false);
        draw.visitInsn(RETURN);
        draw.visitMaxs(0, 0);
        draw.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
