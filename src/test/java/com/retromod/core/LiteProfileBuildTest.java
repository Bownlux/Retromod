/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiteProfileBuildTest {

    @Test
    void liteJarRemainsTheMainArtifactForShading() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        Matcher profile = Pattern.compile(
                "(?s)<profile>\\s*<id>lite</id>(.*?)</profile>")
                .matcher(pom);

        assertTrue(profile.find(), "pom.xml must declare the lite profile");
        String lite = profile.group(1);

        assertTrue(lite.contains("<finalName>retromod-${project.version}-lite</finalName>"),
                "the lite profile must distinguish artifacts through finalName");
        assertFalse(lite.contains("<classifier>lite</classifier>"),
                "a jar classifier leaves Maven without a main artifact for shading");
        assertTrue(lite.contains(
                        "<retromod.shade.classifier>lite-all</retromod.shade.classifier>"),
                "the attached shaded artifact must keep its lite-specific classifier");
        assertTrue(pom.contains(
                        "<shadedClassifierName>${retromod.shade.classifier}</shadedClassifierName>"),
                "the shade execution must use the profile-overridable classifier");
    }
}
