/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.mixin.MixinCompatibilityTransformer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AotJsonParsingBoundsTest {

    @Test
    void refmapRequiresExactUtf8() throws Exception {
        Method method = AotCompiler.class.getDeclaredMethod(
                "remapRefmap", byte[].class, MixinCompatibilityTransformer.class,
                boolean.class, boolean.class);
        method.setAccessible(true);
        MixinCompatibilityTransformer mixins = new MixinCompatibilityTransformer(
                RetromodTransformer.getInstance());

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> method.invoke(null, invalidUtf8Refmap(), mixins, false, true));

        assertInstanceOf(IOException.class, failure.getCause());
    }

    private static byte[] invalidUtf8Refmap() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("{\"mappings\":{\"fixture\":{\"key\":\""
                .getBytes(StandardCharsets.UTF_8));
        output.write(0xc3);
        output.write(0x28);
        output.write("\"}}}".getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }
}
