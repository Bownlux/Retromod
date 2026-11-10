/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core.parallel;

import com.retromod.util.RetromodLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Shared parallel-execution pool for Retromod's batch passes.
 *
 * <p>A dedicated pool keeps batch work from contending with {@link ForkJoinPool#commonPool()}
 * (shared with parallel streams and other libraries) and is easy to size and observe.
 *
 * <p>Default size is {@code Runtime.getRuntime().availableProcessors()}. Override via
 * {@code -Dretromod.parallelism=N}: {@code N=1} forces serial execution (low-memory machines,
 * deterministic debugging), {@code N=0} means all cores, {@code N>=2} caps the thread count.
 *
 * <p>Only the batch flows use this (CLI commands, Fabric pre-launch, Forge/NeoForge constructor);
 * the per-class {@code ClassFileTransformer} agent path is single-class and has nothing to
 * parallelize against.
 *
 * <p>The pool lives for the JVM session: shutting it down mid-run would corrupt in-flight tasks.
 * Workers are daemon threads, so they don't block shutdown.
 */
public final class RetromodExecutors {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Parallel");

    /** Resolved once at class init so the pool size stays stable for the session. */
    private static final int PARALLELISM = resolveParallelism();

    private static volatile ForkJoinPool sharedPool;

    private RetromodExecutors() {}

    /** Effective parallelism level; {@code 1} means serial. */
    public static int getParallelism() {
        return PARALLELISM;
    }

    /** Whether parallel execution is enabled (more than one thread). */
    public static boolean isParallel() {
        return PARALLELISM > 1;
    }

    /** Shared pool, created lazily. Never null. */
    public static ForkJoinPool sharedPool() {
        ForkJoinPool local = sharedPool;
        if (local != null) return local;
        synchronized (RetromodExecutors.class) {
            if (sharedPool != null) return sharedPool;
            // asyncMode=true gives FIFO scheduling, matching "process a queue of classes".
            sharedPool = new ForkJoinPool(PARALLELISM,
                    new NamedDaemonForkJoinWorkerThreadFactory("retromod-worker-"),
                    (t, e) -> LOGGER.warn("Worker {} died: {}", t.getName(), e.getMessage()),
                    true);
            LOGGER.debug(RetromodLog.DETAIL, "Retromod parallel executor initialized with {} threads", PARALLELISM);
            return sharedPool;
        }
    }

    /**
     * Run {@code action} on every element of {@code items}, in parallel when enabled.
     *
     * <p>Exceptions from the action are caught and logged; one bad task doesn't abort the rest.
     * Callers needing per-item failure detail should use {@link #parallelMap}.
     */
    public static <T> void parallelForEach(Collection<T> items, Consumer<T> action) {
        if (items == null || items.isEmpty()) return;
        if (!isParallel() || items.size() == 1) {
            for (T item : items) {
                try {
                    action.accept(item);
                } catch (Exception e) {
                    LOGGER.warn("Task failed: {}", e.getMessage());
                }
            }
            return;
        }

        // invokeAll blocks until every task completes.
        List<ForEachTask<T>> tasks = new ArrayList<>(items.size());
        for (T item : items) tasks.add(new ForEachTask<>(item, action));
        // ForkJoinPool.invokeAll(Collection) does not declare InterruptedException, so the outer
        // try only catches pool-side RuntimeExceptions; future.get() handles the interrupt case.
        try {
            sharedPool().invokeAll(tasks).forEach(future -> {
                try { future.get(); } catch (InterruptedException | ExecutionException ignored) {}
            });
        } catch (Exception e) {
            LOGGER.warn("Parallel forEach encountered error: {}", e.getMessage());
        }
    }

    /** Parallel forEach over map entries; same error handling as {@link #parallelForEach}. */
    public static <K, V> void parallelForEachEntry(Map<K, V> items, BiConsumer<K, V> action) {
        if (items == null || items.isEmpty()) return;
        if (!isParallel() || items.size() == 1) {
            for (Map.Entry<K, V> entry : items.entrySet()) {
                try {
                    action.accept(entry.getKey(), entry.getValue());
                } catch (Exception e) {
                    LOGGER.warn("Task failed for key {}: {}", entry.getKey(), e.getMessage());
                }
            }
            return;
        }
        parallelForEach(items.entrySet(), entry -> action.accept(entry.getKey(), entry.getValue()));
    }

    /**
     * Apply {@code fn} to every input and collect the results, preserving input order.
     *
     * <p>Failed tasks yield {@code null} in the result list; callers filter or treat null as skip.
     */
    public static <T, R> List<R> parallelMap(List<T> inputs, Function<T, R> fn) {
        if (inputs == null || inputs.isEmpty()) return List.of();
        if (!isParallel() || inputs.size() == 1) {
            List<R> out = new ArrayList<>(inputs.size());
            for (T input : inputs) {
                try {
                    out.add(fn.apply(input));
                } catch (Exception e) {
                    LOGGER.warn("Map task failed: {}", e.getMessage());
                    out.add(null);
                }
            }
            return out;
        }

        // Preserve order by assigning indices upfront.
        @SuppressWarnings("unchecked")
        R[] results = (R[]) new Object[inputs.size()];
        AtomicInteger failed = new AtomicInteger();
        List<IndexedMapTask<T, R>> tasks = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            tasks.add(new IndexedMapTask<>(i, inputs.get(i), fn, results, failed));
        }
        // future.get() handles the interrupt case; ForkJoinPool.invokeAll doesn't declare it.
        sharedPool().invokeAll(tasks).forEach(future -> {
            try { future.get(); } catch (InterruptedException | ExecutionException ignored) {}
        });
        if (failed.get() > 0) {
            LOGGER.debug("parallelMap: {} of {} tasks failed", failed.get(), inputs.size());
        }
        // Arrays.asList (not List.of) keeps the nulls we write for failed tasks; copy to make it mutable.
        return new ArrayList<>(Arrays.asList(results));
    }

    /** Resolve the parallelism level from the system property, defaulting to all cores. */
    private static int resolveParallelism() {
        String raw = System.getProperty("retromod.parallelism");
        if (raw == null || raw.isBlank()) {
            return Math.max(1, Runtime.getRuntime().availableProcessors());
        }
        try {
            int n = Integer.parseInt(raw.trim());
            if (n == 0) return Math.max(1, Runtime.getRuntime().availableProcessors());
            return Math.max(1, n);
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid -Dretromod.parallelism={}, falling back to all cores", raw);
            return Math.max(1, Runtime.getRuntime().availableProcessors());
        }
    }

    /** Names workers predictably (helps thread dumps/profilers) and marks them daemon. */
    private static final class NamedDaemonForkJoinWorkerThreadFactory
            implements ForkJoinPool.ForkJoinWorkerThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger();

        NamedDaemonForkJoinWorkerThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public java.util.concurrent.ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            java.util.concurrent.ForkJoinWorkerThread t =
                    ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            t.setName(prefix + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    /** One {@link #parallelForEach} task: a single consumer call per input item. */
    private static final class ForEachTask<T> extends RecursiveAction
            implements java.util.concurrent.Callable<Void> {
        private final T item;
        private final Consumer<T> action;

        ForEachTask(T item, Consumer<T> action) {
            this.item = item;
            this.action = action;
        }

        @Override
        protected void compute() {
            try {
                action.accept(item);
            } catch (Exception e) {
                LOGGER.warn("ForEach task failed: {}", e.getMessage());
            }
        }

        @Override
        public Void call() {
            compute();
            return null;
        }
    }

    /** Runs {@code fn(input)} and writes the result to a shared array at a fixed index, preserving order. */
    private static final class IndexedMapTask<T, R>
            implements java.util.concurrent.Callable<Void> {
        private final int index;
        private final T input;
        private final Function<T, R> fn;
        private final R[] results;
        private final AtomicInteger failed;

        IndexedMapTask(int index, T input, Function<T, R> fn, R[] results, AtomicInteger failed) {
            this.index = index;
            this.input = input;
            this.fn = fn;
            this.results = results;
            this.failed = failed;
        }

        @Override
        public Void call() {
            try {
                results[index] = fn.apply(input);
            } catch (Exception e) {
                LOGGER.debug("Map task {} failed: {}", index, e.getMessage());
                results[index] = null;
                failed.incrementAndGet();
            }
            return null;
        }
    }
}
