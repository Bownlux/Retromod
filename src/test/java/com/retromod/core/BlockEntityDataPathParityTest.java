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

/** Every class-transform path gives a Forge 1.20.1 block entity the same ValueIO saves and member bridges, outer and nested jar alike. */
class BlockEntityDataPathParityTest {

    private static final String NESTED = "META-INF/jarjar/lib.jar";
    private static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    private static final String COMPOUND = "Lnet/minecraft/nbt/CompoundTag;";
    private static final String LOAD = "(Lnet/minecraft/world/level/storage/ValueInput;)V";

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
            "forge, batch, 26.2", "forge, aot, 26.2", "forge, aot, 26.3",
            "neoforge, runtime, 26.3", "neoforge, transform, 26.2", "neoforge, aot, 26.3"})
    @DisplayName("a Forge 1.20.1 block entity saves through ValueIO and reaches the bridges embedded in its jar")
    void blockEntityReachesTheBridges(String loader, String path, String host) throws Exception {
        Map<String, List<String>> found = summary(translate(loader, path, host, jar()));
        // Forge 26.2 dropped a block entity's persistent data; NeoForge keeps its own.
        boolean forge = loader.equals("forge");
        List<String> load = new ArrayList<>(List.of("embedded LegacyNbtMembers.forHost", "embedded LegacyRegistryNbt.provider",
                "embedded LegacyValueIoOverrides.superInput", "BlockEntity.loadAdditional"));
        List<String> save = new ArrayList<>(List.of("embedded LegacyRegistryNbt.provider",
                "embedded LegacyValueIoOverrides.superOutput", "BlockEntity.saveAdditional"));
        if (forge) {
            load.add("embedded LegacyBlockEntityData.load");
            save.add("embedded LegacyBlockEntityData.save");
        }
        save.add("embedded LegacyValueIoOverrides.mergeSuperOutput");
        List<String> run = List.of(forge ? "embedded LegacyBlockEntityData.getPersistentData" : "Chest.getPersistentData",
                "embedded NbtUtilsMembers.loadUUID", "embedded LegacyNbtMembers.loadAllItems",
                "embedded LegacyComponentJson.toJson", "embedded PiglinAiMembers.angerNearbyPiglins",
                "embedded LegacyShareTag.shareTag", "embedded LegacyLootData.of");
        for (String chest : List.of("mod/Chest", "nested:lib/Chest")) {
            assertEquals(List.of("embedded LegacyValueIoOverrides.loadSource", "embedded LegacyValueIoOverrides.registries",
                    "Chest.loadAdditional"), found.get(chest + " ValueIO load"), chest + " the host's load reaches the old one");
            assertEquals(load, found.get(chest + " load"), chest + " the old load reaches the host and the data");
            assertEquals(save, found.get(chest + " save"), chest + " the old save reaches the host and the data");
            assertEquals(run, found.get(chest + " run"), chest + " calls reach the rebuilt members");
        }
        // A subclass's super call reaches its mod parent's old method, which the jar names by its SRG id.
        assertEquals(List.of("Chest.load"), found.get("mod/BigChest load"), "the parent's own load still runs");
        assertEquals(List.of("Chest.saveAdditional"), found.get("mod/BigChest save"), "the parent's own save still runs");
    }

    /** Translates {@code jar} for a {@code loader} host through one path and returns the output jar. */
    private Path translate(String loader, String path, String host, byte[] jar) throws Exception {
        Path input = Files.createDirectories(dir.resolve("in")).resolve("chest.jar");
        Files.write(input, jar);
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(loader);
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = out.resolve("chest.jar");
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
        assertTrue(output != null && Files.isRegularFile(output), path + " must produce a jar");
        return output;
    }

    /** Registers what the loader's entry point registers on {@code host}, then runs its mod transformer. */
    private Path runtime(String loader, String host, Path input, Path out) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
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

    /** The calls of the chest's load, save and {@code run()}, for the mod and its nested library. */
    private static Map<String, List<String>> summary(Path output) throws Exception {
        Map<String, List<String>> found = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            describe(jar, "mod/Chest", "mod/Chest", found);
            describe(jar, "mod/BigChest", "mod/BigChest", found);
            JarEntry entry = jar.getJarEntry(NESTED);
            assertNotNull(entry, "the nested library is missing from " + output.getFileName());
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, jar.getInputStream(entry).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, "lib/Chest", "nested:lib/Chest", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static void describe(JarFile jar, String className, String label, Map<String, List<String>> found)
            throws Exception {
        JarEntry entry = jar.getJarEntry(className + ".class");
        assertNotNull(entry, className + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        for (MethodNode method : node.methods) {
            if (method.name.equals("loadAdditional") && method.desc.equals(LOAD)) {
                found.put(label + " ValueIO load", calls(jar, method));
            }
            if (method.name.equals("load") && method.desc.equals("(" + COMPOUND + ")V")) found.put(label + " load", calls(jar, method));
            if (method.name.equals("saveAdditional") && method.desc.equals("(" + COMPOUND + ")V")) {
                found.put(label + " save", calls(jar, method));
            }
            if (method.name.equals("run")) found.put(label + " run", calls(jar, method));
        }
    }

    /** Each call of {@code method} as {@code Owner.name}, marked embedded when the owner is a copy inside this jar. */
    private static List<String> calls(JarFile jar, MethodNode method) {
        List<String> reached = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            String simple = call.owner.substring(call.owner.lastIndexOf('/') + 1);
            if (call.owner.startsWith(SyntheticEmbedder.PREFIX)) {
                assertNotNull(jar.getJarEntry(call.owner + ".class"), call.owner + " is not in " + jar.getName());
                reached.add("embedded " + simple + "." + call.name);
            } else {
                reached.add(simple + "." + call.name);
            }
        }
        return reached;
    }

    /** A Forge 1.20.1 mod whose block entity uses each member by its SRG id, and a nested library doing the same. */
    private static byte[] jar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", toml("chestlib"));
        lib.put("lib/Chest.class", chest("lib/Chest"));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", toml("chest"));
        mod.put("mod/Chest.class", chest("mod/Chest"));
        mod.put("mod/BigChest.class", bigChest());
        mod.put(NESTED, LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    private static byte[] toml(String modId) {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                + "\"\nversion=\"1.0.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8);
    }

    /** {@code class BigChest extends Chest}, whose load and save only call their parent's, by SRG id. */
    private static byte[] bigChest() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "mod/BigChest", null, "mod/Chest", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "mod/Chest", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        for (String[] method : new String[][] {{"m_142466_", "1"}, {"m_183515_", "4"}}) {
            MethodVisitor mv = cw.visitMethod(Integer.parseInt(method[1]) == 1 ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED,
                    method[0], "(" + COMPOUND + ")V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "mod/Chest", method[0], "(" + COMPOUND + ")V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code class Chest extends BlockEntity} with a 1.20.1 load and save, and a {@code run()} of world-load calls. */
    private static byte[] chest(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, BLOCK_ENTITY, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitInsn(Opcodes.ACONST_NULL);
        init.visitInsn(Opcodes.ACONST_NULL);
        init.visitInsn(Opcodes.ACONST_NULL);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, BLOCK_ENTITY, "<init>", "(Lnet/minecraft/world/level/block/entity/"
                + "BlockEntityType;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        // load (m_142466_) and saveAdditional (m_183515_), each calling its parent first.
        for (String[] method : new String[][] {{"m_142466_", "1"}, {"m_183515_", "4"}}) {
            MethodVisitor mv = cw.visitMethod(Integer.parseInt(method[1]) == 1 ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED,
                    method[0], "(" + COMPOUND + ")V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BLOCK_ENTITY, method[0], "(" + COMPOUND + ")V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, name, "getPersistentData", "()" + COMPOUND, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/nbt/NbtUtils", "m_129233_",
                "(Lnet/minecraft/nbt/Tag;)Ljava/util/UUID;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/world/ContainerHelper", "m_18980_",
                "(" + COMPOUND + "Lnet/minecraft/core/NonNullList;)V", false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/chat/Component$Serializer", "m_130703_",
                "(Lnet/minecraft/network/chat/Component;)Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/world/entity/monster/piglin/PiglinAi", "m_34873_",
                "(Lnet/minecraft/world/entity/player/Player;Z)V", false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/item/Item", "getShareTag",
                "(Lnet/minecraft/world/item/ItemStack;)" + COMPOUND, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/server/MinecraftServer", "m_278653_",
                "()Lnet/minecraft/world/level/storage/loot/LootDataManager;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
