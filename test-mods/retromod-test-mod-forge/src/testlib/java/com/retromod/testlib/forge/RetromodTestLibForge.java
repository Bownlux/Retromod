/*
 * Retromod Test Library (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testlib.forge;

import net.minecraft.world.item.Tier;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.GenericEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.IModBusEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * A library mod for the test mod's #362 case. Its API takes types that Retromod rebuilds inside
 * each translated jar on newer hosts, so the test mod's calls only link when Retromod has it reuse
 * this jar's copies. For #358 its metadata declares no minecraft dependency, so Retromod can find
 * its Minecraft version only in the jar manifest. It also posts events of its own on Forge 1.20.1's
 * event base, which the test mod listens to, cancels and sets results on, and listens on its own mod
 * bus for one the test mod posts to every mod.
 */
@Mod("retromod_test_lib_forge")
public class RetromodTestLibForge {

    private static int modBusEventsSeen;

    public RetromodTestLibForge() {
        // Its own listener for its mod-bus event, which the test mod posts to every mod through ModLoader.
        if (!onNeoForge()) FMLJavaModLoadingContext.get().getModEventBus().register(new ModBusListener());
    }

    /** Counts this library's mod-bus events on its own bus, as DynamicTrees' addons listen to its registry events. */
    public static final class ModBusListener {
        @SubscribeEvent
        public void seen(LibModBusEvent event) {
            modBusEventsSeen++;
        }
    }

    /** How many of its mod-bus events reached this library's own mod bus. */
    public static int modBusEventsSeen() {
        return modBusEventsSeen;
    }

    // NeoForge keeps its own event API, so the Forge 26.2 event cases do not run there.
    private static boolean onNeoForge() {
        try {
            Class.forName("net.neoforged.bus.api.Event", false, RetromodTestLibForge.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Forge's capability holder, rebuilt per mod on NeoForge. */
    public static boolean isPresent(LazyOptional<?> value) {
        return value.isPresent();
    }

    /** The tool tier interface Minecraft 1.21.2 removed, rebuilt per mod on newer hosts. */
    public static float speed(Tier tier) {
        return tier.getSpeed();
    }

    /** An event this library defines on Forge 1.20.1's event base, whose result another mod sets, as citadel's are. */
    @Event.HasResult
    public static class LibResultEvent extends Event {
        public LibResultEvent() {}
    }

    /** Whether the result another mod set on the event allows it, read here in the library. */
    public static boolean allowed(LibResultEvent event) {
        return event.getResult() == Event.Result.ALLOW;
    }

    /** A cancelable event this library posts, which another mod's listener cancels. */
    @Cancelable
    public static class LibCancelableEvent extends Event {
        public LibCancelableEvent() {}
    }

    /** A generic event this library posts for one type at a time, as DynamicTrees posts its registries. */
    public static class LibGenericEvent<T> extends GenericEvent<T> {
        public LibGenericEvent(Class<T> type) {
            super(type);
        }
    }

    /** A mod-bus event this library defines, as DynamicTrees and Zeta define theirs. */
    public static class LibModBusEvent extends Event implements IModBusEvent {
        public LibModBusEvent() {}
    }

    /** Posts a result event on the game bus, then reads the result another mod's listener set. */
    public static boolean postForResult() {
        LibResultEvent event = new LibResultEvent();
        MinecraftForge.EVENT_BUS.post(event);
        return allowed(event);
    }

    /** Posts a cancelable event on the game bus and answers whether a listener cancelled it. */
    public static boolean postCancelable() {
        return MinecraftForge.EVENT_BUS.post(new LibCancelableEvent());
    }

    /** Posts a generic event for {@code type} on the game bus. */
    public static <T> void postGeneric(Class<T> type) {
        MinecraftForge.EVENT_BUS.post(new LibGenericEvent<>(type));
    }

    /** A result with two kinds, which an addon picks between, as Sophisticated Core's upgrade results are. */
    public static class LibResult {
        public LibResult() {}
    }

    public static final class LibSuccess extends LibResult {
        public LibSuccess() {}
    }

    public static final class LibFailure extends LibResult {
        public LibFailure() {}
    }
}
