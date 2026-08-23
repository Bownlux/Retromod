/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.util.ArchivePublication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/** Desktop file picker used to update and install selected mods. */
public class RetromodGui {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-GUI");
    private static final String PREFS_KEY_FIRST_RUN = "retromod_first_run_complete";
    private static final String PREFS_KEY_LAST_DIR = "retromod_last_directory";
    
    private final Path gameDir;
    private final Path modsFolder;
    private final ModCompatibilityChecker checker;
    private final Preferences prefs;
    
    private JFrame mainFrame;
    private JButton addModsButton;
    private boolean transformedAnyMods = false;
    
    public RetromodGui(Path gameDir) {
        this.gameDir = gameDir;
        this.modsFolder = gameDir.resolve("mods");
        this.checker = new ModCompatibilityChecker(gameDir);
        this.prefs = Preferences.userNodeForPackage(RetromodGui.class);
    }
    
    /** Returns whether setup has been shown before. */
    public boolean isFirstRun() {
        return !prefs.getBoolean(PREFS_KEY_FIRST_RUN, false);
    }
    
    /** Remembers that setup has been shown. */
    public void markFirstRunComplete() {
        prefs.putBoolean(PREFS_KEY_FIRST_RUN, true);
    }
    
    /** Shows the first-run setup dialog. */
    public void showFirstRunDialog() {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                LOGGER.debug("Could not use the system look and feel: {}", e.getMessage());
            }
            
            int choice = JOptionPane.showOptionDialog(
                null,
                """
                Choose the old mod jars you want Retromod to update for this
                Minecraft version.

                Retromod keeps the originals and installs updated copies in
                your mods folder. You can also precompile them from Settings
                if you want later launches to do less work.
                """,
                "Set up Retromod",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                new String[]{"Choose Mods", "Not Now"},
                "Choose Mods"
            );
            
            if (choice == 0) {
                openFilePickerAndTransform();
            }
            
