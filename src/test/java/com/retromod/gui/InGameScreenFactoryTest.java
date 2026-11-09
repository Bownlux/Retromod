/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Yes and No answers of Retromod's confirm screens. */
class InGameScreenFactoryTest {

    /** Stands in for fastutil's BooleanConsumer, which is not on the test classpath. */
    public interface BooleanAnswer {
        void accept(boolean confirmed);
    }

    private final AtomicInteger stops = new AtomicInteger();
    private final AtomicInteger returns = new AtomicInteger();

    private BooleanAnswer callback() {
        return (BooleanAnswer) Proxy.newProxyInstance(BooleanAnswer.class.getClassLoader(),
                new Class<?>[]{BooleanAnswer.class},
                InGameScreenFactory.answerHandler(stops::incrementAndGet, returns::incrementAndGet));
    }

    @Test
    void noAndEscapeReturnToTheParentScreen() {
        // ConfirmScreen answers false for both No and Esc.
        callback().accept(false);

        assertEquals(0, stops.get(), "No must not close the game");
        assertEquals(1, returns.get());
    }

    @Test
    void yesRunsTheStopAction() {
        callback().accept(true);

        assertEquals(1, stops.get());
        assertEquals(0, returns.get(), "Yes closes the game, so nothing returns to the parent");
    }

    @Test
    void objectMethodsAreNotAnswers() {
        BooleanAnswer answer = callback();

        assertTrue(answer.equals(answer));
        assertEquals(System.identityHashCode(answer), answer.hashCode());
        assertNotNull(answer.toString());
        assertEquals(0, stops.get() + returns.get(), "only accept(boolean) answers the screen");
    }
}
