/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import com.retromod.loaderapi.LoaderApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.SLF4JServiceProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retromod's log reaches the host's logger at its real level, or standard error without one. */
class HostLogProviderTest {

    /** The part of the host's SLF4J Logger the sink calls, recorded instead of written. */
    public interface RecordingHost {
        void info(String message);

        void info(String message, Throwable error);

        void warn(String message);

        void warn(String message, Throwable error);

        void error(String message);

        void error(String message, Throwable error);

        void debug(String message);

        void debug(String message, Throwable error);

        boolean isDebugEnabled();
    }

    /** One call the host received. */
    record Call(String level, String message, Throwable error) {}

    /** A host that keeps every call, and claims DEBUG is on as Forge and NeoForge do. */
    static class Recorder implements RecordingHost {
        final List<Call> calls = new ArrayList<>();

        @Override
        public void info(String message) {
            calls.add(new Call("info", message, null));
        }

        @Override
        public void info(String message, Throwable error) {
            calls.add(new Call("info", message, error));
        }

        @Override
        public void warn(String message) {
            calls.add(new Call("warn", message, null));
        }

        @Override
        public void warn(String message, Throwable error) {
            calls.add(new Call("warn", message, error));
        }

        @Override
        public void error(String message) {
            calls.add(new Call("error", message, null));
        }

        @Override
        public void error(String message, Throwable error) {
            calls.add(new Call("error", message, error));
        }

        @Override
        public void debug(String message) {
            calls.add(new Call("debug", message, null));
        }

        @Override
        public void debug(String message, Throwable error) {
            calls.add(new Call("debug", message, error));
        }

        @Override
        public boolean isDebugEnabled() {
            return true;
        }
    }

    private static Logger loggerOver(RecordingHost host, boolean prefixName) {
        return new HostLogProvider.HostLogger("Retromod", name -> {
            try {
                return new HostLogProvider.HostSink(name, host, RecordingHost.class, prefixName);
            } catch (NoSuchMethodException e) {
                throw new AssertionError("the recording host lacks a Logger method", e);
            }
        });
    }

    @Test
    @DisplayName("a formatted line and its throwable reach the host once, at WARN")
    void formattedLineReachesTheHost() {
        Recorder host = new Recorder();
        IllegalStateException error = new IllegalStateException("boom");

        loggerOver(host, false).warn("Could not read {}: {}", "a.jar", "boom", error);

        assertEquals(List.of(new Call("warn", "Could not read a.jar: boom", error)), host.calls);
    }

    @Test
    @DisplayName("DEBUG and TRACE stay off even when the host has them on")
    void debugAndTraceStayOff() {
        Recorder host = new Recorder();
        Logger logger = loggerOver(host, false);

        logger.debug("per class {}", "redirect");
        logger.trace("per call site {}", "trace");

        assertFalse(logger.isDebugEnabled(), "the host's DEBUG setting must not turn it on");
        assertFalse(logger.isTraceEnabled());
        assertTrue(host.calls.isEmpty(), "no DEBUG or TRACE line may reach the host: " + host.calls);
    }

    @Test
    @DisplayName("a detail line reaches the host's DEBUG output, and an unmarked DEBUG line does not")
    void detailLineReachesHostDebug() {
        Recorder host = new Recorder();
        Logger logger = loggerOver(host, false);

        logger.debug(RetromodLog.DETAIL, "Transformed {} class files", 12);
        logger.debug("per class {}", "redirect");

        assertEquals(List.of(new Call("debug", "Transformed 12 class files", null)), host.calls);
    }

    @Test
    @DisplayName("log_level DEBUG, from the property or the config file, brings detail lines back")
    void logLevelSettingTurnsDetailLinesOn(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config.json");
        Files.writeString(config, "{ \"log_level\": \"DEBUG\" }");
        Path quiet = dir.resolve("quiet.json");
        Files.writeString(quiet, "{ \"log_level\": \"INFO\" }");

        assertTrue(RetromodLog.resolve(null, config), "the config file's DEBUG must turn it on");
        assertFalse(RetromodLog.resolve(null, quiet), "INFO is the default");
        assertFalse(RetromodLog.resolve(null, dir.resolve("missing.json")), "no file means the default");
        assertTrue(RetromodLog.resolve("debug", quiet), "the property overrides the file");
        assertFalse(RetromodLog.resolve("info", config), "the property overrides the file both ways");
    }

    @Test
    @DisplayName("the logger name prefixes the line only where the host layout drops it")
    void namePrefixFollowsTheFlag() {
        Recorder fabric = new Recorder();
        Recorder forge = new Recorder();

        loggerOver(fabric, true).info("Starting Retromod");
        loggerOver(forge, false).info("Starting Retromod");

        assertEquals(List.of(new Call("info", "[Retromod] Starting Retromod", null)), fabric.calls);
        assertEquals(List.of(new Call("info", "Starting Retromod", null)), forge.calls);
    }

    @Test
    @DisplayName("on an unshaded class path the log goes to standard error in the old format")
    void unshadedClassPathWritesToStandardError() throws Exception {
        String log = LoaderApiTestSupport.captureErr(
                () -> LoggerFactory.getLogger("Retromod-Test").warn("x {}", 1));

        assertTrue(log.contains("WARN Retromod-Test - x 1"), "log was:\n" + log);
    }

    @Test
    @DisplayName("a line the host throws on still reaches standard error")
    void hostFailureFallsBackToStandardError() throws Exception {
        Recorder host = new Recorder() {
            @Override
            public void error(String message) {
                throw new IllegalStateException("host logging is closed");
            }
        };

        String log = LoaderApiTestSupport.captureErr(
                () -> loggerOver(host, true).error("Failed to transform {}", "a.jar"));

        assertTrue(log.contains("ERROR Retromod - Failed to transform a.jar"), "log was:\n" + log);
    }

    @Test
    @DisplayName("Retromod's provider is the only SLF4J backend on the class path")
    void onlyProviderIsHostLogProvider() {
        List<String> providers = ServiceLoader.load(SLF4JServiceProvider.class).stream()
                .map(provider -> provider.type().getName())
                .toList();

        assertEquals(List.of(HostLogProvider.class.getName()), providers,
                "a bundled backend such as slf4j-simple would keep Retromod's log out of latest.log");
    }
}
