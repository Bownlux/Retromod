/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipFile;

import static com.retromod.shim.neoforge.LegacyNeoForgeRegisterAdditionalBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Old RegisterAdditional listeners on NeoForge 1.21.11 and 26.x, which have only standalone models: the event name must resolve. */
class LegacyNeoForgeRegisterAdditionalBridgeTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void neoForgeTarget() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("On NeoForge 1.21.11 and 26.1 to 26.3 a Forge 1.20.1 and a NeoForge 1.21.1 listener both name the rebuilt event")
    void listenersNameTheRebuiltEvent() {
        for (String host : List.of("1.21.11", "26.1", "26.2", "26.3")) {
            registerHostShims(host);
            assertEquals("(L" + REBUILT + ";)V", listenerDesc(FORGE_OLD), "a Forge 1.20.1 listener on " + host);
            assertEquals("(L" + REBUILT + ";)V", listenerDesc(NEO_OLD), "a NeoForge 1.21.1 listener on " + host);
            assertTrue(transformer.getSyntheticClasses().containsKey(REBUILT), "the rebuilt event is embedded");
        }
    }

    @Test
    @DisplayName("NeoForge 1.21.10 and earlier hosts are left alone: the stand-in names Identifier, which 1.21.11 introduced")
    void earlierHostsKeepTheirNames() {
        registerHostShims("1.21.10");
        assertNotEquals("(L" + REBUILT + ";)V", listenerDesc(NEO_OLD));
        assertFalse(transformer.getSyntheticClasses().containsKey(REBUILT));
    }

    @Test
    @DisplayName("NeoForge 21.11.42 has standalone models and no RegisterAdditional")
    void realNeoForge12111() throws Exception {
        Path jar = Path.of(System.getProperty("user.home"),
                "Library/Application Support/PrismLauncher/libraries/net/neoforged/neoforge/21.11.42/neoforge-21.11.42-universal.jar");
        Assumptions.assumeTrue(Files.isRegularFile(jar), "no " + jar);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            assertTrue(hostDroppedRegisterAdditional(name -> zip.getEntry(name + ".class") == null ? null : new ClassNode()));
        }
    }

    // What RetromodNeoForge registers on the host, with the Forge chain NeoForge also runs for Forge mods.
    private void registerHostShims(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("neoforge", "forge", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
    }

    @Test
    @DisplayName("Only a NeoForge host without RegisterAdditional takes the bridge")
    void gate() {
        assertTrue(hostDroppedRegisterAdditional(name -> name.equals(STANDALONE) ? new ClassNode() : null), "NeoForge 21.4 and later");
        assertTrue(hostDroppedRegisterAdditional(name -> null), "offline: the 26.1 shim stands for the host");
        assertFalse(hostDroppedRegisterAdditional(name -> name.equals(NEO_OLD) ? new ClassNode() : null), "NeoForge 1.21.1 keeps its own");
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        LegacyNeoForgeRegisterAdditionalBridge.register(transformer, (Function<String, ClassNode>) name -> null);
        assertFalse(transformer.getSyntheticClasses().containsKey(REBUILT), "a Forge host keeps Retromod's Forge rebuild");
    }

    // A listener method taking the event, then the descriptor the transform gives it.
    private String listenerDesc(String event) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Models", null, "java/lang/Object", null);
        MethodVisitor listener = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onRegister", "(L" + event + ";)V", null, null);
        listener.visitCode();
        listener.visitInsn(RETURN);
        listener.visitMaxs(0, 0);
        listener.visitEnd();
        cw.visitEnd();
        transformer.setModSourceVersion(event.equals(FORGE_OLD) ? "1.20.1" : "1.21.1");
        try {
            ClassNode node = new ClassNode();
            new ClassReader(transformer.transformClass(cw.toByteArray(), "mod/Models.class")).accept(node, 0);
            return node.methods.stream().filter(m -> m.name.equals("onRegister")).findFirst().orElseThrow().desc;
        } finally {
            transformer.setModSourceVersion(null);
        }
    }
}