            markFirstRunComplete();
        });
    }
    
    private boolean fullAotEnabled = false;
    
    /** Opens the file picker and updates the selected mods. */
    public void openFilePickerAndTransform() {
        SwingUtilities.invokeLater(() -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("Choose mod jars");
            fileChooser.setMultiSelectionEnabled(true);
            fileChooser.setFileFilter(new FileNameExtensionFilter("Minecraft mods (*.jar)", "jar"));
            
            JCheckBox fullAotCheckbox =
                new JCheckBox("Precompile updated mods (slower setup, faster launch)");
            fullAotCheckbox.setToolTipText(
                "Prepares more code now so later launches have less work to do.");
            fileChooser.setAccessory(createAotPanel(fullAotCheckbox));
            
            String lastDir = prefs.get(PREFS_KEY_LAST_DIR, System.getProperty("user.home"));
            fileChooser.setCurrentDirectory(new File(lastDir));
            
            int result = fileChooser.showOpenDialog(null);
            
            fullAotEnabled = fullAotCheckbox.isSelected();
            
            if (result == JFileChooser.APPROVE_OPTION) {
                File[] selectedFiles = fileChooser.getSelectedFiles();
                
                if (selectedFiles.length > 0) {
                    prefs.put(PREFS_KEY_LAST_DIR, selectedFiles[0].getParent());
                }
                
                transformSelectedMods(selectedFiles);
            }
        });
    }
    
    /** Updates the selected mod files in a background thread. */
    private void transformSelectedMods(File[] modFiles) {
        if (modFiles == null || modFiles.length == 0) {
            return;
        }
        
        JDialog progressDialog = new JDialog((Frame) null, "Retromod: updating mods", true);
        JProgressBar progressBar = new JProgressBar(0, modFiles.length);
        JLabel statusLabel = new JLabel("Getting ready...");
        JTextArea logArea = new JTextArea(10, 50);
        logArea.setEditable(false);
        
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        panel.add(statusLabel, BorderLayout.NORTH);
        panel.add(progressBar, BorderLayout.CENTER);
        panel.add(new JScrollPane(logArea), BorderLayout.SOUTH);
        
        progressDialog.add(panel);
        progressDialog.pack();
        progressDialog.setLocationRelativeTo(null);
        
        Thread updateThread = new Thread(() -> {
            List<String> successfulMods = new ArrayList<>();
            List<String> failedMods = new ArrayList<>();
            List<String> skippedMods = new ArrayList<>();
            List<Path> transformedModPaths = new ArrayList<>();
            
            try {
                Files.createDirectories(modsFolder);
            } catch (Exception e) {
                LOGGER.error("Could not create mods folder", e);
            }
            
            for (int i = 0; i < modFiles.length; i++) {
                File modFile = modFiles[i];
                final int index = i;
                
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Checking: " + modFile.getName());
                    progressBar.setValue(index);
                });
                
                try {
                    var modrinthResult = com.retromod.core.ModrinthVersionChecker
                        .checkForNativeVersion(modFile.toPath(),
                            com.retromod.core.RetromodVersion.TARGET_MC_VERSION);
                    
                    if (modrinthResult.found()) {
                        boolean skip = com.retromod.core.ModrinthVersionChecker
                            .offerNativeVersion(modrinthResult, modFile.getName());
                        
                        if (skip) {
                            skippedMods.add(modFile.getName());
                            final String msg =
                                "Skipped " + modFile.getName() + ": a native version is available on Modrinth.";
                            SwingUtilities.invokeLater(() -> logArea.append(msg + "\n"));
                            continue;
                        }
                    }
                    
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Updating: " + modFile.getName());
                    });
                    
                    var analysis = checker.analyzeJar(modFile.toPath());
                    
                    String logMessage;
                    if (analysis != null) {
                        Path result = checker.transformAndInstall(modFile.toPath());
                        logMessage = "Updated " + modFile.getName() + " as " + result.getFileName();
                        successfulMods.add(modFile.getName());
                        transformedModPaths.add(result);
                        transformedAnyMods = true;
                    } else {
                        Path dest = modsFolder.resolve(modFile.getName());
                        ArchivePublication.copyReplacing(modFile.toPath(), dest);
                        logMessage = "Copied " + modFile.getName() + ": no update was needed.";
                        successfulMods.add(modFile.getName());
                        transformedModPaths.add(dest);
                    }
                    
                    final String msg = logMessage;
                    SwingUtilities.invokeLater(() -> {
                        logArea.append(msg + "\n");
                    });
                    
                } catch (Exception e) {
                    LOGGER.error("Failed to transform {}: {}", modFile.getName(), e.getMessage());
                    failedMods.add(modFile.getName());
                    
                    SwingUtilities.invokeLater(() -> {
                        logArea.append(modFile.getName() + " could not be updated: "
                            + e.getMessage() + "\n");
                    });
                }
            }
            
            SwingUtilities.invokeLater(() -> {
                progressBar.setValue(modFiles.length);
                statusLabel.setText("Finished");
                
                progressDialog.dispose();
                
                StringBuilder message = new StringBuilder();
                message.append("Your mods are ready.\n\n");
                message.append("Updated: ").append(successfulMods.size()).append("\n");
                if (!skippedMods.isEmpty()) {
                    message.append("No changes needed: ").append(skippedMods.size()).append("\n");
                }
                if (!failedMods.isEmpty()) {
                    message.append("Failed: ").append(failedMods.size()).append("\n");
                }
                message.append("\nInstalled in: ").append(modsFolder).append("\n\n");
                
                if (fullAotEnabled && !transformedModPaths.isEmpty()) {
                    message.append("Retromod will now precompile the updated mods.\n\n");
                }
                
                if (transformedAnyMods) {
                    message.append("Restart Minecraft to load the updated mods.");
                }
                
                JOptionPane.showMessageDialog(
                    null,
                    message.toString(),
                    "Retromod",
                    JOptionPane.INFORMATION_MESSAGE
                );
                
                if (fullAotEnabled && !transformedModPaths.isEmpty()) {
                    runFullAotCompilation(transformedModPaths);
                }
            });
            
        }, "retromod-gui-update");
        updateThread.start();
        
        progressDialog.setVisible(true);
    }
    
    /** Shows a small floating button for choosing more mods. */
    public void showAddModsButton() {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                LOGGER.debug("Could not use the system look and feel: {}", e.getMessage());
            }
            
            mainFrame = new JFrame();
            mainFrame.setUndecorated(true);
            mainFrame.setAlwaysOnTop(true);
            mainFrame.setType(Window.Type.UTILITY);
            
            addModsButton = new JButton("Choose Mods");
            addModsButton.setToolTipText("Choose more mods for Retromod to update");
            addModsButton.addActionListener(e -> openFilePickerAndTransform());
            
            addModsButton.setBackground(new Color(88, 101, 242));
            addModsButton.setForeground(Color.WHITE);
            addModsButton.setFocusPainted(false);
            addModsButton.setBorderPainted(false);
            addModsButton.setFont(new Font("SansSerif", Font.BOLD, 12));
            addModsButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            
            mainFrame.add(addModsButton);
            mainFrame.pack();
            
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            mainFrame.setLocation(
                screenSize.width - mainFrame.getWidth() - 20,
                screenSize.height - mainFrame.getHeight() - 60
            );
            
            final Point[] dragPoint = {null};
            addModsButton.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        mainFrame.setVisible(false);
                    } else {
                        dragPoint[0] = e.getPoint();
                    }
                }
            });
            addModsButton.addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragPoint[0] != null) {
                        Point location = mainFrame.getLocation();
                        mainFrame.setLocation(
                            location.x + e.getX() - dragPoint[0].x,
                            location.y + e.getY() - dragPoint[0].y
                        );
                    }
                }
            });
            
            mainFrame.setVisible(true);
        });
    }
    
    /** Hides the floating button. */
    public void hideAddModsButton() {
        if (mainFrame != null) {
            SwingUtilities.invokeLater(() -> mainFrame.dispose());
        }
    }
    
    /** Returns whether this window updated at least one mod. */
    public boolean didTransformMods() {
        return transformedAnyMods;
    }
    
    /** Creates the precompile option shown in the file picker. */
    private JPanel createAotPanel(JCheckBox checkbox) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Launch performance"));
        
        checkbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(checkbox);
        
        JLabel infoLabel = new JLabel("<html><small>" +
            "Precompiling takes longer once,<br>" +
            "then saves work on later launches.<br>" +
            "The result stays in Retromod's cache." +
            "</small></html>");
        infoLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(Box.createVerticalStrut(10));
        panel.add(infoLabel);
        
        return panel;
    }
    
    /** Precompiles the updated mods. */
    private void runFullAotCompilation(List<Path> modPaths) {
        if (!fullAotEnabled || modPaths.isEmpty()) {
            return;
        }
        
        try {
            com.retromod.aot.FullAotCompiler compiler = 
                com.retromod.aot.FullAotCompiler.getInstance(
                    gameDir, com.retromod.core.RetromodVersion.TARGET_MC_VERSION);
            
            compiler.showProgressDialog(modPaths);
            
        } catch (Exception e) {
            LOGGER.error("Could not precompile updated mods", e);
            JOptionPane.showMessageDialog(
                null,
                "Retromod could not precompile the updated mods: " + e.getMessage() + "\n\n" +
                "The mods are still installed and can use the normal launch-time path.",
                "Could not precompile mods",
                JOptionPane.WARNING_MESSAGE
            );
        }
    }
    
    /** Runs setup when this instance has not shown it before. */
    public static void runFirstTimeSetupIfNeeded(Path gameDir) {
        RetromodGui gui = new RetromodGui(gameDir);
        
        if (gui.isFirstRun()) {
            LOGGER.info("Showing the first-run setup dialog.");
            gui.showFirstRunDialog();
        }
    }
    
    /** Shows the floating button for this game directory. */
    public static void showFloatingButton(Path gameDir) {
        RetromodGui gui = new RetromodGui(gameDir);
        gui.showAddModsButton();
    }
}
