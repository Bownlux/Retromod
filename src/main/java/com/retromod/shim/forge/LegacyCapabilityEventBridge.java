/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;
import org.objectweb.asm.tree.ClassNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Rebuilds the capability events and members Forge 26.2 changed: attach listeners, the register event, and two removals. */
public final class LegacyCapabilityEventBridge {

    static final String ATTACH = LegacyAttachListenerSplit.ATTACH;
    static final String REGISTER_EVENT = "net/minecraftforge/common/capabilities/RegisterCapabilitiesEvent";
    static final String CAPABILITY_MANAGER = "net/minecraftforge/common/capabilities/CapabilityManager";
    static final String CAPABILITY_PROVIDER = "net/minecraftforge/common/capabilities/CapabilityProvider";
    static final String CAPABILITY_DISPATCHER = "net/minecraftforge/common/capabilities/CapabilityDispatcher";
    static final String NEW_REGISTRY_EVENT = "net/minecraftforge/registries/NewRegistryEvent";
    static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";

    private static final String ROOT = RebuiltApiClasses.ROOT + "forge/capabilities/";
    static final String ATTACH_LISTENERS = ROOT + "LegacyAttachListeners";
    /** The stand-in for the removed event; an old listener's parameter names it after the redirect. */
    public static final String STAND_IN = ROOT + "LegacyRegisterCapabilitiesEvent";
    static final String REGISTRATION = ROOT + "LegacyCapabilityRegistration";
    static final String CALLS = ROOT + "LegacyCapabilityCalls";
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    private static final ModConstructorHooks.Call WATCH =
            new ModConstructorHooks.Call(ModConstructorHooks.Position.HEAD, REGISTRATION, "watch", "()V", List.of());
    private static final byte[] REGISTER_EVENT_NAME = REGISTER_EVENT.getBytes(StandardCharsets.ISO_8859_1);

    private LegacyCapabilityEventBridge() {}

    /** Registers each part where the Forge host has the new shape; offline, a Forge target from 26.2. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        String from = ForgeHostShape.FIRST_CHECKED_VERSION;
        // Forge 26.2 fires one attach event per holder kind, each with its own BUS, and dropped getGenericType.
        if (ForgeHostShape.matches(List.of(ATTACH + "$Entities"), List.of(), from, host)) {
            RebuiltApiClasses.registerTree(t, ATTACH_LISTENERS);
            t.enablePostProcess(LegacyAttachListenerSplit.FLAG);
            t.registerMethodRedirect(ATTACH, "getGenericType", "()Ljava/lang/reflect/Type;",
                    ATTACH_LISTENERS, "getGenericType", "(Ljava/lang/Object;)Ljava/lang/reflect/Type;", true);
        }
        // Forge 26.2 removed RegisterCapabilitiesEvent, so no native mod names it; its stand-in is posted from
        // NewRegistryEvent, after every constructor and before any registry event, as Forge 1.20.1 posted it.
        if (ForgeHostShape.matches(List.of(CAPABILITY_MANAGER, NEW_REGISTRY_EVENT), List.of(REGISTER_EVENT), from, host)) {
            RebuiltApiClasses.registerTree(t, REGISTRATION);
            t.registerClassRedirect(REGISTER_EVENT, STAND_IN);
            ModConstructorHooks.register(t, "legacy-register-capabilities", LegacyCapabilityEventBridge::plan);
        }
        // Forge 26.2 compares stacks by their components and saves no stack capabilities.
        if (ForgeHostMembers.lacksMethod(CAPABILITY_PROVIDER, "areCapsCompatible", "(L" + CAPABILITY_PROVIDER + ";)Z",
                from, host)) {
            RebuiltApiClasses.registerTree(t, CALLS);
            for (String other : List.of(CAPABILITY_PROVIDER, CAPABILITY_DISPATCHER)) {
                t.registerMethodRedirect(ITEM_STACK, "areCapsCompatible", "(L" + other + ";)Z",
                        CALLS, "areCapsCompatible", "(Ljava/lang/Object;Ljava/lang/Object;)Z", true);
            }
        }
        // Forge 26.2 made CapabilityManager all static and dropped its INSTANCE.
        if (ForgeHostMembers.lacksField(CAPABILITY_MANAGER, "INSTANCE", from, host)) {
            RebuiltApiClasses.registerTree(t, CALLS);
            String manager = "L" + CAPABILITY_MANAGER + ";";
            t.registerFieldRedirect(CAPABILITY_MANAGER, "INSTANCE", manager, CALLS, "instance", "()" + manager);
        }
        // The rebuilt classes note what they could not do through the game log.
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);
    }

    /** The registration start in each {@code @Mod} constructor of a jar that names the old register event; null otherwise. */
    static ModConstructorHooks.JarPlan plan(Map<String, byte[]> classes, Map<String, String> modIds) {
        for (byte[] original : classes.values()) {
            if (contains(original, REGISTER_EVENT_NAME)) return (modClass, modId) -> List.of(WATCH);
        }
        return null;
    }

    /** Whether a class's event type is the stand-in, which Retromod builds into the jar, so it always binds. */
    static boolean isStandIn(String internalName) {
        return STAND_IN.equals(internalName);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
