/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Every transform path rebuilds NeoForge's old dynamic model API inside the jar and its nested jar, for NeoForge and Forge mods on NeoForge. */
class LegacyNeoForgeModelPathParityTest {

    private static final String OLD_BAKED_MODEL = "net/minecraft/client/resources/model/BakedModel";
    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String OLD_RENDER_TYPE = "Lnet/minecraft/client/renderer/RenderType;";
    // What the backpack's calls become: model data helpers, the shared render type set and its layer table, all embedded.
    private static final List<String> CALLS = List.of("embedded LegacyModelDataCalls.getQuads", "embedded LegacyModelDataCalls.getRenderTypes",
            "embedded LegacyModelDataCalls.getParticleIcon", "embedded LegacyModelDataCalls.getModelData", "embedded ChunkRenderTypeSet.of",
            "embedded LegacyChunkLayers.getRenderLayers");

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void neoForge() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
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
    @DisplayName("A NeoForge 1.21.1 mod: runtime, CLI batch, CLI transform and AOT agree, nested jar too, and every embedded call links")
    void neoForgeMod(String host, @TempDir Path dir) throws Exception {
        assertEveryPathAgrees(host, dir, Fixture.NEOFORGE_1211);
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("A Forge 1.20.1 mod on NeoForge: the same on every path")
    void forgeModOnNeoForge(String host, @TempDir Path dir) throws Exception {
        assertEveryPathAgrees(host, dir, Fixture.FORGE_1201);
    }

    private void assertEveryPathAgrees(String host, Path dir, Fixture fixture) throws Exception {
        Path input = dir.resolve("packs.jar");
        Files.write(input, modJar(fixture));

        Map<String, Path> outputs = new LinkedHashMap<>();
        outputs.put("runtime", runtime(input, dir, host));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("packs.jar"));
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
                assertBackpack(jar, "mod/", where);
                Path nested = Files.createTempFile("nested-", ".jar");
                try {
                    Files.write(nested, jar.getInputStream(jar.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes());
                    try (JarFile lib = new JarFile(nested.toFile())) {
                        assertBackpack(lib, "fixturelib/", where + ", nested jar");
                    }
                } finally {
                    Files.deleteIfExists(nested);
                }
            }
        }
    }

    private static void assertBackpack(JarFile jar, String pkg, String where) throws Exception {
        ClassNode backpack = node(jar, pkg + "Backpack");
        assertEquals(List.of("embedded IDynamicBakedModel"), backpack.interfaces.stream().map(i -> embedded(jar, i)).toList(),
                where + ": the model implements the jar's own copy of the rebuilt interface");
        assertEquals(CALLS, calls(jar, backpack, "draw"), where);
        assertEquals(List.of("embedded LegacyRegisterGeometryLoaders.register"), calls(jar, backpack, "onLoaders"), where);
        assertEquals(List.of(), LegacyBakedItemPathParityTest.unresolved(jar), where + ": every call into an embedded class names a member it has");
    }

    /** What the loader's entry point registers on a NeoForge host, mappings and shims, then one runtime transform. */
    private Path runtime(Path input, Path dir, String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        SrgToMojangMapper.getInstance().applyTo(transformer);
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("neoforge", "forge", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        Path written = new ForgeModTransformer(host).transformMod(input, Files.createDirectory(dir.resolve("runtime")));
        assertNotNull(written, "the runtime wrote a jar");
        return written;
    }

    private static List<String> calls(JarFile jar, ClassNode node, String name) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method(node, name).instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(embedded(jar, call.owner) + "." + call.name);
        }
        return calls;
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

    /** One loader's names for the old model API: its package root, its model data and the geometry register's first argument. */
    enum Fixture {
        NEOFORGE_1211("net/neoforged/neoforge/", "Lnet/neoforged/neoforge/client/model/data/ModelData;", "Lnet/minecraft/resources/ResourceLocation;"),
        FORGE_1201("net/minecraftforge/", "Lnet/minecraftforge/client/model/data/ModelData;", "Ljava/lang/String;");

        final String root;
        final String data;
        final String registerKey;

        Fixture(String root, String data, String registerKey) {
            this.root = root;
            this.data = data;
            this.registerKey = registerKey;
        }
    }

    /** A mod with Sophisticated Backpacks' model, and a nested library mod with the same. */
    private static byte[] modJar(Fixture fixture) throws Exception {
        Map<String, byte[]> lib = files("fixturelib", "fixturelib/", fixture);
        Map<String, byte[]> mod = files("packsmod", "mod/", fixture);
        mod.put("META-INF/jarjar/lib.jar", LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    private static Map<String, byte[]> files(String modId, String pkg, Fixture fixture) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (fixture == Fixture.NEOFORGE_1211) {
            files.put("META-INF/neoforge.mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\""
                    + modId + "\"\nversion=\"1.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\ntype=\"required\"\n"
                    + "versionRange=\"[1.21.1,1.21.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8));
        } else {
            files.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml(modId));
        }
        files.put(pkg + "Backpack.class", backpack(pkg + "Backpack", fixture));
        return files;
    }

    // A dynamic model, the model data calls it makes on its parts, and its geometry loader listener.
    private static byte[] backpack(String name, Fixture f) {
        String set = "L" + f.root + "client/ChunkRenderTypeSet;";
        String event = f.root + "client/event/ModelEvent$RegisterGeometryLoaders";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", new String[]{f.root + "client/model/IDynamicBakedModel"});
        MethodVisitor draw = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "draw", "()V", null, null);
        draw.visitCode();
        invoke(draw, INVOKEINTERFACE, OLD_BAKED_MODEL, "getQuads", "(" + STATE + "Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;"
                + f.data + OLD_RENDER_TYPE + ")Ljava/util/List;");
        invoke(draw, INVOKEINTERFACE, OLD_BAKED_MODEL, "getRenderTypes", "(" + STATE + "Lnet/minecraft/util/RandomSource;" + f.data + ")" + set);
        invoke(draw, INVOKEINTERFACE, OLD_BAKED_MODEL, "getParticleIcon", "(" + f.data + ")Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;");
        invoke(draw, INVOKEINTERFACE, OLD_BAKED_MODEL, "getModelData", "(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
                + STATE + f.data + ")" + f.data);
        invoke(draw, INVOKESTATIC, f.root + "client/ChunkRenderTypeSet", "of", "([" + OLD_RENDER_TYPE + ")" + set);
        invoke(draw, INVOKESTATIC, "net/minecraft/client/renderer/ItemBlockRenderTypes", "getRenderLayers", "(" + STATE + ")" + set);
        draw.visitInsn(RETURN);
        draw.visitMaxs(0, 0);
        draw.visitEnd();

        MethodVisitor loaders = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "onLoaders", "(L" + event + ";)V", null, null);
        loaders.visitCode();
        loaders.visitVarInsn(ALOAD, 0);
        loaders.visitInsn(ACONST_NULL);
        loaders.visitInsn(ACONST_NULL);
        loaders.visitMethodInsn(INVOKEVIRTUAL, event, "register", "(" + f.registerKey + "L" + f.root + "client/model/geometry/IGeometryLoader;)V", false);
        loaders.visitInsn(RETURN);
        loaders.visitMaxs(0, 0);
        loaders.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // Pushes a receiver when the call has one, a null per argument, then drops the result.
    private static void invoke(MethodVisitor mv, int opcode, String owner, String name, String desc) {
        if (opcode != INVOKESTATIC) mv.visitInsn(ACONST_NULL);
        for (Type argument : Type.getArgumentTypes(desc)) {
            mv.visitInsn(argument.getSort() == Type.OBJECT || argument.getSort() == Type.ARRAY ? ACONST_NULL : ICONST_0);
        }
        mv.visitMethodInsn(opcode, owner, name, desc, opcode == INVOKEINTERFACE);
        if (Type.getReturnType(desc).getSort() != Type.VOID) mv.visitInsn(POP);
    }
}
