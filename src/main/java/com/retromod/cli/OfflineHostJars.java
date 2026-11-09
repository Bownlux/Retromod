/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodVersion;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads a patched --mc-jar and NeoForge's universal and loader jars beside it as the host, as the game sees them. */
final class OfflineHostJars {

    private static final Pattern PATCHED_GAME_JAR = Pattern.compile("minecraft-(client|server)-patched");
    private static final Pattern LOADER_IN_ARGS = Pattern.compile("fancymodloader/loader/([^/\\\\]+)/loader-\\1\\.jar");
    // The FancyModLoader major each NeoForge line ships, read from NeoForge's published module metadata.
    private static final Map<String, Integer> LOADER_MAJOR = Map.of(
            "21.9", 10, "21.10", 10, "21.11", 10, "26.1", 11, "26.2", 11, "26.3", 12);

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
        for (Path jar : jars) System.out.println("Reading the host's classes from: " + jar.getFileName());
    }

    /** NeoForge's jars in the same libraries folder as its patched game jar, or none for any other jar. */
    static List<Path> nextTo(Path gameJar) {
        // libraries/net/neoforged/minecraft-client-patched/<version>/<jar>
        Path versionDir = gameJar.toAbsolutePath().getParent();
        Path artifactDir = versionDir == null ? null : versionDir.getParent();
        Path neoforged = artifactDir == null ? null : artifactDir.getParent();
        if (neoforged == null || !PATCHED_GAME_JAR.matcher(artifactDir.getFileName().toString()).matches()) {
            return List.of();
        }
        String version = versionDir.getFileName().toString();
        List<Path> jars = new ArrayList<>();
        Path neoforgeDir = neoforged.resolve("neoforge").resolve(version);
        Path universal = neoforgeDir.resolve("neoforge-" + version + "-universal.jar");
        if (Files.isRegularFile(universal)) jars.add(universal);
        loaderJar(neoforged, neoforgeDir, version).ifPresent(jars::add);
        return jars;
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
