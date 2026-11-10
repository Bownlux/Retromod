/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

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
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 26.2's smaller API removals, from NetworkHooks to the biome builders, on every translation path. */
class ForgeSmallRemovalsPathTest {

    private static final String USES = "mod/Uses";
    private static final String L_PLAYER = "Lnet/minecraft/server/level/ServerPlayer;";
    private static final String L_PROVIDER = "Lnet/minecraft/world/MenuProvider;";
    private static final String L_POS = "Lnet/minecraft/core/BlockPos;";
    private static final String TAG_KEY = "Lnet/minecraft/tags/TagKey;";
    private static final String BIOME_BUILDER = "net/minecraftforge/common/world/BiomeGenerationSettingsBuilder";

    @TempDir
    Path tempDir;

    private String savedTarget;
    private boolean savedForge;

    @BeforeEach
    void forgeTarget() {
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
        savedForge = McReflect.isForceForge();
        // RetromodForge and --target-loader forge set this before any shim registers.
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        ModVersionDetector.setDefaultPreferredLoader("forge");
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(false);
        ModVersionDetector.setDefaultPreferredLoader(null);
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} on Forge {1}")
    @CsvSource({"runtime, 26.2", "runtime, 26.3", "transform, 26.2", "batch, 26.2", "aot, 26.2"})
    @DisplayName("A Forge 1.20.1 mod's removed API calls reach the host's replacements or embedded helpers")
    void removedCallsAreRewritten(String path, String host) throws Exception {
        Path output = translate(path, host, writeMod());
        List<String> refs = references(output, USES);

        assertTrue(refs.stream().noneMatch(r -> r.startsWith("net/minecraftforge/network/NetworkHooks.")), refs.toString());
        assertTrue(refs.contains("net/minecraftforge/common/Tags$Items.TOOLS_SHEAR"), refs.toString());
        assertTrue(refs.contains("net/minecraft/core/registries/Registries.ENCHANTMENT"), refs.toString());
        assertTrue(refs.stream().noneMatch(r -> r.startsWith(BIOME_BUILDER)), refs.toString());
        for (String helper : List.of("LegacyNetworkHooks.openScreen", "LegacyForgeModAttributeObjects.ENTITY_REACH",
                "LegacyToolActions.SHIELD_BLOCK", "LegacyMobGriefing.getMobGriefingEvent", "LegacyBiomeCarvers.carvers",
                "LegacySpawns.spawnerList")) {
            String call = refs.stream().filter(r -> r.contains(helper + "(")).findFirst()
                    .orElseThrow(() -> new AssertionError(path + " must call " + helper + ": " + refs));
            assertTrue(call.startsWith(SyntheticEmbedder.PREFIX), "the helper is embedded in the mod: " + call);
            String owner = call.substring(0, call.indexOf('.'));
            assertTrue(hasEntry(output, owner + ".class"), owner + " travels in the jar");
        }
    }

