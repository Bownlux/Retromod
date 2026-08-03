/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.EnvironmentDetector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.ShimRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * One-time heavy compilation: transforms every class in the given mods upfront and caches the
 * result, so later launches read cached bytecode instead of transforming on the fly. Triggered
 * from the Fabric first-launch dialog or the Forge/NeoForge add-mods GUI.
 */
public class FullAotCompiler {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-FullAOT");

    private static final String CACHE_DIR = "retromod-cache/full-aot";

    private static FullAotCompiler instance;

    private final ShimRegistry shimRegistry;
    private final String targetVersion;
    private final Path cacheDir;

    private volatile int totalClasses = 0;
    private volatile int compiledClasses = 0;
    private volatile String currentMod = "";
    private volatile String currentClass = "";
    private volatile boolean isRunning = false;
    private volatile boolean wasCancelled = false;

    private final List<ProgressListener> listeners = new CopyOnWriteArrayList<>();
    
    public interface ProgressListener {
        void onProgress(int compiled, int total, String mod, String className);
        void onComplete(int totalCompiled, long timeMs);
        void onError(String mod, String className, String error);
    }
    
    private FullAotCompiler(Path gameDir, String targetVersion) {
        this.shimRegistry = new ShimRegistry();
        this.targetVersion = targetVersion;
        this.cacheDir = gameDir.resolve(CACHE_DIR);

        // Creates the cache dir AND wipes it when the Retromod build changed since it was
        // written: nothing below validates the cached per-class files, so without the stamp
        // an updated Retromod would keep serving the previous build's transforms.
        AotCacheStamp.ensureCurrent(cacheDir);
    }
    
    public static synchronized FullAotCompiler getInstance(Path gameDir, String targetVersion) {
        if (instance == null) {
            instance = new FullAotCompiler(gameDir, targetVersion);
        }
        return instance;
    }
    
    /**
     * Reduces a mod id to a single safe directory name.
     *
     * <p>The id is read out of the mod's own metadata, or falls back to its file name, so it is
     * untrusted. Without this an id such as {@code ../../evil} would put the cache, and the class
     * files read back out of it, anywhere the game can write.
     */
    static String safeModId(String modId) {
        String cleaned = modId == null ? "" : modId.replaceAll("[^A-Za-z0-9._-]", "_");
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        return cleaned.isBlank() ? "unnamed-mod" : cleaned;
    }

    public boolean hasCachedCompilation(String modId) {
        Path modCache = cacheDir.resolve(safeModId(modId) + ".aot");
        return Files.exists(modCache);
    }

    public byte[] getCachedClass(String modId, String className) {
        Path classCache = cacheDir.resolve(safeModId(modId))
            .resolve(className.replace('/', '_') + ".class");
        
        if (Files.exists(classCache)) {
            try {
                return Files.readAllBytes(classCache);
            } catch (IOException e) {
                LOGGER.debug("Could not read cached class: {}", className);
            }
        }
        
        return null;
    }
    
