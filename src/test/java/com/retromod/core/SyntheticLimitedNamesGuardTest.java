/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The embedder keeps every name a version-limited class redirect covers when it copies Retromod's own classes, on the
 * rule that such a redirect marks a name the host has again. Some limits mark a name the host lacks instead, so no
 * synthetic may name one of those: on each host, every limited name a synthetic references must exist there.
 */
class SyntheticLimitedNamesGuardTest {

    private static final Path PRISM = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries");
    private static final Path FORGE_263 = Path.of(System.getProperty("user.home"), ".claude/jobs/3da2a019/tmp/forge263");
    private static final List<String> VANILLA = List.of("net/minecraft/", "com/mojang/");
    private static final List<String> EVERYTHING = List.of("");

    /** One host: its loader and version, the jars that list its classes, and the names those jars can answer for. */
    private record Host(String loader, String version, List<Path> jars, List<String> answers) {}

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    // Hosts whose jars are not on this machine, or are obfuscated, list no classes and are skipped name by name.
    private static List<Host> hosts() {
        Path vanilla262 = PRISM.resolve("com/mojang/minecraft/26.2/minecraft-26.2-client.jar");
        List<Path> forge262 = List.of(PRISM.resolve("net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar"),
                PRISM.resolve("net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-universal.jar"),
                PRISM.resolve("net/minecraftforge/fmlcore/26.2-65.0.0/fmlcore-26.2-65.0.0.jar"),
                PRISM.resolve("net/minecraftforge/eventbus/7.0.1/eventbus-7.0.1.jar"));
        List<Path> forge263 = List.of(PRISM.resolve("com/mojang/minecraft/26.3/minecraft-26.3-client.jar"),
                FORGE_263.resolve("forge-26.3-66.1.2-universal.jar"), FORGE_263.resolve("fmlcore-26.3-66.1.2.jar"),
                FORGE_263.resolve("eventbus-7.0.6.jar"));
        List<Host> hosts = new ArrayList<>();
        for (String version : List.of("1.20.4", "1.21.1", "1.21.11")) hosts.add(new Host("forge", version, List.of(), List.of()));
        hosts.add(new Host("forge", "26.1", List.of(PRISM.resolve("com/mojang/minecraft/26.1.2/minecraft-26.1.2-client.jar")), VANILLA));
        hosts.add(new Host("forge", "26.2", forge262, EVERYTHING));
        hosts.add(new Host("forge", "26.3", forge263, EVERYTHING));
        for (String version : List.of("1.20.4", "1.21.1")) {
            hosts.add(new Host("neoforge", version, List.of(), List.of()));
            hosts.add(new Host("fabric", version, List.of(), List.of()));
        }
        hosts.add(new Host("neoforge", "26.2", List.of(vanilla262), VANILLA));
        hosts.add(new Host("fabric", "26.2", List.of(vanilla262), VANILLA));
        return hosts;
    }

    @Test
    @DisplayName("Every version-limited name a synthetic references exists on the host it is registered for")
    void syntheticsNameOnlyLimitedClassesTheHostHas() throws Exception {
        List<String> missing = new ArrayList<>();
        Set<String> checked = new TreeSet<>();
        Set<String> skipped = new TreeSet<>();
        for (Host host : hosts()) {
            register(host);
            Set<String> limited = new HashSet<>(transformer.getClassRedirects().keySet());
            limited.removeAll(transformer.getClassRedirectsForHostCode().keySet());
            boolean jarsPresent = !host.jars().isEmpty() && host.jars().stream().allMatch(Files::isRegularFile);
            for (Map.Entry<String, byte[]> synthetic : transformer.getSyntheticClasses().entrySet()) {
                for (String name : references(synthetic.getValue())) {
                    if (!limited.contains(name)) continue;
                    String row = host.loader() + " " + host.version() + ": " + synthetic.getKey() + " names " + name;
                    if (!jarsPresent || host.answers().stream().noneMatch(name::startsWith)) {
                        skipped.add(row);
                    } else if (hasClass(host.jars(), name)) {
                        checked.add(row);
                    } else {
                        missing.add(row);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "a synthetic keeps a limited name its host lacks, so it would not link: " + missing
                + " (checked " + checked.size() + ", skipped without host classes " + skipped + ")");
        // Forge 26.2's geometry classes name its IGeometryLoader, which the host has again, so the guard sees real rows.
        if (hosts().stream().filter(h -> h.loader().equals("forge") && h.version().equals("26.2"))
                .allMatch(h -> h.jars().stream().allMatch(Files::isRegularFile))) {
            assertTrue(checked.stream().anyMatch(row -> row.startsWith("forge 26.2") && row.endsWith("geometry/IGeometryLoader")),
                    "the guard checked the names it exists for: " + checked);
        }
    }

    // What RetromodForge, RetromodNeoForge and Retromod register on that host.
    private void register(Host host) {
        RetromodVersion.TARGET_MC_VERSION = host.version();
        McReflect.setForceForge(host.loader().equals("forge"));
        McReflect.setForceNeoForge(host.loader().equals("neoforge"));
        transformer.clearRedirectsForTesting();
        Set<String> types = switch (host.loader()) {
            case "forge" -> Set.of("forge", "common");
            case "neoforge" -> Set.of("neoforge", "forge", "common");
            default -> Set.of("fabric", "common");
        };
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host.version())) {
                ShimRegistration.register(shim, transformer);
            }
        }
    }

    private static Set<String> references(byte[] bytes) {
        Set<String> names = new HashSet<>();
        new ClassReader(bytes).accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
            @Override
            public String map(String internalName) {
                names.add(internalName);
                return internalName;
            }
        }), 0);
        return names;
    }

    private static boolean hasClass(List<Path> jars, String name) throws Exception {
        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                if (zip.getEntry(name + ".class") != null) return true;
            }
        }
        return false;
    }
}
