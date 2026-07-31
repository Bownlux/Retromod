/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reports transform failures without hiding the original exception.
 */
public final class TransformationErrorHandler {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Error");
    
    public static final String GITHUB_URL = "https://github.com/Bownlux/Retromod";
    public static final String GITHUB_ISSUES_URL = "https://github.com/Bownlux/Retromod/issues/new?title=Bug%20Report";
    
    private static final List<FailedMod> failedMods = new CopyOnWriteArrayList<>();

    private TransformationErrorHandler() {
    }
    
    public record FailedMod(
        String modName,
        String modId,
        String modLoader,
        String sourceVersion,
        String errorType,
        String errorMessage,
        String stackTrace
    ) {}
    
    /** Records a failed transform and tells the user how to report it. */
    public static void handleError(Path modPath, Throwable error, String modId, 
                                   String modLoader, String sourceVersion) {
        
        String modName = modPath.getFileName().toString();
        String errorType = error.getClass().getSimpleName();
        String errorMessage = error.getMessage() != null ? error.getMessage() : "Unknown error";
        String stackTrace = getStackTraceString(error);
        
        FailedMod failed = new FailedMod(
            modName, modId, modLoader, sourceVersion, errorType, errorMessage, stackTrace
        );
        failedMods.add(failed);
        
        LOGGER.error("Retromod could not update {} ({}): {}", modName, errorType, errorMessage);
        LOGGER.error("Loader: {}, source Minecraft: {}, mod ID: {}",
            known(modLoader), known(sourceVersion), known(modId));
        LOGGER.error("Report this failure at {} and attach logs/latest.log", GITHUB_ISSUES_URL);
        LOGGER.debug("Full transform failure for " + modName, error);
        
        if (EnvironmentDetector.canShowGui()) {
            showErrorDialog(failed);
        }
    }
    
    private static void showErrorDialog(FailedMod failed) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            
            String message = String.format("""
                Retromod could not update %s.

                Error: %s

                The original mod has not been replaced. You can copy a report
                template or open GitHub Issues to tell us what happened.
                """,
                failed.modName(),
                failed.errorType() + ": " + truncate(failed.errorMessage(), 50)
            );
            
            int choice = JOptionPane.showOptionDialog(
                null,
                message,
                "Retromod could not update a mod",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.ERROR_MESSAGE,
                null,
                new String[]{"Open GitHub Issues", "Copy Report", "Close"},
                "Open GitHub Issues"
            );
            
            if (choice == 0) {
                openGitHub();
            } else if (choice == 1) {
                copyBugReport(failed);
            }
        });
    }
    
    /**
     * Opens GitHub through the desktop API. If that is unavailable, shows a
     * copyable URL instead of starting a platform-specific process.
     */
    private static void openGitHub() {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(GITHUB_ISSUES_URL));
                return;
            }
        } catch (Exception e) {
            LOGGER.warn("Could not open browser: {}", e.getMessage());
        }
        JOptionPane.showMessageDialog(
            null,
            "Please visit: " + GITHUB_ISSUES_URL,
            "Open GitHub Issues",
            JOptionPane.INFORMATION_MESSAGE
        );
    }
    
    private static void copyBugReport(FailedMod failed) {
        String report = generateBugReport(failed);
        
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(report), null);
            
            JOptionPane.showMessageDialog(
                null,
                "The report is on your clipboard. Paste it into a new GitHub issue and attach logs/latest.log.",
                "Report copied",
                JOptionPane.INFORMATION_MESSAGE
            );
        } catch (Exception e) {
            JTextArea textArea = new JTextArea(report);
            textArea.setEditable(false);
            textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(500, 400));
            
            JOptionPane.showMessageDialog(
                null,
                scrollPane,
                "Copy this bug report:",
                JOptionPane.PLAIN_MESSAGE
            );
        }
    }
    
    /** Builds the report shown in the dialog and server log. */
    public static String generateBugReport(FailedMod failed) {
        return String.format("""
            # Retromod Bug Report
            
            ## Mod Information
            - **Mod Name:** %s
            - **Mod ID:** %s
            - **Mod Loader:** %s
            - **Source MC Version:** %s
            - **Target MC Version:** %s
            
            ## Error
            - **Type:** %s
            - **Message:** %s
            
            ## Stack Trace
            ```
            %s
            ```
            
            ## System Info
            - **Retromod Version:** %s
            - **Java Version:** %s
            - **OS:** %s
            
            ## What I Was Doing
            Describe the setup and attach `logs/latest.log`.
            """,
            failed.modName(),
            known(failed.modId()),
            known(failed.modLoader()),
            known(failed.sourceVersion()),
            RetromodVersion.TARGET_MC_VERSION,
            failed.errorType(),
            failed.errorMessage(),
            truncate(failed.stackTrace(), 2000),
            RetromodVersion.RETROMOD_VERSION,
            System.getProperty("java.version"),
            System.getProperty("os.name") + " " + System.getProperty("os.version")
        );
    }
    
    /** Prints a report template for headless servers. */
    public static void logBugReportToConsole(FailedMod failed) {
        String report = generateBugReport(failed);
        
        LOGGER.error("Report this failure at {}", GITHUB_ISSUES_URL);
        LOGGER.error("Copy the report below and attach logs/latest.log:");
        for (String line : report.split("\n")) {
            LOGGER.error("{}", line);
        }
    }
    
    private static String getStackTraceString(Throwable error) {
        StringWriter output = new StringWriter();
        error.printStackTrace(new PrintWriter(output));
        return output.toString();
    }
    
    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    private static String known(String value) {
        return value == null || value.isBlank() ? "Unknown" : value;
    }
    
    public static List<FailedMod> getFailedMods() {
        return new ArrayList<>(failedMods);
    }
    
    public static boolean hasFailures() {
        return !failedMods.isEmpty();
    }
    
    /** Logs a compact summary after a batch. */
    public static void showFailureSummary() {
        if (failedMods.isEmpty()) return;
        
        LOGGER.error("Retromod could not update {} mod(s):", failedMods.size());
        for (FailedMod mod : failedMods) {
            LOGGER.error("  - {}: {}", mod.modName(), mod.errorType());
        }
        LOGGER.error("Report them at {} and attach logs/latest.log", GITHUB_ISSUES_URL);
    }
}