    /**
     * Transform and cache every class in the given mod JARs. Returns the count of classes compiled.
     */
    public CompletableFuture<Integer> runFullCompilation(List<Path> modsToCompile) {
        if (isRunning) {
            return CompletableFuture.completedFuture(0);
        }
        
        isRunning = true;
        wasCancelled = false;
        compiledClasses = 0;
        totalClasses = 0;
        
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                LOGGER.info("Full AOT pre-compilation starting, {} mods (may take several minutes)",
                    modsToCompile.size());

                LOGGER.info("Counting classes...");
                for (Path modJar : modsToCompile) {
                    totalClasses += countClasses(modJar);
                }
                LOGGER.info("Total classes to compile: {}", totalClasses);

                com.retromod.core.RetromodTransformer transformer =
                    com.retromod.core.RetromodTransformer.getInstance();
                
                for (Path modJar : modsToCompile) {
                    if (wasCancelled) {
                        LOGGER.info("Compilation cancelled by user");
                        break;
                    }
                    
                    currentMod = modJar.getFileName().toString();
                    LOGGER.info("Compiling: {}", currentMod);
                    
                    compileAllClassesInMod(modJar, transformer);
                }
                
                long elapsed = System.currentTimeMillis() - startTime;
                LOGGER.info("Full AOT compilation complete: {} classes in {}s",
                    compiledClasses, elapsed / 1000);

                return compiledClasses;

            } catch (Exception e) {
                LOGGER.error("Full AOT compilation failed", e);
                return compiledClasses;
            } finally {
                isRunning = false;
                // onComplete must fire on every exit path, else the modal progress dialog never disposes
                long elapsed = System.currentTimeMillis() - startTime;
                for (ProgressListener listener : listeners) {
                    try {
                        listener.onComplete(compiledClasses, elapsed);
                    } catch (Exception e) {
                        LOGGER.debug("Progress listener failed: {}", e.getMessage());
                    }
                }
            }
        });
    }
    
    private int countClasses(Path jarPath) {
        int count = 0;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class") && 
                    !entry.getName().startsWith("META-INF/")) {
                    count++;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not count classes in: {}", jarPath.getFileName());
        }
        return count;
    }
    
    /** Transform every class in a mod JAR in parallel and write the results to the mod's cache dir. */
    private void compileAllClassesInMod(Path jarPath, RetromodTransformer transformer) {
        // Selectors live in annotation text, which the class remap leaves alone, so a cached
        // Mixin would still point at the name the mod was built against.
        com.retromod.mixin.MixinCompatibilityTransformer mixinTransformer =
            new com.retromod.mixin.MixinCompatibilityTransformer(transformer);
        String modId = extractModId(jarPath);
        if (modId == null) {
            modId = jarPath.getFileName().toString().replace(".jar", "");
        }
        modId = safeModId(modId);

        Path modCacheDir = cacheDir.resolve(modId);
        try {
            Files.createDirectories(modCacheDir);
        } catch (IOException e) {
            LOGGER.error("Could not create mod cache dir", e);
            return;
        }
        
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            List<JarEntry> classEntries = new ArrayList<>();
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class") && 
                    !entry.getName().startsWith("META-INF/")) {
                    classEntries.add(entry);
                }
            }
            
            int batchSize = Math.max(10, classEntries.size() / threads);
            List<Future<?>> futures = new ArrayList<>();
            final Path finalModCacheDir = modCacheDir;
            
            for (int i = 0; i < classEntries.size(); i += batchSize) {
                if (wasCancelled) break;
                
                int start = i;
                int end = Math.min(i + batchSize, classEntries.size());
                List<JarEntry> batch = classEntries.subList(start, end);
                
                futures.add(executor.submit(() -> {
                    for (JarEntry entry : batch) {
                        if (wasCancelled) return;
                        
                        String className = entry.getName()
                            .replace(".class", "")
                            .replace('/', '.');
                        
                        currentClass = className;
                        
                        try {
                            byte[] original;
                            try (InputStream is = new BufferedInputStream(jar.getInputStream(entry))) {
                                original = is.readAllBytes();
                            }

                            byte[] transformed = transformer.transformClass(original, className);
                            transformed = AotMixinRepair.apply(mixinTransformer,
                                transformed != null ? transformed : original, className);

                            if (transformed != null && transformed != original) {
                                String cacheFileName = className.replace('.', '_') + ".class";
                                Path cacheFile = finalModCacheDir.resolve(cacheFileName);
                                Files.write(cacheFile, transformed);

                                synchronized (this) {
                                    compiledClasses++;
                                }
                            }

                            if (compiledClasses % 10 == 0) {
                                for (ProgressListener listener : listeners) {
                                    listener.onProgress(compiledClasses, totalClasses, currentMod, className);
                                }
                            }
                            
                        } catch (Exception e) {
                            LOGGER.debug("Could not compile class: {}", className);
                        }
                    }
                }));
            }
            
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    LOGGER.debug("Batch compilation error", e);
                }
            }

            for (ProgressListener listener : listeners) {
                listener.onProgress(compiledClasses, totalClasses, currentMod, "Complete");
            }

            Path marker = modCacheDir.resolve(".complete");
            Files.writeString(marker, String.valueOf(System.currentTimeMillis()));
            
        } catch (Exception e) {
            LOGGER.error("Error processing mod: {}", jarPath.getFileName(), e);
        } finally {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
        }
    }
    
    private String extractModId(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            ZipEntry fabricEntry = jar.getEntry("fabric.mod.json");
            if (fabricEntry != null) {
                String content;
                try (InputStream is = jar.getInputStream(fabricEntry)) {
                    content = new String(is.readAllBytes());
                }
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
                java.util.regex.Matcher matcher = pattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
            
            ZipEntry forgeEntry = jar.getEntry("META-INF/mods.toml");
            if (forgeEntry != null) {
                String content;
                try (InputStream is = jar.getInputStream(forgeEntry)) {
                    content = new String(is.readAllBytes());
                }
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"");
                java.util.regex.Matcher matcher = pattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    public void cancel() {
        wasCancelled = true;
    }

    public boolean isRunning() {
        return isRunning;
    }

    /** Percent of total classes compiled so far. */
    public int getProgress() {
        if (totalClasses == 0) return 0;
        return (int) ((compiledClasses * 100.0) / totalClasses);
    }
    
    public void addProgressListener(ProgressListener listener) {
        listeners.add(listener);
    }

    public void removeProgressListener(ProgressListener listener) {
        listeners.remove(listener);
    }

    /** Run compilation behind a Swing progress dialog, or headless if no GUI is available. */
    public void showProgressDialog(List<Path> modsToCompile) {
        if (!EnvironmentDetector.canShowGui()) {
            runFullCompilation(modsToCompile);
            return;
        }
        
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            
            JDialog dialog = new JDialog((Frame) null, "Retromod: preparing mods", true);
            dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            
            JPanel panel = new JPanel(new BorderLayout(10, 10));
            panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

            JLabel titleLabel = new JLabel("Preparing mods for quicker launches...");
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
            panel.add(titleLabel, BorderLayout.NORTH);

            JPanel progressPanel = new JPanel(new GridLayout(4, 1, 5, 5));
            
            JProgressBar progressBar = new JProgressBar(0, 100);
            progressBar.setStringPainted(true);
            progressPanel.add(progressBar);
            
            JLabel modLabel = new JLabel("Preparing...");
            progressPanel.add(modLabel);
            
            JLabel classLabel = new JLabel(" ");
            classLabel.setFont(classLabel.getFont().deriveFont(Font.PLAIN, 11f));
            progressPanel.add(classLabel);
            
            JLabel statsLabel = new JLabel("0 / 0 classes prepared");
            progressPanel.add(statsLabel);
            
            panel.add(progressPanel, BorderLayout.CENTER);

            JButton cancelButton = new JButton("Cancel");
            cancelButton.addActionListener(e -> {
                cancel();
                dialog.dispose();
            });
            
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttonPanel.add(cancelButton);
            panel.add(buttonPanel, BorderLayout.SOUTH);
            
            dialog.add(panel);
            dialog.pack();
            dialog.setSize(450, 200);
            dialog.setLocationRelativeTo(null);

            ProgressListener listener = new ProgressListener() {
                @Override
                public void onProgress(int compiled, int total, String mod, String className) {
                    SwingUtilities.invokeLater(() -> {
                        int percent = total > 0 ? (int) ((compiled * 100.0) / total) : 0;
                        progressBar.setValue(percent);
                        progressBar.setString(percent + "%");
                        modLabel.setText("Mod: " + mod);
                        classLabel.setText("Class: " + truncate(className, 50));
                        statsLabel.setText(compiled + " / " + total + " classes prepared");
                    });
                }
                
                @Override
                public void onComplete(int totalCompiled, long timeMs) {
                    SwingUtilities.invokeLater(() -> {
                        dialog.dispose();
                        
                        JOptionPane.showMessageDialog(
                            null,
                            String.format("""
                                Retromod finished precompiling your mods.

                                Classes prepared: %d
                                Time: %.1f seconds

                                Later launches can reuse this cache.
                                """, totalCompiled, timeMs / 1000.0),
                            "Precompile finished",
                            JOptionPane.INFORMATION_MESSAGE
                        );
                    });
                }
                
                @Override
                public void onError(String mod, String className, String error) {
                    LOGGER.debug("Could not prepare {}: {}", className, error);
                }
                
                private String truncate(String s, int max) {
                    if (s.length() <= max) return s;
                    return "..." + s.substring(s.length() - max + 3);
                }
            };
            
            addProgressListener(listener);

            // whenComplete, not thenRun: a failed future must still drop the listener or it leaks
            runFullCompilation(modsToCompile).whenComplete((r, t) -> {
                removeProgressListener(listener);
            });

            dialog.setVisible(true);
        });
    }
    
    public void clearCache() {
        try {
            if (Files.exists(cacheDir)) {
                try (var walk = Files.walk(cacheDir)) {
                    walk.sorted((a, b) -> b.toString().length() - a.toString().length())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {}
                        });
                }
            }
            Files.createDirectories(cacheDir);
            LOGGER.info("AOT cache cleared");
        } catch (Exception e) {
            LOGGER.error("Could not clear cache", e);
        }
    }
    
    /** Total size of the cache directory in bytes. */
    public long getCacheSize() {
        try {
            if (!Files.exists(cacheDir)) return 0;
            try (var walk = Files.walk(cacheDir)) {
                return walk.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
            }
        } catch (Exception e) {
            return 0;
        }
    }
}
