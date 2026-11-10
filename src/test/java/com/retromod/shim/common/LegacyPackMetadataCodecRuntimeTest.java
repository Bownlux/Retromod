/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The rebuilt pack metadata codec, run on real DataFixerUpper with stand-ins for the 26.3 metadata classes. */
class LegacyPackMetadataCodecRuntimeTest {

    private static final Path LIBRARIES = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries");
    private static final Path DFU = LIBRARIES.resolve("com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar");
    private static final Path FASTUTIL = LIBRARIES.resolve("it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar");
    private static final Path COMPILED = Path.of("target", "legacyapi-classes");
    private static final String REBUILT = "com/retromod/legacyapi/minecraft/server/packs/metadata/";

    @TempDir
    Path dir;

    private ClassLoader game;

    @BeforeEach
    void loadGame() throws Exception {
        assumeTrue(Files.isRegularFile(DFU) && Files.isRegularFile(FASTUTIL),
                "no local DataFixerUpper 10 or fastutil jar; they are local test material");
        assumeTrue(Files.isDirectory(COMPILED.resolve(REBUILT)), "the rebuilt classes are compiled");
        String gson = Path.of(com.google.gson.JsonParser.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Map<String, byte[]> classes = new HashMap<>(BridgeHarness.compile(dir, HOST, DFU + File.pathSeparator + gson));
        try (Stream<Path> files = Files.list(COMPILED.resolve(REBUILT))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = COMPILED.relativize(file).toString().replace(File.separatorChar, '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        game = new HostLoader(classes, new URL[]{DFU.toUri().toURL(), FASTUTIL.toUri().toURL()});
    }

    @Test
    @DisplayName("A 1.21.1 section reads as every minor format of its pack_format, widened to its supported formats")
    void readsTheOldShape() throws Throwable {
        assertEquals("Dungeons 48.0-48.max", read("{\"description\":\"Dungeons\",\"pack_format\":48}"));
        assertEquals("Dungeons 34.0-57.max", read("{\"description\":\"Dungeons\",\"pack_format\":48,\"supported_formats\":[34,57]}"));
        assertEquals("error: Pack metadata has no pack_format", read("{\"description\":\"Dungeons\"}"));
    }

    @Test
    @DisplayName("A malformed supported_formats is ignored, as 1.21.1 ignored it, and the pack_format still counts")
    void ignoresMalformedSupportedFormats() throws Throwable {
        assertEquals("Dungeons 48.0-48.max", read("{\"description\":\"Dungeons\",\"pack_format\":48,\"supported_formats\":\"all\"}"));
    }

    @Test
    @DisplayName("A section in the 1.21.9 shape reads its min_format and max_format with their minor formats")
    void readsTheNewShape() throws Throwable {
        assertEquals("Dungeons 88.2-101.max", read("{\"description\":\"Dungeons\",\"min_format\":[88,2],\"max_format\":101}"));
        assertEquals("error: Pack metadata needs both min_format and max_format, or neither",
                read("{\"description\":\"Dungeons\",\"min_format\":88}"));
    }

    @Test
    @DisplayName("A range after both pack types gained minor formats is written with min_format and max_format alone")
    void writesNewRangesAsTheHostReadsThem() throws Throwable {
        assertEquals("{\"description\":\"Dungeons\",\"min_format\":[121,0],\"max_format\":[121,2147483647]}",
                write(121, 0, 121, Integer.MAX_VALUE));
        assertEquals("Dungeons 121.0-121.max", read(write(121, 0, 121, Integer.MAX_VALUE)), "it reads back the same");
    }

    @Test
    @DisplayName("An older range also states pack_format and supported_formats, and a format below 15 is written alone")
    void writesOldRangesWithTheOldFields() throws Throwable {
        assertEquals("{\"description\":\"Dungeons\",\"pack_format\":48,\"supported_formats\":[48,57],"
                + "\"min_format\":[48,0],\"max_format\":[57,2147483647]}", write(48, 0, 57, Integer.MAX_VALUE),
                "the host needs both shapes for a range that starts before minor formats");
        assertEquals("Dungeons 48.0-57.max", read(write(48, 0, 57, Integer.MAX_VALUE)));
        assertEquals("{\"description\":\"Dungeons\",\"pack_format\":12}", write(12, 0, 12, Integer.MAX_VALUE),
                "the host refuses a format below 15 next to a range");
    }

    private String read(String json) throws Throwable {
        return (String) probe("read", json);
    }

    private String write(int minMajor, int minMinor, int maxMajor, int maxMinor) throws Throwable {
        return (String) probe("write", "Dungeons", minMajor, minMinor, maxMajor, maxMinor);
    }

    private Object probe(String name, Object... args) throws Throwable {
        Object codec = game.loadClass(REBUILT.replace('/', '.') + "LegacyPackMetadataCodec").getMethod("codec").invoke(null);
        for (var method : game.loadClass("probe.Probe").getMethods()) {
            if (method.getName().equals(name)) {
                Object[] all = new Object[args.length + 1];
                all[0] = codec;
                System.arraycopy(args, 0, all, 1, args.length);
                try {
                    return method.invoke(null, all);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        }
        throw new AssertionError("no probe method " + name);
    }

    /** Loads the stand-ins, the rebuilt classes, DataFixerUpper and fastutil before asking the test's own class path. */
    private static final class HostLoader extends URLClassLoader {
        private final Map<String, byte[]> classes;

        HostLoader(Map<String, byte[]> classes, URL[] libraries) {
            super(libraries, LegacyPackMetadataCodecRuntimeTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = classes.get(name.replace('.', '/'));
                    if (bytes != null) {
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } else if (name.startsWith("com.mojang.") || name.startsWith("it.unimi.")) {
                        loaded = findClass(name);
                    } else {
                        return super.loadClass(name, resolve);
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }

    // Stand-ins with the 26.3 members the codec calls. The format codecs take a [major, minor] list or a bare major,
    // and the cut-offs are 26.3's: resource packs gained minor formats after 64, data packs after 81.
    private static final Map<String, String> HOST = new LinkedHashMap<>();

    static {
        HOST.put("net/minecraft/network/chat/Component.java", """
                package net.minecraft.network.chat;
                public record Component(String text) {}""");
        HOST.put("net/minecraft/network/chat/ComponentSerialization.java", """
                package net.minecraft.network.chat;
                import com.mojang.serialization.Codec;
                public final class ComponentSerialization {
                    public static final Codec<Component> CODEC = Codec.STRING.xmap(Component::new, Component::text);
                }""");
        HOST.put("net/minecraft/server/packs/PackType.java", """
                package net.minecraft.server.packs;
                public enum PackType { CLIENT_RESOURCES, SERVER_DATA }""");
        HOST.put("net/minecraft/util/InclusiveRange.java", """
                package net.minecraft.util;
                import com.mojang.serialization.Codec;
                import java.util.List;
                public record InclusiveRange<T extends Comparable<T>>(T minInclusive, T maxInclusive) {
                    public static final Codec<InclusiveRange<Integer>> INT = Codec.INT.listOf()
                            .xmap(ends -> new InclusiveRange<>(ends.get(0), ends.get(ends.size() - 1)),
                                    range -> List.of(range.minInclusive(), range.maxInclusive()));
                }""");
        HOST.put("net/minecraft/server/packs/metadata/pack/PackFormat.java", """
                package net.minecraft.server.packs.metadata.pack;
                import com.mojang.datafixers.util.Either;
                import com.mojang.serialization.Codec;
                import java.util.List;
                import net.minecraft.server.packs.PackType;
                public record PackFormat(int major, int minor) implements Comparable<PackFormat> {
                    public static final Codec<PackFormat> BOTTOM_CODEC = codec(0);
                    public static final Codec<PackFormat> TOP_CODEC = codec(Integer.MAX_VALUE);
                    private static Codec<PackFormat> codec(int defaultMinor) {
                        return Codec.either(Codec.INT, Codec.INT.listOf()).xmap(
                                value -> value.map(major -> new PackFormat(major, defaultMinor),
                                        list -> new PackFormat(list.get(0), list.size() > 1 ? list.get(1) : defaultMinor)),
                                format -> Either.right(List.of(format.major(), format.minor())));
                    }
                    public static PackFormat of(int major, int minor) { return new PackFormat(major, minor); }
                    public static int lastPreMinorVersion(PackType type) { return type == PackType.CLIENT_RESOURCES ? 64 : 81; }
                    @Override public int compareTo(PackFormat other) {
                        return major != other.major ? Integer.compare(major, other.major) : Integer.compare(minor, other.minor);
                    }
                }""");
        HOST.put("net/minecraft/server/packs/metadata/pack/PackMetadataSection.java", """
                package net.minecraft.server.packs.metadata.pack;
                import net.minecraft.network.chat.Component;
                import net.minecraft.util.InclusiveRange;
                public record PackMetadataSection(Component description, InclusiveRange<PackFormat> supportedFormats) {}""");
        HOST.put("probe/Probe.java", """
                package probe;
                import com.google.gson.JsonParser;
                import com.mojang.serialization.Codec;
                import com.mojang.serialization.JsonOps;
                import net.minecraft.network.chat.Component;
                import net.minecraft.server.packs.metadata.pack.PackFormat;
                import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
                import net.minecraft.util.InclusiveRange;
                public final class Probe {
                    @SuppressWarnings("unchecked")
                    public static String read(Object codec, String json) {
                        return ((Codec<PackMetadataSection>) codec).parse(JsonOps.INSTANCE, JsonParser.parseString(json)).mapOrElse(
                                section -> section.description().text() + " " + format(section.supportedFormats().minInclusive())
                                        + "-" + format(section.supportedFormats().maxInclusive()),
                                error -> "error: " + error.message());
                    }
                    @SuppressWarnings("unchecked")
                    public static String write(Object codec, String description, int minMajor, int minMinor, int maxMajor, int maxMinor) {
                        PackMetadataSection section = new PackMetadataSection(new Component(description),
                                new InclusiveRange<>(PackFormat.of(minMajor, minMinor), PackFormat.of(maxMajor, maxMinor)));
                        return ((Codec<PackMetadataSection>) codec).encodeStart(JsonOps.INSTANCE, section).getOrThrow().toString();
                    }
                    private static String format(PackFormat format) {
                        return format.major() + "." + (format.minor() == Integer.MAX_VALUE ? "max" : String.valueOf(format.minor()));
                    }
                }""");
    }
}
