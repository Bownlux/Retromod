/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core.parallel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads the ASM classes that both Retromod's workers and Mixin use, before the workers start.
 *
 * <p>On Fabric, Retromod's pre-launch transform runs on a thread pool inside Knot. Knot locks a
 * class name while it loads that class, then takes Mixin's transformer lock to apply mixins. A
 * worker that loads a Minecraft class for a frame merge ends up inside the Mixin lock, where
 * MixinExtras lazily loads ASM's analysis classes. If another worker is loading the same ASM class
 * at that moment for its own analysis, each thread holds the lock the other needs and the launch
 * hangs with no log output. Minepathy on 26.2 hung this way on most first launches.
 *
 * <p>Loading these packages once on the calling thread, before any worker exists, leaves nothing
 * for the two sides to race on. A class that is already loaded never reaches the Mixin lock.
 */
public final class SharedLibraryPreloader {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Parallel");

    /** One class per ASM package that both sides load lazily. */
    private static final String[] ANCHORS = {
            "org.objectweb.asm.tree.analysis.Analyzer",
            "org.objectweb.asm.commons.ClassRemapper",
            "org.objectweb.asm.tree.ClassNode",
            "org.objectweb.asm.util.CheckClassAdapter",
    };

    private static volatile boolean done;

    private SharedLibraryPreloader() {}

    /** Loads every class in the anchor packages through {@code loader}. Runs once per session. */
    public static void preload(ClassLoader loader) {
        if (done || loader == null) return;
        synchronized (SharedLibraryPreloader.class) {
            if (done) return;
            int loaded = preloadPackages(loader);
            done = true;
            LOGGER.debug("Preloaded {} shared ASM classes before the parallel transform", loaded);
        }
    }

    /** Loads the anchor packages through {@code loader} and returns how many classes it loaded. */
    static int preloadPackages(ClassLoader loader) {
        int loaded = 0;
        for (String anchor : ANCHORS) {
            loaded += preloadPackageOf(anchor, loader);
        }
        return loaded;
    }

    private static int preloadPackageOf(String anchor, ClassLoader loader) {
        Class<?> anchorClass;
        try {
            anchorClass = Class.forName(anchor, false, loader);
        } catch (ClassNotFoundException | LinkageError absent) {
            return 0; // this ASM module is not on the classpath, so nothing can race on it
        }
        String packagePath = anchor.substring(0, anchor.lastIndexOf('.')).replace('.', '/') + "/";
        Path jar = codeSourceJar(anchorClass);
        if (jar == null) return 1;

        int loaded = 1;
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith(packagePath) || !name.endsWith(".class")
                        || name.indexOf('/', packagePath.length()) >= 0
                        || name.endsWith("module-info.class")) {
                    continue;
                }
                String className = name.substring(0, name.length() - ".class".length())
                        .replace('/', '.');
                try {
                    Class.forName(className, false, loader);
                    loaded++;
                } catch (ClassNotFoundException | LinkageError skipped) {
                    // A class this loader cannot see cannot deadlock it either.
                }
            }
        } catch (java.io.IOException unreadable) {
            LOGGER.debug("Could not list {} for preloading: {}", jar, unreadable.toString());
        }
        return loaded;
    }

    private static Path codeSourceJar(Class<?> anchorClass) {
        try {
            if (anchorClass.getProtectionDomain() == null
                    || anchorClass.getProtectionDomain().getCodeSource() == null) {
                return null;
            }
            URL location = anchorClass.getProtectionDomain().getCodeSource().getLocation();
            if (location == null || !"file".equals(location.getProtocol())) return null;
            Path path = Path.of(location.toURI());
            return path.toString().endsWith(".jar") ? path : null;
        } catch (Exception unusable) {
            return null;
        }
    }
}
