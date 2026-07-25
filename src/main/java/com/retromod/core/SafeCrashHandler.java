/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.retromod.gui.InGameScreenFactory;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Catches errors from transformed mods during gameplay, pauses, saves the world,
 * shows a quit-only dialog, and exits so players don't lose worlds to mod incompatibilities.
 */
public class SafeCrashHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-SafeCrash");

    private static SafeCrashHandler instance;

    // class name -> owning mod id
    private final Map<String, String> classToModMap;

    private final Map<String, Integer> modErrorCounts = new ConcurrentHashMap<>();

    private final AtomicBoolean crashHandled = new AtomicBoolean(false);

    private Object minecraftServer = null;
    private Object minecraftClient = null;

    private final Thread.UncaughtExceptionHandler previousHandler;

    private SafeCrashHandler(Map<String, String> classToModMap) {
        this.classToModMap = classToModMap;
        this.previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this::handleUncaughtException);
    }

    public static synchronized SafeCrashHandler getInstance() {
        if (instance == null) {
            Map<String, String> mapping = new ConcurrentHashMap<>();
            try {
                HybridTransformationEngine engine = HybridTransformationEngine.getInstance();
                mapping = engine.getClassToModMap();
            } catch (Exception e) {
                // leave mapping empty
            }
            instance = new SafeCrashHandler(mapping);
        }
        return instance;
    }

    public void registerServer(Object server) {
        this.minecraftServer = server;
        LOGGER.debug("Registered Minecraft server for safe crash handling");
    }

    public void registerClient(Object client) {
        this.minecraftClient = client;
        LOGGER.debug("Registered Minecraft client for safe crash handling");
    }
    
    /**
     * Records and (for critical errors) handles an error thrown by a transformed class.
     * Returns true if the game should stop, false to propagate.
     */
    public boolean handleTransformError(String className, Throwable error) {
        String modId = identifyMod(className, error);
        modErrorCounts.merge(modId, 1, Integer::sum);
        LOGGER.error("Transformed mod '{}' threw an error in class '{}'", modId, className, error);

        if (isCriticalError(error)) {
            triggerSafeCrash(modId, className, error);
            return true;
        }
        return false;
    }

    // Handle exceptions from transformed mods; chain everything else to the previous handler.
    // Always logs and writes crash-log.txt so a crash never exits without diagnostics.
    private void handleUncaughtException(Thread thread, Throwable error) {
        LOGGER.error("Uncaught exception on thread '{}': {}", thread.getName(), error.getMessage());
        LOGGER.error("Full stack trace:", error);
        System.err.println("[Retromod] Uncaught exception on thread \"" + thread.getName() + "\": " + error);
        error.printStackTrace(System.err);

        writeCrashLog(thread, error);

        String modId = identifyConfirmedModFromStackTrace(error);

        if (modId != null) {
            LOGGER.error("Crash caused by Retromod-transformed mod: '{}'", modId);
            triggerSafeCrash(modId, null, error);
        } else if (previousHandler != null) {
            previousHandler.uncaughtException(thread, error);
        }
        // no previous handler: already logged above, fall through to default JVM behavior
    }

    // Persist crash details in case the console window vanishes (Windows).
    private void writeCrashLog(Thread thread, Throwable error) {

        try {
            java.nio.file.Path crashLogDir = java.nio.file.Path.of("config/retromod");
            java.nio.file.Files.createDirectories(crashLogDir);
            java.nio.file.Path crashLog = crashLogDir.resolve("crash-log.txt");

            StringBuilder sb = new StringBuilder();
            sb.append("=== Retromod Crash Log ===\n");
            sb.append("Time: ").append(java.time.LocalDateTime.now()).append("\n");
            sb.append("Thread: ").append(thread.getName()).append("\n");
            sb.append("Error: ").append(error.getClass().getName())
              .append(": ").append(error.getMessage()).append("\n");
            sb.append("\nStack trace:\n");
            for (StackTraceElement el : error.getStackTrace()) {
                sb.append("  at ").append(el).append("\n");
            }
            if (error.getCause() != null) {
                sb.append("\nCaused by: ").append(error.getCause()).append("\n");
                for (StackTraceElement el : error.getCause().getStackTrace()) {
                    sb.append("  at ").append(el).append("\n");
                }
            }
            sb.append("\nJava: ").append(System.getProperty("java.version")).append("\n");
            sb.append("OS: ").append(System.getProperty("os.name")).append(" ")
              .append(System.getProperty("os.version")).append("\n");
            sb.append("Retromod: 1.3.0-snapshot.3\n");
            sb.append("\nPlease report this at: https://github.com/Bownlux/Retromod/issues\n");

            java.nio.file.Files.writeString(crashLog, sb.toString());
            LOGGER.info("Crash details written to {}", crashLog.toAbsolutePath());
        } catch (Exception e) {
            LOGGER.warn("Could not write crash log file: {}", e.getMessage());
        }
    }

    // Returns a mod id only for a stack-trace class known to be Retromod-transformed.
    private String identifyConfirmedModFromStackTrace(Throwable error) {
        for (StackTraceElement element : error.getStackTrace()) {
            String className = element.getClassName().replace('.', '/');
            if (classToModMap.containsKey(className)) {
                return classToModMap.get(className);
            }
        }
        return null;
    }
    
    // Best-effort mod id for an error: direct mapping, then stack trace, then a guess from the class name.
    private String identifyMod(String className, Throwable error) {
        if (classToModMap.containsKey(className)) {
            return classToModMap.get(className);
        }

        String fromStack = identifyModFromStackTrace(error);
        if (fromStack != null) {
            return fromStack;
        }

        // com/example/mymod/SomeClass -> mymod
        String[] parts = className.replace('.', '/').split("/");
        if (parts.length >= 3) {
            return parts[2];
        }

        return "unknown";
    }

    private String identifyModFromStackTrace(Throwable error) {
        for (StackTraceElement element : error.getStackTrace()) {
            String className = element.getClassName().replace('.', '/');

            if (classToModMap.containsKey(className)) {
                return classToModMap.get(className);
            }

            // skip Minecraft and library classes
            if (className.startsWith("net/minecraft/") ||
                className.startsWith("java/") ||
                className.startsWith("com/mojang/") ||
                className.startsWith("org/lwjgl/") ||
                className.startsWith("com/retromod/")) {
                continue;
            }

            // likely a mod class; extract its mod name
            String[] parts = className.split("/");
            if (parts.length >= 3) {
                return parts[2];
            }
        }

        return null;
    }

    private boolean isCriticalError(Throwable error) {
        if (error instanceof OutOfMemoryError ||
            error instanceof StackOverflowError ||
            error instanceof NoClassDefFoundError ||
            error instanceof NoSuchMethodError ||
            error instanceof NoSuchFieldError ||
            error instanceof IncompatibleClassChangeError) {
            return true;
        }

        // treat errors during a critical game phase as critical
        String message = error.getMessage();
        if (message != null) {
            if (message.contains("tick") ||
                message.contains("render") ||
                message.contains("world") ||
                message.contains("entity") ||
                message.contains("block")) {
                return true;
            }
        }
        
        return false;
    }
    
    private void triggerSafeCrash(String modId, String className, Throwable error) {
        // handle one crash at a time
        if (!crashHandled.compareAndSet(false, true)) {
            return;
        }

        LOGGER.error("=======================================================");
        LOGGER.error("  SAFE CRASH TRIGGERED");
        LOGGER.error("  Mod: {}", modId);
        LOGGER.error("  Class: {}", className != null ? className : "unknown");
        LOGGER.error("  Error: {}", error.getMessage());
        LOGGER.error("=======================================================");

        pauseGame();
        boolean worldSaved = saveWorld();
        showCrashDialog(modId, error, worldSaved);
    }

    private void pauseGame() {
        LOGGER.info("Pausing game...");

        try {
            if (minecraftClient != null) {
                try {
                    Method pauseMethod = minecraftClient.getClass().getMethod("pause");
                    pauseMethod.invoke(minecraftClient);
                    LOGGER.info("Game paused via client");
                } catch (NoSuchMethodException e) {
                    // fall back to clearing the screen
                    try {
                        Method setScreen = minecraftClient.getClass().getMethod("setScreen", Object.class);
                        setScreen.invoke(minecraftClient, (Object) null);
                    } catch (Exception e2) {
                        LOGGER.debug("Could not pause via setScreen");
                    }
                }
            }

            if (minecraftServer != null) {
                try {
                    Method halt = minecraftServer.getClass().getMethod("halt", boolean.class);
                    halt.invoke(minecraftServer, false); // false = don't wait
                } catch (Exception e) {
                    LOGGER.debug("Could not halt server");
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not pause game: {}", e.getMessage());
        }
    }

    private boolean saveWorld() {
        LOGGER.info("Saving world...");

        try {
            if (minecraftServer != null) {
                String[] methodsToTry = {
                    "saveAllChunks",
                    "saveEverything",
                    "save"
                };

                for (String methodName : methodsToTry) {
                    try {
                        Method saveMethod = findMethod(minecraftServer.getClass(), methodName);
                        if (saveMethod != null) {
                            if (saveMethod.getParameterCount() == 0) {
                                saveMethod.invoke(minecraftServer);
                            } else if (saveMethod.getParameterCount() == 3) {
                                saveMethod.invoke(minecraftServer, false, true, true);
                            }
                            LOGGER.info("World saved via {}", methodName);
                            return true;
                        }
                    } catch (Exception e) {
                        LOGGER.debug("Save method {} failed: {}", methodName, e.getMessage());
                    }
                }
            }
            
            LOGGER.warn("Could not save world automatically");
            return false;

        } catch (Exception e) {
            LOGGER.error("Error saving world: {}", e.getMessage());
            return false;
        }
    }

    // Find a method by name, ignoring parameter types.
    private Method findMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }
    
    // Show the crash dialog with options, or log it to console on servers.
    private void showCrashDialog(String modId, Throwable error, boolean worldSaved) {
        StringBuilder message = new StringBuilder();
        message.append("Retromod encountered an error!\n\n");
        
        message.append("═══════════════════════════════════════\n");
        message.append("THE PROBLEM:\n");
        message.append("═══════════════════════════════════════\n\n");
        
        message.append("The mod \"").append(modId).append("\" does not work\n");
        message.append("correctly with Retromod.\n\n");
        
        message.append("Error: ").append(error.getClass().getSimpleName()).append("\n");
        if (error.getMessage() != null) {
            String msg = error.getMessage();
            if (msg.length() > 100) {
                msg = msg.substring(0, 100) + "...";
            }
            message.append("Details: ").append(msg).append("\n");
        }
        message.append("\n");

        if (worldSaved) {
            message.append("✓ Your world has been SAVED automatically.\n\n");
        } else {
            message.append("⚠ Could not save world automatically.\n");
            message.append("  (Your recent progress may be lost)\n\n");
        }
        
        message.append("═══════════════════════════════════════\n");
        message.append("WHAT YOU CAN DO:\n");
        message.append("═══════════════════════════════════════\n\n");
        
        message.append("1. UNINSTALL \"").append(modId).append("\"\n");
        message.append("   Remove this mod and continue using Retromod\n");
        message.append("   with your other mods.\n\n");
        
        message.append("2. FIND AN ALTERNATIVE\n");
        message.append("   Look for a similar mod that works with your\n");
        message.append("   Minecraft version, or has been tested with\n");
        message.append("   Retromod.\n\n");
        
        message.append("3. USE THE ORIGINAL VERSION\n");
        message.append("   Play on the Minecraft version that\n");
        message.append("   \"").append(modId).append("\" was designed for.\n\n");
        
        message.append("═══════════════════════════════════════\n");

        if (EnvironmentDetector.canShowGui()) {
            showGuiCrashDialog(message.toString());
        } else {
            showConsoleCrashMessage(modId, message.toString());
        }
    }

    // Render the crash as a Minecraft screen (client only) rather than a Swing popup.
    private void showGuiCrashDialog(String message) {
        try {
            InGameScreenFactory.showCrashScreen("Transformed Mod", message, () -> {
                LOGGER.info("User acknowledged crash - exiting Minecraft");
                System.exit(1);
            });
        } catch (Exception e) {
            // in-game screen failed: log and exit
            LOGGER.error("Could not show in-game crash screen: {}", e.getMessage());
            LOGGER.error("Crash details:\n{}", message);
            System.exit(1);
        }

        // hold the thread while the screen is up; user should click Save & Quit
        try {
            Thread.sleep(30000); // 30 seconds max
        } catch (InterruptedException ignored) {
            System.exit(1);
        }
    }

    private void showConsoleCrashMessage(String modId, String message) {
        LOGGER.error("");
        LOGGER.error("╔═══════════════════════════════════════════════════════════╗");
        LOGGER.error("║           RETROMOD - MOD COMPATIBILITY ERROR              ║");
        LOGGER.error("╠═══════════════════════════════════════════════════════════╣");
        
        for (String line : message.split("\n")) {
            LOGGER.error("║  {}", padRight(line, 56) + "║");
        }
        
        LOGGER.error("╠═══════════════════════════════════════════════════════════╣");
        LOGGER.error("║  Server will shut down to prevent data corruption.        ║");
        LOGGER.error("║  Remove the mod \"{}\" and restart.{}", modId, padRight("", 56 - 24 - modId.length()) + "║");
        LOGGER.error("╚═══════════════════════════════════════════════════════════╝");
        LOGGER.error("");

        // give logs time to flush
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {}

        LOGGER.error("Shutting down server...");
        System.exit(1);
    }

    // Pad a string to length n, truncating if longer.
    private String padRight(String s, int n) {
        if (s.length() >= n) return s.substring(0, n);
        return s + " ".repeat(n - s.length());
    }

    /** Returns the number of errors recorded for the given mod. */
    public int getErrorCount(String modId) {
        return modErrorCounts.getOrDefault(modId, 0);
    }

    /** Returns true once a crash has been handled. */
    public boolean hasCrashOccurred() {
        return crashHandled.get();
    }
}
