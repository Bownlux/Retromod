/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodVersion;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads a patched --mc-jar, NeoForge's universal and loader jars and the game's libraries beside it as the host, as the game sees them. */
final class OfflineHostJars {

    private static final Pattern PATCHED_GAME_JAR = Pattern.compile("minecraft-(client|server)-patched");
    private static final Pattern LOADER_IN_ARGS = Pattern.compile("fancymodloader/loader/([^/\\\\]+)/loader-\\1\\.jar");
    // The FancyModLoader major each NeoForge line ships, read from NeoForge's published module metadata.
    private static final Map<String, Integer> LOADER_MAJOR = Map.of(
            "21.1", 4, "21.9", 10, "21.10", 10, "21.11", 10, "26.1", 11, "26.2", 11, "26.3", 12);

    private OfflineHostJars() {}

    /** Reads a patched --mc-jar and the loader jars beside it as the host. */
    static void useNextToGameJar(String[] args) {
        Path gameJar = null;
        for (int i = 1; i < args.length - 1; i++) {
            if ("--mc-jar".equals(args[i])) gameJar = Path.of(args[i + 1]);
        }
        List<Path> loaderJars = gameJar == null ? List.of() : nextTo(gameJar);
        if (loaderJars.isEmpty()) return;
        // The game jar comes too: a host that shows NeoForge but no Minecraft class would answer that
        // every vanilla class is missing, and drop moves such as NeoForge's TriState into net.minecraft.util.
        List<Path> jars = new ArrayList<>();
        jars.add(gameJar);
        jars.addAll(loaderJars);
        // The libraries too, since a host class can take a member from one, as a registry does from DataFixerUpper's Keyable.
        List<Path> libraries = libraries(gameJar);
        jars.addAll(libraries);
        List<URL> urls = new ArrayList<>();
        for (Path jar : jars) {
            try {
                urls.add(jar.toUri().toURL());
            } catch (MalformedURLException unreadable) {
                return;
            }
        }
        // Only class files are read from these jars; no class is loaded from them.
        ClassResourceInspector.setOfflineHost(new URLClassLoader(urls.toArray(URL[]::new), null));
        for (Path jar : jars.subList(0, jars.size() - libraries.size())) System.out.println("Reading the host's classes from: " + jar.getFileName());
        if (libraries.isEmpty()) {
            System.out.println("Found none of the game's libraries beside it; pass them with --host-jar for the same result as the game.");
        } else {
            System.out.println("Reading the host's classes from " + libraries.size() + " of the game's libraries too.");
        }
    }

    /** The game's libraries: those a server install's launch arguments name, else those the launcher's file for its Minecraft lists. */
    static List<Path> libraries(Path gameJar) {
        // libraries/net/neoforged/minecraft-client-patched/<version>/<jar>
        Path versionDir = gameJar.toAbsolutePath().getParent();
        Path root = versionDir.getParent().getParent().getParent().getParent();
        String version = versionDir.getFileName().toString();
        Path neoforgeDir = versionDir.getParent().getParent().resolve("neoforge").resolve(version);
        Set<Path> jars = new LinkedHashSet<>();
        for (String args : List.of("unix_args.txt", "win_args.txt")) {
            Path file = neoforgeDir.resolve(args);
            if (!Files.isRegularFile(file)) continue;
            try {
                // Each library is a path under the libraries folder, on the class or module path.
                for (String entry : Files.readString(file).split("[\\s:;]+")) {
                    if (!entry.startsWith("libraries") || !entry.endsWith(".jar")) continue;
                    Path jar = root.resolve(entry.substring("libraries".length() + 1)).normalize();
                    if (Files.isRegularFile(jar)) jars.add(jar);
                }
            } catch (IOException unreadable) {
                // The launcher's file below may still list them.
            }
            if (!jars.isEmpty()) return List.copyOf(jars);
        }
        String minecraft = minecraftVersion(version);
        if (minecraft == null) return List.of();
        // Prism keeps each Minecraft version's library list in meta/, the vanilla launcher in versions/.
        for (Path file : List.of(root.resolveSibling("meta").resolve("net.minecraft").resolve(minecraft + ".json"),
                root.resolveSibling("versions").resolve(minecraft).resolve(minecraft + ".json"))) {
            if (!Files.isRegularFile(file)) continue;
            try {
                JsonObject launch = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                for (JsonElement library : launch.getAsJsonArray("libraries")) {
                    Path jar = mavenJar(root, library.getAsJsonObject().get("name").getAsString());
                    if (jar != null && Files.isRegularFile(jar)) jars.add(jar);
                }
            } catch (IOException | RuntimeException unreadable) {
                continue;
            }
            if (!jars.isEmpty()) return List.copyOf(jars);
        }
        return List.of();
    }

    /** {@code group:artifact:version} under the libraries folder; a native classifier's jar is left out. */
    private static Path mavenJar(Path root, String name) {
        String[] parts = name.split(":");
        if (parts.length != 3) return null;
        return root.resolve(parts[0].replace('.', '/')).resolve(parts[1]).resolve(parts[2]).resolve(parts[1] + "-" + parts[2] + ".jar");
    }

    /** The Minecraft version a NeoForge version is for: 21.11.42 is for 1.21.11 and 21.0.1 for 1.21; 26.1.2.60 is for 26.1.2 and 26.3.0.43 for 26.3. */
    static String minecraftVersion(String neoforgeVersion) {
        String[] parts = neoforgeVersion.split("-")[0].split("\\.");
        if (parts.length < 3) return null;
        try {
            if (Integer.parseInt(parts[0]) >= 26) {
                return parts.length > 3 && !parts[2].equals("0") ? parts[0] + "." + parts[1] + "." + parts[2] : parts[0] + "." + parts[1];
            }
        } catch (NumberFormatException unknown) {
            return null;
        }
        return "1." + parts[0] + (parts[1].equals("0") ? "" : "." + parts[1]);
    }

