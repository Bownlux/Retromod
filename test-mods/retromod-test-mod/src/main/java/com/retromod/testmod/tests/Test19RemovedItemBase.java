/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.item.EnchantedBookItem;
import net.minecraft.item.Item;
import net.minecraft.item.MusicDiscItem;
import net.minecraft.sound.SoundEvent;

/**
 * Regression test for issue #260: a mod that extends a vanilla item class the game later deleted.
 *
 * <p>Minecraft keeps folding hardcoded item subclasses into data components. {@code MusicDiscItem}
 * (Mojang {@code RecordItem}) went that way when jukebox playability became a component, and
 * {@code EnchantedBookItem} went the same way for stored enchantments. Neither has a class
 * successor, because one old class did not become one new class.
 *
 * <p>Retromod's class-move table nonetheless named the nearest surviving class for both. The music
 * disc landed on {@code JukeboxSong}, a final record, so a subclass died at class load with
 * {@code IncompatibleClassChangeError} naming a class the mod never mentions. The enchanted book
 * landed on {@code Items}, a static holder with no constructor, so it died at the {@code super(...)}
 * call instead. Both now rebase onto a generated {@code Item} subclass that carries the old
 * constructor.
 *
 * <p>The check is deliberately about linkage, not behavior. Loading the subclass is what proves the
 * {@code extends} edge resolves, and calling its constructor is what proves the rebased
 * {@code super(...)} still has something to land on. What the old base did with the arguments it no
 * longer passes on is gone by design, so this test must not assert anything about jukebox playback
 * or stored enchantments.
 */
public class Test19RemovedItemBase implements Test {

    @Override
    public String description() {
        return "removed item bases (MusicDiscItem, EnchantedBookItem)";
    }

    @Override
    public TestResult run() {
        String failure = check("MusicDiscItem", RemovedBaseProbe::newDisc);
        if (failure != null) return TestResult.fail(failure);

        failure = check("EnchantedBookItem", RemovedBaseProbe::newBook);
        if (failure != null) return TestResult.fail(failure);

        return TestResult.success();
    }

    /**
     * Run one probe and report only the failures this test is about.
     *
     * <p>Constructing an item outside registration is legitimate on the versions this mod is built
     * for and can be rejected on later ones for reasons that have nothing to do with the transform,
     * so only linkage errors count. Anything else means the class linked, which is what is being
     * tested.
     */
    private static String check(String label, Runnable probe) {
        try {
            probe.run();
            return null;
        } catch (IncompatibleClassChangeError | NoClassDefFoundError | VerifyError e) {
            return label + " did not link: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (Throwable other) {
            // Linked, then refused to build an unregistered item. Not this test's concern.
            return null;
        }
    }

    /**
     * Deliberately separate so the subclasses are transformed and then loaded on first use, rather
     * than while the runner is assembling its test list.
     */
    private static final class RemovedBaseProbe {

        private RemovedBaseProbe() {}

        static void newDisc() {
            new LegacyDisc();
        }

        static void newBook() {
            new LegacyBook();
        }

        /** Extends a base that no longer exists, and calls its four argument constructor. */
        private static class LegacyDisc extends MusicDiscItem {
            LegacyDisc() {
                super(1, (SoundEvent) null, new Item.Settings(), 1);
            }
        }

        /** Same shape, different removed base and a one argument constructor. */
        private static class LegacyBook extends EnchantedBookItem {
            LegacyBook() {
                super(new Item.Settings());
            }
        }
    }
}
