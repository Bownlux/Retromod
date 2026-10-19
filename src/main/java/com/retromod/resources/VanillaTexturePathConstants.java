/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Repoints vanilla texture paths that a mod's code binds by name after Minecraft moved the file. */
public final class VanillaTexturePathConstants {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    private static final byte[] MARKER = "textures/".getBytes(StandardCharsets.UTF_8);
    private static final String VANILLA = "minecraft";
    private static final String QUALIFIED_PREFIX = VANILLA + ":textures/";
    private static final String PATH_PREFIX = "textures/";

    /** The resource id class in each namespace a class can reach this pass in. */
    private static final Set<String> RESOURCE_ID_CLASSES = Set.of(
            "net/minecraft/resources/Identifier",
            "net/minecraft/resources/ResourceLocation",
            "net/minecraft/util/ResourceLocation",
            "net/minecraft/class_2960");

    // Sheets a release split into several textures, with the first release without the sheet:
    // gui/icons.png and gui/widgets.png are in the 1.20.1 client and not the 1.20.2 one, and
    // environment/moon_phases.png is in 1.21.10 and became eight files in 1.21.11.
    private static final Map<String, String> SPLIT_SHEETS = Map.of(
            "gui/icons.png", "1.20.2",
            "gui/widgets.png", "1.20.2",
            "environment/moon_phases.png", "1.21.11");

    private static final Map<PackFormat, HostMoves> MOVES_BY_HOST = new ConcurrentHashMap<>();
    /** Paths already logged, so each unmoved texture is reported once. */
    private static final Set<String> REPORTED_PATHS = ConcurrentHashMap.newKeySet();

    /** A host's moves, split by whether the file at the end is the same art in the same layout. */
    private record HostMoves(Map<String, String> sameLayout, Map<String, String> newLayout) {
    }

    private VanillaTexturePathConstants() {
    }

