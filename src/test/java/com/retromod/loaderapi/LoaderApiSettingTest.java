/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi;

import com.google.gson.JsonPrimitive;
import com.retromod.core.RetromodConfig;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code loader_api_runtime} setting: the auto rule, parsing and precedence. */
class LoaderApiSettingTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("auto is on for snapshot builds only")
    void autoRuleFollowsTheBuild() {
        assertTrue(RetromodVersion.isSnapshotBuild("1.4.0-snapshot.2"));
        assertTrue(RetromodVersion.isSnapshotBuild("1.4.0-SNAPSHOT"));
        assertFalse(RetromodVersion.isSnapshotBuild("1.4.0-rc.1"));
        assertFalse(RetromodVersion.isSnapshotBuild("1.4.0"));
        assertFalse(RetromodVersion.isSnapshotBuild("1.3.4"));
        assertFalse(RetromodVersion.isSnapshotBuild(""));
        assertFalse(RetromodVersion.isSnapshotBuild(null));

        assertTrue(resolve(null, null, null, "1.4.0-snapshot.2").on());
        assertFalse(resolve(null, null, null, "1.4.0-rc.1").on());
        assertFalse(resolve(null, null, null, "1.4.0").on());
    }

    @Test
    @DisplayName("auto, true, false and unknown values parse as documented")
    void valuesParse() {
        assertEquals(LoaderApiSetting.Mode.AUTO, LoaderApiSetting.parse("auto", "test"));
        assertEquals(LoaderApiSetting.Mode.ON, LoaderApiSetting.parse(" TRUE ", "test"));
        assertEquals(LoaderApiSetting.Mode.OFF, LoaderApiSetting.parse("false", "test"));
        assertEquals(LoaderApiSetting.Mode.AUTO, LoaderApiSetting.parse("sometimes", "test"),
                "an unknown value counts as auto");
        assertNull(LoaderApiSetting.parse((String) null, "test"));
        assertEquals(LoaderApiSetting.Mode.ON, LoaderApiSetting.parse(new JsonPrimitive(true), "test"));
        assertEquals(LoaderApiSetting.Mode.OFF, LoaderApiSetting.parse(new JsonPrimitive("false"), "test"));
    }

    @Test
    @DisplayName("The system property beats the CLI flag, which beats the config")
    void precedence() {
        LoaderApiSetting.Effective property = resolve("false", "true", "true", "1.4.0-snapshot.2");
        assertFalse(property.on());
        assertEquals(LoaderApiSetting.Source.SYSTEM_PROPERTY, property.source());

        LoaderApiSetting.Effective flag = resolve(null, "true", "false", "1.4.0");
        assertTrue(flag.on());
        assertEquals(LoaderApiSetting.Source.CLI_FLAG, flag.source());

        LoaderApiSetting.Effective config = resolve(null, null, "true", "1.4.0");
        assertTrue(config.on(), "true in config turns it on in a stable build");
        assertEquals(LoaderApiSetting.Source.CONFIG, config.source());

        LoaderApiSetting.Effective killSwitch = resolve(null, null, "false", "1.4.0-snapshot.2");
        assertFalse(killSwitch.on(), "false in config turns it off in a snapshot");
    }

    @Test
    @DisplayName("The config value is read without creating a missing file")
    void configValueIsRead() throws Exception {
        Path config = dir.resolve("config.json");
        assertTrue(RetromodConfig.findSetting(config, LoaderApiSetting.CONFIG_KEY).isEmpty());
        assertFalse(Files.exists(config));
        Files.writeString(config, "{\"loader_api_runtime\": \"false\"}");
        assertEquals("false", RetromodConfig.findSetting(config, LoaderApiSetting.CONFIG_KEY)
                .orElseThrow().getAsString());
        Files.writeString(config, "{\"loader_api_runtime\": true}");
        assertTrue(RetromodConfig.findSetting(config, LoaderApiSetting.CONFIG_KEY)
                .orElseThrow().getAsBoolean());
    }

    @Test
    @DisplayName("The CLI flag is removed before commands read their positional arguments")
    void cliFlagIsStripped() {
        String[] flag = new String[1];
        String[] args = LoaderApiSetting.stripCliFlag(new String[]{"transform",
                "--loader-api-runtime=false", "mod.jar", "--output", "out.jar"}, flag);
        assertArrayEquals(new String[]{"transform", "mod.jar", "--output", "out.jar"}, args);
        assertEquals("false", flag[0]);

        String[] spaced = new String[1];
        args = LoaderApiSetting.stripCliFlag(new String[]{"aot", "--loader-api-runtime", "true",
                "mod.jar"}, spaced);
        assertArrayEquals(new String[]{"aot", "mod.jar"}, args);
        assertEquals("true", spaced[0]);
    }

    @Test
    @DisplayName("A graduated provider is on under auto from its release, not its candidates")
    void graduation() {
        assertFalse(LoaderApiRuntime.hasGraduated(null, "1.5.0"));
        assertTrue(LoaderApiRuntime.hasGraduated("1.5.0", "1.5.0"));
        assertTrue(LoaderApiRuntime.hasGraduated("1.5.0", "1.6.0-rc.1"));
        assertFalse(LoaderApiRuntime.hasGraduated("1.5.0", "1.5.0-rc.2"));
        assertFalse(LoaderApiRuntime.hasGraduated("1.5.0", "1.4.9"));
    }

    @Test
    @DisplayName("Under auto, a provider runs in a stable build once it has graduated")
    void graduatedProviderRunsInStableBuild() throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        GraduatedProvider provider = new GraduatedProvider("1.5.0");
        LoaderApiContext context = new LoaderApiContext("fabric", "26.3",
                LoaderApiContext.TransformPath.TEST, null);
        try {
            transformer.clearRedirectsForTesting();
            try (var stable = LoaderApiRuntime.overrideForTesting(new LoaderApiSetting.Effective(false,
                    LoaderApiSetting.Mode.AUTO, LoaderApiSetting.Source.DEFAULT, "1.5.0"), List.of(provider))) {
                LoaderApiRuntime.prepare(transformer, context);
                assertEquals(1, provider.registrations.get(), "auto runs a graduated provider");
                assertEquals("graduated/1", LoaderApiRuntime.translationIdentity("fabric", "26.3"));
            }
            transformer.clearRedirectsForTesting();
            try (var candidate = LoaderApiRuntime.overrideForTesting(new LoaderApiSetting.Effective(false,
                    LoaderApiSetting.Mode.AUTO, LoaderApiSetting.Source.DEFAULT, "1.5.0-rc.1"), List.of(provider))) {
                LoaderApiRuntime.prepare(transformer, context);
                assertNull(LoaderApiRuntime.translationIdentity("fabric", "26.3"),
                        "a release candidate of the graduating version is still before it");
            }
            transformer.clearRedirectsForTesting();
            try (var off = LoaderApiRuntime.overrideForTesting(new LoaderApiSetting.Effective(false,
                    LoaderApiSetting.Mode.OFF, LoaderApiSetting.Source.CONFIG, "1.5.0"), List.of(provider))) {
                LoaderApiRuntime.prepare(transformer, context);
                assertNull(LoaderApiRuntime.translationIdentity("fabric", "26.3"),
                        "false turns off a graduated provider too");
            }
            assertEquals(1, provider.registrations.get(), "only the graduated stable build registered");
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("The startup line names the switch that works where Retromod runs")
    void offHintNamesTheWorkingSwitch() throws Exception {
        String property = System.getProperty(LoaderApiSetting.SYSTEM_PROPERTY);
        try (var restore = LoaderApiRuntime.overrideForTesting(null, null)) {
            System.clearProperty(LoaderApiSetting.SYSTEM_PROPERTY);
            String cli = LoaderApiTestSupport.captureErr(() -> LoaderApiRuntime.configureForCli("true"));
            assertTrue(cli.contains("--loader-api-runtime=false"), cli);
            assertFalse(cli.contains("config.json"), "the CLI never reads config/retromod/config.json");

            String game = LoaderApiTestSupport.captureErr(
                    () -> LoaderApiRuntime.configureForGame(dir, "fabric", "26.3"));
            if (LoaderApiRuntime.isEffectivelyOn()) {
                assertTrue(game.contains("loader_api_runtime") && game.contains("config/retromod/config.json"),
                        game);
            }

            System.setProperty(LoaderApiSetting.SYSTEM_PROPERTY, "true");
            String forced = LoaderApiTestSupport.captureErr(
                    () -> LoaderApiRuntime.configureForGame(dir, "fabric", "26.3"));
            assertTrue(forced.contains("-Dretromod.loaderApiRuntime=false"),
                    "a system property beats the config file, so the hint must name it: " + forced);
        } finally {
            if (property == null) {
                System.clearProperty(LoaderApiSetting.SYSTEM_PROPERTY);
            } else {
                System.setProperty(LoaderApiSetting.SYSTEM_PROPERTY, property);
            }
        }
    }

    /** Registers one claimed class redirect and counts registrations. */
    private static final class GraduatedProvider implements LoaderApiProvider {
        final java.util.concurrent.atomic.AtomicInteger registrations =
                new java.util.concurrent.atomic.AtomicInteger();
        private final String graduatedIn;

        GraduatedProvider(String graduatedIn) {
            this.graduatedIn = graduatedIn;
        }

        @Override
        public String id() {
            return "graduated";
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
            registrations.incrementAndGet();
            registration.redirectClass("net/fabricmc/fabric/api/fixture/v1/Graduated",
                    LoaderApiTestSupport.OverlapProvider.TARGET);
        }

        @Override
        public String graduatedIn() {
            return graduatedIn;
        }
    }

    private static LoaderApiSetting.Effective resolve(String property, String flag, String config,
            String version) {
        return LoaderApiSetting.resolve(property, flag,
                config == null ? Optional.empty() : Optional.of(new JsonPrimitive(config)), version);
    }
}
