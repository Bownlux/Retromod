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
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.retromod.shim.common.LegacyPackSupplierAdapter.*;
import static org.junit.jupiter.api.Assertions.*;

/** A pack supplier written for 1.21.1 to 26.2 answers the openMetadata and openResources a 26.3 host calls. */
class LegacyPackSupplierAdapterTest {

    private static final String HOST = "net/minecraft/server/packs/repository/Host";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Without the adapter a 26.3 host reading an old supplier's metadata fails with AbstractMethodError")
    void oldSupplierFailsWithoutTheAdapter(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.1", t -> {});
        Object supplier = run.call("mod/Source", "supplier", "Locate replacer");
        assertThrows(AbstractMethodError.class, () -> run.call(HOST, "readMetadata", supplier, "yungsapi"));
    }

    @Test
    @DisplayName("A 26.3 host reads an old supplier's metadata from its primary pack and opens its full pack as a stream of one")
    void oldSupplierAnswersTheHost(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.1", t -> register(t, name -> null));
        Object supplier = run.call("mod/Source", "supplier", "Locate replacer");
        assertEquals("yungsapi:Locate replacer:primary:closed", run.call(HOST, "readMetadata", supplier, "yungsapi"),
                "the host reads the pack section from openPrimary's pack and closes it");
        assertEquals("yungsapi:full:with overlays", run.call(HOST, "open", supplier, "yungsapi", "with overlays"),
                "the stream holds the one pack openFull built from the host's metadata");
    }

    @Test
    @DisplayName("The adapter turns on only where the host's supplier lost openPrimary; offline the target decides")
    void followsTheHost() {
        assertFalse(hostReplacedIt(supplier("openPrimary", OPEN_PRIMARY, "openFull", OPEN_FULL)), "1.21.1 to 26.2 hosts");
        assertTrue(hostReplacedIt(supplier("openMetadata", OPEN_METADATA, "openResources", OPEN_RESOURCES)));
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertFalse(hostReplacedIt(null));
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(hostReplacedIt(null));
    }