    /** Rewrites moved vanilla texture paths in a class; returns the original array when there are none. */
    public static byte[] apply(byte[] classBytes, String hostVersion) {
        // A quick byte search skips every class that names no texture at all.
        if (classBytes == null || !contains(classBytes, MARKER)) return classBytes;
        PackFormat host = PackFormat.resourceTargetOrNull(hostVersion);
        if (host == null) return classBytes;
        HostMoves moves = hostMoves(host);

        // A mod that draws a vanilla texture builds its id from a string, such as
        // new ResourceLocation("textures/entity/bat.png"). When 26.1 moved most entity textures, and
        // 1.21.11 the sun, such a mod drew the missing texture.
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String constant)) continue;
                String path = vanillaTexturePath(constant);
                if (path == null || !buildsVanillaId(ldc, constant)) continue;
                // Rewrite the path and keep whatever prefix the string had.
                String moved = moves.sameLayout().get(path);
                if (moved != null) {
                    ldc.cst = constant.substring(0, constant.length() - path.length()) + moved;
                    changed = true;
                } else {
                    reportUnmoved(path, moves.newLayout().get(path), node.name, hostVersion);
                }
            }
        }
        if (!changed) return classBytes;
        // Only string constants changed, so the class is written back without recomputing frames.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Every texture path gone on a host with format {@code host}, mapped to where the same art is now. */
    static Map<String, String> movesOnHost(PackFormat host) {
        return hostMoves(host).sameLayout();
    }

    /** The host's moves, computed once per resource format. */
    private static HostMoves hostMoves(PackFormat host) {
        return MOVES_BY_HOST.computeIfAbsent(host, VanillaTexturePathConstants::computeHostMoves);
    }

    /** Follows the one-to-one moves in {@code texture-migrations.tsv} up to the host's resource format. */
    private static HostMoves computeHostMoves(PackFormat host) {
        List<VersionedTexturePathMappings.Migration> rows = VersionedTexturePathMappings.migrations().stream()
                .filter(row -> row.requiredFormat().compareTo(host) <= 0)
                .toList();
        Map<String, String> sameLayout = new HashMap<>();
        Map<String, String> newLayout = new HashMap<>();
        // The transform does not know which release a mod was built for, so only paths gone on the
        // host move: each source was checked absent from the vanilla clients of 1.20.1 through 26.3,
        // and a path a later row brought back is skipped, so a working reference never breaks.
        for (VersionedTexturePathMappings.Migration first : rows) {
            String source = first.source();
            if (sameLayout.containsKey(source) || newLayout.containsKey(source) || cameBackLater(rows, first)) {
                continue;
            }
            // Follow later moves of the same file to where it is now.
            String path = source;
            boolean keepsLayout = true;
            for (VersionedTexturePathMappings.Migration move : rows) {
                if (move.requiredFormat().compareTo(first.requiredFormat()) >= 0 && move.source().equals(path)) {
                    path = move.destination();
                    keepsLayout &= move.keepsLayout();
                }
            }
            // A move that ends on new art in another layout, such as the sign sheet 26.2 turned into a
            // smaller block sprite, is kept apart: old coordinates would draw the wrong parts of it.
            (keepsLayout ? sameLayout : newLayout).put(source, path);
        }
        return new HostMoves(Map.copyOf(sameLayout), Map.copyOf(newLayout));
    }

    /** Whether a later row moved another file onto the path {@code row} moved away from. */
    private static boolean cameBackLater(List<VersionedTexturePathMappings.Migration> rows,
                                         VersionedTexturePathMappings.Migration row) {
        for (VersionedTexturePathMappings.Migration other : rows) {
            if (other.destination().equals(row.source())
                    && other.requiredFormat().compareTo(row.requiredFormat()) > 0) {
                return true;
            }
        }
        return false;
    }

    /** The path under {@code textures/} a vanilla texture string names, or null. */
    private static String vanillaTexturePath(String constant) {
        String path;
        if (constant.startsWith(QUALIFIED_PREFIX)) {
            path = constant.substring(QUALIFIED_PREFIX.length());
        } else if (constant.startsWith(PATH_PREFIX)) {
            path = constant.substring(PATH_PREFIX.length());
        } else {
            return null;
        }
        return path.endsWith(".png") && path.matches("[a-z0-9_./-]+") ? path : null;
    }

    /** Whether the string goes straight into a resource id built in the {@code minecraft} namespace. */
    private static boolean buildsVanillaId(LdcInsnNode ldc, String constant) {
        // The next instruction must build a resource id: a constructor, or a static factory that
        // returns its own class, taking only strings.
        AbstractInsnNode next = nextReal(ldc.getNext());
        if (!(next instanceof MethodInsnNode call) || !RESOURCE_ID_CLASSES.contains(call.owner)) return false;
        boolean constructor = call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>");
        boolean factory = call.getOpcode() == Opcodes.INVOKESTATIC
                && Type.getReturnType(call.desc).getSort() == Type.OBJECT
                && Type.getReturnType(call.desc).getInternalName().equals(call.owner);
        if (!constructor && !factory) return false;
        Type[] arguments = Type.getArgumentTypes(call.desc);
        for (Type argument : arguments) {
            if (!argument.getDescriptor().equals("Ljava/lang/String;")) return false;
        }
        // A one-argument form reads a bare path as vanilla's. A two-argument form must take the
        // constant "minecraft" pushed just before the path.
        if (arguments.length == 1) return true;
        if (arguments.length != 2 || constant.startsWith(QUALIFIED_PREFIX)) return false;
        AbstractInsnNode previous = previousReal(ldc.getPrevious());
        return previous instanceof LdcInsnNode namespace && VANILLA.equals(namespace.cst);
    }

    /** The next instruction that is not a label, line number or frame. */
    private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
        while (instruction != null && instruction.getOpcode() < 0) instruction = instruction.getNext();
        return instruction;
    }

    /** The previous instruction that is not a label, line number or frame. */
    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        while (instruction != null && instruction.getOpcode() < 0) instruction = instruction.getPrevious();
        return instruction;
    }

    /** Logs, once per path, a vanilla texture the class binds that has no one path to move to. */
    private static void reportUnmoved(String path, String redrawnAt, String className, String hostVersion) {
        // The host only has the art redrawn in another layout.
        if (redrawnAt != null) {
            if (REPORTED_PATHS.add(path)) {
                LOGGER.info("{} binds minecraft:textures/{}, which Minecraft {} only has redrawn as textures/{} "
                        + "in another layout. The old coordinates would draw the wrong parts of it, so that "
                        + "drawing shows the missing texture.", className, path, hostVersion, redrawnAt);
            }
            return;
        }
        // A release cut the sheet into several sprites, such as gui/icons.png.
        String splitIn = SPLIT_SHEETS.get(path);
        if (splitIn == null || RetromodVersion.compareMcVersions(hostVersion, splitIn) < 0) return;
        if (REPORTED_PATHS.add(path)) {
            LOGGER.info("{} binds minecraft:textures/{}, which Minecraft {} split into several textures. "
                    + "No single path replaces it, so that drawing shows the missing texture.",
                    className, path, splitIn);
        }
    }

    /** Whether {@code needle} appears anywhere in {@code haystack}. */
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
