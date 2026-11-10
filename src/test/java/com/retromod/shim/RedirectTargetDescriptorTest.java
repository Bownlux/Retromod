/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

/** A redirect to one of Retromod's own methods must still find it after the 26.x class moves rename the call's types. */
class RedirectTargetDescriptorTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
        transformer.clearSrgNameMappings();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"forge, 26.2", "forge, 26.3", "neoforge, 26.2", "neoforge, 26.3"})
    @DisplayName("Every Retromod method a redirect names with a renamed type is declared with the renamed descriptor")
    void renamedDescriptorsResolve(String loader, String host) throws IOException {
        register(loader, host);
        Remapper moves = new SimpleRemapper(transformer.getClassRedirects());
        List<String> unresolved = new ArrayList<>();
        int renamed = 0;
        for (Map.Entry<RetromodTransformer.MethodKey, RetromodTransformer.MethodTarget> redirect
                : transformer.getMethodRedirects().entrySet()) {
            RetromodTransformer.MethodTarget target = redirect.getValue();
            String callDesc = moves.mapMethodDesc(target.desc());
            if (!target.owner().startsWith("com/retromod/") || callDesc.equals(target.desc())) continue;
            renamed++;
            // An embedded copy is renamed with the mod; a class in Retromod's own jar keeps what it was compiled with.
            byte[] embedded = transformer.getSyntheticClasses().get(target.owner());
            byte[] bytes = embedded != null ? embedded : compiled(target.owner());
            if (bytes == null || !declares(bytes, target.name(), callDesc, embedded != null ? moves : null)) {
                unresolved.add(redirect.getKey().owner() + "." + redirect.getKey().name() + redirect.getKey().desc()
                        + " -> " + target.owner() + "." + target.name() + callDesc);
            }
        }
        assertTrue(renamed > 0, "the scan saw redirects whose types the class moves rename");
        assertEquals(List.of(), unresolved, "these calls would fail with NoSuchMethodError on " + loader + " " + host);
    }

    /** What the running game registers for this loader and host, its member names included. */
    private void register(String loader, String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        transformer.clearRedirectsForTesting();
        SrgToMojangMapper.getInstance().applyTo(transformer);
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            String type = shim.getModLoaderType();
            // NeoForge runs the Forge shims too, for Forge mods it bridges.
            boolean applies = type.equals("common") || type.equals(loader)
                    || (loader.equals("neoforge") && type.equals("forge"));
            if (applies && ShimRegistry.isAvailableOnHost(shim, host)) ShimRegistration.register(shim, transformer);
        }
    }

    private static boolean declares(byte[] bytes, String name, String desc, Remapper moves) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        for (MethodNode method : node.methods) {
            String declared = moves == null ? method.desc : moves.mapMethodDesc(method.desc);
            if (method.name.equals(name) && declared.equals(desc)) return true;
        }
        if (node.superName == null || !node.superName.startsWith("com/retromod/")) return false;
        byte[] parent = compiled(node.superName);
        return parent != null && declares(parent, name, desc, moves);
    }

    private static byte[] compiled(String name) throws IOException {
        try (InputStream in = RedirectTargetDescriptorTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            return in == null ? null : in.readAllBytes();
        }
    }
}
