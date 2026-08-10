/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;

/** Reads class structure from loader resources without defining or initializing the class. */
public final class ClassResourceInspector {

    private ClassResourceInspector() {}

    public static ClassNode read(String internalName) {
        ClassNode fabricNode = readFabricPreMixin(internalName);
        if (fabricNode != null) return fabricNode;

        String resource = internalName + ".class";
        ClassLoader own = ClassResourceInspector.class.getClassLoader();
        ClassLoader context = Thread.currentThread().getContextClassLoader();

        ClassNode node = readFrom(own, resource);
        if (node == null && context != own) {
            node = readFrom(context, resource);
        }
        if (node == null && internalName.startsWith("java/")) {
            node = readBootstrap(resource);
        }
        return node;
    }

    public static boolean exists(String internalName) {
        if (read(internalName) != null) return true;
        if (internalName.startsWith("java/")) {
            try {
                Class.forName(internalName.replace('/', '.'), false,
                        ClassLoader.getPlatformClassLoader());
                return true;
            } catch (ClassNotFoundException | LinkageError ignored) {
                return false;
            }
        }
        return false;
    }

    private static ClassNode readFrom(ClassLoader loader, String resource) {
        if (loader == null) return null;
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node,
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static ClassNode readBootstrap(String resource) {
        try (InputStream in = Object.class.getModule().getResourceAsStream(resource)) {
            if (in == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node,
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Fabric stores Minecraft under its raw obfuscated resource names. Ask Knot for the mapped,
     * pre-Mixin bytes so intermediary names can be inspected without defining the target class.
     */
    private static ClassNode readFabricPreMixin(String internalName) {
        try {
            ClassLoader loader = ClassResourceInspector.class.getClassLoader();
            Class<?> base = Class.forName(
                    "net.fabricmc.loader.impl.launch.FabricLauncherBase", false, loader);
            Object launcher = base.getMethod("getLauncher").invoke(null);
            if (launcher == null) return null;
            Class<?> api = Class.forName(
                    "net.fabricmc.loader.impl.launch.FabricLauncher", false, loader);
            byte[] bytes = (byte[]) api.getMethod("getClassByteArray", String.class, boolean.class)
                    .invoke(launcher, internalName.replace('/', '.'), true);
            if (bytes == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node,
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
