/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.mojang.datafixers.util.Pair;
import java.util.Optional;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.LoggerFactory;

/**
 * Event and registry members that Forge 26.2, Forge 26.3 and Minecraft 1.21.2 changed: a render event's entity, a
 * typed character's modifiers, a scroll's delta, a key's scan code, an advancement's id, Registry.getTags() pairs,
 * and a mod's own event on LivingEvent, which Forge 26.2 made an interface. Server cases log once a server starts;
 * client cases log the first time the game posts their event.
 */
public final class EventMemberCases {

    private static boolean renderLogged;
    private static int renderEventsWithoutEntity;
    private static boolean keyLogged;
    private static boolean characterLogged;
    private static boolean scrollLogged;
    private static boolean gameScrollLogged;
    private static boolean gameKeyLogged;
    private static boolean advancementLogged;
    private static boolean ownEventHeard;

    private EventMemberCases() {}

    static void register() {
        MinecraftForge.EVENT_BUS.addListener(EventMemberCases::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(EventMemberCases::onAdvancementEarned);
        // A mod's own LivingEvent is a Forge case: NeoForge keeps LivingEvent a class.
        if (!LegacyEventBaseCase.onNeoForge()) MinecraftForge.EVENT_BUS.register(OwnLivingEventListener.class);
    }

    /** The client listeners, as Alex's Mobs, JEI and Inventory Profiles Next register theirs. Client only. */
    static void registerClient() {
        MinecraftForge.EVENT_BUS.register(ClientListeners.class);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        log("Registry.getTags() streams (tag key, named set) pairs to a mod built before 1.21.2 (server started)",
                result(EventMemberCases::tagPairs));
        log("Advancement.getId() on an advancement looked up by id (server started)",
                result(() -> advancementId(event)));
        if (!LegacyEventBaseCase.onNeoForge()) {
            log("A mod's own event on LivingEvent posts to its listener and keeps its entity (server started)",
                    result(EventMemberCases::ownLivingEvent));
        }
    }

    // InventoryProfilesNext and REI read every tag of a registry as pairs; 1.21.2 streams the named sets alone.
    private static String tagPairs() {
        Optional<Pair<TagKey<Item>, HolderSet.Named<Item>>> first = BuiltInRegistries.ITEM.getTags().findFirst();
        if (first.isEmpty()) return "fail: the item registry listed no tags once the server started";
        Object key = first.get().getFirst();
        Object set = first.get().getSecond();
        if (!(key instanceof TagKey<?>) || !(set instanceof HolderSet.Named<?>)) {
            return "fail: the pair held " + key + " and " + set;
        }
        return ((HolderSet.Named<?>) set).key().equals(key) ? "success" : "fail: the key " + key + " is not its set's";
    }

    private static String advancementId(ServerStartedEvent event) {
        ResourceLocation root = new ResourceLocation("minecraft", "story/root");
        Advancement advancement = event.getServer().getAdvancements().getAdvancement(root);
        if (advancement == null) return "fail: minecraft:story/root was not found";
        return root.equals(advancement.getId()) ? "success" : "fail: its id read as " + advancement.getId();
    }

    private static String ownLivingEvent() {
        ownEventHeard = false;
        OwnLivingEvent posted = new OwnLivingEvent(null);
        MinecraftForge.EVENT_BUS.post(posted);
        if (!ownEventHeard) return "fail: the listener did not hear the event";
        if (!(posted instanceof LivingEvent)) return "fail: the event is no LivingEvent";
        return posted.getEntity() == null ? "success" : "fail: the entity read as " + posted.getEntity();
    }

    // Lootr reads the advancement's id in its own trigger.
    private static void onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        if (advancementLogged) return;
        advancementLogged = true;
        log("AdvancementEarnEvent.getAdvancement().getId() (first advancement earned)", result(() -> {
            ResourceLocation id = event.getAdvancement().getId();
            return id != null ? "success" : "fail: the advancement has no id";
        }));
    }

    /** A Curios-style event: a mod's own subclass of LivingEvent, posted on the game bus. */
    public static final class OwnLivingEvent extends LivingEvent {
        public OwnLivingEvent(LivingEntity entity) {
            super(entity);
        }
    }

    public static final class OwnLivingEventListener {
        @SubscribeEvent
        public static void onOwnEvent(OwnLivingEvent event) {
            ownEventHeard = true;
        }
    }

    public static final class ClientListeners {
        // Alex's Mobs reads the entity of every living entity drawn.
        @SubscribeEvent
        public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
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

        @SubscribeEvent
        public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
            if (keyLogged) return;
            keyLogged = true;
            log("ScreenEvent.KeyPressed getKeyCode(), getScanCode() and getModifiers() (first key pressed in a screen)",
                    result(() -> {
                        int answered = event.getKeyCode() + event.getScanCode() + event.getModifiers();
                        return answered != Integer.MIN_VALUE ? "success" : "fail";
                    }));
        }

        @SubscribeEvent
        public static void onCharacterTyped(ScreenEvent.CharacterTyped.Pre event) {
            if (characterLogged) return;
            characterLogged = true;
            log("ScreenEvent.CharacterTyped getModifiers() follows the held Shift key (first character typed)", result(() -> {
                boolean shift = (event.getModifiers() & 1) != 0;
                return shift == net.minecraft.client.gui.screens.Screen.hasShiftDown() ? "success"
                        : "fail: the modifiers read " + event.getModifiers();
            }));
        }

        // Create and Supplementaries read the wheel in game; balm, Kiwi and voicechat read the key's scan code.
        @SubscribeEvent
        public static void onGameScroll(InputEvent.MouseScrollingEvent event) {
            if (gameScrollLogged) return;
            gameScrollLogged = true;
            log("InputEvent.MouseScrollingEvent getScrollDelta() (first scroll in game)",
                    result(() -> event.getScrollDelta() != 0 ? "success" : "fail: the scroll read 0"));
        }

        @SubscribeEvent
        public static void onGameKey(InputEvent.Key event) {
            if (gameKeyLogged) return;
            gameKeyLogged = true;
            log("InputEvent.Key getKey(), getScanCode() and getModifiers() (first key pressed in game)", result(() -> {
                int answered = event.getKey() + event.getScanCode() + event.getModifiers();
                return answered != Integer.MIN_VALUE ? "success" : "fail";
            }));
        }

        @SubscribeEvent
        public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
            if (scrollLogged) return;
            scrollLogged = true;
            log("ScreenEvent.MouseScrolled getScrollDelta() (first scroll in a screen)",
                    result(() -> event.getScrollDelta() != 0 ? "success" : "fail: the scroll read 0"));
        }
    }

    private static String result(java.util.concurrent.Callable<String> check) {
        try {
            return check.call();
        } catch (Throwable t) {
            return "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }
}
