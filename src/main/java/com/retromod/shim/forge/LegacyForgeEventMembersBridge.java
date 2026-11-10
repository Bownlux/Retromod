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
    }

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

    /** Whether the host's {@code owner} lost {@code oldName} and declares {@code newName}; offline, a Forge target from {@code offlineFrom}. */
    static boolean renamedOnHost(String owner, String oldName, String newName, String desc, String offlineFrom) {
        if (!ForgeHostMembers.lacksMethod(owner, oldName, desc, offlineFrom)) return false;
        ClassNode host = ClassResourceInspector.read(owner);
        // Offline the target version has already answered; a running host must have the new member to call.
        return host == null || host.methods.stream().anyMatch(m -> m.name.equals(newName) && m.desc.equals(desc));
    }
}
