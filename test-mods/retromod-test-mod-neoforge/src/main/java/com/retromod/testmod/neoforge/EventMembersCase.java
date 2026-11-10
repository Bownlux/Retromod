/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import com.mojang.datafixers.util.Pair;
import java.util.Optional;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.LoggerFactory;

/**
 * Members a 1.21.1 mod calls that later NeoForge and Minecraft releases changed: Registry.getTags() pairs, a render
 * event's entity, and a typed character's modifiers. The client cases log the first time the game posts their event.
 */
final class EventMembersCase {

    private static boolean renderLogged;
    private static int renderEventsWithoutEntity;
    private static boolean characterLogged;

    private EventMembersCase() {}

    /** REI and Inventory Profiles Next read every tag of a registry as pairs; 1.21.2 streams the named sets alone. */
    static String tagPairs() {
        Optional<Pair<TagKey<Item>, HolderSet.Named<Item>>> first = BuiltInRegistries.ITEM.getTags().findFirst();
        if (first.isEmpty()) return "fail: the item registry listed no tags once the server started";
        Object key = first.get().getFirst();
        Object set = first.get().getSecond();
        if (!(key instanceof TagKey<?>) || !(set instanceof HolderSet.Named<?>)) {
            return "fail: the pair held " + key + " and " + set;
        }
        return ((HolderSet.Named<?>) set).key().equals(key) ? "success" : "fail: the key " + key + " is not its set's";
    }

    /** The client listeners. Client only. */
    static void registerClient() {
        NeoForge.EVENT_BUS.addListener(EventMembersCase::onRenderLiving);
        NeoForge.EVENT_BUS.addListener(EventMembersCase::onCharacterTyped);
    }

    // Alex's Mobs and PuzzlesLib read the entity of every living entity drawn.
    private static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        if (renderLogged) return;
        LivingEntity entity;
        try {
            entity = event.getEntity();
        } catch (Throwable t) {
            renderLogged = true;
            log("RenderLivingEvent.getEntity() (first living entity drawn)", "fail: " + t);
            return;
        }
        // A mob drawn outside the world, or one of several that fit the same state, has none; one in the world is found.
        if (entity == null && ++renderEventsWithoutEntity < 200) return;
        renderLogged = true;
        log("RenderLivingEvent.getEntity() (first living entity drawn)",
                entity != null ? "success" : "fail: 200 drawn entities had no entity");
    }

    private static void onCharacterTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (characterLogged) return;
        characterLogged = true;
        String result;
        try {
            boolean shift = (event.getModifiers() & 1) != 0;
            result = shift == net.minecraft.client.gui.screens.Screen.hasShiftDown() ? "success"
                    : "fail: the modifiers read " + event.getModifiers();
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        log("ScreenEvent.CharacterTyped getModifiers() follows the held Shift key (first character typed)", result);
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-NeoForge").info("[Retromod-Test-NeoForge] {}: {}", what, result);
    }
}
