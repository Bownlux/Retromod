/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/** Reads loader metadata to determine which side should install a mod. */
public final class ModEnvironmentDetector {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-EnvDetect");

    private ModEnvironmentDetector() {}

    public enum ModEnvironment {
        /** Runs on both client and server */
        BOTH("*"),
        /** Runs only on the client */
        CLIENT("client"),
        /** Runs only on the server */
        SERVER("server"),
        /** Unknown: treat as BOTH for safety */
        UNKNOWN("*");

        private final String fabricValue;

        ModEnvironment(String fabricValue) {
            this.fabricValue = fabricValue;
        }

        public String getFabricValue() {
            return fabricValue;
        }
    }

    public static ModEnvironment detectEnvironment(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {

            ZipEntry fabricEntry = jar.getEntry("fabric.mod.json");
            if (fabricEntry != null) {
                try (InputStream is = jar.getInputStream(fabricEntry)) {
                    String content = new String(is.readAllBytes());
                    return parseFabricEnvironment(content);
                }
            }

            ZipEntry forgeEntry = jar.getEntry("META-INF/mods.toml");
            if (forgeEntry == null) {
                forgeEntry = jar.getEntry("META-INF/neoforge.mods.toml");
            }
            if (forgeEntry != null) {
                try (InputStream is = jar.getInputStream(forgeEntry)) {
                    String content = new String(is.readAllBytes());
                    return parseForgeEnvironment(content);
                }
            }

        } catch (Exception e) {
            LOGGER.debug("Could not detect environment for: {}", jarPath.getFileName());
        }

        return ModEnvironment.UNKNOWN;
    }

    private static ModEnvironment parseFabricEnvironment(String json) {
        Pattern pattern = Pattern.compile("\"environment\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);

        if (matcher.find()) {
            String env = matcher.group(1).toLowerCase();
            return switch (env) {
                case "client" -> ModEnvironment.CLIENT;
                case "server" -> ModEnvironment.SERVER;
                case "*" -> ModEnvironment.BOTH;
                default -> ModEnvironment.UNKNOWN;
            };
        }

        return ModEnvironment.UNKNOWN;
    }

    /**
     * Infer a mod's side from raw {@code mods.toml}/{@code neoforge.mods.toml} content
     * (used by {@code ForgeModTransformer} while patching the extracted toml text).
     */
    public static ModEnvironment parseSide(String toml) {
        return parseForgeEnvironment(toml);
    }

    private static ModEnvironment parseForgeEnvironment(String toml) {
        Pattern pattern = Pattern.compile("side\\s*=\\s*\"?(BOTH|CLIENT|SERVER|DEDICATED_SERVER)\"?",
            Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(toml);

        if (matcher.find()) {
            String side = matcher.group(1).toUpperCase();
            return switch (side) {
                case "CLIENT" -> ModEnvironment.CLIENT;
                case "SERVER", "DEDICATED_SERVER" -> ModEnvironment.SERVER;
                case "BOTH" -> ModEnvironment.BOTH;
                default -> ModEnvironment.UNKNOWN;
            };
        }

        return ModEnvironment.UNKNOWN;
    }

    /** False means the mod can run on one side without the other needing Retromod. */
    public static boolean requiresBothSides(Path jarPath) {
        ModEnvironment env = detectEnvironment(jarPath);
        return env == ModEnvironment.BOTH || env == ModEnvironment.UNKNOWN;
    }

    public static boolean isServerOnly(Path jarPath) {
        return detectEnvironment(jarPath) == ModEnvironment.SERVER;
    }

    public static boolean isClientOnly(Path jarPath) {
        return detectEnvironment(jarPath) == ModEnvironment.CLIENT;
    }

    public static void logModEnvironment(Path jarPath) {
        ModEnvironment env = detectEnvironment(jarPath);
        String fileName = jarPath.getFileName().toString();

        switch (env) {
            case SERVER -> {
                LOGGER.info("{} is server-only. Joining clients do not need Retromod for it.",
                    fileName);
            }
            case CLIENT -> {
                LOGGER.info("{} is client-only. It does not need to be installed on the server.",
                    fileName);
            }
            case BOTH -> {
                LOGGER.info("{} runs on both client and server", fileName);
            }
            default -> {
                LOGGER.debug("{} does not declare a side, so Retromod will treat it as both.",
                    fileName);
            }
        }
    }

    public static String getEnvironmentDescription(ModEnvironment env) {
        return switch (env) {
            case SERVER -> "Server-only. Joining clients do not need Retromod for this mod.";
            case CLIENT -> "Client-only. Install it on the client.";
            case BOTH -> "Install on both the client and server.";
            case UNKNOWN -> "No side declared. Retromod will treat it as both.";
        };
    }
}
