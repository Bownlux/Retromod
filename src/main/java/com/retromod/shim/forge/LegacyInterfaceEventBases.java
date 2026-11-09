/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.LoaderTypeKinds;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Moves a mod's own event off a Forge 1.20.1 event class that Forge 26.2 made an interface, onto a rebuilt class. */
public final class LegacyInterfaceEventBases {

    static final String EVENT_ROOT = RebuiltApiClasses.ROOT + "forge/event/";
    // Each old base with the rebuilt class that keeps its constructor and getters, as checked on Forge 26.2 and 26.3.
    static final Map<String, String> BASES = Map.of(
            "net/minecraftforge/event/entity/EntityEvent", EVENT_ROOT + "LegacyEntityEvent",
            "net/minecraftforge/event/entity/living/LivingEvent", EVENT_ROOT + "LegacyLivingEvent",
            "net/minecraftforge/event/entity/player/PlayerEvent", EVENT_ROOT + "LegacyPlayerEvent",
            "net/minecraftforge/event/level/LevelEvent", EVENT_ROOT + "LegacyLevelEvent",
            "net/minecraftforge/event/level/BlockEvent", EVENT_ROOT + "LegacyBlockEvent");
    private static final String INHERITABLE = "net/minecraftforge/eventbus/api/event/InheritableEvent";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    // The bases this target made interfaces, while the registrations last.
    private static final Map<String, String> ACTIVE = new ConcurrentHashMap<>();

    private LegacyInterfaceEventBases() {}

    /** Registers the rebuilt class of each old base the host made an interface. */
    public static void register(RetromodTransformer t) {
        Map<String, String> enabled = new java.util.HashMap<>();
        BASES.forEach((old, rebuilt) -> {
            if (interfaceOnHost(old)) enabled.put(old, rebuilt);
        });
        if (enabled.isEmpty()) return;
        enabled.values().forEach(rebuilt -> RebuiltApiClasses.registerTree(t, rebuilt));
        ACTIVE.putAll(enabled);
        t.onRegistrationReset(() -> ACTIVE.keySet().removeAll(enabled.keySet()));
    }

    /** Whether the type is an interface on the host; offline, whether the checked table lists it for this Forge target. */
    static boolean interfaceOnHost(String type) {
        ClassNode host = ClassResourceInspector.read(type);
        if (host != null) return (host.access & Opcodes.ACC_INTERFACE) != 0;
        return LoaderTypeKinds.tabledInterface(type);
    }

    /** The class moved onto the rebuilt base, or the same array when it extends none of the old bases. */
    public static byte[] apply(byte[] classBytes) {
        if (ACTIVE.isEmpty() || classBytes == null) return classBytes;
        // The header names the superclass, so most classes are skipped without parsing them.
        ClassReader reader = new ClassReader(classBytes);
        String old = reader.getSuperName();
        String rebuilt = old == null ? null : ACTIVE.get(old);
        if (rebuilt == null || (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0) return classBytes;
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        // A mixin into the host's own event mirrors its target, which Mixin checks.
        if (hasAnnotation(node.visibleAnnotations) || hasAnnotation(node.invisibleAnnotations)) return classBytes;

        node.superName = rebuilt;
        if (node.signature != null) node.signature = LegacyEventBaseAdapter.rebaseSignature(node.signature, old, rebuilt);
        // EventBus 7 passes listeners down from inheritable parents, as the old bus did through superclasses.
        if (!node.interfaces.contains(INHERITABLE)) {
            node.interfaces.add(INHERITABLE);
            if (node.signature != null) node.signature += "L" + INHERITABLE + ";";
        }
        // The super(...) call and super.getEntity() style calls now name the rebuilt class.
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals(old)) {
                    call.owner = rebuilt;
                    call.itf = false;
                }
            }
        }
        // Only names change, so the class keeps its own frames.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (MIXIN.equals(annotation.desc)) return true;
        }
        return false;
    }
}
