/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.aot.AotCompiler;
import com.retromod.cli.RetromodCli;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseVersionConsistencyTest {

    @Test
    void releaseSurfacesUseTheCanonicalVersion() throws Exception {
        String version = RetromodVersion.RETROMOD_VERSION;
        String unixBuilder = Files.readString(Path.of("build-all.sh"));
        String windowsBuilder = Files.readString(Path.of("build-all.bat"));
        String cliArtifact = "retromod-" + version + "-cli.jar";

        assertTrue(Files.readString(Path.of("pom.xml"))
                .contains("<version>" + version + "</version>"));
        assertTrue(unixBuilder.contains("VERSION=\"" + version + "\""));
        assertTrue(windowsBuilder.contains("set \"VERSION=" + version + "\""));
        assertTrue(Files.readString(Path.of("README.md"))
                .contains("Version-" + version.replace("-", "--") + "-blueviolet"));
        assertTrue(Files.readString(Path.of("CHANGELOG.md"))
                .contains("## [" + version + "]"));
        assertTrue(Files.readString(Path.of("docs/cli.md")).contains(cliArtifact));
        assertTrue(Files.readString(Path.of("docs/installation.md")).contains(cliArtifact));
        assertTrue(Files.readString(Path.of("scripts/tests/test_release_artifacts.py"))
                .contains("VERSION = \"" + version + "\""));
        assertTrue(Pattern.compile("\\\"retromod_version\\\"\\s*:\\s*\\\""
                        + Pattern.quote(version) + "\\\"")
                .matcher(Files.readString(Path.of("docs/assets/probe-db.json")))
                .find());
        assertSnapshotChangelogDatesMatch(version);

        assertEquals(version, privateStaticString(RetromodCli.class, "VERSION"));
        assertEquals(version, privateStaticString(AotCompiler.class, "AOT_VERSION"));
    }

    @Test
    void maintainerGuidesTrackDependenciesAndProviderCounts() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        Matcher junit = Pattern.compile(
                "<artifactId>junit-jupiter</artifactId>\\s*<version>([^<]+)</version>")
                .matcher(pom);
        assertTrue(junit.find(), "pom.xml must declare junit-jupiter directly");

        long shimCount = serviceProviderCount(
                "src/main/resources/META-INF/services/com.retromod.core.VersionShim");
        long polyfillCount = serviceProviderCount(
                "src/main/resources/META-INF/services/com.retromod.polyfill.PolyfillProvider");
        String junitRow = "| JUnit Jupiter | " + junit.group(1) + " | Testing |";

        for (String guideName : List.of("AGENTS.md", "CLAUDE.md")) {
            String guide = Files.readString(Path.of(guideName));
            assertTrue(guide.contains(junitRow), guideName + " has a stale JUnit version");
            assertTrue(guide.contains("(" + shimCount + " registered providers"),
                    guideName + " has a stale version-shim provider count");
            assertTrue(guide.contains("(" + polyfillCount + " registered providers"),
                    guideName + " has a stale polyfill provider count");
        }
    }

    @Test
    void releaseBuildersUseTheSameMatrixAndIntegrityGates() throws Exception {
        String unixBuilder = Files.readString(Path.of("build-all.sh"));
        String windowsBuilder = Files.readString(Path.of("build-all.bat"));

        assertEquals(shellArray(unixBuilder, "MC_VERSIONS"),
                batchWords(windowsBuilder, "MC_VERSIONS"));
        assertEquals(shellArray(unixBuilder, "LOADERS"),
                batchWords(windowsBuilder, "LOADERS"));
        assertTrue(unixBuilder.contains(
                "if ! mvn clean package -DskipTests -Dexec.skip=true; then"));
        assertTrue(unixBuilder.contains("if ! command -v python3"));
        assertTrue(unixBuilder.contains(
                "if [ ! -f \"$TEMP_DIR/fabric.mod.json\" ] "
                    + "|| [ ! -f \"$TEMP_DIR/quilt.mod.json\" ]; then"));
        assertTrue(unixBuilder.contains(
                "expected_metadata_by_loader[loader]"));
        assertTrue(unixBuilder.contains(
                "set_dependency(\"java\", java_requirement)"));
        assertTrue(unixBuilder.contains(
                "set_dependency(\"minecraft\", \"=\" + minecraft_version)"));
        assertTrue(unixBuilder.contains(
                "quilt_dependencies.get(\"minecraft\") == \"=\" + minecraft_version"));
        assertFalse(unixBuilder.contains("Warning: Could not update fabric.mod.json"));
        assertTrue(windowsBuilder.contains("-Dexec.skip=true"));
        assertTrue(windowsBuilder.contains("SHADED_JAR=target\\retromod-%VERSION%-all.jar"));
        assertTrue(windowsBuilder.contains("POM_VERSION"));
        assertTrue(windowsBuilder.contains("--require-self-hash"));
        assertTrue(windowsBuilder.contains("scripts\\compute-self-hash.py"));
        assertTrue(windowsBuilder.contains("EXPECTED_FABRIC=23"));
        assertTrue(windowsBuilder.contains("EXPECTED_FORGE=23"));
        assertTrue(windowsBuilder.contains("EXPECTED_NEOFORGE=22"));
        assertTrue(windowsBuilder.contains("EXPECTED_CLI=1"));
        assertTrue(windowsBuilder.contains("EXPECTED_TOTAL=69"));
        assertTrue(windowsBuilder.contains("SHA256SUMS.txt"));
        assertTrue(windowsBuilder.contains(
                "'fabric':{'fabric.mod.json','quilt.mod.json'}"));
        assertTrue(windowsBuilder.contains("qmap.get('minecraft')=='='+mc"));
        assertTrue(windowsBuilder.contains("qmap.get('java')==j"));
    }

    @Test
    void sourceQuiltMetadataDeclaresTheBaselineJavaRequirement() throws Exception {
        JsonObject root = JsonParser.parseString(
                Files.readString(Path.of("src/main/resources/quilt.mod.json")))
                .getAsJsonObject();
        var dependencies = root.getAsJsonObject("quilt_loader")
                .getAsJsonArray("depends");

        String javaRequirement = null;
        for (var dependency : dependencies) {
            JsonObject object = dependency.getAsJsonObject();
            if ("java".equals(object.get("id").getAsString())) {
                javaRequirement = object.get("versions").getAsString();
            }
        }

        assertEquals(">=17", javaRequirement);
    }

    @Test
    void sourceQuiltMetadataUsesTheFabricCompatibleEntrypointContracts() throws Exception {
        JsonObject root = JsonParser.parseString(
                Files.readString(Path.of("src/main/resources/quilt.mod.json")))
                .getAsJsonObject();
        JsonObject entrypoints = root.getAsJsonObject("quilt_loader")
                .getAsJsonObject("entrypoints");

        assertEquals(Retromod.class.getName(), entrypoints.get("main").getAsString());
        assertEquals(RetromodClient.class.getName(), entrypoints.get("client").getAsString());
        assertEquals(RetromodServer.class.getName(), entrypoints.get("server").getAsString());
        assertEquals(RetromodPreLaunch.class.getName(),
                entrypoints.get("preLaunch").getAsString());
        assertFalse(entrypoints.has("init"));
        assertFalse(entrypoints.has("client_init"));
        assertFalse(entrypoints.has("server_init"));
        assertFalse(entrypoints.has("pre_launch"));

        assertTrue(ModInitializer.class.isAssignableFrom(Retromod.class));
        assertTrue(ClientModInitializer.class.isAssignableFrom(RetromodClient.class));
        assertTrue(DedicatedServerModInitializer.class.isAssignableFrom(RetromodServer.class));
        assertTrue(PreLaunchEntrypoint.class.isAssignableFrom(RetromodPreLaunch.class));
    }

    @Test
    void legacyBuildScriptsDelegateToTheReleaseBuilders() throws Exception {
        String unixDelegate = Files.readString(Path.of("build.sh"));
        String windowsDelegate = Files.readString(Path.of("build.bat"));

        assertTrue(unixDelegate.contains(
                "exec bash \"$SCRIPT_DIR/build-all.sh\" \"$@\""));
        assertTrue(windowsDelegate.contains(
                "call \"%~dp0build-all.bat\" %*"));
        assertFalse(unixDelegate.contains("VERSION="));
        assertFalse(windowsDelegate.contains("VERSION="));
    }

    @Test
    void canonicalVersionIsTheOnlyJavaSourceLiteral() throws Exception {
        String version = RetromodVersion.RETROMOD_VERSION;
        long filesWithLiteral;
        try (var sources = Files.walk(Path.of("src/main/java"))) {
            filesWithLiteral = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, version))
                    .count();
        }
        assertEquals(1, filesWithLiteral,
                "runtime versions must reference RetromodVersion instead of copying its literal");
    }

    private static String privateStaticString(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static void assertSnapshotChangelogDatesMatch(String version) throws Exception {
        Matcher snapshot = Pattern.compile("-snapshot\\.(\\d+)$").matcher(version);
        if (!snapshot.find()) {
            return;
        }

        Matcher fullHeading = Pattern.compile(
                "(?m)^## \\[" + Pattern.quote(version) + "\\] - (\\d{4}-\\d{2}-\\d{2})$")
                .matcher(Files.readString(Path.of("CHANGELOG.md")));
        assertTrue(fullHeading.find(), "CHANGELOG.md is missing the current snapshot date");

        Matcher publicHeading = Pattern.compile(
                "(?m)^### Snapshot " + Pattern.quote(snapshot.group(1))
                        + ", ([A-Za-z]+ \\d{1,2}, \\d{4})$")
                .matcher(Files.readString(Path.of("docs/changelog.md")));
        assertTrue(publicHeading.find(), "docs/changelog.md is missing the current snapshot date");

        LocalDate fullDate = LocalDate.parse(fullHeading.group(1));
        LocalDate publicDate = LocalDate.parse(
                publicHeading.group(1),
                DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US));
        assertEquals(fullDate, publicDate,
                "the full and public changelogs must use the same snapshot date");
    }

    private static boolean contains(Path path, String value) {
        try {
            return Files.readString(path).contains(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not inspect " + path, e);
        }
    }

    private static long serviceProviderCount(String descriptor) throws Exception {
        try (var lines = Files.lines(Path.of(descriptor))) {
            return lines.map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .count();
        }
    }

    private static List<String> shellArray(String script, String name) {
        Matcher assignment = Pattern.compile(
                "(?m)^" + Pattern.quote(name) + "=\\(([^)]*)\\)$")
                .matcher(script);
        assertTrue(assignment.find(), "missing shell array " + name);

        Matcher value = Pattern.compile("\"([^\"]+)\"").matcher(assignment.group(1));
        List<String> values = new ArrayList<>();
        while (value.find()) {
            values.add(value.group(1));
        }
        return values;
    }

    private static List<String> batchWords(String script, String name) {
        Matcher assignment = Pattern.compile(
                "(?mi)^set \\\"" + Pattern.quote(name) + "=([^\"]*)\\\"\\s*$")
                .matcher(script);
        assertTrue(assignment.find(), "missing batch value " + name);
        String value = assignment.group(1).trim();
        return value.isEmpty() ? List.of() : Arrays.asList(value.split("\\s+"));
    }
}
