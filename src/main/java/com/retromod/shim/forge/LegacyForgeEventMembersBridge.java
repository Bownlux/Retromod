/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

/** Answers render, screen and input event members Forge 1.20.1 mods call that Forge 26.2 and 26.3 removed. */
public final class LegacyForgeEventMembersBridge {

    static final String CLIENT_EVENT = "net/minecraftforge/client/event/";
    static final String RENDER_LIVING = CLIENT_EVENT + "RenderLivingEvent";
    static final String SCREEN = CLIENT_EVENT + "ScreenEvent$";
    static final String CHARACTER_TYPED = SCREEN + "CharacterTyped";
    static final String MOUSE_SCROLLED = SCREEN + "MouseScrolled";
    static final String KEY_INPUT = SCREEN + "KeyInput";
    static final String MOUSE_SCROLLING = CLIENT_EVENT + "InputEvent$MouseScrollingEvent";
    static final String INPUT_KEY = CLIENT_EVENT + "InputEvent$Key";

    static final String RENDER_MEMBERS = RebuiltApiClasses.ROOT + "forge/client/event/RenderLivingMembers";
    static final String KEY_MEMBERS = RebuiltApiClasses.ROOT + "forge/client/event/ScreenKeyMembers";
    static final String HELD_MODIFIERS = RebuiltApiClasses.ROOT + "minecraft/client/input/LegacyHeldModifiers";
    static final String CLIENT_READS = RebuiltApiClasses.ROOT + "forge/client/event/LegacyForgeClientEventReads";
    static final String EVENT_READS = RebuiltApiClasses.ROOT + "forge/event/LegacyForgeEventReads";
    static final String VIEWPORT = CLIENT_EVENT + "ViewportEvent";
    static final String NAME_TAG = CLIENT_EVENT + "RenderNameTagEvent";
    static final String KEY_MODIFIER = "net/minecraftforge/client/settings/KeyModifier";
    static final String SET_SPAWN = "net/minecraftforge/event/entity/player/PlayerSetSpawnEvent";

    static final String GET_ENTITY = "()Lnet/minecraft/world/entity/LivingEntity;";
    static final String MODIFIERS_HELPER = "(Ljava/lang/Object;)I";

    // The classes an old mod names for each member: the base and the Pre and Post records that inherit it.
    static final List<String> RENDER_LIVING_EVENTS = List.of(RENDER_LIVING, RENDER_LIVING + "$Pre", RENDER_LIVING + "$Post");
    static final List<String> CHARACTER_EVENTS = List.of(CHARACTER_TYPED, CHARACTER_TYPED + "$Pre", CHARACTER_TYPED + "$Post");
    static final List<String> SCROLL_EVENTS = List.of(MOUSE_SCROLLED, MOUSE_SCROLLED + "$Pre", MOUSE_SCROLLED + "$Post");
    static final List<String> KEY_EVENTS = List.of(KEY_INPUT, SCREEN + "KeyPressed", SCREEN + "KeyPressed$Pre",
            SCREEN + "KeyPressed$Post", SCREEN + "KeyReleased", SCREEN + "KeyReleased$Pre", SCREEN + "KeyReleased$Post");

    private LegacyForgeEventMembersBridge() {}

