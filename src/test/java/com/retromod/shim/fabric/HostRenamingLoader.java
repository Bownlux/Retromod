/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs an embedded runtime helper against test stand-ins that carry the host's class names.
 *
 * <p>Minecraft and DataFixerUpper are not on the test classpath, so stand-ins are written as
 * ordinary test classes and renamed here as they load. The helper and every class under the stub
 * package are defined by this loader, so their names resolve to each other and not to the test
 * classpath's copies.
 */
final class HostRenamingLoader extends ClassLoader {

    private final String stubPackage;
    private final Map<String, String> hostNames;
    private final Map<String, String> stubByHostName = new HashMap<>();
    private final String helper;

    /**
     * @param stubPackage internal package prefix of the stand-ins, ending in a slash
     * @param hostNames   stand-in internal name to the host internal name it plays
     * @param helper      the embedded runtime class under test
     */
    HostRenamingLoader(String stubPackage, Map<String, String> hostNames, Class<?> helper) {
        super(HostRenamingLoader.class.getClassLoader());
        this.stubPackage = stubPackage;
        this.hostNames = hostNames;
        this.helper = helper.getName();
        hostNames.forEach((stub, host) -> stubByHostName.put(host.replace('/', '.'), stub));
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) return loaded;
            String source = stubByHostName.getOrDefault(name, name.replace('.', '/'));
            boolean ours = stubByHostName.containsKey(name) || source.startsWith(stubPackage)
                    || name.equals(helper);
            if (!ours) return super.loadClass(name, resolve);
            byte[] bytes = renamed(source);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private byte[] renamed(String internalName) throws ClassNotFoundException {
        try (InputStream in = getParent().getResourceAsStream(internalName + ".class")) {
            if (in == null) throw new ClassNotFoundException(internalName);
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(in.readAllBytes()).accept(
                    new ClassRemapper(writer, new SimpleRemapper(hostNames)), 0);
            return writer.toByteArray();
        } catch (IOException e) {
            throw new ClassNotFoundException(internalName, e);
        }
    }
}
