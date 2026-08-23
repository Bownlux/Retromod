/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.RetromodTransformer;
import com.retromod.mixin.MixinCompatibilityTransformer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliJsonParsingBoundsTest {

    @Test
    void versionMetadataDepthIsBounded(@TempDir Path directory) throws Exception {
        String metadata = "{\"id\":\"26.2\",\"padding\":"
                + "[".repeat(257) + "0" + "]".repeat(257) + "}";
        Path jar = directory.resolve("target.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("version.json"));
            output.write(metadata.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        assertNull(RetromodCli.inferMinecraftVersion(jar));
    }

    @Test
    void mixinConfigRequiresExactUtf8() throws Exception {
        byte[] input = invalidUtf8Json("{\"package\":\"fixture", "\",\"required\":true}");
        Method method = RetromodCli.class.getDeclaredMethod(
                "makeMixinConfigNonFatal", byte[].class);
        method.setAccessible(true);

        assertSame(input, method.invoke(null, (Object) input));
    }

    @Test
    void refmapRequiresExactUtf8() throws Exception {
        byte[] input = invalidUtf8Json("{\"mappings\":{\"fixture\":{\"key\":\"",
                "\"}}}");
        Method method = RetromodCli.class.getDeclaredMethod(
                "remapRefmap", byte[].class, MixinCompatibilityTransformer.class,
                boolean.class, boolean.class);
        method.setAccessible(true);
        MixinCompatibilityTransformer mixins = new MixinCompatibilityTransformer(
                RetromodTransformer.getInstance());

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> method.invoke(null, input, mixins, false, true));

        assertInstanceOf(IOException.class, failure.getCause());
    }

    private static byte[] invalidUtf8Json(String prefix, String suffix) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(prefix.getBytes(StandardCharsets.UTF_8));
        output.write(0xc3);
        output.write(0x28);
        output.write(suffix.getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }
}
