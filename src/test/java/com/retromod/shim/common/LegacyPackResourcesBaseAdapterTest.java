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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.retromod.shim.common.LegacyPackResourcesBaseAdapter.*;
import static org.junit.jupiter.api.Assertions.*;

/** A pack written for 26.2's AbstractPackResources is still a PackResources on 26.3, which moved it onto a metadata-only base. */
class LegacyPackResourcesBaseAdapterTest {

    private static final String HOST = "net/minecraft/server/packs/Host";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("With the base move alone, the 26.3 host's cast of an old pack to PackResources fails")
    void moveAloneLosesTheInterface(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, t -> {});
        Object pack = run.call("mod/Packs", "create", "yungsapi");
        assertThrows(ClassCastException.class, () -> run.call(HOST, "namespaces", pack));
    }

    @Test
    @DisplayName("With the adapter the old pack is a PackResources again and answers through its own methods")
    void oldPackStaysAPackResources(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, t -> register(t, name -> null));
        Object pack = run.call("mod/Packs", "create", "yungsapi");
        assertEquals("yungsapi:yungsapi", run.call(HOST, "namespaces", pack), "the location comes from the new base");
    }

    @Test
    @DisplayName("The pass turns on only where the host has the new base and not the old one; offline the target decides")
    void followsTheHost() {
        ClassNode present = new ClassNode();
        assertTrue(hostReplacedIt(name -> name.equals(NEW_BASE) ? present : null), "26.3");
        assertFalse(hostReplacedIt(name -> present), "a host that still has AbstractPackResources");
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertFalse(hostReplacedIt(name -> null));
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(hostReplacedIt(name -> null));
    }

    @Test
    @DisplayName("Mods built for 26.3, packs missing a resource method, mixins, and abstract packs of unknown mods stay as they are")
    void leavesOthersAlone() {
        byte[] full = pack("mod/Full", Opcodes.ACC_PUBLIC, RESOURCE_METHODS, null);
        assertSame(full, apply(full, "26.3"), "a 26.3 mod may extend the metadata base on purpose");
        assertEquals(List.of(RESOURCES), interfaces(apply(full, "1.21.1")));
        assertEquals(List.of(RESOURCES), interfaces(apply(full, null)), "a concrete pack with every method is old");
        byte[] once = apply(full, "1.21.1");
        assertSame(once, apply(once, "1.21.1"), "a second pass finds the interface present");

        byte[] partial = pack("mod/Partial", Opcodes.ACC_PUBLIC, RESOURCE_METHODS.subList(0, 2), null);
        assertSame(partial, apply(partial, "1.21.1"), "a concrete pack without getNamespaces could not answer the host");
        byte[] mixin = pack("mod/PackMixin", Opcodes.ACC_PUBLIC, RESOURCE_METHODS, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertSame(mixin, apply(mixin, "1.21.1"));
        byte[] base = pack("mod/BasePack", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, List.of(), null);
        assertSame(base, apply(base, null), "with no version, an abstract pack may be a 26.3 metadata reader");
        assertEquals(List.of(RESOURCES), interfaces(apply(base, "1.21.1")), "an old abstract pack leaves the methods to subclasses");
    }

    private BridgeHarness.Run run(Path dir, Consumer<RetromodTransformer> register) throws Exception {
        Consumer<RetromodTransformer> all = t -> {
            t.registerClassRedirect(OLD_BASE, NEW_BASE);
            register.accept(t);
            t.setModSourceVersion("1.21.1");
        };
        Map<String, byte[]> mod = BridgeHarness.transform("26.3", all,
                BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), withShared(OLD_262)), "mod/"));
        return new BridgeHarness.Run(BridgeHarness.compile(dir.resolve("host"), withShared(HOST_263)), mod);
    }

    /** A pack class on the new base declaring the given resource methods, each returning nothing useful. */
    private static byte[] pack(String name, int access, List<String> methods, String annotation) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, access, name, null, NEW_BASE, null);
        if (annotation != null) writer.visitAnnotation(annotation, false).visitEnd();
        for (String method : methods) {
            int paren = method.indexOf('(');
            MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, method.substring(0, paren), method.substring(paren), null, null);
            mv.visitCode();
            if (method.endsWith(")V")) {
                mv.visitInsn(Opcodes.RETURN);
            } else {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> interfaces(byte[] bytes) {
        return List.of(new ClassReader(bytes).getInterfaces());
    }

    private static Map<String, String> withShared(Map<String, String> sources) {
        Map<String, String> all = new java.util.HashMap<>(SHARED);
        all.putAll(sources);
        return all;
    }

    /** Types that are the same on 26.2 and 26.3. */
    private static final Map<String, String> SHARED = Map.of(
            "net/minecraft/server/packs/PackType.java", """
                    package net.minecraft.server.packs;
                    public enum PackType { CLIENT_RESOURCES, SERVER_DATA }""",
            "net/minecraft/resources/Identifier.java", """
                    package net.minecraft.resources;
                    public record Identifier(String namespace, String path) {}""",
            "net/minecraft/server/packs/resources/IoSupplier.java", """
                    package net.minecraft.server.packs.resources;
                    public interface IoSupplier<T> { T get() throws java.io.IOException; }""",
            "net/minecraft/server/packs/PackLocationInfo.java", """
                    package net.minecraft.server.packs;
                    public record PackLocationInfo(String id) {}""");

    /** 26.2's pack types, and a mod pack written like YUNG's API's. */
    private static final Map<String, String> OLD_262 = Map.of(
            "net/minecraft/server/packs/PackResources.java", """
                    package net.minecraft.server.packs;
                    import java.io.InputStream;
                    import java.util.Set;
                    import net.minecraft.resources.Identifier;
                    import net.minecraft.server.packs.resources.IoSupplier;
                    public interface PackResources extends AutoCloseable {
                        IoSupplier<InputStream> getRootResource(String... path);
                        IoSupplier<InputStream> getResource(PackType type, Identifier id);
                        void listResources(PackType type, String namespace, String path, ResourceOutput output);
                        Set<String> getNamespaces(PackType type);
                        PackLocationInfo location();
                        @Override void close();
                        interface ResourceOutput extends java.util.function.BiConsumer<Identifier, IoSupplier<InputStream>> {}
                    }""",
            "net/minecraft/server/packs/AbstractPackResources.java", """
                    package net.minecraft.server.packs;
                    public abstract class AbstractPackResources implements PackResources {
                        private final PackLocationInfo location;
                        protected AbstractPackResources(PackLocationInfo location) { this.location = location; }
                        @Override public PackLocationInfo location() { return location; }
                    }""",
            "mod/ModPack.java", """
                    package mod;
                    import java.io.InputStream;
                    import java.util.Set;
                    import net.minecraft.resources.Identifier;
                    import net.minecraft.server.packs.AbstractPackResources;
                    import net.minecraft.server.packs.PackLocationInfo;
                    import net.minecraft.server.packs.PackType;
                    import net.minecraft.server.packs.resources.IoSupplier;
                    public class ModPack extends AbstractPackResources {
                        ModPack(PackLocationInfo location) { super(location); }
                        public IoSupplier<InputStream> getRootResource(String... path) { return null; }
                        public IoSupplier<InputStream> getResource(PackType type, Identifier id) { return null; }
                        public void listResources(PackType type, String namespace, String path, ResourceOutput output) {}
                        public Set<String> getNamespaces(PackType type) { return Set.of(location().id()); }
                        public void close() {}
                    }""",
            "mod/Packs.java", """
                    package mod;
                    import net.minecraft.server.packs.PackLocationInfo;
                    public class Packs {
                        public static Object create(String id) { return new ModPack(new PackLocationInfo(id)); }
                    }""");

    /** 26.3's pack types, with a host class that uses a pack as the resource manager does. */
    private static final Map<String, String> HOST_263 = Map.of(
            "net/minecraft/server/packs/PackMetadataResources.java", """
                    package net.minecraft.server.packs;
                    import java.io.InputStream;
                    import net.minecraft.server.packs.resources.IoSupplier;
                    public interface PackMetadataResources extends AutoCloseable {
                        PackLocationInfo location();
                        IoSupplier<InputStream> getRootResource(String... path);
                        @Override void close();
                    }""",
            "net/minecraft/server/packs/PackResources.java", """
                    package net.minecraft.server.packs;
                    import java.io.InputStream;
                    import java.util.Set;
                    import net.minecraft.resources.Identifier;
                    import net.minecraft.server.packs.resources.IoSupplier;
                    public interface PackResources extends PackMetadataResources {
                        IoSupplier<InputStream> getResource(PackType type, Identifier id);
                        void listResources(PackType type, String namespace, String path, ResourceOutput output);
                        Set<String> getNamespaces(PackType type);
                        interface ResourceOutput extends java.util.function.BiConsumer<Identifier, IoSupplier<InputStream>> {}
                    }""",
            "net/minecraft/server/packs/AbstractPackMetadataResources.java", """
                    package net.minecraft.server.packs;
                    public abstract class AbstractPackMetadataResources implements PackMetadataResources {
                        private final PackLocationInfo location;
                        protected AbstractPackMetadataResources(PackLocationInfo location) { this.location = location; }
                        @Override public PackLocationInfo location() { return location; }
                    }""",
            "net/minecraft/server/packs/Host.java", """
                    package net.minecraft.server.packs;
                    public final class Host {
                        public static String namespaces(Object pack) {
                            PackResources resources = (PackResources) pack;
                            return resources.location().id() + ":" + String.join(",", resources.getNamespaces(PackType.SERVER_DATA));
                        }
                    }""");
}
