/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.module.InvalidModuleDescriptorException;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Removes a root module-info.class that names packages its jar lacks, which strict module loaders refuse. */
public final class ModuleInfoRepair {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    private static final String MODULE_INFO = "module-info.class";

    private ModuleInfoRepair() {}

    /** Checks an extracted jar; returns true when its module-info was removed. */
    public static boolean dropInvalid(Path modDir, String jarName) throws IOException {
        Path moduleInfo = modDir.resolve(MODULE_INFO);
        if (!Files.isRegularFile(moduleInfo)) return false;
        Set<String> packages = new HashSet<>();
        try (Stream<Path> files = Files.walk(modDir)) {
            files.filter(Files::isRegularFile)
                    .map(file -> modDir.relativize(file).toString().replace('\\', '/'))
                    .forEach(name -> addPackage(packages, name));
        }
        String problem = problem(Files.readAllBytes(moduleInfo), packages);
        if (problem == null) return false;
        Files.delete(moduleInfo);
        report(jarName, problem);
        return true;
    }

    /** Checks a jar on disk and rewrites it without its module-info when the descriptor is invalid. */
    public static boolean dropInvalid(Path jar) throws IOException {
        String problem;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry moduleInfo = zip.getEntry(MODULE_INFO);
            if (moduleInfo == null) return false;
            Set<String> packages = new HashSet<>();
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                addPackage(packages, entries.nextElement().getName());
            }
            try (InputStream in = zip.getInputStream(moduleInfo)) {
                problem = problem(in.readAllBytes(), packages);
            }
            if (problem == null) return false;
            // Copy every other entry as it is, then put the copy in place of the jar.
            Path copy = Files.createTempFile(jar.toAbsolutePath().getParent(), "." + jar.getFileName(), ".tmp");
            try (OutputStream out = Files.newOutputStream(copy); ZipOutputStream zipOut = new ZipOutputStream(out)) {
                for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.getName().equals(MODULE_INFO)) continue;
                    zipOut.putNextEntry(new ZipEntry(entry.getName()));
                    if (!entry.isDirectory()) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            in.transferTo(zipOut);
                        }
                    }
                    zipOut.closeEntry();
                }
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(copy);
                throw e;
            }
            Files.move(copy, jar, StandardCopyOption.REPLACE_EXISTING);
        }
        report(jar.getFileName().toString(), problem);
        return true;
    }

    /** The JDK's objection to this descriptor against the jar's packages, or null when it is valid. */
    static String problem(byte[] moduleInfo, Set<String> packages) {
        try {
            ModuleDescriptor.read(new ByteArrayInputStream(moduleInfo), () -> packages);
            return null;
        } catch (InvalidModuleDescriptorException invalid) {
            return invalid.getMessage();
        } catch (IOException unreadable) {
            return "unreadable descriptor: " + unreadable.getMessage();
        }
    }

    // A class outside META-INF names its package; multi-release copies under META-INF/versions do not
    // count, because the root descriptor must stand on the root classes.
    private static void addPackage(Set<String> packages, String entryName) {
        if (!entryName.endsWith(".class") || entryName.startsWith("META-INF/")) return;
        int slash = entryName.lastIndexOf('/');
        if (slash > 0) packages.add(entryName.substring(0, slash).replace('/', '.'));
    }

    private static void report(String jarName, String problem) {
        // A shaded library's descriptor left at the root, as in JEI, is the usual cause.
        LOGGER.warn("Removed the module descriptor from {}: {}. Forge refuses a jar whose descriptor "
                + "names a package the jar does not contain, so it now loads as an automatic module.",
                jarName, problem);
    }
}
