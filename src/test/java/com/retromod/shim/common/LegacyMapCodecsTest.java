/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A 1.20.1 generator's codec gets one map codec, so its registration and its rebuilt codec() name the same object.
 * Minimal stand-ins of the DataFixerUpper types are compiled here, since the real library is not on the test classpath.
 */
class LegacyMapCodecsTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("an xmap'd codec maps to one shared map codec, and a record codec to its own map codec")
    void sharedMapCodecs() throws Exception {
        ClassLoader loader = compileStandIns();
        Class<?> codecs = new ClassLoader(loader) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.equals(LegacyMapCodecsSynthetic.INTERNAL.replace('/', '.'))) throw new ClassNotFoundException(name);
                byte[] bytes = LegacyMapCodecsSynthetic.generate();
                return defineClass(name, bytes, 0, bytes.length);
            }
        }.loadClass(LegacyMapCodecsSynthetic.INTERNAL.replace('/', '.'));
        Method of = codecs.getMethod("of", Object.class);

        Object xmapped = loader.loadClass("com.mojang.serialization.XmapCodec").getConstructor().newInstance();
        Object first = of.invoke(null, xmapped);
        assertSame(first, of.invoke(null, xmapped), "the registration and codec() must get the same map codec");
        assertTrue(loader.loadClass("com.mojang.serialization.MapCodec").isInstance(first));

        Object record = loader.loadClass("com.mojang.serialization.MapCodec$MapCodecCodec").getConstructor().newInstance();
        Object recordMap = record.getClass().getMethod("codec").invoke(record);
        assertSame(recordMap, of.invoke(null, record), "a record codec answers with the map codec it wraps");
    }

    private ClassLoader compileStandIns() throws Exception {
        Map<String, String> sources = Map.of(
                "com/mojang/serialization/Codec.java", "package com.mojang.serialization; public interface Codec {}",
                "com/mojang/serialization/XmapCodec.java", "package com.mojang.serialization; public class XmapCodec implements Codec {}",
                "com/mojang/serialization/MapCodec.java", """
                        package com.mojang.serialization;
                        public abstract class MapCodec {
                            public static MapCodec assumeMapUnsafe(Codec codec) { return new MapCodec() {}; }
                            public static final class MapCodecCodec implements Codec {
                                private final MapCodec codec = new MapCodec() {};
                                public MapCodec codec() { return codec; }
                            }
                        }""");
        Path out = Files.createDirectories(dir.resolve("out"));
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-nowarn"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = dir.resolve("src").resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(new String[0])));
        return new URLClassLoader(new URL[]{out.toUri().toURL()}, getClass().getClassLoader());
    }
}
