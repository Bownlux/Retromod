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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Sends old result calls on Forge 26.2 events that lost their result to Retromod's {@code EventResults}. */
public final class LegacyForgeEventResultBridge {

    /** Turns on {@link #apply} in the post-remap class passes. */
    public static final String FLAG = "forge-legacy-event-result";

    static final String EVENT_RESULTS = RebuiltApiClasses.ROOT + "forge/eventbus/EventResults";
    // Forge 26.2 and 26.3 read this event's result to refuse a break, which Forge 1.20.1 never did.
    static final String BREAK = "net/minecraftforge/event/level/BlockEvent$BreakEvent";
    /**
     * The first Minecraft version whose Forge gave BreakEvent a result its break check reads. It comes from Forge's
     * source history (the 1.21.5 branch has none, the 1.21.6 EventBus 7 port adds it) and was not checked against a
     * Forge 1.21.5 or 1.21.6 jar, which are not available here.
     */
    static final String FIRST_BREAK_RESULT = "1.21.6";

    private static final String L_RESULT = "L" + LegacyEventBaseBridge.RESULT + ";";
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    // Forge's own event packages; a mod's event class keeps its own methods.
    private static final List<String> OWNER_PACKAGES = List.of("net/minecraftforge/event/", "net/minecraftforge/client/event/");

    // Each old member, by name and post-remap descriptor, with the helper's descriptor that takes the event first.
    private static final Map<String, String> HELPERS = Map.of(
            "getResult()" + L_RESULT, "(Ljava/lang/Object;)" + L_RESULT,
            "setResult(" + L_RESULT + ")V", "(Ljava/lang/Object;" + L_RESULT + ")V",
            "hasResult()Z", "(Ljava/lang/Object;)Z");

    /** The Forge events with a result, implementing HasResult, read with javap from Forge 65.0.0 and 66.1.2. */
    static final Set<String> HOST_RESULT_EVENTS = Set.of(
            "net/minecraftforge/client/event/ScreenEvent$MouseButtonPressed$Post",
            "net/minecraftforge/client/event/ScreenEvent$MouseButtonReleased$Post",
            "net/minecraftforge/event/entity/EntityMobGriefingEvent",
            "net/minecraftforge/event/entity/living/LivingPackSizeEvent",
            "net/minecraftforge/event/entity/living/MobEffectEvent$Applicable",
            "net/minecraftforge/event/entity/living/MobSpawnEvent$AllowDespawn",
            "net/minecraftforge/event/entity/living/MobSpawnEvent$PositionCheck",
            "net/minecraftforge/event/entity/living/MobSpawnEvent$SpawnPlacementCheck",
            "net/minecraftforge/event/entity/living/ZombieEvent$SummonAidEvent",
            "net/minecraftforge/event/entity/player/BonemealEvent",
            "net/minecraftforge/event/entity/player/CriticalHitEvent",
            "net/minecraftforge/event/entity/player/EntityItemPickupEvent",
            "net/minecraftforge/event/entity/player/FillBucketEvent",
            "net/minecraftforge/event/entity/player/PlayerSpawnPhantomsEvent",
            "net/minecraftforge/event/entity/player/SleepingLocationCheckEvent",
            "net/minecraftforge/event/entity/player/SleepingTimeCheckEvent",
            BREAK,
            "net/minecraftforge/event/level/BlockEvent$CreateFluidSourceEvent",
            "net/minecraftforge/event/level/BlockEvent$CropGrowEvent$Pre",
            "net/minecraftforge/event/level/BlockFeatureGrowEvent");

    // The host's classes do not change while the game runs, so each one is read once per registration.
    private static final Map<String, Optional<ClassNode>> HOST_CLASSES = new ConcurrentHashMap<>();
    private static final Function<String, ClassNode> HOST = LegacyForgeEventResultBridge::readHost;

    private LegacyForgeEventResultBridge() {}