    /** Translates {@code input} for a Forge {@code host} through one path and returns the output jar. */
    private Path translate(String path, String host, Path input) throws Exception {
        Path out = Files.createDirectories(tempDir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> {
                registerForgeHost(host);
                yield new ForgeModTransformer(host).transformMod(input, out);
            }
            case "transform" -> {
                CliHarness.transform(input, out.resolve("out.jar"), host);
                yield out.resolve("out.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("out.jar"), host);
                yield out.resolve("out.jar");
            }
            default -> {
                CliHarness.batch(input.getParent(), out, host);
                yield out.resolve(input.getFileName());
            }
        };
        assertNotNull(output, path + " must produce a jar");
        assertTrue(Files.isRegularFile(output), path + " must produce " + output);
        return output;
    }

    /** Registers what RetromodForge registers on a Forge {@code host} that runs Mojang names. */
    private static void registerForgeHost(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("forge", "common").contains(shim.getModLoaderType())
                    && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
    }

    private Path writeMod() throws Exception {
        Path input = Files.createDirectories(tempDir.resolve("in")).resolve("smallmod-1.20.1.jar");
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"smallmod\"\nversion=\"1.0.0\"\n"
                + "[[dependencies.smallmod]]\nmodId=\"forge\"\nmandatory=true\nversionRange=\"[47,)\"\n"
                + "[[dependencies.smallmod]]\nmodId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.20.1,1.21)\"\n";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new JarEntry("META-INF/mods.toml"));
            out.write(toml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry(USES + ".class"));
            out.write(uses());
            out.closeEntry();
        }
        return input;
    }

    /** One call or read of each removed member, as a Forge 1.20.1 mod writes them. */
    private static byte[] uses() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, USES, null, "java/lang/Object", null);
        MethodVisitor open = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "open", "(" + L_PLAYER + L_PROVIDER + L_POS + ")V", null, null);
        open.visitCode();
        open.visitVarInsn(ALOAD, 0);
        open.visitVarInsn(ALOAD, 1);
        open.visitVarInsn(ALOAD, 2);
        open.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/network/NetworkHooks", "openScreen",
                "(" + L_PLAYER + L_PROVIDER + L_POS + ")V", false);
        open.visitInsn(RETURN);
        open.visitMaxs(0, 0);
        open.visitEnd();

        read(cw, "reach", "net/minecraftforge/common/ForgeMod", "ENTITY_REACH", "Lnet/minecraftforge/registries/RegistryObject;");
        read(cw, "shears", "net/minecraftforge/common/Tags$Items", "SHEARS", TAG_KEY);
        read(cw, "shield", "net/minecraftforge/common/ToolActions", "SHIELD_BLOCK", "Lnet/minecraftforge/common/ToolAction;");
        read(cw, "enchantments", "net/minecraftforge/registries/ForgeRegistries$Keys", "ENCHANTMENTS",
                "Lnet/minecraft/resources/ResourceKey;");

        String grief = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;)Z";
        MethodVisitor griefs = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "grief", grief, null, null);
        griefs.visitCode();
        griefs.visitVarInsn(ALOAD, 0);
        griefs.visitVarInsn(ALOAD, 1);
        griefs.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/event/ForgeEventFactory", "getMobGriefingEvent", grief, false);
        griefs.visitInsn(IRETURN);
        griefs.visitMaxs(0, 0);
        griefs.visitEnd();

        MethodVisitor carvers = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "carvers",
                "(L" + BIOME_BUILDER + ";)Ljava/util/List;", null, null);
        carvers.visitCode();
        carvers.visitVarInsn(ALOAD, 0);
        carvers.visitInsn(ACONST_NULL);
        carvers.visitMethodInsn(INVOKEVIRTUAL, BIOME_BUILDER, "getCarvers",
                "(Lnet/minecraft/world/level/levelgen/GenerationStep$Carving;)Ljava/util/List;", false);
        carvers.visitInsn(ARETURN);
        carvers.visitMaxs(0, 0);
        carvers.visitEnd();

        String spawnBuilder = "net/minecraftforge/common/world/MobSpawnSettingsBuilder";
        String category = "Lnet/minecraft/world/entity/MobCategory;";
        MethodVisitor spawns = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "spawns",
                "(L" + spawnBuilder + ";" + category + ")Ljava/util/List;", null, null);
        spawns.visitCode();
        spawns.visitVarInsn(ALOAD, 0);
        spawns.visitVarInsn(ALOAD, 1);
        spawns.visitMethodInsn(INVOKEVIRTUAL, spawnBuilder, "getSpawner", "(" + category + ")Ljava/util/List;", false);
        spawns.visitInsn(ARETURN);
        spawns.visitMaxs(0, 0);
        spawns.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void read(ClassWriter cw, String name, String owner, String field, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, owner, field, desc);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static boolean hasEntry(Path jar, String name) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getJarEntry(name) != null;
        }
    }

    /** Every field and method reference in one class of {@code jar}, as owner.name(desc) or owner.name. */
    private static List<String> references(Path jar, String className) throws Exception {
        ClassNode node = new ClassNode();
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(className + ".class");
            assertNotNull(entry, className + " is missing from " + jar.getFileName());
            new ClassReader(file.getInputStream(entry).readAllBytes()).accept(node, 0);
        }
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
                else if (insn instanceof FieldInsnNode field) refs.add(field.owner + "." + field.name);
            }
        }
        return refs;
    }
}
