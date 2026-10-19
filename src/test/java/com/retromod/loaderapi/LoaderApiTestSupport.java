/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi;

import com.retromod.core.RedirectKey;
import com.retromod.core.RetromodVersion;
import com.retromod.loaderapi.fabric.v26.FabricApi26Provider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Test access to the runtime's seams, for tests outside this package. */
public final class LoaderApiTestSupport {

    private LoaderApiTestSupport() {}

    /** Turns the runtime on with {@code providers}; close the handle to restore the setting. */
    public static AutoCloseable runtimeOn(LoaderApiProvider... providers) {
        return LoaderApiRuntime.overrideForTesting(new LoaderApiSetting.Effective(true,
                LoaderApiSetting.Mode.ON, LoaderApiSetting.Source.SYSTEM_PROPERTY,
                RetromodVersion.RETROMOD_VERSION), providers.length == 0 ? null : List.of(providers));
    }

    /** Turns the runtime off with {@code providers}; close the handle to restore the setting. */
    public static AutoCloseable runtimeOff(LoaderApiProvider... providers) {
        return LoaderApiRuntime.overrideForTesting(new LoaderApiSetting.Effective(false,
                LoaderApiSetting.Mode.OFF, LoaderApiSetting.Source.SYSTEM_PROPERTY,
                RetromodVersion.RETROMOD_VERSION), providers.length == 0 ? null : List.of(providers));
    }

    /** The bundled Fabric provider. */
    public static LoaderApiProvider fabricProvider() {
        return new FabricApi26Provider();
    }

    /** Sets the context the class-load hook falls back to. */
    public static void defaultContext(String loader, String host) {
        LoaderApiRuntime.setDefaultContextForTesting(new LoaderApiContext(loader, host,
                LoaderApiContext.TransformPath.TEST, null));
    }

    /** Something a test runs that may throw. */
    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }

    /** Runs {@code action} and returns what it printed to standard error, where the test log goes. */
    public static String captureErr(Action action) throws Exception {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A provider that records every call, to prove the off path never reaches one. */
    public static final class CountingProvider implements LoaderApiProvider {
        public final AtomicInteger calls = new AtomicInteger();

        @Override
        public String id() {
            calls.incrementAndGet();
            return "counting";
        }

        @Override
        public String rowsVersion() {
            calls.incrementAndGet();
            return "1";
        }

        @Override
        public boolean appliesTo(LoaderApiContext context) {
            calls.incrementAndGet();
            return true;
        }

        @Override
        public void register(LoaderApiRegistration registration, LoaderApiContext context) {
            calls.incrementAndGet();
        }
    }

    /**
     * A provider that takes over keys older shims already overwrite one another on 26.3, the case
     * that showed registering last is not enough. It keeps the ones no bundled batch serves yet;
     * HudRenderCallback and ScreenEvents.beforeRender moved to batch S1.
     */
    public static final class OverlapProvider implements LoaderApiProvider {
        public static final String TARGET = "com/retromod/loaderapi/OwnershipTarget";
        public static final List<String> TYPES = List.of(
                "net/fabricmc/fabric/api/item/v1/FabricItemSettings",
                "net/fabricmc/fabric/api/renderer/v1/Renderer",
                "net/fabricmc/fabric/api/renderer/v1/material/MaterialFinder",
                "net/fabricmc/fabric/api/renderer/v1/material/RenderMaterial",
                "net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents",
                "net/fabricmc/fabric/api/client/rendering/v1/WorldRenderContext");
        public static final RedirectKey ENTITY_BUILDER = RedirectKey.type(
                "net/fabricmc/fabric/api/object/builder/v1/entity/FabricEntityTypeBuilder");

        @Override
        public String id() {
            return "overlap-test";
        }

        @Override
        public String rowsVersion() {
            return "1";
        }

        @Override
        public boolean appliesTo(LoaderApiContext context) {
            return "fabric".equals(context.loader());
        }

        @Override
        public void register(LoaderApiRegistration registration, LoaderApiContext context) {
            for (String type : TYPES) registration.redirectClass(type, TARGET);
            registration.suppress(ENTITY_BUILDER);
        }

        /** Every key this provider declares. */
        public static List<RedirectKey> keys() {
            List<RedirectKey> keys = new ArrayList<>();
            for (String type : TYPES) keys.add(RedirectKey.type(type));
            keys.add(ENTITY_BUILDER);
            return keys;
        }
    }
}
