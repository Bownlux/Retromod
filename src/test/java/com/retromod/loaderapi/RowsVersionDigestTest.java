/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.loaderapi.fabric.v26.HostSurface;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** A change to what the Fabric runtime registers on any host comes with a new rows_version. */
class RowsVersionDigestTest {

    private static final String DIGESTS = "/loaderapi/fabric-26x-registration-digests.tsv";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    @Test
    @DisplayName("The runtime's registration on each indexed host matches the digest recorded for its rows_version")
    void registrationChangesBumpRowsVersion() throws Exception {
        String rowsVersion = LoaderApiTestSupport.fabricProvider().rowsVersion();
        Map<String, String> current = new LinkedHashMap<>();
        for (String host : HostSurface.INDEXED_LINES) current.put(host, digestOn(host));

        Map<String, String> recorded = recorded().get(rowsVersion);
        StringBuilder lines = new StringBuilder();
        current.forEach((host, digest) -> lines.append(rowsVersion).append('\t').append(host).append('\t')
                .append(digest).append('\n'));
        assertNotNull(recorded, "rows_version " + rowsVersion + " has no recorded digest. Add these lines to "
                + DIGESTS + ":\n" + lines);
        assertEquals(recorded, current, "What the runtime registers changed, but rows_version is still "
                + rowsVersion + ", so jars translated before the change would never be translated again. Bump "
                + "rows_version in renames.tsv, then add the new version's lines to " + DIGESTS + ".\nThe "
                + "registration now gives:\n" + lines);
    }

    /**
     * A SHA-256 over the transformer table lines the runtime adds or removes on {@code host},
     * starting from empty tables, so shims and static mixin registries do not count.
     */
    private String digestOn(String host) throws Exception {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = host;
        Set<String> before = transformerLines();
        try (AutoCloseable runtime = LoaderApiTestSupport.runtimeOn(LoaderApiTestSupport.fabricProvider())) {
            LoaderApiRuntime.prepare(transformer, new LoaderApiContext("fabric", host,
                    LoaderApiContext.TransformPath.TEST, null));
        }
        Set<String> after = transformerLines();
        TreeSet<String> changed = new TreeSet<>();
        for (String line : after) if (!before.contains(line)) changed.add("+ " + line);
        for (String line : before) if (!after.contains(line)) changed.add("- " + line);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (String line : changed) sha.update((line + "\n").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(sha.digest());
    }

    private Set<String> transformerLines() {
        Set<String> lines = new TreeSet<>();
        for (String line : transformer.registrationLines()) {
            if (line.startsWith("transformer.")) lines.add(line);
        }
        return lines;
    }

    /** The recorded digests: rows_version to host to digest. */
    private static Map<String, Map<String, String>> recorded() throws IOException {
        Map<String, Map<String, String>> byVersion = new HashMap<>();
        try (InputStream in = RowsVersionDigestTest.class.getResourceAsStream(DIGESTS)) {
            assertNotNull(in, DIGESTS + " is missing");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("rows_version\t")) continue;
                List<String> cells = List.of(line.trim().split("\t"));
                byVersion.computeIfAbsent(cells.get(0), version -> new LinkedHashMap<>()).put(cells.get(1),
                        cells.get(2));
            }
        }
        return byVersion;
    }
}