    @Test
    @DisplayName("A mod built for 26.3 keeps its supplier as it is, and an old one gets each method once")
    void newerModsAreLeftAlone() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        register(transformer, name -> null);
        byte[] old = modSupplier("mod/Old", Opcodes.ACC_PUBLIC, null);
        transformer.setModSourceVersion("26.3");
        assertEquals(List.of("openPrimary", "openFull"), names(transformer.transformClass(old, "mod/Old")),
                "a 26.3 mod's openPrimary is its own method, not the old supplier's");
        transformer.setModSourceVersion("1.21.1");
        byte[] once = transformer.transformClass(old, "mod/Old");
        assertEquals(List.of("openPrimary", "openFull", "openMetadata", "openResources"), names(once));
        assertSame(once, apply(once, "1.21.1"), "a second pass finds both methods present");
    }

    @Test
    @DisplayName("Mixins, interfaces, and abstract classes of a mod with no version are left as they are")
    void leavesOthersAlone() {
        byte[] mixin = modSupplier("mod/SupplierMixin", Opcodes.ACC_PUBLIC, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertSame(mixin, apply(mixin, "1.21.1"), "a mixin's members would land on the host's supplier");
        byte[] iface = modSupplier("mod/OwnSupplier", Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null);
        assertSame(iface, apply(iface, "1.21.1"));
        byte[] base = modSupplier("mod/BaseSupplier", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, null);
        assertSame(base, apply(base, null), "an abstract class with no version may be a 26.3 base");
        assertNotSame(base, apply(base, "1.21.1"));
        byte[] concrete = modSupplier("mod/Concrete", Opcodes.ACC_PUBLIC, null);
        assertNotSame(concrete, apply(concrete, null), "a concrete class must answer the host, so its old methods are old");
    }

    private BridgeHarness.Run run(Path dir, String modVersion, Consumer<RetromodTransformer> register) throws Exception {
        Consumer<RetromodTransformer> withVersion = register.andThen(t -> t.setModSourceVersion(modVersion));
        Map<String, byte[]> mod = BridgeHarness.transform("26.3", withVersion,
                BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), OLD_1211), "mod/"));
        return new BridgeHarness.Run(BridgeHarness.compile(dir.resolve("host"), HOST_263), mod);
    }

    /** A host supplier interface declaring two methods. */
    private static ClassNode supplier(String first, String firstDesc, String second, String secondDesc) {
        ClassNode node = new ClassNode();
        node.name = SUPPLIER;
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, first, firstDesc, null, null));
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, second, secondDesc, null, null));
        return node;
    }

    /** A supplier class with concrete 1.21.1 methods that return null, optionally annotated. */
    private static byte[] modSupplier(String name, int access, String annotation) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, access, name, null, "java/lang/Object", new String[] {SUPPLIER});
        if (annotation != null) writer.visitAnnotation(annotation, false).visitEnd();
        for (String[] method : new String[][] {{"openPrimary", OPEN_PRIMARY}, {"openFull", OPEN_FULL}}) {
            MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, method[0], method[1], null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> names(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().map(m -> m.name).toList();
    }

    /** The 1.21.1 to 26.2 supplier shape, and a mod supplier written like YUNG's API's anonymous one. */
    private static final Map<String, String> OLD_1211 = Map.of(
            "net/minecraft/server/packs/PackLocationInfo.java", """
                    package net.minecraft.server.packs;
                    public record PackLocationInfo(String id) {}""",
            "net/minecraft/server/packs/PackResources.java", """
                    package net.minecraft.server.packs;
                    public interface PackResources extends AutoCloseable {
                        PackLocationInfo location();
                        String getMetadataSection(String section);
                        @Override void close();
                    }""",
            "net/minecraft/server/packs/repository/Pack.java", """
                    package net.minecraft.server.packs.repository;
                    import net.minecraft.server.packs.PackLocationInfo;
                    import net.minecraft.server.packs.PackResources;
                    public class Pack {
                        public record Metadata(String description) {}
                        public interface ResourcesSupplier {
                            PackResources openPrimary(PackLocationInfo location);
                            PackResources openFull(PackLocationInfo location, Metadata metadata);
                        }
                    }""",
            "mod/ModPack.java", """
                    package mod;
                    import net.minecraft.server.packs.PackLocationInfo;
                    import net.minecraft.server.packs.PackResources;
                    public class ModPack implements PackResources {
                        private final PackLocationInfo location;
                        private final String description;
                        private final String kind;
                        private boolean closed;
                        ModPack(PackLocationInfo location, String description, String kind) {
                            this.location = location;
                            this.description = description;
                            this.kind = kind;
                        }
                        public PackLocationInfo location() { return location; }
                        public String getMetadataSection(String section) {
                            return section.equals("pack") ? description + ":" + kind : null;
                        }
                        public void close() { closed = true; }
                        public boolean closed() { return closed; }
                        @Override public String toString() { return location.id() + ":" + kind; }
                    }""",
            "mod/Source.java", """
                    package mod;
                    import net.minecraft.server.packs.PackLocationInfo;
                    import net.minecraft.server.packs.PackResources;
                    import net.minecraft.server.packs.repository.Pack;
                    public class Source {
                        public static Pack.ResourcesSupplier supplier(String description) {
                            return new Pack.ResourcesSupplier() {
                                public PackResources openPrimary(PackLocationInfo location) {
                                    return new ModPack(location, description, "primary");
                                }
                                public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
                                    return new ModPack(location, description, "full:" + metadata.description());
                                }
                            };
                        }
                    }""");

    /** The 26.3 shape, with a host class that opens a pack the way 26.3's Pack does. */
    private static final Map<String, String> HOST_263 = Map.of(
            "net/minecraft/server/packs/PackLocationInfo.java", """
                    package net.minecraft.server.packs;
                    public record PackLocationInfo(String id) {}""",
            "net/minecraft/server/packs/PackMetadataResources.java", """
                    package net.minecraft.server.packs;
                    public interface PackMetadataResources extends AutoCloseable {
                        PackLocationInfo location();
                        String getMetadataSection(String section);
                        @Override void close();
                    }""",
            "net/minecraft/server/packs/PackResources.java", """
                    package net.minecraft.server.packs;
                    public interface PackResources extends PackMetadataResources {}""",
            "net/minecraft/server/packs/repository/Pack.java", """
                    package net.minecraft.server.packs.repository;
                    import java.util.stream.Stream;
                    import net.minecraft.server.packs.PackLocationInfo;
                    import net.minecraft.server.packs.PackMetadataResources;
                    import net.minecraft.server.packs.PackResources;
                    public class Pack {
                        public record Metadata(String description) {}
                        public interface ResourcesSupplier {
                            PackMetadataResources openMetadata(PackLocationInfo location);
                            Stream<PackResources> openResources(PackLocationInfo location, Metadata metadata);
                        }
                    }""",
            "net/minecraft/server/packs/repository/Host.java", """
                    package net.minecraft.server.packs.repository;
                    import java.util.stream.Collectors;
                    import net.minecraft.server.packs.PackLocationInfo;
                    import net.minecraft.server.packs.PackMetadataResources;
                    public final class Host {
                        public static String readMetadata(Pack.ResourcesSupplier supplier, String id) throws Exception {
                            PackMetadataResources opened;
                            String section;
                            try (PackMetadataResources resources = supplier.openMetadata(new PackLocationInfo(id))) {
                                opened = resources;
                                section = resources.location().id() + ":" + resources.getMetadataSection("pack");
                            }
                            boolean closed = (Boolean) opened.getClass().getMethod("closed").invoke(opened);
                            return section + (closed ? ":closed" : ":open");
                        }
                        public static String open(Pack.ResourcesSupplier supplier, String id, String description) {
                            return supplier.openResources(new PackLocationInfo(id), new Pack.Metadata(description))
                                    .map(Object::toString).collect(Collectors.joining(","));
                        }
                    }""");
}