    /** Registers the result helper and the class pass where the Forge host has HasResult and lacks Event.Result. */
    public static void register(RetromodTransformer t) {
        HOST_CLASSES.clear();
        if (!hostLostEventResult()) return;
        RebuiltApiClasses.registerTree(t, EVENT_RESULTS);
        // LegacyCancel, which a denied left click goes through, notes cancels it cannot carry in the game log.
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);
        // The host now reads BreakEvent's result and denies the break, where Forge 1.20.1 ignored it, so a value
        // an older mod sets stays beside the event. Mods built for the new result keep the host's member.
        for (Map.Entry<String, String> member : Map.of("getResult", "()" + L_RESULT,
                "setResult", "(" + L_RESULT + ")V").entrySet()) {
            String name = member.getKey();
            String desc = member.getValue();
            t.registerMethodRedirect(BREAK, name, desc, EVENT_RESULTS, name, HELPERS.get(name + desc), true);
            t.limitRedirectToModsBefore(BREAK, name, desc, FIRST_BREAK_RESULT);
        }
        t.enablePostProcess(FLAG);
    }

    /** Whether the Forge host has the new HasResult and lacks EventBus 6's Event.Result; offline, a Forge target from 26.2. */
    static boolean hostLostEventResult() {
        return hostLostEventResult(ClassResourceInspector::read);
    }

    static boolean hostLostEventResult(Function<String, ClassNode> host) {
        return ForgeHostShape.matches(List.of(LegacyEventBaseBridge.HAS_RESULT), List.of(LegacyEventBaseBridge.OLD_RESULT),
                ForgeHostShape.FIRST_CHECKED_VERSION, host);
    }

    private static ClassNode readHost(String name) {
        return HOST_CLASSES.computeIfAbsent(name, key -> Optional.ofNullable(ClassResourceInspector.read(key))).orElse(null);
    }

    /** The class with old result calls on host events that lack them sent to {@code EventResults}, or the same array. */
    public static byte[] apply(byte[] classBytes) {
        return apply(classBytes, HOST);
    }

    static byte[] apply(byte[] classBytes, Function<String, ClassNode> host) {
        if (classBytes == null || !mayCallResult(classBytes)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    changed |= rewriteCall(call, host);
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (int i = 0; i < indy.bsmArgs.length; i++) {
                        if (!(indy.bsmArgs[i] instanceof Handle handle)) continue;
                        Handle helper = helperHandle(handle, host);
                        changed |= helper != handle;
                        indy.bsmArgs[i] = helper;
                    }
                } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle handle) {
                    Handle helper = helperHandle(handle, host);
                    changed |= helper != handle;
                    ldc.cst = helper;
                }
            }
        }
        if (!changed) return classBytes;
        // The static call takes the same arguments the virtual one did, so the frames and stack sizes still hold.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** A cheap text check, so a class that names no result member on a Forge type is never parsed. */
    private static boolean mayCallResult(byte[] classBytes) {
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        return text.contains("Result") && text.contains("net/minecraftforge/");
    }

    private static boolean rewriteCall(MethodInsnNode call, Function<String, ClassNode> host) {
        int opcode = call.getOpcode();
        if (opcode != Opcodes.INVOKEVIRTUAL && opcode != Opcodes.INVOKEINTERFACE) return false;
        String helper = helperDescriptor(call.owner, call.name, call.desc, host);
        if (helper == null) return false;
        // Forge 26.2 turned some event classes into interfaces, so the transformer may have made the call one.
        call.setOpcode(Opcodes.INVOKESTATIC);
        call.owner = EVENT_RESULTS;
        call.desc = helper;
        call.itf = false;
        return true;
    }

    // A method reference such as RightClickBlock::getResult points at the static helper instead.
    private static Handle helperHandle(Handle handle, Function<String, ClassNode> host) {
        int tag = handle.getTag();
        if (tag != Opcodes.H_INVOKEVIRTUAL && tag != Opcodes.H_INVOKEINTERFACE) return handle;
        String helper = helperDescriptor(handle.getOwner(), handle.getName(), handle.getDesc(), host);
        return helper == null ? handle : new Handle(Opcodes.H_INVOKESTATIC, EVENT_RESULTS, handle.getName(), helper, false);
    }

    /** The helper's descriptor when the call is an old result member on a Forge event the host gives none, else null. */
    private static String helperDescriptor(String owner, String name, String desc, Function<String, ClassNode> host) {
        String helper = HELPERS.get(name + desc);
        if (helper == null || OWNER_PACKAGES.stream().noneMatch(owner::startsWith)) return null;
        return hostLacks(owner, name, desc, host) ? helper : null;
    }

    /** Whether the host's {@code owner} and its supertypes lack the member; offline, from the HasResult events list. */
    static boolean hostLacks(String owner, String name, String desc, Function<String, ClassNode> host) {
        if (host.apply(ForgeHostShape.ANCHOR) == null) {
            // No Forge 26.2 or 26.3 class declares hasResult.
            return name.equals("hasResult") || !HOST_RESULT_EVENTS.contains(owner);
        }
        return LegacyCancelBridge.hostLacks(owner, name, desc, host);
    }
}
