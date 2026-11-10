/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/** Sends Retromod's log to the game's own log, or to standard error where the game has none. */
public final class HostLogProvider implements SLF4JServiceProvider {

    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final MDCAdapter mdcAdapter = new NOPMDCAdapter();
    private final ILoggerFactory loggerFactory = new HostLoggerFactory();

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return "2.0.99";
    }

    @Override
    public void initialize() {
        // Nothing to set up: each logger finds its output on its first line.
    }

    /** Where one logger's lines go. */
    interface Sink {
        void write(Level level, String message, Throwable error);

        /** A detail line: the host's DEBUG output where it has one, nowhere otherwise. */
        void detail(String message, Throwable error);
    }

    /** Hands out one logger per name. */
    private static final class HostLoggerFactory implements ILoggerFactory {
        private final ConcurrentMap<String, Logger> loggers = new ConcurrentHashMap<>();

        @Override
        public Logger getLogger(String name) {
            // The output is resolved later, never here: resolving inside computeIfAbsent could
            // ask this same factory for this same name and throw.
            return loggers.computeIfAbsent(name, key -> new HostLogger(key, Binding::sinkFor));
        }
    }

    /** A logger that formats like slf4j-simple and writes INFO, WARN, ERROR, and detail lines to its sink. */
    static final class HostLogger extends LegacyAbstractLogger {
        private static final long serialVersionUID = 1L;

        private final transient Function<String, Sink> sinks;
        private transient volatile Sink sink;

        HostLogger(String name, Function<String, Sink> sinks) {
            this.name = name;
            this.sinks = sinks;
        }

        // DEBUG and TRACE stay off whatever the host says. Forge and NeoForge set their root
        // level to all, and hundreds of debug lines per class would slow translation and flood
        // debug.log.
        @Override
        public boolean isTraceEnabled() {
            return false;
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        // Detail lines are the exception: a few per mod, so they can reach the host's DEBUG output.
        @Override
        public boolean isDebugEnabled(Marker marker) {
            return RetromodLog.isDetail(marker);
        }

        @Override
        public boolean isInfoEnabled() {
            return true;
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return null;
        }

        @Override
        protected void handleNormalizedLoggingCall(Level level, Marker marker, String pattern,
                Object[] arguments, Throwable error) {
            String message = MessageFormatter.basicArrayFormat(pattern, arguments);
            // Only detail lines arrive at DEBUG. log_level DEBUG writes them at INFO, so they reach
            // latest.log on every loader, Fabric included, which has no debug.log.
            if (level != Level.DEBUG) {
                sink().write(level, message, error);
            } else if (RetromodLog.detailInGameLog()) {
                sink().write(Level.INFO, message, error);
            } else {
                sink().detail(message, error);
            }
        }

        private Sink sink() {
            Sink current = sink;
            if (current == null) {
                current = sinks.apply(name);
                sink = current;
            }
            return current;
        }
    }

    /** Writes through a host SLF4J logger, falling back to standard error if the host fails. */
    static final class HostSink implements Sink {
        private final String name;
        private final Object logger;
        private final boolean prefixName;
        private final Method info;
        private final Method infoThrown;
        private final Method warn;
        private final Method warnThrown;
        private final Method error;
        private final Method errorThrown;
        private final Method debug;
        private final Method debugThrown;
        private final StandardErrorSink fallback;

        HostSink(String name, Object logger, Class<?> loggerType, boolean prefixName)
                throws NoSuchMethodException {
            this.name = name;
            this.logger = logger;
            this.prefixName = prefixName;
            // Looked up on the host's Logger interface, so any host implementation can be called.
            this.info = loggerType.getMethod("info", String.class);
            this.infoThrown = loggerType.getMethod("info", String.class, Throwable.class);
            this.warn = loggerType.getMethod("warn", String.class);
            this.warnThrown = loggerType.getMethod("warn", String.class, Throwable.class);
            this.error = loggerType.getMethod("error", String.class);
            this.errorThrown = loggerType.getMethod("error", String.class, Throwable.class);
            this.debug = loggerType.getMethod("debug", String.class);
            this.debugThrown = loggerType.getMethod("debug", String.class, Throwable.class);
            this.fallback = new StandardErrorSink(name);
        }

        @Override
        public void write(Level level, String message, Throwable thrown) {
            // The Fabric and Quilt log layout prints no logger name, so the line carries it.
            String text = prefixName ? "[" + name + "] " + message : message;
            try {
                if (thrown == null) {
                    method(level, false).invoke(logger, text);
                } else {
                    method(level, true).invoke(logger, text, thrown);
                }
            } catch (Throwable hostFailed) {
                // A line the host could not take still reaches the console.
                fallback.write(level, message, thrown);
            }
        }

        @Override
        public void detail(String message, Throwable thrown) {
            String text = prefixName ? "[" + name + "] " + message : message;
            try {
                if (thrown == null) {
                    debug.invoke(logger, text);
                } else {
                    debugThrown.invoke(logger, text, thrown);
                }
            } catch (Throwable hostFailed) {
                // A detail line is never worth a fallback write.
            }
        }

        private Method method(Level level, boolean withThrowable) {
            return switch (level) {
                case ERROR -> withThrowable ? errorThrown : error;
                case WARN -> withThrowable ? warnThrown : warn;
                default -> withThrowable ? infoThrown : info;
            };
        }
    }

    /** Writes the slf4j-simple line format to standard error, as Retromod always did. */
    static final class StandardErrorSink implements Sink {
        private static final Object LOCK = new Object();
        private final String name;

        StandardErrorSink(String name) {
            this.name = name;
        }

        // The CLI and tests have no DEBUG output to fill.
        @Override
        public void detail(String message, Throwable error) {
        }

        @Override
        public void write(Level level, String message, Throwable error) {
            // System.err is read on every line, so the CLI and tests that capture it still work.
            PrintStream out = System.err;
            synchronized (LOCK) {
                out.println("[" + Thread.currentThread().getName() + "] " + level.name() + " "
                        + name + " - " + message);
                if (error != null) error.printStackTrace(out);
                out.flush();
            }
        }
    }

    /** How this copy of SLF4J reaches the host, decided once on the first line logged. */
    private static final class Binding {
        private static final Binding INSTANCE = resolve();

        private final Method getLogger;
        private final Class<?> loggerType;
        private final boolean prefixName;

        private Binding(Method getLogger, Class<?> loggerType, boolean prefixName) {
            this.getLogger = getLogger;
            this.loggerType = loggerType;
            this.prefixName = prefixName;
        }

        static Sink sinkFor(String name) {
            Binding binding = INSTANCE;
            if (binding.getLogger == null) return new StandardErrorSink(name);
            try {
                Object logger = binding.getLogger.invoke(null, name);
                return new HostSink(name, logger, binding.loggerType, binding.prefixName);
            } catch (Throwable unavailable) {
                return new StandardErrorSink(name);
            }
        }

        private static Binding resolve() {
            ClassLoader loader = HostLogProvider.class.getClassLoader();
            Binding standardError = new Binding(null, null, false);
            try {
                // Built at runtime: shade would rewrite a literal to Retromod's own relocated copy.
                String slf4j = HostLibraryNames.SLF4J_PACKAGE;
                Class<?> factory = Class.forName(
                        HostLibraryNames.binaryName(slf4j + "/LoggerFactory"), true, loader);
                // Shade relocates this class literal, so the two match only on an unshaded class
                // path (tests, mvn exec:java), where asking the factory would come back here.
                if (factory == LoggerFactory.class) return standardError;
                Class<?> loggerType = Class.forName(
                        HostLibraryNames.binaryName(slf4j + "/Logger"), false, loader);
                return new Binding(factory.getMethod("getLogger", String.class), loggerType,
                        isFabricFamily(loader));
            } catch (Throwable unavailable) {
                // The CLI and the bootstrap's private loader have no host SLF4J.
                return standardError;
            }
        }

        // Probed without McReflect, whose own logger would ask this provider while it resolves.
        private static boolean isFabricFamily(ClassLoader loader) {
            try {
                Class.forName("net.fabricmc.loader.api.FabricLoader", false, loader);
                return true;
            } catch (ClassNotFoundException | LinkageError absent) {
                return false;
            }
        }
    }
}
