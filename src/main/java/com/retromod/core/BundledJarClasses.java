/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.util.RetromodLog;
import com.retromod.util.ZipSecurity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** Lets frame joins and hierarchy walks read the classes of the jars a mod bundles (jar-in-jar). */
public final class BundledJarClasses {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    /** The folders Forge and NeoForge (jarjar) and Fabric (jars) load bundled jars from. */
    private static final List<String> FOLDERS = List.of("META-INF/jarjar/", "META-INF/jars/");

    /** Packages a bundled library never defines, so a lookup there never needs the bundled jars. */
    private static final List<String> HOST_PACKAGES = List.of("java/", "javax/", "jdk/", "sun/",
            "net/minecraft/", "com/mojang/", "net/neoforged/", "net/minecraftforge/", "net/fabricmc/");

    /** No bundled jars: attaching it leaves a provider as it is. */
    public static final Bundled NONE = new Bundled(List.of());

    private BundledJarClasses() {}

    /** The bundled jars of one jar, read the first time a lookup needs them. */
    public static final class Bundled {
        private final List<Supplier<byte[]>> jars;
        private volatile Map<String, byte[]> skeletons;

        private Bundled(List<Supplier<byte[]>> jars) {
            this.jars = List.copyOf(jars);
        }

        boolean isEmpty() {
            return jars.isEmpty();
        }

        /** Whether the bundled jars have been read, for tests. */
        boolean isRead() {
            return skeletons != null;
        }

        /** The class's members without code, or null when no bundled jar defines it. */
        byte[] skeleton(String name) {
            if (name == null || isHostName(name)) return null;
            Map<String, byte[]> loaded = skeletons;
            if (loaded == null) {
                synchronized (this) {
                    if (skeletons == null) skeletons = read(jars);
                    loaded = skeletons;
                }
            }
            return loaded.get(name);
        }
    }

    /**
     * A jar's own classes that also carry its bundled ones. Applying it still answers with the
     * jar's own classes only, so code that asks "is this class part of the jar" is unchanged.
     */
    public record WithBundled(Function<String, byte[]> own, Bundled bundled)
            implements Function<String, byte[]> {
        @Override
        public byte[] apply(String name) {
            return own == null ? null : own.apply(name);
        }
    }

    /** Attaches the bundled classes to a jar's own class provider, or returns it when there are none. */
    public static Function<String, byte[]> attach(Function<String, byte[]> own, Bundled bundled) {
        return bundled == null || bundled.isEmpty() ? own : new WithBundled(own, bundled);
    }

    /** The provider a hierarchy walk reads: the jar's own classes, then the ones it bundles. */
    public static Function<String, byte[]> withBundled(Function<String, byte[]> provider) {
        if (!(provider instanceof WithBundled attached)) return provider;
        return name -> {
            byte[] own = attached.apply(name);
            return own != null ? own : attached.bundled().skeleton(name);
        };
    }

    /** The jars directly inside the bundled-jar folders of an extracted jar, read from disk when needed. */
    public static Bundled fromDirectory(Path dir) {
        List<Supplier<byte[]>> jars = new ArrayList<>();
        for (String folder : FOLDERS) {
            Path nested = dir.resolve(folder);
            if (!Files.isDirectory(nested)) continue;
            try (Stream<Path> listing = Files.list(nested)) {
                for (Path jar : listing.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                    jars.add(() -> readFile(jar));
                }
            } catch (IOException e) {
                LOGGER.debug(RetromodLog.DETAIL, "Could not list the bundled jars in {}: {}", folder, e.getMessage());
            }
        }
        return jars.isEmpty() ? NONE : new Bundled(jars);
    }

    /**
     * The jars directly inside the bundled-jar folders of a jar file. Their bytes are copied now,
     * since the jar file may be closed by the time a lookup needs them, and parsed only then.
     */
    public static Bundled fromJar(JarFile jar) {
        List<Supplier<byte[]>> jars = new ArrayList<>();
        for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
            JarEntry entry = entries.nextElement();
            if (!isBundledJar(entry.getName())) continue;
            try (InputStream in = jar.getInputStream(entry)) {
                byte[] data = ZipSecurity.safeReadAllBytes(in);
                jars.add(() -> data);
            } catch (IOException e) {
                LOGGER.debug(RetromodLog.DETAIL, "Could not read bundled jar {}: {}", entry.getName(), e.getMessage());
            }
        }
        return jars.isEmpty() ? NONE : new Bundled(jars);
    }

    static boolean isBundledJar(String entryName) {
        for (String folder : FOLDERS) {
            if (entryName.startsWith(folder) && entryName.endsWith(".jar")
                    && entryName.indexOf('/', folder.length()) < 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHostName(String name) {
        for (String prefix : HOST_PACKAGES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private static byte[] readFile(Path jar) {
        try {
            return Files.readAllBytes(jar);
        } catch (IOException e) {
            LOGGER.debug(RetromodLog.DETAIL, "Could not read bundled jar {}: {}", jar.getFileName(), e.getMessage());
            return null;
        }
    }

    private static Map<String, byte[]> read(List<Supplier<byte[]>> jars) {
        Map<String, byte[]> skeletons = new HashMap<>();
        RetromodTransformer.NestedArchiveBudget budget = RetromodTransformer.NestedArchiveBudget.defaults();
        for (Supplier<byte[]> source : jars) {
            byte[] jarData = source.get();
            if (jarData == null) continue;
            // An unreadable bundled jar only costs the frame joins that needed it, as before.
            try {
                RetromodTransformer.readJarClassBytes(jarData, budget)
                        .forEach((name, bytes) -> skeletons.putIfAbsent(name, skeleton(bytes)));
            } catch (IOException | RuntimeException e) {
                LOGGER.debug(RetromodLog.DETAIL, "Could not read the classes of a bundled jar: {}", e.getMessage());
            }
        }
        return skeletons;
    }

    /** The class without method bodies, which hierarchy walks never read, so the copy stays small. */
    private static byte[] skeleton(byte[] bytes) {
        try {
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(bytes).accept(writer, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return writer.toByteArray();
        } catch (RuntimeException unreadable) {
            return bytes;
        }
    }
}
