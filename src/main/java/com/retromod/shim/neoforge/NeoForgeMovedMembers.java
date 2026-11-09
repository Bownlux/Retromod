/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

/** Follows NeoForge 1.21.1 members that later NeoForge releases renamed, moved, or retyped, on hosts that changed them. */
public final class NeoForgeMovedMembers {

    private static final String NEO = "net/neoforged/neoforge/";
    private static final String FALL_MEMBERS = RebuiltApiClasses.ROOT + "neoforge/event/LivingFallMembers";
    private static final String UPDATE_CAUSE = RebuiltApiClasses.ROOT + "neoforge/event/TagsUpdateCause";
    private static final String RENDER_MEMBERS = RebuiltApiClasses.ROOT + "neoforge/client/event/RenderLivingMembers";
    private static final String HELD_MODIFIERS = RebuiltApiClasses.ROOT + "minecraft/client/input/LegacyHeldModifiers";

    // The screen key events a mod can name; the members live on their package-private base.
    static final List<String> SCREEN_KEY_EVENTS = List.of("KeyInput", "KeyPressed", "KeyPressed$Pre",
            "KeyPressed$Post", "KeyReleased", "KeyReleased$Pre", "KeyReleased$Post");

    private NeoForgeMovedMembers() {}

    public static void register(RetromodTransformer t) {
        // Only the client sends to the server, so sendToServer moved to ClientPacketDistributor.
        String payload = "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;";
        String send = "(" + payload + "[" + payload + ")V";
        if (moved(NEO + "network/PacketDistributor", "sendToServer", send,
                NEO + "client/network/ClientPacketDistributor", "sendToServer", send, "1.21.11")) {
            t.registerMethodRedirect(NEO + "network/PacketDistributor", "sendToServer", send,
                    NEO + "client/network/ClientPacketDistributor", "sendToServer", send);
        }
        registerKeyEvents(t);
        registerRetypedEvents(t);
        registerRenderAndCharacterEvents(t);
    }

    /** 26.3 named the key event members after its KeyEvent: the key, and the keycode that was the scan code. */
    private static void registerKeyEvents(RetromodTransformer t) {
        String screen = NEO + "client/event/ScreenEvent$";
        if (moved(screen + "KeyInput", "getKeyCode", "()I", screen + "KeyInput", "getKey", "()I", "26.3")) {
            for (String event : SCREEN_KEY_EVENTS) {
                t.registerMethodRedirect(screen + event, "getKeyCode", "()I", screen + event, "getKey", "()I");
                t.registerMethodRedirect(screen + event, "getScanCode", "()I", screen + event, "getKeycode", "()I");
            }
        }
        String key = NEO + "client/event/InputEvent$Key";
        if (moved(key, "getScanCode", "()I", key, "getKeycode", "()I", "26.3")) {
            t.registerMethodRedirect(key, "getScanCode", "()I", key, "getKeycode", "()I");
        }
    }

    /** Members later releases dropped with nothing of the same meaning left on the event. */
    private static void registerRenderAndCharacterEvents(RetromodTransformer t) {
        // 1.21.2 passes a render state to RenderLivingEvent, so the entity is found from that state, where the host makes
        // a state per draw that names its entity type. 1.21.2 to 1.21.8 keep the old call, which fails as before.
        String render = NEO + "client/event/RenderLivingEvent";
        String state = "()Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;";
        String getEntity = "()Lnet/minecraft/world/entity/LivingEntity;";
        if (moved(render, "getEntity", getEntity, render, "getRenderState", state, "1.21.2")
                && com.retromod.shim.common.RenderStateLookupHost.supported()) {
            RebuiltApiClasses.registerTree(t, RENDER_MEMBERS);
            for (String event : List.of(render, render + "$Pre", render + "$Post")) {
                t.registerMethodRedirect(event, "getEntity", getEntity, RENDER_MEMBERS, "getEntity",
                        "(L" + render + ";)Lnet/minecraft/world/entity/LivingEntity;", true);
            }
        }
        // 26.2 dropped a typed character's modifiers, so they come from the keys held.
        String character = NEO + "client/event/ScreenEvent$CharacterTyped";
        if (moved(character, "getModifiers", "()I", character, "getCharacterEvent",
                "()Lnet/minecraft/client/input/CharacterEvent;", "26.2")) {
            RebuiltApiClasses.registerTree(t, HELD_MODIFIERS);
            for (String event : List.of(character, character + "$Pre", character + "$Post")) {
                t.registerMethodRedirect(event, "getModifiers", "()I", HELD_MODIFIERS, "modifiers", "(Ljava/lang/Object;)I", true);
            }
        }
    }

    /** Members whose type changed, answered by Retromod's rebuilt helpers. */
    private static void registerRetypedEvents(RetromodTransformer t) {
        // The fall distance became a double.
        String fall = NEO + "event/entity/living/LivingFallEvent";
        if (moved(fall, "getDistance", "()F", fall, "getDistance", "()D", "1.21.11")) {
            RebuiltApiClasses.registerTree(t, FALL_MEMBERS);
            t.registerMethodRedirect(fall, "getDistance", "()F", FALL_MEMBERS, "fallDistance", "(L" + fall + ";)F", true);
            t.registerMethodRedirect(fall, "setDistance", "(F)V", FALL_MEMBERS, "setFallDistance", "(L" + fall + ";F)V", true);
        }
        // An explosion event's explosion is the server explosion that implements Explosion.
        String explosion = NEO + "event/level/ExplosionEvent";
        String oldExplosion = "()Lnet/minecraft/world/level/Explosion;";
        String newExplosion = "()Lnet/minecraft/world/level/ServerExplosion;";
        if (moved(explosion, "getExplosion", oldExplosion, explosion, "getExplosion", newExplosion, "1.21.11")) {
            for (String event : List.of("", "$Start", "$Detonate")) {
                t.registerMethodRedirect(explosion + event, "getExplosion", oldExplosion,
                        explosion + event, "getExplosion", newExplosion);
            }
        }
        // 26.3 fires a subclass for each tag update cause, and its registries kept their 26.1 name.
        String tags = NEO + "event/TagsUpdatedEvent";
        String registryAccess = "()Lnet/minecraft/core/RegistryAccess;";
        if (moved(tags, "getRegistryAccess", registryAccess, tags, "getRegistries", registryAccess, "26.1")) {
            t.registerMethodRedirect(tags, "getRegistryAccess", registryAccess, tags, "getRegistries", registryAccess);
        }
        String cause = "()L" + tags + "$UpdateCause;";
        if (moved(tags, "getUpdateCause", cause, tags + "$ClientPacketReceived", null, null, "26.3")) {
            RebuiltApiClasses.registerTree(t, UPDATE_CAUSE);
            t.registerMethodRedirect(tags, "getUpdateCause", cause, UPDATE_CAUSE, "of",
                    "(L" + tags + ";)L" + UPDATE_CAUSE + ";", true);
            t.registerClassRedirect(tags + "$UpdateCause", UPDATE_CAUSE);
        }
    }

    /**
     * Whether the host dropped {@code owner.name desc} and has the replacement ({@code newName} null
     * means the new class alone). Offline, where the host cannot be read, the target version decides.
     */
    static boolean moved(String owner, String name, String desc, String newOwner, String newName, String newDesc,
                         String since) {
        ClassNode old = ClassResourceInspector.read(owner);
        ClassNode replacement = ClassResourceInspector.read(newOwner);
        if (old == null && replacement == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, since) >= 0;
        }
        if (old != null && declares(old, name, desc)) return false;
        return replacement != null && (newName == null || declares(replacement, newName, newDesc));
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }
}