    /** The members Forge 26.2 removed, registered from the 26.2 shim so they carry on to 26.3. */
    public static void register(RetromodTransformer t) {
        // RenderLivingEvent carries the frame's render state now, so the entity is found from that state.
        if (ForgeHostMembers.lacksMethod(RENDER_LIVING, "getEntity", GET_ENTITY, ForgeHostShape.FIRST_CHECKED_VERSION)
                && com.retromod.shim.common.RenderStateLookupHost.supported()) {
            RebuiltApiClasses.registerTree(t, RENDER_MEMBERS);
            for (String event : RENDER_LIVING_EVENTS) {
                t.registerMethodRedirect(event, "getEntity", GET_ENTITY, RENDER_MEMBERS, "getEntity",
                        "(L" + RENDER_LIVING + ";)" + GET_ENTITY.substring(2), true);
            }
        }
        // A typed character carries only its code point, so the modifiers come from the keys held.
        if (ForgeHostMembers.lacksMethod(CHARACTER_TYPED, "getModifiers", "()I", ForgeHostShape.FIRST_CHECKED_VERSION)) {
            RebuiltApiClasses.registerTree(t, HELD_MODIFIERS);
            for (String event : CHARACTER_EVENTS) {
                t.registerMethodRedirect(event, "getModifiers", "()I", HELD_MODIFIERS, "modifiers", MODIFIERS_HELPER, true);
            }
        }
        // The vertical scroll is getDeltaY, beside the horizontal one Forge 1.20.1 did not report, on screens and in game.
        if (renamedOnHost(MOUSE_SCROLLED, "getScrollDelta", "getDeltaY", "()D", ForgeHostShape.FIRST_CHECKED_VERSION)) {
            for (String event : SCROLL_EVENTS) {
                t.registerMethodRedirect(event, "getScrollDelta", "()D", event, "getDeltaY", "()D");
            }
        }
        if (renamedOnHost(MOUSE_SCROLLING, "getScrollDelta", "getDeltaY", "()D", ForgeHostShape.FIRST_CHECKED_VERSION)) {
            t.registerMethodRedirect(MOUSE_SCROLLING, "getScrollDelta", "()D", MOUSE_SCROLLING, "getDeltaY", "()D");
        }
        // Members that kept their name but changed a number type; the call converts the value it passes or gets.
        for (String[] drift : PRIMITIVE_DRIFT) {
            if (ForgeHostMembers.lacksMethod(drift[0], drift[1], drift[2], ForgeHostShape.FIRST_CHECKED_VERSION)
                    && hostDeclaresOrOffline(drift[0], drift[1], drift[3])) {
                int opcode = Integer.parseInt(drift[4]);
                boolean getter = drift[2].startsWith("()");
                t.registerConvertingRedirect(drift[0], drift[1], drift[2], drift[0], drift[1], drift[3],
                        getter ? 0 : opcode, getter ? opcode : 0);
            }
        }
        registerStateReads(t);
        registerRenamedMembers(t);
    }

