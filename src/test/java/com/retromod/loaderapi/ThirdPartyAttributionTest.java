/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fabric API (Apache-2.0) adaptations are listed, keep their header, and ship the license. */
class ThirdPartyAttributionTest {

    private static final String ADAPTED = "Adapted for Retromod from FabricMC/fabric ";
    private static final Pattern LINE = Pattern.compile(
            "Adapted for Retromod from FabricMC/fabric \\S+/\\S+ at \\S+ \\(Apache-2\\.0\\)\\. Changes: .+\\.");
    private static final Pattern ROW = Pattern.compile("^\\| `?([^|`]+?)`? \\| [^|]+ \\| [^|]+ \\| Apache-2\\.0 \\|$",
            Pattern.MULTILINE);

    @Test
    @DisplayName("Adapted files and THIRD_PARTY_NOTICES.md list the same files")
    void noticesMatchSources() throws IOException {
        Set<String> adapted = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!source.contains(ADAPTED)) continue;
                String name = file.toString().replace('\\', '/');
                assertTrue(LINE.matcher(source).find(), name + " has a malformed adaptation line");
                assertTrue(source.contains("Licensed under the Apache License, Version 2.0"),
                        name + " must keep the upstream Apache-2.0 header");
                adapted.add(name);
            }
        }
        Set<String> listed = new TreeSet<>();
        Matcher row = ROW.matcher(Files.readString(Path.of("THIRD_PARTY_NOTICES.md"), StandardCharsets.UTF_8));
        while (row.find()) {
            if (!row.group(1).equals("Retromod file")) listed.add(row.group(1).trim());
        }
        assertEquals(adapted, listed, "THIRD_PARTY_NOTICES.md must list every adapted file, and only those");
    }

    @Test
    @DisplayName("The Apache-2.0 text ships in the jar under META-INF/licenses/fabric-api/")
    void licenseTextShips() throws IOException {
        Path license = Path.of("src/main/resources/META-INF/licenses/fabric-api/LICENSE");
        String text = Files.readString(license, StandardCharsets.UTF_8);
        assertTrue(text.contains("Apache License") && text.contains("Version 2.0, January 2004"),
                "the license file must be the Apache License 2.0 text");
        assertTrue(ThirdPartyAttributionTest.class.getResource("/META-INF/licenses/fabric-api/LICENSE") != null,
                "the license must be on the classpath, so it is packaged");
    }
}
