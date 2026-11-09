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

/** Every path moves a 1.21.1 mod's own single loot entry, in the mod and its nested library, onto the rebuilt base on 26.3. */
class LootSingletonPathParityTest {

    private static final String ENTRIES = "net/minecraft/world/level/storage/loot/entries/";
    private static final String SINGLETON = ENTRIES + "LootPoolSingletonContainer";
    private static final String UNIFORM = ENTRIES + "UniformContainerBase";
    private static final String BASE = "com/retromod/legacyapi/minecraft/world/level/storage/loot/entries/LegacyLootPoolSingletonContainer";
    private static final String SUPPORT = "com/retromod/shim/common/embedded/LegacyLootSerializers";
    private static final String LIST_CONSTRUCTOR = "(IILjava/util/List;Ljava/util/List;)V";
    private static final String FIELDS = "(Lcom/mojang/serialization/codecs/RecordCodecBuilder$Instance;)Lcom/mojang/datafixers/Products$P4;";

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
    @CsvSource({"forge, runtime, 26.3", "forge, transform, 26.3", "forge, batch, 26.3", "forge, aot, 26.3",
            "neoforge, runtime, 26.3", "neoforge, transform, 26.3", "neoforge, batch, 26.3", "neoforge, aot, 26.3",
            "fabric, runtime, 26.3", "fabric, transform, 26.3", "fabric, aot, 26.3",
            "neoforge, runtime, 26.2", "fabric, transform, 26.2"})
    @DisplayName("the entry extends the embedded rebuilt base on 26.3 and keeps the host's on 26.2")
    void everyPathRebasesTheEntry(String loader, String path, String host) throws Exception {
        boolean bridged = RetromodVersion.compareMcVersions(host, "26.3") >= 0;
        Path output = translate(loader, path, host, writeMod(loader));

        try (JarFile jar = new JarFile(output.toFile())) {
            Path nested = Files.createTempFile(dir, "nested-", ".jar");
            Files.write(nested, jar.getInputStream(jar.getJarEntry(nestedPath(loader))).readAllBytes());
            try (JarFile lib = new JarFile(nested.toFile())) {
                for (Map.Entry<String, JarFile> part : Map.of("mod", jar, "lib", lib).entrySet()) {
                    String where = part.getKey() + " (" + loader + " " + path + " on " + host + ")";
                    ClassNode entry = read(part.getValue(), part.getKey() + "/NamedItemEntry");
                    List<MethodInsnNode> init = calls(method(entry, "<init>"));
                    MethodInsnNode superCall = init.get(init.size() - 1);
                    if (!bridged) {
                        assertEquals(SINGLETON, entry.superName, where);
                        assertEquals(SINGLETON + ".<init>" + LIST_CONSTRUCTOR, superCall.owner + ".<init>" + superCall.desc, where);
                        continue;
                    }
                    assertTrue(entry.superName.startsWith(SyntheticEmbedder.PREFIX) && entry.superName.endsWith(BASE),
                            where + ": " + entry.superName);
                    assertEquals(entry.superName + ".<init>" + LIST_CONSTRUCTOR, superCall.owner + ".<init>" + superCall.desc, where);
                    assertEquals("singletonFields" + FIELDS, only(method(entry, "fields")).name + FIELDS, where);
                    MethodInsnNode builder = only(method(entry, "builder"));
                    assertEquals("(L" + entry.superName + "$EntryConstructor;)L" + UNIFORM + "$Builder;", builder.desc, where);

                    ClassNode base = read(jar, lib, entry.superName);
                    assertEquals(ENTRIES + "SingleEntryContainerBase", base.superName, where);
                    MethodInsnNode holder = calls(method(base, "<init>")).get(0);
                    assertTrue(holder.owner.startsWith(SyntheticEmbedder.PREFIX) && holder.owner.endsWith(SUPPORT), where + ": " + holder.owner);
                    assertNotNull(read(jar, lib, holder.owner), where + ": the list helpers travel with the base");
                    assertNotNull(read(jar, lib, entry.superName + "$EntryConstructor"), where);
                }
            }
        }
    }

