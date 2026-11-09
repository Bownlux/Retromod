/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodVersion;
import com.retromod.inplace.InPlaceStatus;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Does on a NeoForge dedicated server what Forge's dist cleaner did for a Forge mod's
 * {@code @OnlyIn(Dist.CLIENT)} members: removes them.
 *
 * <p>Forge deleted those members on the server, and its event bus only listed public methods.
 * NeoForge does not honor Forge's {@code @OnlyIn} and lists every method of a subscriber class, which
 * resolves each parameter type. A client-setup handler such as an MCreator dimension's sky-effects hook
 * then failed the whole mod's subscriber registration on the server with a missing
 * {@code net.minecraft.client} class, usually through the handler's private lambda. The marked
 * members are removed, and so is any private synthetic method, such as that lambda, that nothing
 * left in the class still uses. A class marked as a whole is left alone; nothing loads it on a
 * server unless the mod reaches it from common code, which Forge did not allow either.
 *
 * <p>NeoForge's own marker gets the same treatment, for mods built for NeoForge 1.21.1 or older.
 * FancyModLoader up to NeoForge 1.21.1 removed {@code net.neoforged.api.distmarker.OnlyIn(Dist.CLIENT)}
 * members on a server (its {@code RuntimeDistCleaner}); FML 10 to 12 only clean NeoForge's own
 * development build, so a 1.21.1 mod's client listener in a common subscriber class, such as
 * Create's world-render hook, stopped the server.
 *
 * <p>The loaders stripped at class load and never wrote anything. This writes the result into the
 * translated jar, in both modes, so the jar's manifest says it was made on a dedicated server: a
 * client's discovery pass holds such a jar back and export refuses it. Forge's own
 * {@code @OnlyIn} is redirected away on NeoForge, so no loader cleaner ever sees it there.
 */
public final class ForgeDistCleaner {

    private static final String ONLY_IN = "Lnet/minecraftforge/api/distmarker/OnlyIn;";
    private static final String DIST = "Lnet/minecraftforge/api/distmarker/Dist;";
    private static final String NEO_ONLY_IN = "Lnet/neoforged/api/distmarker/OnlyIn;";
    private static final String NEO_DIST = "Lnet/neoforged/api/distmarker/Dist;";

    /** The newest NeoForge whose loader still removed its own client-only members on a server. */
    static final String LAST_NEOFORGE_WITH_RUNTIME_CLEANER = "1.21.1";

    /** The client-only markers a translation removes members for. */
    public enum Markers {
        /** Nothing is removed. */
        NONE,
        /** Forge's marker only. */
        FORGE,
        /** Forge's and NeoForge's markers. */
        FORGE_AND_NEOFORGE
    }

    private ForgeDistCleaner() {}

    /**
     * What a translation removes: nothing off a NeoForge dedicated server, and NeoForge's marker only
     * for a mod built for NeoForge 1.21.1 or older.
     */
    public static Markers markersFor(boolean neoForgeHost, boolean dedicatedServer, Properties properties,
            String modSourceVersion) {
        if (!neoForgeDedicatedServer(neoForgeHost, dedicatedServer, properties)) return Markers.NONE;
        // An unknown build version is never old.
        boolean neoForgeMarker = modSourceVersion != null
                && RetromodVersion.compareMcVersions(modSourceVersion, LAST_NEOFORGE_WITH_RUNTIME_CLEANER) <= 0;
        return neoForgeMarker ? Markers.FORGE_AND_NEOFORGE : Markers.FORGE;
    }

    /**
     * Whether this launch is a NeoForge dedicated server. The side the loader reported wins; a loader
     * that reports none, such as NeoForge 1.20.2 to 1.20.4, leaves it to Retromod's own detection.
     */
    public static boolean neoForgeDedicatedServer(boolean neoForgeHost, boolean detectedServer, Properties properties) {
        if (!neoForgeHost) return false;
        String reported = properties == null ? null : properties.getProperty(InPlaceStatus.DIST_PROPERTY);
        if (InPlaceStatus.DIST_DEDICATED_SERVER.equals(reported)) return true;
        if (InPlaceStatus.DIST_CLIENT.equals(reported)) return false;
        return detectedServer;
    }

    /** The class without the client-only members the markers name, or the same array when it has none. */
    public static byte[] stripClientOnly(byte[] classBytes, Markers markers) {
        if (classBytes == null || markers == null || markers == Markers.NONE) return classBytes;
        boolean neoForge = markers == Markers.FORGE_AND_NEOFORGE;
        if (!mentions(classBytes, ONLY_IN) && !(neoForge && mentions(classBytes, NEO_ONLY_IN))) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = node.methods.removeIf(method -> clientOnly(method.visibleAnnotations, neoForge)
                || clientOnly(method.invisibleAnnotations, neoForge));
        changed |= node.fields.removeIf(field -> clientOnly(field.visibleAnnotations, neoForge)
                || clientOnly(field.invisibleAnnotations, neoForge));
        if (!changed) return classBytes;
        removeOrphanedSynthetics(node);
        ClassWriter writer = new ClassWriter(0); // whole members removed; remaining code is unchanged
        node.accept(writer);
        return writer.toByteArray();
    }

    /** A cheap text check, so a class without the marker is never parsed. */
    private static boolean mentions(byte[] classBytes, String descriptor) {
        return new String(classBytes, StandardCharsets.ISO_8859_1)
                .indexOf(descriptor.substring(1, descriptor.length() - 1)) >= 0;
    }

    private static boolean clientOnly(List<AnnotationNode> annotations, boolean neoForge) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            // Each marker names its own Dist enum.
            String dist = ONLY_IN.equals(annotation.desc) ? DIST
                    : neoForge && NEO_ONLY_IN.equals(annotation.desc) ? NEO_DIST : null;
            if (dist == null || annotation.values == null) continue;
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if ("value".equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof String[] value
                        && dist.equals(value[0]) && "CLIENT".equals(value[1])) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Removes private synthetic methods, such as lambda bodies, that no remaining code refers to. */
    private static void removeOrphanedSynthetics(ClassNode node) {
        boolean removed = true;
        while (removed) {
            Set<String> used = new HashSet<>();
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && call.owner.equals(node.name)) {
                        used.add(call.name + call.desc);
                    } else if (insn instanceof InvokeDynamicInsnNode indy) {
                        for (Object arg : indy.bsmArgs) {
                            if (arg instanceof Handle handle && handle.getOwner().equals(node.name)) {
                                used.add(handle.getName() + handle.getDesc());
                            }
                        }
                    }
                }
            }
            removed = node.methods.removeIf(method -> (method.access & Opcodes.ACC_SYNTHETIC) != 0
                    && (method.access & Opcodes.ACC_PRIVATE) != 0
                    && !used.contains(method.name + method.desc));
        }
    }
}
