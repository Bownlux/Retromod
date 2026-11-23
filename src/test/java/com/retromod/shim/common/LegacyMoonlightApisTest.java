/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The 26.x APIs Moonlight and Supplementaries reach first: schedules, pack metadata, loot types and registry queues. */
class LegacyMoonlightApisTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Schedules and the metadata serializer follow the host; offline the target decides")
    void gatesFollowTheHost() {
        assertTrue(LegacyScheduleBridge.hostRemovedIt(true, false));
        assertFalse(LegacyScheduleBridge.hostRemovedIt(true, true));
        assertTrue(LegacyMetadataSectionBridge.hostFoldedIt(true, false));
        assertFalse(LegacyMetadataSectionBridge.hostFoldedIt(true, true));
        RetromodVersion.TARGET_MC_VERSION = "1.21.3";
        assertFalse(LegacyMetadataSectionBridge.hostFoldedIt(false, false), "1.21.3 still has the serializer");
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        assertFalse(LegacyScheduleBridge.hostRemovedIt(false, false));
        assertTrue(LegacyMetadataSectionBridge.hostFoldedIt(false, false), "1.21.4 removed the serializer");
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        assertTrue(LegacyScheduleBridge.hostRemovedIt(false, false));
        assertTrue(LegacyMetadataSectionBridge.hostFoldedIt(false, false));
    }

    @Test
    @DisplayName("The metadata stream reader gives the section, and null instead of an exception for a broken file")
    void metadataReaderNeverThrows() throws Throwable {
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("host"), Map.of(
                "net/minecraft/server/packs/metadata/MetadataSectionType.java", """
                        package net.minecraft.server.packs.metadata;
                        public class MetadataSectionType<T> {}""",
                "net/minecraft/server/packs/resources/ResourceMetadata.java", """
                        package net.minecraft.server.packs.resources;
                        import net.minecraft.server.packs.metadata.MetadataSectionType;
                        public interface ResourceMetadata {
                            <T> java.util.Optional<T> getSection(MetadataSectionType<T> type);
                            static ResourceMetadata fromJsonStream(java.io.InputStream in) throws java.io.IOException {
                                if (in.read() != '{') throw new java.io.IOException("not JSON");
                                return new ResourceMetadata() {
                                    @SuppressWarnings("unchecked")
                                    public <T> java.util.Optional<T> getSection(MetadataSectionType<T> type) { return java.util.Optional.of((T) "pack"); }
                                };
                            }
                        }"""));
        host.put(LegacyMetadataSectionBridge.HELPER, LegacyMetadataSectionBridge.generateHelper());
        BridgeHarness.Run run = new BridgeHarness.Run(host, Map.of());
        Object type = run.loadClass("net.minecraft.server.packs.metadata.MetadataSectionType").getConstructor().newInstance();
        assertEquals("pack", run.call(LegacyMetadataSectionBridge.HELPER, "metadataFromStream", type,
                new java.io.ByteArrayInputStream("{}".getBytes())));
        assertNull(run.call(LegacyMetadataSectionBridge.HELPER, "metadataFromStream", type,
                new java.io.ByteArrayInputStream("broken".getBytes())));
    }

    @Test
    @DisplayName("The metadata stream reader and the loot getType readers verify")
    void generatedHelpersVerify() throws Exception {
        verify(LegacyMetadataSectionBridge.generateHelper());
        verify(LegacyLootEntryTypeBridge.generateTypes());
        ClassNode types = read(LegacyLootEntryTypeBridge.generateTypes());
        for (String getter : List.of("typeOf", "functionTypeOf", "conditionTypeOf")) {
            assertTrue(types.methods.stream().anyMatch(m -> m.name.equals(getter)), getter);
        }
    }

    @Test
    @DisplayName("A registry queue's supplier runs under the key it registers its value with")
    void supplierRegistrationGetsItsKey() throws Exception {
        Map<String, byte[]> compiled = BridgeHarness.compile(dir, Map.of(
                "net/minecraft/resources/Identifier.java", "package net.minecraft.resources; public class Identifier {}",
                "net/minecraft/resources/ResourceKey.java",
                "package net.minecraft.resources; public class ResourceKey<T> { public Identifier identifier() { return null; } }",
                "net/minecraft/core/RegistrationInfo.java", "package net.minecraft.core; public class RegistrationInfo {}",
                "net/minecraft/core/WritableRegistry.java", """
                        package net.minecraft.core;
                        public interface WritableRegistry<T> {
                            Object register(net.minecraft.resources.ResourceKey<T> key, T value, RegistrationInfo info);
                        }""",
                "mod/Holder.java", """
                        package mod;
                        public class Holder<T> {
                            private final net.minecraft.resources.ResourceKey<T> id;
                            private final java.util.function.Supplier<T> supplier;
                            private T value;
                            public Holder(net.minecraft.resources.ResourceKey<T> id, java.util.function.Supplier<T> supplier) {
                                this.id = id;
                                this.supplier = supplier;
                            }
                            void initialize(net.minecraft.core.WritableRegistry<T> registry) {
                                this.value = this.supplier.get();
                                try {
                                    registry.register(this.id, this.value, null);
                                } finally {
                                    this.value = null;
                                }
                            }
                        }"""));
        byte[] holder = compiled.get("mod/Holder");
        byte[] stamped = SupplierRegistrationIdStamper.apply(holder, "test/Bridge");
        assertNotSame(holder, stamped);
        List<String> calls = BridgeHarness.calls(stamped, "initialize");
        int enter = calls.indexOf("test/Bridge.enter(Lnet/minecraft/resources/Identifier;)Ljava/lang/Object;");
        int get = calls.indexOf("java/util/function/Supplier.get()Ljava/lang/Object;");
        assertTrue(enter >= 0 && enter < get, "the key's id is set before the supplier runs: " + calls);
        assertTrue(calls.subList(get, calls.size()).contains("test/Bridge.leave(Ljava/lang/Object;)V"));

        // On 26.2 and newer the register call first goes through the processor type bridge, and still gets its id.
        ClassNode bridged = read(holder);
        for (MethodNode method : bridged.methods) {
            for (var insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("register")) {
                    call.setOpcode(org.objectweb.asm.Opcodes.INVOKESTATIC);
                    call.owner = LegacyRegistryMapCodecBridge.WRITABLE_REGISTER;
                    call.name = "registerWritable";
                    call.desc = LegacyRegistryMapCodecBridge.WRITABLE_HELPER_DESC;
                    call.itf = false;
                }
            }
        }
        org.objectweb.asm.ClassWriter out = new org.objectweb.asm.ClassWriter(0);
        bridged.accept(out);
        List<String> bridgedCalls = BridgeHarness.calls(SupplierRegistrationIdStamper.apply(out.toByteArray(), "test/Bridge"),
                "initialize");
        assertTrue(bridgedCalls.contains("test/Bridge.enter(Lnet/minecraft/resources/Identifier;)Ljava/lang/Object;"),
                "the bridged register is a registration too: " + bridgedCalls);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void verify(byte[] bytes) throws Exception {
        ClassNode node = read(bytes);
        for (MethodNode method : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
    }
}
