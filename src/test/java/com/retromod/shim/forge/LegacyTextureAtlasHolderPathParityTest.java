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

/** Every transform path rebases an old sprite atlas holder, like JEI's, onto its jar's embedded copy, nested jars too. */
class LegacyTextureAtlasHolderPathParityTest {

    private static final String MANAGER = "net/minecraft/client/renderer/texture/TextureManager";
    private static final String ID = "net/minecraft/resources/Identifier";
    private static final String SPRITE = "net/minecraft/client/renderer/texture/TextureAtlasSprite";
    private static final String CONSTRUCTOR = "(L" + MANAGER + ";L" + ID + ";L" + ID + ";)V";
    private static final String GET_SPRITE = "getSprite(L" + ID + ";)L" + SPRITE + ";";
    // The host calls the embedded holder makes, which must keep the host's names in every copy.
    private static final Set<String> HOST_CALLS = new TreeSet<>(Set.of(
            MANAGER + ".register(L" + ID + ";Lnet/minecraft/client/renderer/texture/AbstractTexture;)V",
            "net/minecraft/client/renderer/texture/TextureAtlas.location()L" + ID + ";",
            "net/minecraft/client/renderer/texture/TextureAtlas.getSprite(L" + ID + ";)L" + SPRITE + ";",
            "net/minecraft/client/renderer/texture/TextureAtlas.upload(Lnet/minecraft/client/renderer/texture/SpriteLoader$Preparations;)V",
            "net/minecraft/client/renderer/texture/TextureAtlas.clearTextureData()V",
            "net/minecraft/client/renderer/texture/SpriteLoader.create(Lnet/minecraft/client/renderer/texture/TextureAtlas;)"
                    + "Lnet/minecraft/client/renderer/texture/SpriteLoader;",
            "net/minecraft/client/renderer/texture/SpriteLoader.loadAndStitch(Lnet/minecraft/server/packs/resources/ResourceManager;L" + ID
                    + ";ILjava/util/concurrent/Executor;Ljava/util/Set;)Ljava/util/concurrent/CompletableFuture;",
            "net/minecraft/client/renderer/texture/SpriteLoader$Preparations.readyForUpload()Ljava/util/concurrent/CompletableFuture;",
            "net/minecraft/server/packs/resources/PreparableReloadListener$SharedState.resourceManager()"
                    + "Lnet/minecraft/server/packs/resources/ResourceManager;",
            // The guard that keeps a holder off Minecraft's own atlas ids.
            ID + ".getNamespace()Ljava/lang/String;",
            ID + ".getPath()Ljava/lang/String;",
            ID + ".fromNamespaceAndPath(Ljava/lang/String;Ljava/lang/String;)L" + ID + ";"));

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
    @DisplayName("Forge: the runtime, CLI batch, CLI transform and AOT paths rebase a Forge 1.20.1 holder")
    void forge(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceForge(true);
        assertEveryPathAgrees(host, dir, Fixture.FORGE_1201);
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("NeoForge: every path rebases a NeoForge 1.21.1 holder and a Forge 1.20.1 one")
    void neoForge(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceNeoForge(true);
        assertEveryPathAgrees(host, dir.resolve("neo"), Fixture.NEOFORGE_1211);
        transformer.clearRedirectsForTesting();
        assertEveryPathAgrees(host, dir.resolve("forge"), Fixture.FORGE_1201);
    }

    @ParameterizedTest(name = "Fabric {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("Fabric: every path rebases a Fabric 1.21.1 holder after the intermediary remap")
    void fabric(String host, @TempDir Path dir) throws Exception {
        assertEveryPathAgrees(host, dir, Fixture.FABRIC_1211);
    }

    private void assertEveryPathAgrees(String host, Path dir, Fixture fixture) throws Exception {
        Files.createDirectories(dir);
        Path input = dir.resolve("sprites.jar");
        Files.write(input, modJar(fixture));

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("runtime", summary(runtime(input, dir, host, fixture), fixture));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("sprites.jar"));
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
            expected.put(prefix + "holder", "extends the embedded LegacyTextureAtlasHolder, whose constructor and getSprite it links to");
            expected.put(prefix + "embedded", "a host PreparableReloadListener that calls " + HOST_CALLS);
        }
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), fixture + " on " + host + ", " + output.getKey());
        }
    }

    /** What the loader's entry point registers on the host, mappings and shims, then one runtime transform. */
    private Path runtime(Path input, Path dir, String host, Fixture fixture) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        Path out = Files.createDirectory(dir.resolve("runtime"));
        if (fixture == Fixture.FABRIC_1211) {
            IntermediaryToMojangMapper.applyTo(transformer);
            registerShims(host, Set.of("fabric", "common"));
            return new FabricModTransformer(host).transformMod(input, out);
        }
        // RetromodForge and RetromodNeoForge decode the mod's SRG names on Mojang-named hosts.
        SrgToMojangMapper.getInstance().applyTo(transformer);
        registerShims(host, McReflect.isNeoForge() ? Set.of("neoforge", "forge", "common") : Set.of("forge", "common"));
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        Path written = new ForgeModTransformer(host).transformMod(input, out);
        assertNotNull(written, "the runtime wrote a jar");
        return written;
    }

    private void registerShims(String host, Set<String> loaders) {
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (loaders.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
    }

    private static Map<String, String> summary(Path jar, Fixture fixture) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            describe(file, "mod/GuiSprites", "", found);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry(fixture.nested)).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, "fixturelib/LibSprites", "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    // The holder's base, and that the base has the constructor and getSprite the holder uses.
    private static void describe(JarFile jar, String holderClass, String prefix, Map<String, String> found) throws Exception {
        ClassNode holder = node(jar, holderClass);
        String base = holder.superName;
        boolean embedded = base.startsWith(SyntheticEmbedder.PREFIX) && base.endsWith("/LegacyTextureAtlasHolder")
                && jar.getJarEntry(base + ".class") != null;
        if (!embedded) {
            found.put(prefix + "holder", "extends " + base);
            return;
        }
        ClassNode rebuilt = node(jar, base);
        MethodInsnNode superCall = null;
        for (AbstractInsnNode insn : method(holder, "<init>").instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("<init>") && call.owner.equals(base)) superCall = call;
        }
        boolean links = superCall != null && superCall.desc.equals(CONSTRUCTOR) && hasMethod(rebuilt, "<init>", CONSTRUCTOR)
                && hasMethod(holder, "getSprite", GET_SPRITE.substring("getSprite".length()))
                && hasMethod(rebuilt, "getSprite", GET_SPRITE.substring("getSprite".length()));
        found.put(prefix + "holder", "extends the embedded LegacyTextureAtlasHolder, whose constructor and getSprite it "
                + (links ? "links to" : "does not match: " + (superCall == null ? "no super call" : superCall.desc)));
        Set<String> hostCalls = new TreeSet<>();
        for (MethodNode m : rebuilt.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call && !call.owner.startsWith(SyntheticEmbedder.PREFIX)
                        && !call.owner.startsWith("java/") && !call.name.equals("<init>")) {
                    hostCalls.add(call.owner + "." + call.name + call.desc);
                }
            }
        }
        boolean listener = rebuilt.interfaces.contains("net/minecraft/server/packs/resources/PreparableReloadListener");
        found.put(prefix + "embedded", (listener ? "a host PreparableReloadListener" : "no reload listener") + " that calls " + hostCalls);
    }

    private static boolean hasMethod(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static ClassNode node(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return ModConstructorHooksTest.node(jar.getInputStream(entry).readAllBytes());
    }

    /** The names an old holder is written in: SRG for Forge 1.20.1, Mojang for NeoForge 1.21.1, intermediary for Fabric 1.21.1. */
    enum Fixture {
        FORGE_1201("net/minecraft/client/resources/TextureAtlasHolder", MANAGER, "net/minecraft/resources/ResourceLocation", SPRITE,
                "m_118901_", "META-INF/jarjar/lib.jar"),
        NEOFORGE_1211("net/minecraft/client/resources/TextureAtlasHolder", MANAGER, "net/minecraft/resources/ResourceLocation", SPRITE,
                "getSprite", "META-INF/jarjar/lib.jar"),
        FABRIC_1211("net/minecraft/class_4075", "net/minecraft/class_1060", "net/minecraft/class_2960", "net/minecraft/class_1058",
                "method_18667", "META-INF/jars/lib.jar");

        final String holder;
        final String manager;
        final String id;
        final String sprite;
        final String getSprite;
        final String nested;

        Fixture(String holder, String manager, String id, String sprite, String getSprite, String nested) {
            this.holder = holder;
            this.manager = manager;
            this.id = id;
            this.sprite = sprite;
            this.getSprite = getSprite;
            this.nested = nested;
        }
    }

    /** A mod with its own sprite atlas holder, and a nested library mod with the same. */
    private static byte[] modJar(Fixture fixture) throws Exception {
        Map<String, byte[]> lib = files("fixturelib", "fixturelib/LibSprites", fixture, null);
        Map<String, byte[]> mod = files("spritesmod", "mod/GuiSprites", fixture, fixture.nested);
        mod.put(fixture.nested, LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    private static Map<String, byte[]> files(String modId, String rendererClass, Fixture fixture, String nestedJar) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        switch (fixture) {
            case FORGE_1201 -> files.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml(modId));
            case NEOFORGE_1211 -> files.put("META-INF/neoforge.mods.toml", LegacyItemRendererPathParityTest.neoforgeToml(modId));
            case FABRIC_1211 -> files.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0\","
                    + "\"depends\":{\"minecraft\":\"1.21.1\"}" + (nestedJar == null ? "" : ",\"jars\":[{\"file\":\"" + nestedJar + "\"}]")
                    + "}").getBytes(StandardCharsets.UTF_8));
        }
        files.put(rendererClass + ".class", holder(rendererClass, fixture));
        return files;
    }

    // As JEI's sprite uploader: super(manager, atlas, info) and a public getSprite that calls the base's.
    private static byte[] holder(String name, Fixture fixture) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, fixture.holder, null);
        String id = "L" + fixture.id + ";";
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + fixture.manager + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitInsn(ACONST_NULL);
        init.visitInsn(ACONST_NULL);
        init.visitMethodInsn(INVOKESPECIAL, fixture.holder, "<init>", "(L" + fixture.manager + ";" + id + id + ")V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        String spriteDesc = "(" + id + ")L" + fixture.sprite + ";";
        MethodVisitor sprite = cw.visitMethod(ACC_PUBLIC, fixture.getSprite, spriteDesc, null, null);
        sprite.visitCode();
        sprite.visitVarInsn(ALOAD, 0);
        sprite.visitVarInsn(ALOAD, 1);
        sprite.visitMethodInsn(INVOKESPECIAL, fixture.holder, fixture.getSprite, spriteDesc, false);
        sprite.visitInsn(ARETURN);
        sprite.visitMaxs(0, 0);
        sprite.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
