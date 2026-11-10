/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** The adapter cache an addon gets a copy of keeps one adapter per object, and lets both go when unused. */
class StandInAdapterCacheTest {

    /** An adapter holds its value, as the generated ones hold their delegate. */
    private record Adapter(Object delegate) {}

    /** A value equal to every other value with the same number. */
    private record Value(int number) {}

    @Test
    @DisplayName("equal but distinct values get their own adapters")
    void equalValuesStayApart() {
        StandInAdapterCache cache = new StandInAdapterCache();
        Value first = new Value(5);
        Value second = new Value(5);
        Object firstAdapter = cache.keep(first, new Adapter(first));
        Object secondAdapter = cache.keep(second, new Adapter(second));

        assertNotSame(firstAdapter, secondAdapter);
        assertSame(firstAdapter, cache.find(first));
        assertSame(secondAdapter, cache.find(second));
    }

    @Test
    @DisplayName("a value nobody holds, and its adapter, are released")
    void droppedValuesAreReleased() throws InterruptedException {
        StandInAdapterCache cache = new StandInAdapterCache();
        for (int i = 0; i < 20_000; i++) {
            Value value = new Value(i);
            cache.keep(value, new Adapter(value));
        }
        int kept = Integer.MAX_VALUE;
        for (int attempt = 0; attempt < 20 && kept > 100; attempt++) {
            System.gc();
            Thread.sleep(20);
            kept = cache.size();
        }
        assertTrue(kept <= 100, kept + " entries kept after their values were dropped");
    }

    @Test
    @DisplayName("an adapter someone still holds stays the one handed out")
    void heldAdapterStays() {
        StandInAdapterCache cache = new StandInAdapterCache();
        Value value = new Value(1);
        Object held = cache.keep(value, new Adapter(value));
        System.gc();

        assertSame(held, cache.keep(value, new Adapter(value)), "a second adapter must not replace a live one");
    }

    @Test
    @DisplayName("threads converting one value at once all get the same adapter")
    void concurrentKeepsAgree() throws Exception {
        StandInAdapterCache cache = new StandInAdapterCache();
        Value value = new Value(7);
        Set<Object> handedOut = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    for (int n = 0; n < 1_000; n++) {
                        Object found = cache.find(value);
                        handedOut.add(found != null ? found : cache.keep(value, new Adapter(value)));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> run : runs) run.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, handedOut.size(), "one adapter per value, whichever thread made it");
        assertTrue(Objects.nonNull(handedOut.iterator().next()));
    }
}
