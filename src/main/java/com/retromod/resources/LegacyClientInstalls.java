/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/** Which of a translated Forge-family jar's mods run one of Retromod's client installs from their {@code @Mod} constructor. */
final class LegacyClientInstalls {

    // A Forge mod on NeoForge may keep Forge's annotation, so both count.
    private static final Set<String> MODS = Set.of("Lnet/minecraftforge/fml/common/Mod;", "Lnet/neoforged/fml/common/Mod;");

    private LegacyClientInstalls() {}

    /** The declared mod ids whose {@code @Mod} constructor calls {@code installer.install()}; null for none or another target. */
    static Set<String> modsInstalling(MigrationContext context, Collection<String> classNames, Function<String, byte[]> readClass,
            String installer, String firstHost) {
        return modsInstalling(context, classNames, readClass, installer, firstHost, MigrationContext.Loader.FORGE);
    }

    /** As {@link #modsInstalling(MigrationContext, Collection, Function, String, String)}, for an installer built for {@code loader}. */
    static Set<String> modsInstalling(MigrationContext context, Collection<String> classNames, Function<String, byte[]> readClass,
            String installer, String firstHost, MigrationContext.Loader loader) {
        // Only the loader the installer is built for, from its first host, gets it; the other loaders keep their own APIs.
        if (context.targetLoader() != loader || !context.targetAtLeast(firstHost)) return null;
        Set<String> declared = context.declaredNamespaces();
        if (declared.isEmpty()) return null;
        // An item definition naming a type no mod added fails whole, and the item shows the missing model. A type an
        // install adds exists only for a mod whose constructor runs it, which the embedded copy alone does not prove.
        byte[] installerName = installer.getBytes(StandardCharsets.ISO_8859_1);
        Set<String> installing = new LinkedHashSet<>();
        for (String name : classNames) {
            if (!name.endsWith(".class")) continue;
            byte[] bytes = readClass.apply(name);
            // Only a class that names the installer can call it, so the others are not parsed.
            if (bytes == null || !contains(bytes, installerName)) continue;
            String modId = installingModId(bytes, installer);
            if (modId != null && declared.contains(modId)) installing.add(modId);
        }
        return installing.isEmpty() ? null : installing;
    }

    /** The {@code @Mod} id of a class whose constructor calls the install, or null. */
    private static String installingModId(byte[] bytes, String installer) {
        ClassNode node = new ClassNode();
        try {
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException unreadable) {
            return null;
        }
        String modId = null;
        if (node.visibleAnnotations != null) {
            for (AnnotationNode annotation : node.visibleAnnotations) {
                if (MODS.contains(annotation.desc)) modId = annotationValue(annotation);
            }
        }
        if (modId == null) return null;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) continue;
            for (AbstractInsnNode insn : method.instructions) {
                // The installer under its own name, or relocated into a jar's embedded package.
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && (call.owner.equals(installer) || call.owner.endsWith("/" + installer))
                        && call.name.equals("install") && call.desc.equals("()V")) {
                    return modId;
                }
            }
        }
        return null;
    }

    private static String annotationValue(AnnotationNode annotation) {
        if (annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if ("value".equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof String value) return value;
        }
        return null;
    }

    /** {@code <mod>:<path>}: the item's own mod when it installs, otherwise the first mod that does. */
    static String typeId(String itemNamespace, Set<String> mods, String path) {
        String mod = mods.contains(itemNamespace) ? itemNamespace : mods.iterator().next();
        return mod + ":" + path;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            for (int i = 0; i < needle.length; i++) {
                if (haystack[start + i] != needle[i]) continue outer;
            }
            return true;
        }
        return false;
    }
}
