/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.embedder.ModVersionInfo;
import com.retromod.loaderapi.LoaderApiContext;
import com.retromod.loaderapi.LoaderApiRuntime;
import com.retromod.loaderapi.LoaderApiTestSupport;
import com.retromod.polyfill.PolyfillRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** With the runtime off, the hook leaves every registration table unchanged on every path. */
class LoaderApiRuntimeOffTest {

    private static final String HOST = "26.3";
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final LoaderApiTestSupport.CountingProvider counting =
            new LoaderApiTestSupport.CountingProvider();
    private AutoCloseable runtime;
    private String savedHost;

    @BeforeEach
    void setUp() {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = HOST;
        transformer.clearRedirectsForTesting();
        runtime = LoaderApiTestSupport.runtimeOff(counting);
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    // The digest covers the static mixin registries too. LoaderApiRuntimeOffOutputTest compares
    // whole jars with the pre-runtime build.
    @ParameterizedTest
    @EnumSource(LoaderApiContext.TransformPath.class)
    @DisplayName("The pre-launch registration digest is the same with and without the hook")
    void preLaunchDigestUnchanged(LoaderApiContext.TransformPath path) {
        RetromodPreLaunch.registerShimsForTransform(HOST);
        // The first transform drops phantom targets; take the digest after that sweep.
        transformer.transformClass(emptyClass(), "fixture/Empty");
        String before = transformer.registrationDigest();
        LoaderApiRuntime.prepare(transformer, new LoaderApiContext("fabric", HOST, path, null));
        LoaderApiRuntime.prepareForProcess(transformer, path);
        transformer.transformClass(emptyClass(), "fixture/Empty");
        assertEquals(before, transformer.registrationDigest(),
                "the hook must not change any table while the runtime is off");
        assertEquals(0, counting.calls.get(), "no provider may be asked anything while off");
    }

    @Test
    @DisplayName("The CLI registration digest is the same with and without the hook")
    void cliDigestUnchanged() throws Exception {
        ModVersionInfo info = new ModVersionInfo("fixture", "1.0", "1.21.1", "fabric", "1.0",
                java.util.Set.of(), java.util.Set.of(), false);
        new PolyfillRegistry().loadAndRegister(transformer);
        var auxiliary = com.retromod.cli.RetromodCli.class.getDeclaredMethod(
                "registerAuxiliaryRedirects", RetromodTransformer.class, ModVersionInfo.class, List.class);
        auxiliary.setAccessible(true);
        auxiliary.invoke(null, transformer, info, List.of());
        String before = transformer.registrationDigest();
        LoaderApiRuntime.prepare(transformer, new LoaderApiContext("fabric", HOST,
                LoaderApiContext.TransformPath.CLI_TRANSFORM, "1.21.1"));
        assertEquals(before, transformer.registrationDigest());
        assertEquals(0, counting.calls.get());
    }

    @Test
    @DisplayName("Off means no translation identity and no cache stamp suffix")
    void noIdentityWhileOff() {
        LoaderApiTestSupport.defaultContext("fabric", HOST);
        assertNull(LoaderApiRuntime.translationIdentity("fabric", HOST));
        assertNull(LoaderApiRuntime.translationIdentity("quilt", HOST));
        assertEquals("", LoaderApiRuntime.cacheStampSuffix());
        assertEquals(0, counting.calls.get());
    }

    private static byte[] emptyClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Empty", null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
