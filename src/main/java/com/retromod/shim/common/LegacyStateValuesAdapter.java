/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.embedded.LegacyStateValues;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Keeps an older mod's {@code getValues()} call on a block or fluid state working. */
public final class LegacyStateValuesAdapter {

    /** The return shape of {@code getValues()} up to 1.20.4: each property to its value. */
    static final String IMMUTABLE_MAP = "()Lcom/google/common/collect/ImmutableMap;";
    /** The return shape from 1.20.5 to 1.21.11. */
    static final String MAP = "()Ljava/util/Map;";
    /** The return shape from 26.1: a stream of {@code Property.Value} records. */
    static final String STREAM = "()Ljava/util/stream/Stream;";

    // The method kept its name but changed its return type twice, so an old call fails with
    // NoSuchMethodError. MCreator mods hit it in every procedure that swaps one block for another
    // and copies the properties across, such as eating a slice of a cake. The shapes are oldest
    // first, so a call needs a repair when its shape comes before the host's.
    private static final List<String> SHAPES = List.of(IMMUTABLE_MAP, MAP, STREAM);

    static final String HELPER = "com/retromod/shim/common/embedded/LegacyStateValues";
    private static final String FROM_STREAM_DESC = "(Ljava/util/stream/Stream;)Ljava/util/Map;";
    private static final String GUAVA_MAP = "com/google/common/collect/ImmutableMap";
    private static final String COPY_OF_DESC = "(Ljava/util/Map;)Lcom/google/common/collect/ImmutableMap;";

    /** One namespace's spelling of the method and the state classes a mod calls it on. */
    record Names(String method, String stateHolder, Set<String> owners) {}

    // Both names are matched, because Fabric mods stay intermediary on hosts before 26.1. Only
    // calls on Minecraft's own state classes are rewritten.
    static final Names MOJANG = new Names("getValues",
            "net/minecraft/world/level/block/state/StateHolder",
            Set.of("net/minecraft/world/level/block/state/StateHolder",
                    "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase",
                    "net/minecraft/world/level/block/state/BlockState",
                    "net/minecraft/world/level/material/FluidState"));

    static final Names INTERMEDIARY = new Names("method_11656",
            "net/minecraft/class_2688",
            Set.of("net/minecraft/class_2688", "net/minecraft/class_4970$class_4971",
                    "net/minecraft/class_2680", "net/minecraft/class_3610"));

    /** A namespace together with the shape the host's {@code getValues()} has in it. */
    record HostShape(Names names, String desc) {}

    private static volatile List<HostShape> hostShapes;

    private LegacyStateValuesAdapter() {}

    /** Repairs the old calls in one class; {@code registerHelper} embeds the helper when a call needs it. */
    public static byte[] apply(byte[] classBytes, Consumer<String> registerHelper) {
        if (classBytes == null) return null;
        // A class that never names the method is returned as is, without parsing it.
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        if (!text.contains(MOJANG.method()) && !text.contains(INTERMEDIARY.method())) return classBytes;
        List<HostShape> shapes = hostShapes();
        if (shapes.isEmpty()) return classBytes;
        return apply(classBytes, shapes, registerHelper);
    }

    /** Repairs the old calls in one class against the given host shapes. */
    static byte[] apply(byte[] classBytes, List<HostShape> shapes, Consumer<String> registerHelper) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if (node.name.startsWith("net/minecraft/")) return classBytes;
        boolean changed = false;
        boolean usesHelper = false;
        for (MethodNode method : node.methods) {
            // Only direct calls change. A method reference to the old getValues is left alone.
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                HostShape host = shapeFor(call, shapes);
                if (host == null) continue;
                // Call the host's own shape, then convert the result back to the old type.
                usesHelper |= STREAM.equals(host.desc());
                method.instructions.insert(call, convertBack(call.desc, host.desc()));
                call.desc = host.desc();
                changed = true;
            }
        }
        if (!changed) return classBytes;
        if (usesHelper) registerHelper.accept(HELPER);
        // Each conversion consumes one reference and pushes one of the type the code already
        // expected, so the existing frames stay valid.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** The host shape this call must be moved to, or null when it already matches or is not ours. */
    private static HostShape shapeFor(MethodInsnNode call, List<HostShape> shapes) {
        int callShape = SHAPES.indexOf(call.desc);
        if (callShape < 0) return null;
        for (HostShape host : shapes) {
            if (call.name.equals(host.names().method()) && host.names().owners().contains(call.owner)
                    && callShape < SHAPES.indexOf(host.desc())) {
                return host;
            }
        }
        return null;
    }

    /** Turns the host's result back into what the mod's {@code oldDesc} returned. */
    private static InsnList convertBack(String oldDesc, String hostDesc) {
        // A stream becomes a map through LegacyStateValues, and a map is copied into an
        // ImmutableMap when the mod expects one.
        InsnList list = new InsnList();
        if (STREAM.equals(hostDesc)) {
            list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "fromValueStream", FROM_STREAM_DESC, false));
        }
        if (IMMUTABLE_MAP.equals(oldDesc)) {
            list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUAVA_MAP, "copyOf", COPY_OF_DESC, false));
        }
        return list;
    }

    /** The namespaces whose host changed {@code getValues()}, with the host's shape in each. */
    static List<HostShape> hostShapes() {
        List<HostShape> known = hostShapes;
        if (known != null) return known;
        // Ask the host's own state class in each namespace. When neither is readable, the run is
        // offline and the target version decides.
        String mojang = hostDesc(MOJANG);
        String intermediary = hostDesc(INTERMEDIARY);
        List<HostShape> needed = new ArrayList<>();
        if (mojang == null && intermediary == null) {
            needed.addAll(offlineShapes(RetromodVersion.TARGET_MC_VERSION));
        } else {
            addIfChanged(needed, MOJANG, mojang);
            addIfChanged(needed, INTERMEDIARY, intermediary);
        }
        hostShapes = List.copyOf(needed);
        return hostShapes;
    }

    /** Adds the namespace when the host's shape is newer than the oldest one. */
    private static void addIfChanged(List<HostShape> needed, Names names, String desc) {
        if (MAP.equals(desc) || STREAM.equals(desc)) needed.add(new HostShape(names, desc));
    }

    /** The host's {@code getValues()} descriptor in {@code names}, or null if its class is unreadable. */
    private static String hostDesc(Names names) {
        ClassNode stateHolder = ClassResourceInspector.read(names.stateHolder());
        return stateHolder == null ? null : hostDesc(stateHolder, names);
    }

    /** The descriptor of {@code getValues()} in a host state class, or "" when it has none by that name. */
    static String hostDesc(ClassNode stateHolder, Names names) {
        for (MethodNode method : stateHolder.methods) {
            if (method.name.equals(names.method()) && method.desc.startsWith("()")) return method.desc;
        }
        return "";
    }

    /** Offline, where the host is not readable, the target version decides the shape. */
    static List<HostShape> offlineShapes(String targetVersion) {
        // Before 1.20.5 the host still returns an ImmutableMap, so nothing needs a repair.
        if (targetVersion == null || RetromodVersion.compareMcVersions(targetVersion, "1.20.5") < 0) {
            return List.of();
        }
        if (RetromodVersion.compareMcVersions(targetVersion, "26.1") < 0) {
            return List.of(new HostShape(MOJANG, MAP), new HostShape(INTERMEDIARY, MAP));
        }
        // 26.1 and newer have no intermediary names left: Fabric mods are remapped before this runs.
        return List.of(new HostShape(MOJANG, STREAM));
    }
}