    /** Translates {@code input} for a {@code loader} host through one path and returns the output jar. */
    private Path translate(String loader, String path, String host, Path input) throws Exception {
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(loader.equals("fabric") ? null : loader);
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> runtime(loader, host, input, out);
            case "transform" -> {
                CliHarness.transform(input, out.resolve("named.jar"), host);
                yield out.resolve("named.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("named.jar"), host);
                yield out.resolve("named.jar");
            }
            default -> {
                CliHarness.batch(input.getParent(), out, host);
                try (var jars = Files.list(out)) {
                    yield jars.filter(jar -> jar.toString().endsWith(".jar")).findFirst().orElseThrow();
                }
            }
        };
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

    private Path writeMod(String loader) throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>(fixture(loader, "lib"));
        Map<String, byte[]> mod = new LinkedHashMap<>(fixture(loader, "mod"));
        mod.put(nestedPath(loader), LoaderApiFixtures.jar(lib));
        Path input = Files.createDirectories(dir.resolve("in")).resolve("named-" + loader + ".jar");
        Files.write(input, LoaderApiFixtures.jar(mod));
        return input;
    }

    private static String nestedPath(String loader) {
        return loader.equals("fabric") ? "META-INF/jars/lib.jar" : "META-INF/jarjar/lib.jar";
    }

    /** A 1.21.1 mod for {@code loader} with Moonlight's optional item entry shape. */
    private static Map<String, byte[]> fixture(String loader, String pkg) {
        String id = "named" + pkg;
        String metadata = switch (loader) {
            case "fabric" -> "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\","
                    + "\"depends\":{\"minecraft\":\"1.21.1\",\"fabricloader\":\">=0.15.0\"}"
                    + (pkg.equals("mod") ? ",\"jars\":[{\"file\":\"META-INF/jars/lib.jar\"}]" : "") + "}";
            default -> "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + id
                    + "\"\nversion=\"1.0.0\"\n[[dependencies." + id + "]]\nmodId=\"minecraft\"\ntype=\"required\"\n"
                    + "mandatory=true\nversionRange=\"[1.21.1,1.22)\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
        };
        String metadataPath = switch (loader) {
            case "fabric" -> "fabric.mod.json";
            case "forge" -> "META-INF/mods.toml";
            default -> "META-INF/neoforge.mods.toml";
        };
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(metadataPath, metadata.getBytes(StandardCharsets.UTF_8));
        entries.put(pkg + "/NamedItemEntry.class", entry(loader, pkg + "/NamedItemEntry"));
        return entries;
    }

    /**
     * {@code class NamedItemEntry extends LootPoolSingletonContainer} as javac writes it: its constructor passes the two
     * lists, and its own class names the inherited singletonFields and simpleBuilder. Intermediary names on Fabric.
     */
    private static byte[] entry(String loader, String name) {
        boolean fabric = loader.equals("fabric");
        String base = fabric ? "net/minecraft/class_85" : SINGLETON;
        String builderDesc = "(L" + base + (fabric ? "$class_89;)L" + base + "$class_86;" : "$EntryConstructor;)L" + base + "$Builder;");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT, name, null, base, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", LIST_CONSTRUCTOR, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ILOAD, 1);
        init.visitVarInsn(ILOAD, 2);
        init.visitVarInsn(ALOAD, 3);
        init.visitVarInsn(ALOAD, 4);
        init.visitMethodInsn(INVOKESPECIAL, base, "<init>", LIST_CONSTRUCTOR, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        staticCall(cw, name, "fields", FIELDS, fabric ? "method_53290" : "singletonFields");
        staticCall(cw, name, "builder", builderDesc, fabric ? "method_434" : "simpleBuilder");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void staticCall(ClassWriter cw, String owner, String name, String desc, String helper) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, owner, helper, desc, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** A class from the nested library, or from the outer jar where the embedder put it. */
    private static ClassNode read(JarFile jar, JarFile lib, String name) throws Exception {
        return lib.getJarEntry(name + ".class") != null ? read(lib, name) : read(jar, name);
    }

    private static ClassNode read(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name));
    }

    private static MethodInsnNode only(MethodNode method) {
        List<MethodInsnNode> calls = calls(method);
        assertEquals(1, calls.size(), method.name + " makes one call");
        return calls.get(0);
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }
}
