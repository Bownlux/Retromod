/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformerJsonParsingBoundsTest {

    @Test
    void fabricDependencyMetadataDepthIsBounded() throws Exception {
        String input = "{\"depends\":{\"minecraft\":\"1.20.1\",\"fixture\":\"1\"},"
                + "\"padding\":" + "[".repeat(257) + "0" + "]".repeat(257) + "}";
        FabricModTransformer transformer = new FabricModTransformer("26.2");
        Method method = FabricModTransformer.class.getDeclaredMethod(
                "moveNonCoreDepsToSuggests", String.class);
        method.setAccessible(true);

        assertEquals(input, method.invoke(transformer, input));
    }

    @Test
    void fabricDebugSettingUsesBoundedConfigParser(@TempDir Path directory) throws Exception {
        Path config = directory.resolve("config.json");
        Files.writeString(config, "{\"debug\":true,\"padding\":"
                + "[".repeat(257) + "0" + "]".repeat(257) + "}");

        assertEquals(false, FabricModTransformer.isDebugEnabled(config));

        Files.writeString(config, "{\"debug\":true}");
        assertEquals(true, FabricModTransformer.isDebugEnabled(config));
    }

    @Test
    void fabricRefmapRequiresExactUtf8(@TempDir Path directory) throws Exception {
        Path refmap = directory.resolve("fixture-refmap.json");
        Files.write(refmap, invalidUtf8Json(
                "{\"mappings\":{\"fixture\":{\"key\":\"", "\"}}}"));
        FabricModTransformer transformer = new FabricModTransformer("26.2");
        Method method = FabricModTransformer.class.getDeclaredMethod(
                "stripBrokenRefmapEntries", Path.class, Path.class);
        method.setAccessible(true);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> method.invoke(transformer, directory, refmap));

        assertInstanceOf(IOException.class, failure.getCause());
    }

    @Test
    void forgeLegacyMetadataDepthIsBounded() throws Exception {
        String input = "{\"modid\":\"fixture\",\"padding\":"
                + "[".repeat(257) + "0" + "]".repeat(257) + "}";
        Method method = ForgeModTransformer.class.getDeclaredMethod(
                "parseMcmodEntries", String.class);
        method.setAccessible(true);

        Object result = method.invoke(null, input);

        assertTrue(((List<?>) result).isEmpty());
    }

    @Test
    void forgeMixinConfigDepthIsBounded() throws Exception {
        String input = "{\"package\":\"fixture\",\"padding\":"
                + "[".repeat(257) + "0" + "]".repeat(257) + "}";
        ForgeModTransformer transformer = new ForgeModTransformer("26.2");
        Method method = ForgeModTransformer.class.getDeclaredMethod(
                "makeMixinConfigNonFatal", String.class);
        method.setAccessible(true);

        assertEquals(input, method.invoke(transformer, input));
    }

    @Test
    void forgeLegacyMetadataRequiresExactUtf8(@TempDir Path directory) throws Exception {
        Files.write(directory.resolve("mcmod.info"), invalidUtf8Json(
                "[{\"modid\":\"fixture", "\"}]"));

        assertThrows(IOException.class,
                () -> new ForgeModTransformer("26.2").generateTomlFromMcmodInfo(directory));
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
