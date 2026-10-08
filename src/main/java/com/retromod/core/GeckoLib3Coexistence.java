/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lets a translated GeckoLib 3 jar load beside the host's GeckoLib 4.
 *
 * <p>GeckoLib 3 and GeckoLib 4 have different mod IDs ({@code geckolib3} and {@code geckolib}) and
 * different library packages, so a 3.x mod can keep its own library. Both releases also ship the
 * demo package {@code software.bernie.example}, and Forge and NeoForge refuse to boot when two
 * modules contain the same package ({@code ResolutionException: Module geckolib contains package
 * software.bernie.example.registry, module geckolib3 exports package ...}). GeckoLib 3's
 * {@code @Mod} entry point also lives in that package, so it cannot simply be deleted. The package
 * moves under {@code software/bernie/geckolib3/example}, which only GeckoLib 3 owns.
 */
final class GeckoLib3Coexistence {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-ForgeTransform");

    static final String DEMO_PACKAGE = "software/bernie/example/";
    static final String RELOCATED_DEMO_PACKAGE = "software/bernie/geckolib3/example/";

    private static final Pattern GECKOLIB3_MOD_ID =
            Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"geckolib3\"");

    private GeckoLib3Coexistence() {
    }

    /**
     * Moves GeckoLib 3's demo package in an extracted jar that declares the {@code geckolib3} mod.
     * Other jars, including mods that depend on {@code geckolib3}, are left untouched.
     *
     * @return the number of class files moved
     */
    static int relocateDemoPackage(Path extractedJar) throws IOException {
        Path demo = extractedJar.resolve(DEMO_PACKAGE);
        if (!Files.isDirectory(demo) || !declaresGeckoLib3(extractedJar)) {
            return 0;
        }
        List<Path> classFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(extractedJar)) {
            walk.filter(path -> path.toString().endsWith(".class")).forEach(classFiles::add);
        }
        for (Path classFile : classFiles) {
            byte[] relocated = relocateReferences(Files.readAllBytes(classFile));
            if (relocated != null) {
                Files.write(classFile, relocated);
            }
        }

        // Every entry moves, sources and resources included: a leftover file would keep the old
        // package in the module and the split would remain.
        List<Path> entries;
        try (Stream<Path> walk = Files.walk(demo)) {
            entries = walk.filter(Files::isRegularFile).toList();
        }
        Path target = extractedJar.resolve(RELOCATED_DEMO_PACKAGE);
        int moved = 0;
        for (Path entry : entries) {
            Path destination = target.resolve(demo.relativize(entry).toString());
            Files.createDirectories(destination.getParent());
            Files.move(entry, destination);
            if (entry.toString().endsWith(".class")) {
                moved++;
            }
        }
        try (Stream<Path> walk = Files.walk(demo)) {
            for (Path directory : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(directory);
            }
        }
        LOGGER.info("Moved {} GeckoLib 3 demo class(es) to {} so it can load beside GeckoLib 4",
                moved, RELOCATED_DEMO_PACKAGE);
        return moved;
    }

    /** Rewrites demo-package references in one class, or returns null when it has none. */
    static byte[] relocateReferences(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        boolean[] changed = {false};
        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                if (internalName.startsWith(DEMO_PACKAGE)) {
                    changed[0] = true;
                    return RELOCATED_DEMO_PACKAGE + internalName.substring(DEMO_PACKAGE.length());
                }
                return internalName;
            }
        };
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassRemapper(writer, remapper), 0);
        return changed[0] ? writer.toByteArray() : null;
    }

    private static boolean declaresGeckoLib3(Path extractedJar) throws IOException {
        for (String toml : new String[]{"META-INF/mods.toml", "META-INF/neoforge.mods.toml"}) {
            Path path = extractedJar.resolve(toml);
            if (Files.isRegularFile(path)) {
                String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                if (isGeckoLib3ModsBlock(content)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a mods.toml declares the {@code geckolib3} mod itself. A dependency block names it
     * with the same key, so only the {@code [[mods]]} section before the first dependency counts.
     */
    static boolean isGeckoLib3ModsBlock(String toml) {
        int dependencies = toml.indexOf("[[dependencies");
        String modsSection = dependencies >= 0 ? toml.substring(0, dependencies) : toml;
        return GECKOLIB3_MOD_ID.matcher(modsSection).find();
    }
}