    /** Partial tick, light, entity, held modifier and spawn reads that Forge 26.2 dropped, answered by rebuilt helpers. */
    static void registerStateReads(RetromodTransformer t) {
        // Each viewport event returns a float partial tick, which old code read as a double; the base is an interface.
        for (String event : List.of(VIEWPORT + "$ComputeCameraAngles", VIEWPORT + "$ComputeFov", VIEWPORT + "$RenderFog",
                VIEWPORT + "$ComputeFogColor")) {
            if (ForgeHostMembers.lacksMethod(event, "getPartialTick", "()D", ForgeHostShape.FIRST_CHECKED_VERSION)
                    && hostDeclaresOrOffline(event, "getPartialTick", "()F")) {
                t.registerConvertingRedirect(event, "getPartialTick", "()D", event, "getPartialTick", "()F", 0, org.objectweb.asm.Opcodes.F2D);
            }
        }
        boolean helper = false;
        String living = "(L" + RENDER_LIVING + ";)";
        if (ForgeHostMembers.lacksMethod(RENDER_LIVING, "getPartialTick", "()F", ForgeHostShape.FIRST_CHECKED_VERSION)) {
            for (String event : RENDER_LIVING_EVENTS) {
                t.registerMethodRedirect(event, "getPartialTick", "()F", CLIENT_READS, "livingPartialTick", living + "F", true);
                t.registerMethodRedirect(event, "getPackedLight", "()I", CLIENT_READS, "livingLight", living + "I", true);
            }
            helper = true;
        }
        // The name tag event carries the entity's render state instead of the entity.
        if (ForgeHostMembers.lacksMethod(NAME_TAG, "getEntity", "()Lnet/minecraft/world/entity/Entity;",
                ForgeHostShape.FIRST_CHECKED_VERSION) && hostDeclaresOrOffline(NAME_TAG, "getState",
                "()Lnet/minecraft/client/renderer/entity/state/EntityRenderState;")) {
            String tag = "(L" + NAME_TAG + ";)";
            t.registerMethodRedirect(NAME_TAG, "getEntity", "()Lnet/minecraft/world/entity/Entity;", CLIENT_READS,
                    "nameTagEntity", tag + "Lnet/minecraft/world/entity/Entity;", true);
            t.registerMethodRedirect(NAME_TAG, "getPackedLight", "()I", CLIENT_READS, "nameTagLight", tag + "I", true);
            t.registerMethodRedirect(NAME_TAG, "getPartialTick", "()F", CLIENT_READS, "nameTagPartialTick", tag + "F", true);
            helper = true;
        }
        // 26.2 draws the first person arm from a record without the player, which is always the local one.
        String arm = CLIENT_EVENT + "RenderArmEvent";
        if (ForgeHostMembers.lacksMethod(arm, "getPlayer", "()Lnet/minecraft/client/player/AbstractClientPlayer;",
                ForgeHostShape.FIRST_CHECKED_VERSION)) {
            t.registerMethodRedirect(arm, "getPlayer", "()Lnet/minecraft/client/player/AbstractClientPlayer;", CLIENT_READS, "armPlayer",
                    "(L" + arm + ";)Lnet/minecraft/client/player/AbstractClientPlayer;", true);
            helper = true;
        }
        // Skull models are registered as factories of the model set, which the event no longer hands out.
        String skulls = CLIENT_EVENT + "EntityRenderersEvent$CreateSkullModels";
        String modelSet = "Lnet/minecraft/client/model/geom/EntityModelSet;";
        String skullType = "Lnet/minecraft/world/level/block/SkullBlock$Type;";
        String skullModel = "Lnet/minecraft/client/model/object/skull/SkullModelBase;";
        if (ForgeHostMembers.lacksMethod(skulls, "getEntityModelSet", "()" + modelSet, ForgeHostShape.FIRST_CHECKED_VERSION)) {
            t.registerMethodRedirect(skulls, "getEntityModelSet", "()" + modelSet, CLIENT_READS, "skullModelSet",
                    "(L" + skulls + ";)" + modelSet, true);
            t.registerMethodRedirect(skulls, "registerSkullModel", "(" + skullType + skullModel + ")V", CLIENT_READS,
                    "registerSkullModel", "(L" + skulls + ";" + skullType + skullModel + ")V", true);
            helper = true;
        }
        String modifier = "()L" + KEY_MODIFIER + ";";
        if (ForgeHostMembers.lacksMethod(KEY_MODIFIER, "getActiveModifier", modifier, ForgeHostShape.FIRST_CHECKED_VERSION)) {
            t.registerMethodRedirect(KEY_MODIFIER, "getActiveModifier", modifier, CLIENT_READS, "activeModifier", modifier);
            helper = true;
        }
        if (helper) RebuiltApiClasses.registerTree(t, CLIENT_READS);
        // A new spawn arrives as a respawn config.
        if (ForgeHostMembers.lacksMethod(SET_SPAWN, "getNewSpawn", "()Lnet/minecraft/core/BlockPos;",
                ForgeHostShape.FIRST_CHECKED_VERSION)) {
            RebuiltApiClasses.registerTree(t, EVENT_READS);
            String event = "(L" + SET_SPAWN + ";)";
            t.registerMethodRedirect(SET_SPAWN, "getNewSpawn", "()Lnet/minecraft/core/BlockPos;", EVENT_READS, "newSpawn",
                    event + "Lnet/minecraft/core/BlockPos;", true);
            t.registerMethodRedirect(SET_SPAWN, "getSpawnLevel", "()Lnet/minecraft/resources/ResourceKey;", EVENT_READS,
                    "spawnLevel", event + "Lnet/minecraft/resources/ResourceKey;", true);
            t.registerMethodRedirect(SET_SPAWN, "isForced", "()Z", EVENT_READS, "forced", event + "Z", true);
        }
        // A break event a mod builds itself now takes a result, which starts as the default.
        String breakEvent = "net/minecraftforge/event/level/BlockEvent$BreakEvent";
        String oldBreak = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
                + "Lnet/minecraft/world/entity/player/Player;)V";
        if (ForgeHostMembers.lacksMethod(breakEvent, "<init>", oldBreak, ForgeHostShape.FIRST_CHECKED_VERSION)) {
            RebuiltApiClasses.registerTree(t, EVENT_READS);
            t.registerConstructorRedirect(breakEvent, oldBreak, EVENT_READS, "breakEvent",
                    oldBreak.substring(0, oldBreak.length() - 1) + "L" + breakEvent + ";");
        }
        // The destroy hook takes a server level, as the game rule it reads lives on the server.
        String hooks = "net/minecraftforge/common/ForgeHooks";
        String destroy = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;)Z";
        if (ForgeHostMembers.lacksMethod(hooks, "canEntityDestroy", destroy, ForgeHostShape.FIRST_CHECKED_VERSION)) {
            RebuiltApiClasses.registerTree(t, EVENT_READS);
            t.registerMethodRedirect(hooks, "canEntityDestroy", destroy, EVENT_READS, "canEntityDestroy", destroy);
        }
        // The drops event no longer carries the looting level, so it is read from the killer.
        String drops = "net/minecraftforge/event/entity/living/LivingDropsEvent";
        if (ForgeHostMembers.lacksMethod(drops, "getLootingLevel", "()I", ForgeHostShape.FIRST_CHECKED_VERSION)) {
            RebuiltApiClasses.registerTree(t, EVENT_READS);
            t.registerMethodRedirect(drops, "getLootingLevel", "()I", EVENT_READS, "lootingLevel", "(L" + drops + ";)I", true);
        }
    }

    /** Members Forge 26.2 kept under another name. */
    static void registerRenamedMembers(RetromodTransformer t) {
        String crafted = "net/minecraftforge/event/entity/player/PlayerEvent$ItemCraftedEvent";
        String container = "()Lnet/minecraft/world/Container;";
        if (renamedOnHost(crafted, "getInventory", "getContainer", container, ForgeHostShape.FIRST_CHECKED_VERSION)) {
            t.registerMethodRedirect(crafted, "getInventory", container, crafted, "getContainer", container);
        }
        // MobSpawnType became EntitySpawnReason in 1.21.2, and the event's getter followed it.
        String finalize = "net/minecraftforge/event/entity/living/MobSpawnEvent$FinalizeSpawn";
        String reason = "()Lnet/minecraft/world/entity/EntitySpawnReason;";
        if (renamedOnHost(finalize, "getSpawnType", "getSpawnReason", reason, ForgeHostShape.FIRST_CHECKED_VERSION)) {
            t.registerMethodRedirect(finalize, "getSpawnType", reason, finalize, "getSpawnReason", reason);
        }
    }

    /** Owner, member, Forge 1.20.1 descriptor, Forge 26.2 descriptor, and the conversion opcode; checked with javap on Forge 65.0.0. */
    static final String[][] PRIMITIVE_DRIFT = {
            {"net/minecraftforge/event/entity/living/LivingFallEvent", "getDistance", "()F", "()D", String.valueOf(org.objectweb.asm.Opcodes.D2F)},
            {"net/minecraftforge/event/entity/living/LivingFallEvent", "setDistance", "(F)V", "(D)V", String.valueOf(org.objectweb.asm.Opcodes.F2D)},
            {CLIENT_EVENT + "ViewportEvent$ComputeFov", "getFOV", "()D", "()F", String.valueOf(org.objectweb.asm.Opcodes.F2D)},
            {CLIENT_EVENT + "ViewportEvent$ComputeFov", "setFOV", "(D)V", "(F)V", String.valueOf(org.objectweb.asm.Opcodes.D2F)},
            {"net/minecraftforge/event/AnvilUpdateEvent", "getCost", "()I", "()J", String.valueOf(org.objectweb.asm.Opcodes.L2I)},
            {"net/minecraftforge/event/AnvilUpdateEvent", "setCost", "(I)V", "(J)V", String.valueOf(org.objectweb.asm.Opcodes.I2L)},
            {"net/minecraftforge/event/level/BlockEvent$FarmlandTrampleEvent", "getFallDistance", "()F", "()D", String.valueOf(org.objectweb.asm.Opcodes.D2F)},
    };

    /** The scan code Forge 26.3 removed from its screen and in-game key events, registered from the 26.3 shim. */
    public static void registerScanCode(RetromodTransformer t) {
        if (ForgeHostMembers.lacksMethod(KEY_INPUT, "getScanCode", "()I", "26.3")) {
            RebuiltApiClasses.registerTree(t, KEY_MEMBERS);
            for (String event : KEY_EVENTS) {
                t.registerMethodRedirect(event, "getScanCode", "()I", KEY_MEMBERS, "scanCode", "(L" + KEY_INPUT + ";)I", true);
            }
        }
        if (ForgeHostMembers.lacksMethod(INPUT_KEY, "getScanCode", "()I", "26.3")) {
            RebuiltApiClasses.registerTree(t, KEY_MEMBERS);
            t.registerMethodRedirect(INPUT_KEY, "getScanCode", "()I", KEY_MEMBERS, "inputScanCode", "(L" + INPUT_KEY + ";)I", true);
        }
    }

    /** Whether the running host's {@code owner} declares the member; offline, the caller's version check has answered. */
    static boolean hostDeclaresOrOffline(String owner, String name, String desc) {
        ClassNode host = ClassResourceInspector.read(owner);
        return host == null || host.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    /** Whether the host's {@code owner} lost {@code oldName} and declares {@code newName}; offline, a Forge target from {@code offlineFrom}. */
    static boolean renamedOnHost(String owner, String oldName, String newName, String desc, String offlineFrom) {
        if (!ForgeHostMembers.lacksMethod(owner, oldName, desc, offlineFrom)) return false;
        ClassNode host = ClassResourceInspector.read(owner);
        // Offline the target version has already answered; a running host must have the new member to call.
        return host == null || host.methods.stream().anyMatch(m -> m.name.equals(newName) && m.desc.equals(desc));
    }
}