    /** NeoForge's jars in the same libraries folder as its patched game jar, or none for any other jar. */
    static List<Path> nextTo(Path gameJar) {
        // libraries/net/neoforged/minecraft-client-patched/<version>/<jar>
        Path versionDir = gameJar.toAbsolutePath().getParent();
        Path artifactDir = versionDir == null ? null : versionDir.getParent();
        Path neoforged = artifactDir == null ? null : artifactDir.getParent();
        if (neoforged == null) return List.of();
        String version = versionDir.getFileName().toString();
        // Older lines such as 21.1 keep the patched game jar in NeoForge's own folder: neoforge/<version>/neoforge-<version>-client.jar.
        Matcher older = Pattern.compile(Pattern.quote("neoforge-" + version + "-") + "(client|server)\\.jar")
                .matcher(gameJar.getFileName().toString());
        boolean olderLayout = artifactDir.getFileName().toString().equals("neoforge") && older.matches();
        if (!olderLayout && !PATCHED_GAME_JAR.matcher(artifactDir.getFileName().toString()).matches()) {
            return List.of();
        }
        List<Path> jars = new ArrayList<>();
        Path neoforgeDir = neoforged.resolve("neoforge").resolve(version);
        if (olderLayout) {
            // That jar holds only the classes NeoForge patches, so without the vanilla jar it would answer that most classes are missing.
            Optional<Path> vanilla = vanillaJar(neoforged.getParent(), neoforgeDir, version, older.group(1));
            if (vanilla.isEmpty()) return List.of();
            jars.add(vanilla.get());
        }
        Path universal = neoforgeDir.resolve("neoforge-" + version + "-universal.jar");
        if (Files.isRegularFile(universal)) jars.add(universal);
        loaderJar(neoforged, neoforgeDir, version).ifPresent(jars::add);
        return jars;
    }

    /** The Mojang-named game an older NeoForge line patches: net/minecraft/<side>/<mc>-<neoform>/<side>-<mc>-<neoform>-srg.jar. */
    private static Optional<Path> vanillaJar(Path net, Path neoforgeDir, String neoforgeVersion, String side) {
        String minecraft = minecraftVersion(neoforgeVersion);
        Path sideDir = net.resolve("minecraft").resolve(side);
        if (minecraft == null || !Files.isDirectory(sideDir)) return Optional.empty();
        String named = launchArgument(neoforgeDir, "--fml.neoFormVersion");
        List<Path> found = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(sideDir)) {
            for (Path dir : dirs.toList()) {
                String build = dir.getFileName().toString();
                if (!build.startsWith(minecraft + "-") || named != null && !build.equals(minecraft + "-" + named)) continue;
                Path jar = dir.resolve(side + "-" + build + "-srg.jar");
                if (Files.isRegularFile(jar)) found.add(jar);
            }
        } catch (IOException unreadable) {
            return Optional.empty();
        }
        // Two NeoForm builds and no launch arguments to choose one: an unknown host is safer than a wrong one.
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    /** The value after {@code name} in the installer's launch arguments, or null. */
    private static String launchArgument(Path neoforgeDir, String name) {
        for (String args : List.of("unix_args.txt", "win_args.txt")) {
            try {
                Matcher value = Pattern.compile(Pattern.quote(name) + "\\s+(\\S+)").matcher(Files.readString(neoforgeDir.resolve(args)));
                if (value.find()) return value.group(1);
            } catch (IOException missing) {
                // The other file, or none.
            }
        }
        return null;
    }

    /** The loader the installer named in its launch arguments, else the newest of the major this NeoForge line ships. */
    private static Optional<Path> loaderJar(Path neoforged, Path neoforgeDir, String neoforgeVersion) {
        Path loaders = neoforged.resolve("fancymodloader").resolve("loader");
        for (String args : List.of("unix_args.txt", "win_args.txt")) {
            Path file = neoforgeDir.resolve(args);
            if (!Files.isRegularFile(file)) continue;
            try {
                Matcher named = LOADER_IN_ARGS.matcher(Files.readString(file));
                if (named.find()) return existing(loaders, named.group(1));
            } catch (IOException unreadable) {
                // The folder listing below still finds a loader.
            }
        }
        List<String> versions = loaderVersions(loaders);
        Integer major = LOADER_MAJOR.get(line(neoforgeVersion));
        // An unknown NeoForge line could pair with a loader of another major, so pick none; --host-jar names it.
        if (major == null) return Optional.empty();
        Optional<String> chosen = versions.stream()
                .filter(v -> v.startsWith(major + "."))
                .max(RetromodVersion::compareMcVersions);
        return chosen.flatMap(v -> existing(loaders, v));
    }

    private static Optional<Path> existing(Path loaders, String version) {
        Path jar = loaders.resolve(version).resolve("loader-" + version + ".jar");
        return Files.isRegularFile(jar) ? Optional.of(jar) : Optional.empty();
    }

    private static List<String> loaderVersions(Path loaders) {
        if (!Files.isDirectory(loaders)) return List.of();
        try (Stream<Path> dirs = Files.list(loaders)) {
            return dirs.filter(Files::isDirectory).map(dir -> dir.getFileName().toString()).toList();
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    // 26.3.0.43-beta is the 26.3 line; 21.11.42 is the 21.11 line.
    private static String line(String neoforgeVersion) {
        String[] parts = neoforgeVersion.split("\\.");
        return parts.length < 2 ? neoforgeVersion : parts[0] + "." + parts[1];
    }
}
