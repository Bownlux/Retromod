/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.ModVersionDetector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.mapping.IntermediaryToMojangMapper;
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
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** GeckoLib 4 and a mod built on it, dropped into mods/ together, stay on software.bernie through the in-place pass. */
class GeckoLibInPlacePassTest {

    private static final String OLD = "software/bernie/geckolib/";
    private static final String MAIN = OLD + "GeckoLib";
    private static final String GEO_ITEM = OLD + "animatable/GeoItem";
    private static final String UTIL = OLD + "util/GeckoLibUtil";
    private static final String MOD_CLASS = "mod/GeoThing";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path tmp;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"neoforge, 26.3", "forge, 26.2"})
    @DisplayName("Both translations in the cache keep the names GeckoLib 4 has, though the 26.x table moves them")
    void geckoLib4AndItsModStayTogether(String loader, String host) throws Exception {
        Path game = Files.createDirectories(tmp.resolve("game"));
        Path mods = Files.createDirectories(game.resolve("mods"));
        jar(mods.resolve("a-geothing.jar"), "geothing", MOD_CLASS, geoThing());
        jar(mods.resolve("z-geckolib.jar"), "geckolib", MAIN, plainClass(MAIN, 0),
                GEO_ITEM, plainClass(GEO_ITEM, ACC_INTERFACE | ACC_ABSTRACT), UTIL, util());
        registerAsTheEntryPoint(loader, host);
        assertNotEquals(GEO_ITEM, transformer.classRedirectForCurrentMod(GEO_ITEM),
                "GeckoLib 4 is held back from the loader, so no GeckoLib is readable when the shims register");

        DiscoveryEngine.Outcome first = discover(game, loader, host);
        Properties properties = new Properties();
        properties.setProperty(InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_IN_PLACE);
        properties.setProperty(InPlaceStatus.STATUS_PROPERTY, first.statusFile().toString());
        int translated = InPlaceLaunch.decide(first.statusFile().getParent(), properties).translatePending(host,
                () -> (InPlaceLaunch.LinkingTranslator) new ForgeModTransformer(host)::translateOrThrow);

        assertEquals(2, translated);
        List<Path> served = served(discover(game, loader, host));
        Path geckoLib = find(served, "z-geckolib");
        try (JarFile file = new JarFile(geckoLib.toFile())) {
            for (String name : List.of(MAIN, GEO_ITEM, UTIL)) {
                assertEquals(name, read(file, name).name, "GeckoLib 4's class file keeps its own name");
            }
        }
        try (JarFile file = new JarFile(find(served, "a-geothing").toFile())) {
            assertEquals(Set.of(GEO_ITEM, UTIL), geckoLibNames(read(file, MOD_CLASS)),
                    "the mod names the GeckoLib 4 translated with it");
        }
    }

    /** What the loader's entry point registers on {@code host} before the pass, with no GeckoLib on its class path. */
    private void registerAsTheEntryPoint(String loader, String host) {
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(loader);
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        Set<String> types = loader.equals("forge") ? Set.of("forge", "common") : Set.of("neoforge", "forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
    }

    private static DiscoveryEngine.Outcome discover(Path game, String loader, String host) throws Exception {
        List<Path> listing;
        try (Stream<Path> files = Files.list(game.resolve("mods"))) {
            listing = files.sorted().toList();
        }
        boolean neoForge = loader.equals("neoforge");
        return DiscoveryEngine.run(new DiscoveryEngine.Request(game, game.resolve("mods"), listing, loader, host, "",
                neoForge ? ForgeModMetadata.NEOFORGE_TOML : ForgeModMetadata.FORGE_TOML,
                neoForge ? DiscoveryEngine.ServeStyle.FILES : DiscoveryEngine.ServeStyle.FOLDER,
                new Properties(), (level, message) -> { }));
    }

    private static List<Path> served(DiscoveryEngine.Outcome outcome) throws Exception {
        List<Path> served = new ArrayList<>(outcome.serveFiles());
        if (outcome.serveFolder() != null && Files.isDirectory(outcome.serveFolder())) {
            try (Stream<Path> files = Files.list(outcome.serveFolder())) {
                served.addAll(files.toList());
            }
        }
        return served;
    }

    private static Path find(List<Path> served, String prefix) {
        return served.stream().filter(jar -> jar.getFileName().toString().startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError(prefix + " is not served: " + served));
    }

    private static ClassNode read(JarFile file, String className) throws Exception {
        JarEntry entry = file.getJarEntry(className + ".class");
        assertNotNull(entry, className + " is in " + file.getName());
        ClassNode node = new ClassNode();
        new ClassReader(file.getInputStream(entry).readAllBytes()).accept(node, 0);
        return node;
    }

    /** Every GeckoLib name a class carries, in any position. */
    private static Set<String> geckoLibNames(ClassNode node) {
        Set<String> names = new TreeSet<>();
        node.accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
            @Override
            public String map(String internalName) {
                names.add(internalName);
                return internalName;
            }
        }));
        names.removeIf(name -> !name.contains("geckolib"));
        return names;
    }

    /** A Forge 1.20.1 jar for {@code modId} with the given classes, as name and bytes pairs. */
    private static void jar(Path file, String modId, Object... classes) throws Exception {
        String toml = TestModJars.toml(modId, "1.0.0", "[1.20.1,1.21)", "forge", "[47,)", "mandatory=true");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new JarEntry("META-INF/mods.toml"));
            out.write(toml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (int i = 0; i < classes.length; i += 2) {
                out.putNextEntry(new JarEntry(classes[i] + ".class"));
                out.write((byte[]) classes[i + 1]);
                out.closeEntry();
            }
        }
    }

    private static byte[] plainClass(String name, int kind) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | (kind == 0 ? ACC_SUPER : kind), name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** GeckoLib's util class: {@code static Object createInstanceCache(GeoItem item)}. */
    private static byte[] util() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, UTIL, null, "java/lang/Object", null);
        MethodVisitor create = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "createInstanceCache",
                "(L" + GEO_ITEM + ";)Ljava/lang/Object;", null, null);
        create.visitCode();
        create.visitInsn(ACONST_NULL);
        create.visitInsn(ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code GeoThing implements GeoItem}, building its cache through GeckoLib's util class. */
    private static byte[] geoThing() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, MOD_CLASS, null, "java/lang/Object", new String[]{GEO_ITEM});
        MethodVisitor cache = writer.visitMethod(ACC_PUBLIC, "cache", "()Ljava/lang/Object;", null, null);
        cache.visitCode();
        cache.visitVarInsn(ALOAD, 0);
        cache.visitMethodInsn(INVOKESTATIC, UTIL, "createInstanceCache", "(L" + GEO_ITEM + ";)Ljava/lang/Object;",
                false);
        cache.visitInsn(ARETURN);
        cache.visitMaxs(0, 0);
        cache.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
