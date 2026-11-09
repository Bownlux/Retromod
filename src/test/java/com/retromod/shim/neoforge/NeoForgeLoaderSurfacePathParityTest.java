/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.ModVersionDetector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.forge.ForgeNeoForgeSynthetics;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Every transform path gives a NeoForge 1.21.1 mod and its nested library the old config types and mod file lookups on 26.3. */
class NeoForgeLoaderSurfacePathParityTest {

    private static final String TYPE = LegacyModConfigTypeBridge.TYPE;

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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"runtime", "transform", "batch", "aot"})
    @DisplayName("Each path keeps COMMON and SERVER configs on their old files and finds the mod's own files")
    void everyPathBridgesTheLoaderSurface(String path) throws Exception {
        Path output = translate(path, "26.3", modJar());

        Map<String, Map<String, byte[]>> jars = classes(output);
        for (Map.Entry<String, Map<String, byte[]>> jar : jars.entrySet()) {
            String where = jar.getKey() + " (" + path + ")";
            List<String> configs = LegacyModConfigTypeBridgeTest.refs(jar.getValue().get(jar.getKey() + "/Configs"));
            assertTrue(configs.contains("GETSTATIC " + TYPE + ".LOCAL"), where + " " + configs);
            assertFalse(configs.stream().anyMatch(ref -> ref.endsWith(".COMMON")), where + " " + configs);
            assertTrue(configs.stream().anyMatch(ref -> ref.contains("LegacyModConfigs.registerConfig")), where + " " + configs);
            List<String> files = LegacyModConfigTypeBridgeTest.refs(jar.getValue().get(jar.getKey() + "/Files"));
            assertTrue(files.stream().anyMatch(ref -> ref.contains("LegacyModFiles.findResource")), where + " " + files);
            assertTrue(jar.getValue().keySet().stream().anyMatch(name -> name.endsWith("legacyapi/neoforge/fml/LegacyModConfigs")),
                    "the config helper travels with " + where);
        }
        // The copies in the jar run against FancyModLoader 12's shape.
        assertEquals(List.of("oldconfigs-common.toml", "oldconfigs-server.toml", "oldconfigs-server.toml", "commonSERVER"),
                runConfigs(output), "the old file names and type names (" + path + ")");
        runFiles(output, path);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"runtime", "transform", "batch", "aot"})
    @DisplayName("Each path turns a Forge 1.21.1 mod's FMLLoader statics and FMLEnvironment fields into NeoForge 26.3 calls")
    void forgeModsReachTheLoaderInstance(String path) throws Exception {
        Path output = translate(path, "26.3", forgeModJar(), "sides.jar");

        byte[] side;
        try (JarFile jar = new JarFile(output.toFile())) {
            side = jar.getInputStream(jar.getJarEntry("forgesides/Side.class")).readAllBytes();
        }
        List<String> refs = LegacyModConfigTypeBridgeTest.refs(side);
        String loader = "net/neoforged/fml/loading/FMLLoader";
        String environment = "net/neoforged/fml/loading/FMLEnvironment";
        assertEquals(3, refs.stream().filter(ref -> ref.equals("INVOKESTATIC " + environment
                + ".getDist()Lnet/neoforged/api/distmarker/Dist;")).count(), path + " " + refs);
        assertTrue(refs.contains("INVOKESTATIC " + environment + ".isProduction()Z"), path + " " + refs);
        assertTrue(refs.contains("INVOKESTATIC " + loader + ".getCurrent()L" + loader + ";"), path + " " + refs);
        assertTrue(refs.contains("INVOKEVIRTUAL " + loader + ".getGameDir()Ljava/nio/file/Path;"), path + " " + refs);
        assertFalse(refs.stream().anyMatch(ref -> ref.startsWith("INVOKESTATIC " + loader + ".get") && !ref.contains("getCurrent")
                || ref.startsWith("GETSTATIC") || ref.contains("minecraftforge/fml/loading")), path + " " + refs);
    }

    @ParameterizedTest(name = "{0} host")
    @CsvSource({"neoforge, 26.2", "forge, 26.3", "fabric, 26.3"})
    @DisplayName("A host without the rename, a Forge host and a Fabric host leave COMMON alone")
    void otherHostsLeaveTheConfigTypes(String loader, String host) {
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        McReflect.setForceForge(loader.equals("forge"));
        // A NeoForge host also registers the Forge shims, for Forge mods.
        register(loader.equals("neoforge") ? Set.of("neoforge", "forge", "common") : Set.of(loader, "common"), host);
        transformer.setModSourceVersion("1.21.1");

        List<String> refs = LegacyModConfigTypeBridgeTest.refs(transformer.transformClass(
                LegacyModConfigTypeBridgeTest.configMod("oldconfigs/Configs", "COMMON", "SERVER"), "oldconfigs/Configs"));
        assertTrue(refs.contains("GETSTATIC " + TYPE + ".COMMON"), loader + " " + host + " " + refs);
        assertFalse(refs.stream().anyMatch(ref -> ref.contains("LegacyModConfigs")), refs.toString());
    }

    private Path translate(String path, String host, byte[] jar) throws Exception {
        return translate(path, host, jar, "oldconfigs.jar");
    }

    private Path translate(String path, String host, byte[] jar, String name) throws Exception {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        ModVersionDetector.setDefaultPreferredLoader("neoforge");
        Path input = Files.createDirectories(dir.resolve("in")).resolve(name);
        Files.write(input, jar);
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> {
                register(Set.of("neoforge", "forge", "common"), host);
                SrgToMojangMapper.getInstance().applyTo(transformer);
                IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
                ForgeNeoForgeSynthetics.register(transformer);
                yield new ForgeModTransformer(host).transformMod(input, out);
            }
            case "transform" -> {
                CliHarness.transform(input, out.resolve(name), host);
                yield out.resolve(name);
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve(name), host);
                yield out.resolve(name);
            }
            default -> {
                CliHarness.batch(input.getParent(), out, host);
                try (var jars = Files.list(out)) {
                    yield jars.filter(file -> file.toString().endsWith(".jar")).findFirst().orElseThrow();
                }
            }
        };
        assertTrue(Files.isRegularFile(output), path + " must produce " + output);
        return output;
    }

    /** Registers what a running game on {@code host} registers for these loader types. */
    private void register(Set<String> loaders, String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (loaders.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
    }

    /** Runs the translated {@code Configs.register} against FancyModLoader 12 stand-ins and lists what it registered. */
    private List<String> runConfigs(Path output) throws Exception {
        try (URLClassLoader host = standIns(); URLClassLoader mod = new URLClassLoader(
                new URL[]{output.toUri().toURL()}, host)) {
            Object container = host.loadClass("net.neoforged.fml.ModContainer").getConstructor().newInstance();
            Object spec = host.loadClass("standin.Spec").getConstructor().newInstance();
            Method register = mod.loadClass("oldconfigs.Configs").getMethod("register",
                    host.loadClass("net.neoforged.fml.ModContainer"), host.loadClass("net.neoforged.fml.config.IConfigSpec"));
            String names = (String) register.invoke(null, container, spec);
            List<String> registered = new ArrayList<>(files(container));
            registered.addAll(files(host.loadClass("net.neoforged.fml.config.ConfigTracker").getField("INSTANCE").get(null)));
            registered.add(names);
            return registered;
        }
    }

    /** Runs the translated {@code Files.read} against a mod file whose jar holds one bundled file. */
    private void runFiles(Path output, String path) throws Exception {
        Path bundled = dir.resolve("bundled-" + path + ".jar");
        Files.write(bundled, LoaderApiFixtures.jar(Map.of("data/bundled.json", "{}".getBytes(StandardCharsets.UTF_8))));
        try (URLClassLoader host = standIns(); URLClassLoader mod = new URLClassLoader(
                new URL[]{output.toUri().toURL()}, host)) {
            Class<?> modFile = host.loadClass("net.neoforged.neoforgespi.locating.IModFile");
            Object file = host.loadClass("standin.JarModFile").getConstructor(Path.class).newInstance(bundled);
            Object[] read = (Object[]) mod.loadClass("oldconfigs.Files").getMethod("read", modFile).invoke(null, file);
            assertEquals("{}", Files.readString((Path) read[0]), "findResource reads the bundled file (" + path + ")");
            assertTrue(Files.exists(((Path) read[1]).resolve("data/bundled.json")), "the jar's root holds it (" + path + ")");
            assertEquals(bundled.toUri(), read[2], "moduleDataProvider().uri() is the jar (" + path + ")");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> files(Object recorder) throws Exception {
        return (List<String>) recorder.getClass().getField("files").get(recorder);
    }

    /** FancyModLoader 12 stand-ins: the renamed config types, and mod files read through their contents. */
    private URLClassLoader standIns() throws Exception {
        Path src = dir.resolve("standin-src");
        Path classes = dir.resolve("standin-classes");
        if (!Files.isDirectory(classes)) {
            List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "--release", "17"));
            for (Map.Entry<String, String> source : STAND_INS.entrySet()) {
                Path file = src.resolve(source.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue());
                args.add(file.toString());
            }
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            assertNotNull(compiler, "the test needs a JDK to compile the FancyModLoader 12 stand-ins");
            assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the stand-ins must compile");
        }
        return new URLClassLoader(new URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    private static final Map<String, String> STAND_INS = Map.of(
            "net/neoforged/fml/config/IConfigSpec.java", """
                    package net.neoforged.fml.config;
                    public interface IConfigSpec {}""",
            "net/neoforged/fml/config/ModConfig.java", """
                    package net.neoforged.fml.config;
                    public final class ModConfig {
                        public enum Type {
                            LOCAL, CLIENT, SYNCED, STARTUP;
                            public String extension() { return name().toLowerCase(java.util.Locale.ROOT); }
                        }
                    }""",
            "net/neoforged/fml/config/ConfigTracker.java", """
                    package net.neoforged.fml.config;
                    import net.neoforged.fml.ModContainer;
                    public class ConfigTracker {
                        public static final ConfigTracker INSTANCE = new ConfigTracker();
                        public final java.util.List<String> files = new java.util.ArrayList<>();
                        public ModConfig registerConfig(ModConfig.Type type, IConfigSpec spec, ModContainer container) {
                            return registerConfig(type, spec, container, container.getModId() + "-" + type.extension() + ".toml");
                        }
                        public ModConfig registerConfig(ModConfig.Type type, IConfigSpec spec, ModContainer container, String file) {
                            files.add(file);
                            return null;
                        }
                    }""",
            "net/neoforged/fml/ModContainer.java", """
                    package net.neoforged.fml;
                    import net.neoforged.fml.config.IConfigSpec;
                    import net.neoforged.fml.config.ModConfig;
                    public class ModContainer {
                        public final java.util.List<String> files = new java.util.ArrayList<>();
                        public final String getModId() { return "oldconfigs"; }
                        public void registerConfig(ModConfig.Type type, IConfigSpec spec) {
                            registerConfig(type, spec, getModId() + "-" + type.extension() + ".toml");
                        }
                        public void registerConfig(ModConfig.Type type, IConfigSpec spec, String file) { files.add(file); }
                    }""",
            "net/neoforged/fml/jarcontents/JarContents.java", """
                    package net.neoforged.fml.jarcontents;
                    public interface JarContents extends java.io.Closeable {
                        java.util.Collection<java.nio.file.Path> getContentRoots();
                    }""",
            "net/neoforged/neoforgespi/locating/IModFile.java", """
                    package net.neoforged.neoforgespi.locating;
                    public interface IModFile {
                        net.neoforged.fml.jarcontents.JarContents getContents();
                        java.nio.file.Path getFilePath();
                    }""",
            "standin/Spec.java", """
                    package standin;
                    public class Spec implements net.neoforged.fml.config.IConfigSpec {}""",
            "standin/JarModFile.java", """
                    package standin;
                    import java.nio.file.Path;
                    public record JarModFile(Path getFilePath) implements net.neoforged.neoforgespi.locating.IModFile,
                            net.neoforged.fml.jarcontents.JarContents {
                        public net.neoforged.fml.jarcontents.JarContents getContents() { return this; }
                        public java.util.Collection<Path> getContentRoots() { return java.util.List.of(getFilePath); }
                        public void close() {}
                    }""");

    /** A NeoForge 1.21.1 mod with configs and file lookups, and a nested library with the same. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/neoforge.mods.toml", toml("oldlib"));
        lib.put("oldlib/Configs.class", LegacyModConfigTypeBridgeTest.configMod("oldlib/Configs", "COMMON", "SERVER"));
        lib.put("oldlib/Files.class", LegacyModConfigTypeBridgeTest.modFileMod("oldlib/Files"));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/neoforge.mods.toml", toml("oldconfigs"));
        mod.put("oldconfigs/Configs.class", LegacyModConfigTypeBridgeTest.configMod("oldconfigs/Configs", "COMMON", "SERVER"));
        mod.put("oldconfigs/Files.class", LegacyModConfigTypeBridgeTest.modFileMod("oldconfigs/Files"));
        mod.put("META-INF/jarjar/lib.jar", LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    /** A Forge 1.21.1 mod that reads its side and paths, with a NeoForge class beside it as Collective ships. */
    private static byte[] forgeModJar() throws Exception {
        String forgeLoader = "net/minecraftforge/fml/loading/FMLLoader";
        String neoLoader = "net/neoforged/fml/loading/FMLLoader";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "forgesides/Side", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, forgeLoader, "getDist", "()Lnet/minecraftforge/api/distmarker/Dist;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, forgeLoader, "isProduction", "()Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, forgeLoader, "getGamePath", "()Ljava/nio/file/Path;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraftforge/fml/loading/FMLEnvironment", "dist",
                "Lnet/minecraftforge/api/distmarker/Dist;");
        mv.visitInsn(Opcodes.POP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, neoLoader, "getDist", "()Lnet/neoforged/api/distmarker/Dist;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[52,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"forgesides\"\n"
                + "version=\"1.0.0\"\n[[dependencies.forgesides]]\nmodId=\"forge\"\nmandatory=true\nversionRange=\"[52,)\"\n"
                + "ordering=\"NONE\"\nside=\"BOTH\"\n[[dependencies.forgesides]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.21.1,1.21.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", toml.getBytes(StandardCharsets.UTF_8));
        mod.put("forgesides/Side.class", cw.toByteArray());
        return LoaderApiFixtures.jar(mod);
    }

    private static byte[] toml(String modId) {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                + "\"\nversion=\"1.0.0\"\n[[dependencies." + modId + "]]\nmodId=\"neoforge\"\ntype=\"required\"\n"
                + "versionRange=\"[21.1,)\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\ntype=\"required\"\n"
                + "versionRange=\"[1.21.1,1.21.2)\"\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Every class of the mod and of its nested library, by jar. */
    private static Map<String, Map<String, byte[]>> classes(Path output) throws Exception {
        Map<String, Map<String, byte[]>> found = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            found.put("oldconfigs", read(jar));
            var lib = jar.getJarEntry("META-INF/jarjar/lib.jar");
            assertNotNull(lib, "the nested library is missing from " + output);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, jar.getInputStream(lib).readAllBytes());
                try (JarFile library = new JarFile(nested.toFile())) {
                    found.put("oldlib", read(library));
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static Map<String, byte[]> read(JarFile jar) throws Exception {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (var entry : java.util.Collections.list(jar.entries())) {
            if (!entry.getName().endsWith(".class")) continue;
            classes.put(entry.getName().substring(0, entry.getName().length() - ".class".length()),
                    jar.getInputStream(entry).readAllBytes());
        }
        return classes;
    }
}
