/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.agent.RetromodAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Under the Java agent, the phantom sweep's own class loads call back into the transformer. The
 * sweep has to tolerate that on its own thread and must not make a class-loading thread wait.
 */
class AgentPhantomSweepTest {

    private static final long PROBE_TIMEOUT_SECONDS = 60;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a sweep re-entered by the agent keeps every loadable target")
    void reentrantSweepKeepsLoadableTargets() throws Exception {
        String output = runProbe("reentry");
        assertTrue(output.contains("REDIRECTED 4/4"),
                "a loadable redirect target was dropped as phantom. Probe output:\n" + output);
    }

    @Test
    @DisplayName("a class loading during the sweep does not deadlock with it")
    void classLoadDuringSweepDoesNotDeadlock() throws Exception {
        for (long delayMillis : new long[] {1, 5, 20}) {
            String output = runProbe("concurrent-load", String.valueOf(delayMillis));
            assertTrue(output.contains("FINISHED"),
                    "the probe did not finish with a " + delayMillis + " ms load delay. Output:\n"
                            + output);
        }
    }

    private String runProbe(String... args) throws Exception {
        Path agentJar = manifestOnlyAgentJar();
        Path log = tempDir.resolve("probe-" + String.join("-", args) + ".log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(java, "-Xmx256M",
                "-javaagent:" + agentJar, "-cp", System.getProperty("java.class.path"),
                AgentPhantomSweepProbe.class.getName()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            boolean exited = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String output = Files.readString(log, StandardCharsets.UTF_8);
            assertTrue(exited, "the probe hung for " + PROBE_TIMEOUT_SECONDS + " s. Output:\n" + output);
            assertEquals(0, process.exitValue(), "the probe failed. Output:\n" + output);
            return output;
        } finally {
            process.destroyForcibly();
        }
    }

    /** The agent class is already on the classpath; the jar only has to name it in its manifest. */
    private Path manifestOnlyAgentJar() throws IOException {
        Path jar = tempDir.resolve("agent.jar");
        if (Files.exists(jar)) return jar;
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", RetromodAgent.class.getName());
        // Matches the shipped agent jar; the agent registers a retransformable transformer.
        manifest.getMainAttributes().putValue("Can-Retransform-Classes", "true");
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream ignored = new JarOutputStream(out, manifest)) {
            // An empty jar with the manifest is enough.
        }
        return jar;
    }
}
