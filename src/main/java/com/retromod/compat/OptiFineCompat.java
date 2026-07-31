/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Detects OptiFine so Retromod can explain why it is outside the normal transform path.
 */
public final class OptiFineCompat {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-OptiFine");
    
    private static boolean optiFineDetected = false;
    private static String optiFineVersion = null;

    private OptiFineCompat() {
    }
    
    /** Check if a JAR is OptiFine. */
    public static boolean isOptiFine(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            ZipEntry entry = jar.getEntry("optifine/OptiFineTransformationService.class");
            if (entry != null) return true;

            entry = jar.getEntry("net/optifine/Config.class");
            if (entry != null) return true;

            entry = jar.getEntry("optifine/Installer.class");
            if (entry != null) return true;

            String name = jarPath.getFileName().toString().toLowerCase();
            if (name.contains("optifine")) {
                return true;
            }

        } catch (Exception e) {
            LOGGER.debug("Could not check JAR for OptiFine: {}", e.getMessage());
        }
        
        return false;
    }
    
    /** Get OptiFine version from JAR. */
    public static String getOptiFineVersion(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            ZipEntry entry = jar.getEntry("changelog.txt");
            if (entry != null) {
                try (InputStream in = jar.getInputStream(entry)) {
                    String content = new String(in.readAllBytes());
                    String firstLine = content.split("\n")[0];
                    if (firstLine.contains("OptiFine")) {
                        return firstLine.trim();
                    }
                }
            }

            // OptiFine_1.20.4_HD_U_I7.jar -> 1.20.4_HD_U_I7
            String name = jarPath.getFileName().toString();
            if (name.contains("OptiFine_")) {
                return name.replace("OptiFine_", "").replace(".jar", "");
            }

        } catch (Exception e) {
            LOGGER.debug("Could not get OptiFine version: {}", e.getMessage());
        }
        
        return "Unknown";
    }
    
    /** Warns once Retromod has identified an OptiFine jar. */
    public static void handleOptiFineDetected(Path jarPath, boolean isServer) {
        optiFineDetected = true;
        optiFineVersion = getOptiFineVersion(jarPath);
        
        LOGGER.warn("OptiFine {} was detected. Retromod cannot reliably translate its renderer changes.",
            optiFineVersion);
        LOGGER.warn("It may fail on a newer Minecraft version or conflict with another rendering mod.");
        LOGGER.warn("Compatibility notes: https://bownlux.github.io/Retromod/incompatible-mods/");
        
        if (!isServer) {
            showOptiFineWarningDialog();
        }
    }


    private static void showOptiFineWarningDialog() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        final int[] choiceHolder = {JOptionPane.CLOSED_OPTION};
        Runnable showDialog = () -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            String message = """
                Retromod found OptiFine.

                OptiFine replaces parts of Minecraft's renderer, so Retromod cannot
                translate it reliably. Continuing may lead to missing features,
                rendering problems, or a crash.
                """;

            choiceHolder[0] = JOptionPane.showOptionDialog(
                null,
                message,
                "OptiFine compatibility",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                new String[]{"Read Compatibility Notes", "Continue Anyway", "Cancel"},
                "Read Compatibility Notes"
            );
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                showDialog.run();
            } else {
                SwingUtilities.invokeAndWait(showDialog);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return; // treat as "continue anyway"
        } catch (Exception e) {
            LOGGER.debug("Could not show OptiFine warning dialog: {}", e.getMessage());
            return;
        }

        int choice = choiceHolder[0];
        if (choice == 0) {
            try {
                Desktop.getDesktop().browse(
                    URI.create("https://bownlux.github.io/Retromod/incompatible-mods/"));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "Please visit: https://bownlux.github.io/Retromod/incompatible-mods/",
                    "Compatibility notes",
                    JOptionPane.INFORMATION_MESSAGE));
            }
        } else if (choice == 2) {
            throw new RuntimeException("User cancelled OptiFine installation");
        }
        // choice == 1 or dialog closed: continue anyway
    }

    public static boolean isOptiFinePresent() {
        return optiFineDetected;
    }

    public static String getDetectedVersion() {
        return optiFineVersion;
    }

    /** Mods known to conflict with OptiFine. */
    public static final String[] CONFLICTING_MODS = {
        "sodium",
        "iris",
        "indium",
        "rubidium",
        "embeddium",
        "canvas",
        "starlight",
        "phosphor"
    };
    
    /** Check if a mod ID conflicts with OptiFine. */
    public static boolean conflictsWithOptiFine(String modId) {
        if (!optiFineDetected) return false;

        String lower = modId.toLowerCase();
        for (String conflict : CONFLICTING_MODS) {
            if (lower.contains(conflict)) {
                return true;
            }
        }
        return false;
    }

    public static void logConflict(String modId) {
        LOGGER.error("OptiFine conflicts with {}. Remove one of them before restarting Minecraft.",
            modId);
    }
}
