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

    /**
     * Largest class file worth inspecting. The rest of the codebase reads class bytes through
     * {@link com.retromod.util.ZipSecurity#safeReadAllBytes}, and this path was the exception:
     * it handed the raw stream to ASM, which grows a buffer to whatever the stream produces. The
     * resource can come from a mod's own jar on a flat class loader, so the size is not ours to
     * trust. Minecraft's largest class is comfortably under this.
     */
    private static final long MAX_CLASS_BYTES = 8L * 1024 * 1024;

    private static ClassNode readFrom(ClassLoader loader, String resource) {
        if (loader == null) return null;
        try (InputStream in = loader.getResourceAsStream(resource)) {
            return parse(in);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static ClassNode readBootstrap(String resource) {
        try (InputStream in = Object.class.getModule().getResourceAsStream(resource)) {
            return parse(in);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Read a bounded number of bytes, then parse structure only. */
    private static ClassNode parse(InputStream in) throws IOException {
        if (in == null) return null;
        byte[] bytes = com.retromod.util.ZipSecurity.safeReadAllBytes(in, MAX_CLASS_BYTES);
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
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
