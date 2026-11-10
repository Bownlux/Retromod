/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.fml;

import net.neoforged.neoforgespi.locating.IModFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Finds a mod's own files, which FancyModLoader 10 stopped offering with SecureJarHandler. */
public final class LegacyModFiles {

    // One file system per mod jar, left open as the old loader left its jars open.
    private static final Map<Path, FileSystem> OPENED = new ConcurrentHashMap<>();

    private LegacyModFiles() {}

    /** {@code file.findResource(path...)}: the file in the mod, which may not exist. */
    public static Path findResource(IModFile file, String... path) {
        if (path.length < 1) throw new IllegalArgumentException("Missing path");
        String relative = relative(String.join("/", path));
        List<Path> roots = roots(file);
        // A folder mod in a development run can have several roots; the first that has the file wins.
        for (Path root : roots) {
            Path found = root.resolve(relative);
            if (Files.exists(found)) return found;
        }
        return roots.get(0).resolve(relative);
    }

    /** {@code file.getSecureJar()}, answering the path and location calls mods made on it. */
    public static LegacySecureJar getSecureJar(IModFile file) {
        return new ModJar(file);
    }

    /** Where the mod's files are read from: inside its jar, or its folders. */
    static List<Path> roots(IModFile file) {
        List<Path> roots = new ArrayList<>();
        for (Path content : file.getContents().getContentRoots()) {
            Path root = rootOf(content);
            if (root != null) roots.add(root);
        }
        // A jar that cannot be opened still answers with a path that does not exist, as the old lookup never threw.
        if (roots.isEmpty()) roots.add(file.getFilePath());
        return roots;
    }

    private static Path rootOf(Path content) {
        if (Files.isDirectory(content)) return content;
        FileSystem jar = OPENED.computeIfAbsent(content, LegacyModFiles::open);
        return jar == null ? null : jar.getPath("/");
    }

    private static FileSystem open(Path jar) {
        try {
            return FileSystems.newFileSystem(jar, (ClassLoader) null);
        } catch (IOException | RuntimeException notAJar) {
            return null;
        }
    }

    // The old lookup read every path from the jar's root, with or without a leading slash.
    private static String relative(String path) {
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') start++;
        return path.substring(start);
    }

    /** A mod file seen through the old {@code SecureJar} calls. */
    private static final class ModJar implements LegacySecureJar, LegacySecureJar.ModuleDataProvider {

        private final IModFile file;

        ModJar(IModFile file) {
            this.file = file;
        }

        @Override
        public Path getPrimaryPath() {
            return file.getFilePath();
        }

        @Override
        public Path getRootPath() {
            return roots(file).get(0);
        }

        @Override
        public Path getPath(String first, String... rest) {
            String[] path = new String[rest.length + 1];
            path[0] = first;
            System.arraycopy(rest, 0, path, 1, rest.length);
            return findResource(file, path);
        }

        @Override
        public ModuleDataProvider moduleDataProvider() {
            return this;
        }

        @Override
        public URI uri() {
            return file.getFilePath().toUri();
        }
    }
}
