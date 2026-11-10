/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
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

/** Every class-transform path sends the world-load members an old mod uses to the same bridges, outer and nested jar alike. */
class WorldLoadBridgePathParityTest {

    private static final String NESTED = "META-INF/jarjar/lib.jar";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} {1} on {2}")
    @CsvSource({"forge, runtime, 26.2", "forge, runtime, 26.3", "forge, transform, 26.2", "forge, transform, 26.3",
            "forge, batch, 26.2", "forge, aot, 26.2", "forge, aot, 26.3"})
    @DisplayName("a Forge 1.20.1 mod's world-load members reach the bridges embedded in its jar")
    void forgeMembersReachTheBridges(String loader, String path, String host) throws Exception {
        Path output = translate(loader, path, host, forgeJar());
        // 26.3 has material rules instead, with the old API rebuilt on them.
        boolean surfaceRules = host.equals("26.2");
        List<String> expected = List.of(
                "ChunkPos.pack", "embedded WeightedEntry.wrap", "embedded WeightedEntryMembers.getData",
                "embedded WeightedEntryMembers.getTotalWeight", "embedded CompoundTagSerializer.get",
                "embedded LegacyLootTables.lootTableId", "embedded LegacyLootTables.setLootTable",
                "embedded Legacy262Members.setDetail",
                surfaceRules ? "embedded LegacySurfaceRules.isBiome" : "embedded LegacyMaterialRules.isBiome",
                surfaceRules ? "embedded LegacySurfaceRules.biome" : "embedded LegacyMaterialRules.biome",
                "embedded LegacyVillagerData.profession", CONSTRUCTED);
        assertEquals(Map.of("mod/WorldLoad", expected, "nested:lib/WorldLoad", expected), summary(output));
    }

    @ParameterizedTest(name = "{0} {1} on {2}")
    @CsvSource({"neoforge, runtime, 26.3", "neoforge, transform, 26.2", "neoforge, aot, 26.3",
            "fabric, runtime, 26.3", "fabric, transform, 26.2", "fabric, runtime, 1.21.11"})
    @DisplayName("the weighted entries and chunk keys every loader shares reach the same bridges")
    void sharedMembersOnOtherLoaders(String loader, String path, String host) throws Exception {
        Path output = translate(loader, path, host, sharedJar(loader));
        boolean neoForge = loader.equals("neoforge");
        // A Fabric 1.21.11 host still has asLong under its intermediary name.
        String chunkKey = host.equals("1.21.11") ? "class_1923.method_8331" : "ChunkPos.pack";
        String wrap = loader.equals("fabric") && host.equals("1.21.11") ? "method_34980" : "wrap";
        List<String> expected = new ArrayList<>(List.of(chunkKey, "embedded WeightedEntry." + wrap,
                "embedded WeightedEntryMembers.getData", "embedded WeightedEntryMembers.getTotalWeight",
                "embedded LegacyVillagerData.profession"));
        // NeoForge registers the serializer from the mod's constructor; Fabric has no constructor to start it from.
        if (neoForge) expected.addAll(List.of("embedded CompoundTagSerializer.get", CONSTRUCTED));
        assertEquals(Map.of("mod/WorldLoad", expected, "nested:lib/WorldLoad", expected), summary(output));
    }

    /** The registration start the mod's constructor gains when the jar names the old serializer field. */
    private static final String CONSTRUCTED = "constructor: embedded CompoundTagSerializer.watch";

    /** Translates {@code jar} for a {@code loader} host through one path and returns the output jar. */
    private Path translate(String loader, String path, String host, byte[] jar) throws Exception {
        Path input = Files.createDirectories(dir.resolve("in")).resolve("worldload.jar");
        Files.write(input, jar);
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(loader.equals("fabric") ? null : loader);
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = out.resolve("worldload.jar");
        switch (path) {
            case "runtime" -> output = runtime(loader, host, input, out);
            case "transform" -> CliHarness.transform(input, output, host);
            case "aot" -> CliHarness.aot(input, output, host);
            default -> {
                CliHarness.batch(input.getParent(), out, host);
                try (var jars = Files.list(out)) {
                    List<Path> all = jars.filter(file -> file.toString().endsWith(".jar")).toList();
                    assertEquals(1, all.size(), "the batch wrote one jar: " + all);
                    output = all.get(0);
                }
            }
        }
        assertNotNull(output, path + " must produce a jar");
        assertTrue(Files.isRegularFile(output), path + " must produce " + output);
        return output;
    }

    /** Registers what the loader's entry point registers on {@code host}, then runs its mod transformer. */
    private Path runtime(String loader, String host, Path input, Path out) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        if (loader.equals("fabric")) {
            RetromodPreLaunch.registerShimsForTransform(host);
            return new FabricModTransformer(host).transformMod(input, out);
        }
        Set<String> types = loader.equals("forge") ? Set.of("forge", "common") : Set.of("neoforge", "forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        return new ForgeModTransformer(host).transformMod(input, out);
    }

    /** What {@code run()} reaches, for the mod and its nested library. */
    private static Map<String, List<String>> summary(Path output) throws Exception {
        Map<String, List<String>> found = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            found.put("mod/WorldLoad", describe(jar, "mod/WorldLoad"));
            // Forge-family jars nest under jarjar, Fabric jars under jars.
            JarEntry entry = jar.getJarEntry(NESTED);
            if (entry == null) entry = jar.getJarEntry("META-INF/jars/lib.jar");
            assertNotNull(entry, "the nested library is missing from " + output.getFileName());
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, jar.getInputStream(entry).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    found.put("nested:lib/WorldLoad", describe(lib, "lib/WorldLoad"));
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    /** Each call and field read of {@code run()} as {@code Owner.name}, marked embedded when the owner is a copy inside this jar. */
    private static List<String> describe(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className + ".class");
        assertNotNull(entry, className + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        List<String> reached = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (method.name.equals("run")) reached.addAll(reached(jar, method, ""));
        }
        // A constructor call to an embedded copy is a hook the transform added.
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) continue;
            for (String call : reached(jar, method, "constructor: ")) {
                if (call.startsWith("constructor: embedded ")) reached.add(call);
            }
        }
        return reached;
    }

    /** Each call and field access of {@code method} as {@code Owner.name}, after {@code prefix}. */
    private static List<String> reached(JarFile jar, MethodNode method, String prefix) {
        List<String> reached = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            String owner;
            String name;
            if (insn instanceof MethodInsnNode call) {
                owner = call.owner;
                name = call.name;
            } else if (insn instanceof FieldInsnNode field) {
                owner = field.owner;
                name = field.name;
            } else {
                continue;
            }
            String simple = owner.substring(owner.lastIndexOf('/') + 1);
            if (owner.startsWith(SyntheticEmbedder.PREFIX)) {
                assertNotNull(jar.getJarEntry(owner + ".class"), owner + " is not in " + jar.getName());
                reached.add(prefix + "embedded " + simple + "." + name);
            } else {
                reached.add(prefix + simple + "." + name);
            }
        }
        return reached;
    }

    /** A Forge 1.20.1 mod whose {@code run()} uses each member by its SRG id, and a nested library doing the same. */
    private static byte[] forgeJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", forgeToml("worldloadlib"));
        lib.put("lib/WorldLoad.class", forgeClass("lib/WorldLoad"));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", forgeToml("worldload"));
        mod.put("mod/WorldLoad.class", forgeClass("mod/WorldLoad"));
        mod.put(NESTED, LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    private static byte[] forgeToml(String modId) {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                + "\"\nversion=\"1.0.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] forgeClass(String name) {
        String random = "net/minecraft/util/random/";
        String container = "net/minecraft/world/level/block/entity/RandomizableContainerBlockEntity";
        String id = "Lnet/minecraft/resources/ResourceLocation;";
        String rules = "net/minecraft/world/level/levelgen/SurfaceRules";
        return runClass(name, "Lnet/minecraftforge/fml/common/Mod;", mv -> {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.ICONST_0);
            call(mv, Opcodes.INVOKESTATIC, "net/minecraft/world/level/ChunkPos", "m_45589_", "(II)J", false);
            mv.visitInsn(Opcodes.POP2);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ICONST_1);
            call(mv, Opcodes.INVOKESTATIC, random + "WeightedEntry", "m_146290_",
                    "(Ljava/lang/Object;I)L" + random + "WeightedEntry$Wrapper;", true);
            call(mv, Opcodes.INVOKEVIRTUAL, random + "WeightedEntry$Wrapper", "m_146310_", "()Ljava/lang/Object;", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            call(mv, Opcodes.INVOKESTATIC, random + "WeightedRandom", "m_146312_", "(Ljava/util/List;)I", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/network/syncher/EntityDataSerializers", "f_135042_",
                    "Lnet/minecraft/network/syncher/EntityDataSerializer;");
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitFieldInsn(Opcodes.GETFIELD, container, "f_59605_", id);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.LCONST_0);
            call(mv, Opcodes.INVOKEVIRTUAL, container, "m_59626_", "(" + id + "J)V", false);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            call(mv, Opcodes.INVOKEVIRTUAL, "net/minecraft/SystemReport", "m_143522_",
                    "(Ljava/lang/String;Ljava/util/function/Supplier;)V", false);
            mv.visitInsn(Opcodes.ACONST_NULL);
            call(mv, Opcodes.INVOKESTATIC, rules, "m_189416_",
                    "([Lnet/minecraft/resources/ResourceKey;)L" + rules + "$ConditionSource;", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitFieldInsn(Opcodes.GETFIELD, rules + "$Context", "f_189555_", "Ljava/util/function/Supplier;");
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            call(mv, Opcodes.INVOKEVIRTUAL, "net/minecraft/world/entity/npc/VillagerData", "m_35571_",
                    "()Lnet/minecraft/world/entity/npc/VillagerProfession;", false);
            mv.visitInsn(Opcodes.POP);
        });
    }

    /** A NeoForge 1.21.1 or Fabric 1.21.1 mod using the shared members by its loader's names. */
    private static byte[] sharedJar(String loader) throws Exception {
        boolean fabric = loader.equals("fabric");
        String metadataPath = fabric ? "fabric.mod.json" : "META-INF/neoforge.mods.toml";
        String nested = fabric ? "META-INF/jars/lib.jar" : NESTED;
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put(metadataPath, sharedMetadata(fabric, "worldloadlib", null));
        lib.put("lib/WorldLoad.class", sharedClass("lib/WorldLoad", fabric));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put(metadataPath, sharedMetadata(fabric, "worldload", nested));
        mod.put("mod/WorldLoad.class", sharedClass("mod/WorldLoad", fabric));
        mod.put(nested, LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    private static byte[] sharedMetadata(boolean fabric, String modId, String nested) {
        String text = fabric
                ? "{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0.0\","
                        + "\"depends\":{\"minecraft\":\"1.21.1\",\"fabricloader\":\">=0.15.0\"}"
                        + (nested != null ? ",\"jars\":[{\"file\":\"" + nested + "\"}]" : "") + "}"
                : "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                        + "\"\nversion=\"1.0.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\n"
                        + "type=\"required\"\nversionRange=\"[1.21.1,1.22)\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sharedClass(String name, boolean fabric) {
        String chunkPos = fabric ? "net/minecraft/class_1923" : "net/minecraft/world/level/ChunkPos";
        String entry = fabric ? "net/minecraft/class_6008" : "net/minecraft/util/random/WeightedEntry";
        String wrapper = entry + (fabric ? "$class_6010" : "$Wrapper");
        String weightedRandom = fabric ? "net/minecraft/class_6011" : "net/minecraft/util/random/WeightedRandom";
        String villagerData = fabric ? "net/minecraft/class_3850" : "net/minecraft/world/entity/npc/VillagerData";
        String profession = fabric ? "net/minecraft/class_3852" : "net/minecraft/world/entity/npc/VillagerProfession";
        return runClass(name, fabric ? null : "Lnet/neoforged/fml/common/Mod;", mv -> {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.ICONST_0);
            call(mv, Opcodes.INVOKESTATIC, chunkPos, fabric ? "method_8331" : "asLong", "(II)J", false);
            mv.visitInsn(Opcodes.POP2);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ICONST_1);
            call(mv, Opcodes.INVOKESTATIC, entry, fabric ? "method_34980" : "wrap",
                    "(Ljava/lang/Object;I)L" + wrapper + ";", true);
            call(mv, Opcodes.INVOKEVIRTUAL, wrapper, fabric ? "method_34983" : "getData", "()Ljava/lang/Object;", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            call(mv, Opcodes.INVOKESTATIC, weightedRandom, fabric ? "method_34984" : "getTotalWeight", "(Ljava/util/List;)I", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            call(mv, Opcodes.INVOKEVIRTUAL, villagerData, fabric ? "method_16924" : "getProfession", "()L" + profession + ";", false);
            mv.visitInsn(Opcodes.POP);
            if (!fabric) {
                mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/network/syncher/EntityDataSerializers", "COMPOUND_TAG",
                        "Lnet/minecraft/network/syncher/EntityDataSerializer;");
                mv.visitInsn(Opcodes.POP);
            }
        });
    }

    private static void call(MethodVisitor mv, int opcode, String owner, String name, String desc, boolean itf) {
        mv.visitMethodInsn(opcode, owner, name, desc, itf);
    }

    /** A class whose {@code static void run()} holds what {@code body} writes, made a {@code @Mod} when {@code mod} names the annotation. */
    private static byte[] runClass(String name, String mod, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        if (mod != null) {
            var annotation = cw.visitAnnotation(mod, true);
            annotation.visit("value", name.startsWith("lib/") ? "worldloadlib" : "worldload");
            annotation.visitEnd();
        }
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
