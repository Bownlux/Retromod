/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.retromod.shim.common.LegacyVanillaGapsBridge.*;
import static com.retromod.shim.common.LegacyVanillaGapsOlderHosts.*;
import static org.junit.jupiter.api.Assertions.*;

/** The vanilla gaps reach NeoForge and Forge hosts before 26.1 through helpers that link there, and only where the host lacks them. */
class LegacyVanillaGapsOlderHostsTest {

    record Call(int opcode, String owner, String name, String desc) {}

    static final String L_RL = "L" + RESOURCE_LOCATION + ";";
    static final String MONSTER = "net/minecraft/world/entity/monster/Monster";

    /** Calls a Forge 1.20.1 mod makes that a 1.21.1 host lacks. */
    static final List<Call> OLD_CALLS = List.of(
            new Call(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "getUseDuration", "()I"),
            new Call(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "getAttributeModifiers", "(" + L_SLOT + ")" + MULTIMAP),
            new Call(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "getEnchantmentTags", "()Lnet/minecraft/nbt/ListTag;"),
            new Call(Opcodes.INVOKESTATIC, ENCHANTMENT_HELPER, "getEnchantmentId", "(" + L_TAG + ")" + L_RL),
            new Call(Opcodes.INVOKESTATIC, ENCHANTMENT_HELPER, "getEnchantmentLevel", "(" + L_TAG + ")I"),
            new Call(Opcodes.INVOKESTATIC, ENTITY_TYPE, "updateCustomEntityTag", "(" + L_LEVEL + L_PLAYER + L_ENTITY + L_TAG + ")V"),
            new Call(Opcodes.INVOKEVIRTUAL, ENTITY, "getPassengersRidingOffset", "()D"),
            new Call(Opcodes.INVOKEVIRTUAL, BLOCK, "getCloneItemStack", "(Lnet/minecraft/world/level/BlockGetter;" + L_POS + L_STATE + ")" + L_STACK),
            new Call(Opcodes.INVOKEVIRTUAL, CRASH_REPORT, "getFriendlyReport", "()Ljava/lang/String;"),
            new Call(Opcodes.INVOKESTATIC, EXTRA_CODECS, "validate",
                    "(Lcom/mojang/serialization/Codec;Ljava/util/function/Function;)Lcom/mojang/serialization/Codec;"),
            new Call(Opcodes.INVOKESTATIC, EXTRA_CODECS, "lazyInitializedCodec", "(Ljava/util/function/Supplier;)Lcom/mojang/serialization/Codec;"),
            new Call(Opcodes.INVOKESTATIC, PACK, "readMetaAndCreate", OLD_READ_META),
            new Call(Opcodes.INVOKESPECIAL, PATH_PACK, "<init>", "(Ljava/lang/String;Ljava/nio/file/Path;Z)V"),
            new Call(Opcodes.INVOKEVIRTUAL, ENCHANTMENT, "getFullname", "(I)" + L_COMPONENT),
            new Call(Opcodes.INVOKESTATIC, RESOURCE_LOCATION, "of", "(Ljava/lang/String;C)" + L_RL),
            new Call(Opcodes.INVOKESTATIC, ROOT_UTIL, "identityStrategy", "()Lit/unimi/dsi/fastutil/Hash$Strategy;"),
            new Call(Opcodes.INVOKEVIRTUAL, GAME_MODE, "getPickRange", "()F"),
            new Call(Opcodes.INVOKEVIRTUAL, CUBE, "compile", OLD_CUBE_COMPILE),
            new Call(Opcodes.INVOKEVIRTUAL, GUI_GRAPHICS, "blitNineSliced", "(" + L_RL + "IIIIIIIIII)V"),
            new Call(Opcodes.INVOKEVIRTUAL, DISPLAY_INFO, "getFrame", "()Lnet/minecraft/advancements/AdvancementType;"),
            new Call(Opcodes.INVOKEVIRTUAL, LOCAL_PLAYER, "getEntityReach", "()D"));

    private String previousTarget;
    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.setModSourceVersion("1.20.1");
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        transformer.setModSourceVersion(null);
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
    }

    @Test
    @DisplayName("on a 1.21.1 host each old call reaches a helper built on that host's shapes, or the member that replaced it")
    void everyCallIsBridgedOn1211() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyVanillaGapsOlderHosts.register(transformer, host1211()::get);

        byte[] transformed = transformer.transformClass(oldCalls(OLD_CALLS), "test/older/Old");
        List<String> refs = refs(transformed);

        assertTrue(refs.contains(WORLD + ".getUseDuration(" + L_STACK + ")I"), refs.toString());
        assertTrue(refs.contains(WORLD + ".getAttributeModifiers(" + L_STACK + L_SLOT + ")" + MULTIMAP), refs.toString());
        assertTrue(refs.contains(BEFORE26 + ".getEnchantmentTags(" + L_STACK + ")Lnet/minecraft/nbt/ListTag;"), refs.toString());
        assertTrue(refs.contains(BEFORE26 + ".getEnchantmentId(" + L_TAG + ")" + L_RL), refs.toString());
        assertTrue(refs.contains(BEFORE26 + ".getEnchantmentLevel(" + L_TAG + ")I"), refs.toString());
        assertTrue(refs.contains(BEFORE26 + ".updateCustomEntityTag(" + L_LEVEL + L_PLAYER + L_ENTITY + L_TAG + ")V"), refs.toString());
        assertTrue(refs.contains(WORLD + ".getPassengersRidingOffset(" + L_ENTITY + ")D"), refs.toString());
        assertTrue(refs.contains(BEFORE26 + ".getCloneItemStack(L" + BLOCK + ";Lnet/minecraft/world/level/BlockGetter;" + L_POS + L_STATE + ")"
                + L_STACK), refs.toString());
        assertTrue(refs.contains(WORLD + ".getFriendlyReport(L" + CRASH_REPORT + ";)Ljava/lang/String;"), refs.toString());
        assertTrue(refs.contains(WORLD + ".validate(Lcom/mojang/serialization/Codec;Ljava/util/function/Function;)Lcom/mojang/serialization/Codec;"),
                refs.toString());
        assertTrue(refs.contains(WORLD + ".lazyInitializedCodec(Ljava/util/function/Supplier;)Lcom/mojang/serialization/Codec;"), refs.toString());
        assertTrue(refs.contains(WORLD + ".readMetaAndCreate" + OLD_READ_META), refs.toString());
        assertTrue(refs.contains(WORLD + ".pathPackResources(Ljava/lang/String;Ljava/nio/file/Path;Z)L" + PATH_PACK + ";"), refs.toString());
        assertTrue(refs.contains(WORLD + ".getFullname(L" + ENCHANTMENT + ";I)" + L_COMPONENT), refs.toString());
        assertTrue(refs.contains(RESOURCE_LOCATION + ".bySeparator(Ljava/lang/String;C)" + L_RL), refs.toString());
        assertTrue(refs.contains(WORLD + ".identityStrategy()Lit/unimi/dsi/fastutil/Hash$Strategy;"), refs.toString());
        assertTrue(refs.contains(CLIENT + ".getPickRange(L" + GAME_MODE + ";)F"), refs.toString());
        assertTrue(refs.contains(CLIENT + ".compile(L" + CUBE + ";" + OLD_CUBE_COMPILE.substring(1)), refs.toString());
        assertTrue(refs.contains(CLIENT_BEFORE26 + ".blitNineSliced(L" + GUI_GRAPHICS + ";" + L_RL + "IIIIIIIIII)V"), refs.toString());
        assertTrue(refs.contains(DISPLAY_INFO + ".getType()Lnet/minecraft/advancements/AdvancementType;"), refs.toString());
        assertTrue(refs.contains(LOCAL_PLAYER + ".entityInteractionRange()D"), refs.toString());
        for (String helper : List.of(WORLD, CLIENT, BEFORE26, CLIENT_BEFORE26)) {
            assertTrue(transformer.getSyntheticClasses().containsKey(helper), helper + " must ship with the mod");
        }
        // The 26.x helper ships without the methods this host cannot link.
        ClassNode world = node(transformer.getSyntheticClasses().get(WORLD));
        assertFalse(world.methods.stream().anyMatch(m -> m.name.equals("getEnchantmentId")), "names Identifier");
        assertFalse(world.methods.stream().anyMatch(m -> m.name.equals("handleInteract")), "names the 26.1 attack packet");
    }

    @Test
    @DisplayName("removed fields and classes read through the helper or the class that replaced them on 1.21.1")
    void fieldsAndClassesOn1211() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyVanillaGapsOlderHosts.register(transformer, host1211()::get);

        byte[] transformed = transformer.transformClass(oldFieldsAndClasses(), "test/older/Fields");

        assertTrue(refs(transformed).contains(WORLD + ".latency(Ljava/lang/Object;)I"), refs(transformed).toString());
        assertTrue(refs(transformed).contains(WORLD + ".formattingCodec()Lcom/mojang/serialization/Codec;"), refs(transformed).toString());
        assertTrue(fields(transformed).contains(SCREEN + ".MENU_BACKGROUND"), fields(transformed).toString());
        assertTrue(types(transformed).contains(MESSAGE_SCREEN), types(transformed).toString());
        assertTrue(types(transformed).contains(JORBIS_STREAM), types(transformed).toString());
    }

    @Test
    @DisplayName("a mob's super call to its parent's riding offset takes the helper too, as an MCreator mob makes it, here and on 26.x")
    void subclassSuperCallReachesTheHelper() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyVanillaGapsOlderHosts.register(transformer, host1211()::get);
        // The transform reads the mob's parents the way it reads a mod jar's and the host's classes.
        ClassWriter monster = new ClassWriter(0);
        monster.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MONSTER, null, ENTITY, null);
        byte[] mob = mobOverride();
        Map<String, byte[]> classes = Map.of(MONSTER, monster.toByteArray(), "test/older/Mob", mob);

        List<String> refs;
        try (var scope = transformer.pushJarClassBytesProvider(classes::get)) {
            refs = refs(transformer.transformClass(mob, "test/older/Mob"));
        }

        assertTrue(refs.contains(WORLD + ".getPassengersRidingOffset(" + L_ENTITY + ")D"), refs.toString());

        // 26.x takes the same call through the 26.1 class moves.
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        Map<String, ClassNode> host = new HashMap<>();
        host.put(ENTITY, named(ENTITY));
        LegacyVanillaGapsBridge.register(transformer, host::get, false);
        try (var scope = transformer.pushJarClassBytesProvider(classes::get)) {
            refs = refs(transformer.transformClass(mob, "test/older/Mob"));
        }
        assertTrue(refs.contains(WORLD + ".getPassengersRidingOffset(" + L_ENTITY + ")D"), "on 26.3: " + refs);
    }

    @Test
    @DisplayName("a host that still has a member keeps the mod's call to it")
    void hostMembersAreKept() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        Map<String, ClassNode> host = host1211();
        for (Call call : OLD_CALLS) host.get(call.owner()).methods.add(new MethodNode(Opcodes.ACC_PUBLIC, call.name(), call.desc(), null, null));
        LegacyVanillaGapsOlderHosts.register(transformer, host::get);

        List<String> refs = refs(transformer.transformClass(oldCalls(OLD_CALLS), "test/older/Kept"));

        for (Call call : OLD_CALLS) {
            assertTrue(refs.contains(call.owner() + "." + call.name() + call.desc()), call + " in " + refs);
        }
    }

    @Test
    @DisplayName("a helper method that names what the host lacks is left out, so the call stays as written")
    void unlinkedHelpersAreNotUsed() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        Map<String, ClassNode> host = host1211();
        host.remove("net/minecraft/world/entity/EntityAttachments");
        LegacyVanillaGapsOlderHosts.register(transformer, host::get);

        List<String> refs = refs(transformer.transformClass(oldCalls(OLD_CALLS), "test/older/Unlinked"));

        assertTrue(refs.contains(ENTITY + ".getPassengersRidingOffset()D"), refs.toString());
        ClassNode world = node(transformer.getSyntheticClasses().get(WORLD));
        assertFalse(world.methods.stream().anyMatch(m -> m.name.equals("getPassengersRidingOffset")), "the embedded copy must not name it");
    }

    @Test
    @DisplayName("on a 1.21.11 host the 26.x helpers answer the reads whose shapes it has, and helpers built on 1.21.11 the rest")
    void lateShapesOn12111() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        LegacyVanillaGapsOlderHosts.register(transformer, host12111()::get);
        String message = "(" + L_COMPONENT + ")V";
        List<Call> calls = List.of(
                new Call(Opcodes.INVOKESTATIC, ENCHANTMENT_HELPER, "getEnchantmentId", "(" + L_TAG + ")" + L_ID),
                new Call(Opcodes.INVOKESTATIC, ENCHANTMENT_HELPER, "getEnchantmentLevel", "(" + L_TAG + ")I"),
                new Call(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "getEnchantmentTags", "()Lnet/minecraft/nbt/ListTag;"),
                new Call(Opcodes.INVOKESTATIC, ENTITY_TYPE, "updateCustomEntityTag", "(" + L_LEVEL + L_PLAYER + L_ENTITY + L_TAG + ")V"),
                new Call(Opcodes.INVOKEVIRTUAL, BLOCK, "getCloneItemStack", "(Lnet/minecraft/world/level/BlockGetter;" + L_POS + L_STATE + ")" + L_STACK),
                new Call(Opcodes.INVOKEVIRTUAL, GUI_GRAPHICS, "blitNineSliced", "(" + L_ID + "IIIIIIIIII)V"),
                new Call(Opcodes.INVOKEVIRTUAL, ENTITY, "sendSystemMessage", message));

        List<String> refs = refs(transformer.transformClass(oldCalls(calls), "test/older/Late"));

        assertTrue(refs.contains(WORLD + ".getEnchantmentId(" + L_TAG + ")" + L_ID), refs.toString());
        assertTrue(refs.contains(WORLD + ".getEnchantmentLevel(" + L_TAG + ")I"), refs.toString());
        assertTrue(refs.contains(WORLD + ".getEnchantmentTags(" + L_STACK + ")Lnet/minecraft/nbt/ListTag;"), refs.toString());
        assertTrue(refs.contains(WORLD + ".updateCustomEntityTag(" + L_LEVEL + L_PLAYER + L_ENTITY + L_TAG + ")V"), refs.toString());
        assertTrue(refs.contains(WORLD + ".getCloneItemStack(L" + BLOCK + ";Lnet/minecraft/world/level/BlockGetter;" + L_POS + L_STATE + ")"
                + L_STACK), refs.toString());
        assertTrue(refs.contains(CLIENT_BEFORE26 + ".blitNineSliced(L" + GUI_GRAPHICS + ";" + L_ID + "IIIIIIIIII)V"), refs.toString());
        // 1.21.11 players have no sendSystemMessage of their own, so the 26.x helper's call does not link.
        assertTrue(refs.contains(BEFORE26 + ".sendSystemMessage(" + L_ENTITY + L_COMPONENT + ")V"), refs.toString());
    }

    @Test
    @DisplayName("a 1.19.2 mod's block state read asks the registry's lookup on 1.21.1 and the registry itself on 1.21.11")
    void blockStateReadFollowsTheRegistryShape() {
        transformer.setModSourceVersion("1.19.2");
        List<Call> read = List.of(new Call(Opcodes.INVOKESTATIC, NBT_UTILS, "readBlockState", "(" + L_TAG + ")" + L_STATE));
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyVanillaGapsOlderHosts.register(transformer, host1211()::get);
        assertTrue(refs(transformer.transformClass(oldCalls(read), "test/older/Read")).contains(BEFORE26 + ".readBlockState(" + L_TAG + ")"
                + L_STATE), "a 1.21.1 registry is not its own lookup");

        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        LegacyVanillaGapsOlderHosts.register(transformer, host12111()::get);
        assertTrue(refs(transformer.transformClass(oldCalls(read), "test/older/Read")).contains(WORLD + ".readBlockState(" + L_TAG + ")"
                + L_STATE), "a 1.21.11 registry is its own lookup");
    }

    @Test
    @DisplayName("a block override's super call to the old clone stack stays for the block override repair from 1.21.5, here and on 26.x")
    void cloneStackSuperCallsStay() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        LegacyVanillaGapsOlderHosts.register(transformer, host12111()::get);
        assertSuperCallKept(transformer.transformClass(blockOverride(), "test/older/Block"));

        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        Map<String, ClassNode> host = new HashMap<>();
        host.put(BLOCK, named(BLOCK));
        LegacyVanillaGapsBridge.register(transformer, host::get, false);
        assertSuperCallKept(transformer.transformClass(blockOverride(), "test/older/Block"));
    }

    private static void assertSuperCallKept(byte[] transformed) {
        MethodInsnNode call = null;
        for (AbstractInsnNode insn : instructions(transformed)) {
            if (insn instanceof MethodInsnNode method && method.name.equals("getCloneItemStack")) call = method;
        }
        assertNotNull(call);
        // Sent to the helper, the call would reach the override again through the state's clone stack.
        assertEquals(Opcodes.INVOKESPECIAL, call.getOpcode(), call.owner + "." + call.name + call.desc);
        assertEquals(BLOCK, call.owner);
    }

    @Test
    @DisplayName("a 1.20.1 pack supplier lambda is built as a function of the pack id and wrapped in a supplier of the host's shape")
    void oldPackSupplierLambdasAreWrapped() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        Map<String, ClassNode> host = host1211();
        ClassNode supplier = named(LegacyPackSupplierLambdas.SUPPLIER);
        supplier.access |= Opcodes.ACC_INTERFACE;
        supplier.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "openPrimary", LegacyPackSupplierLambdas.OPEN_PRIMARY, null, null));
        supplier.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "openFull", LegacyPackSupplierLambdas.OPEN_FULL, null, null));
        host.put(LegacyPackSupplierLambdas.SUPPLIER, supplier);
        LegacyVanillaGapsOlderHosts.register(transformer, host::get);

        byte[] transformed = transformer.transformClass(supplierLambda(), "test/older/Packs");

        assertTrue(refs(transformed).contains(LegacyPackSupplierLambdas.ADAPTER + ".supplier" + LegacyPackSupplierLambdas.WRAP_DESC),
                refs(transformed).toString());
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyPackSupplierLambdas.ADAPTER));
        ClassNode adapter = node(transformer.getSyntheticClasses().get(LegacyPackSupplierLambdas.ADAPTER));
        assertTrue(adapter.methods.stream().anyMatch(m -> (m.name + m.desc).equals("openFull" + LegacyPackSupplierLambdas.OPEN_FULL)));
    }

    @Test
    @DisplayName("26.1 and newer hosts are left to the 26.1 class moves, and older ones to their own shims")
    void outsideTheRangeRegistersNothing() {
        for (String target : new String[]{"26.1", "26.3", "1.20.4"}) {
            transformer.clearRedirectsForTesting();
            RetromodVersion.TARGET_MC_VERSION = target;
            LegacyVanillaGapsOlderHosts.register(transformer, host1211()::get);

            List<String> refs = refs(transformer.transformClass(oldCalls(OLD_CALLS), "test/older/Outside"));

            assertTrue(refs.contains(ITEM_STACK + ".getUseDuration()I"), target + ": " + refs);
            assertFalse(transformer.getSyntheticClasses().containsKey(BEFORE26), target);
        }
    }

    @Test
    @DisplayName("each NeoForge and Forge shim calls it and every call registers, so a later call wins a shared key")
    void everyCallRegisters() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyVanillaGapsOlderHosts.register(transformer, host1211()::get);
        int synthetics = transformer.getSyntheticClasses().size();
        transformer.registerMethodRedirect(ITEM_STACK, "getUseDuration", "()I", "test/Other", "getUseDuration", "(" + L_STACK + ")I", true);

        LegacyVanillaGapsOlderHosts.register(transformer, host1211()::get);

        assertEquals(synthetics, transformer.getSyntheticClasses().size());
        assertTrue(refs(transformer.transformClass(oldCalls(OLD_CALLS), "test/older/Twice")).contains(WORLD + ".getUseDuration(" + L_STACK + ")I"));
    }

    @Test
    @DisplayName("with no Minecraft class to read, as offline with no host jar, nothing registers")
    void noHostRegistersNothing() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyVanillaGapsOlderHosts.register(transformer, name -> null);

        assertTrue(transformer.getSyntheticClasses().isEmpty(), transformer.getSyntheticClasses().keySet().toString());
        assertTrue(refs(transformer.transformClass(oldCalls(OLD_CALLS), "test/older/NoHost")).contains(ITEM_STACK + ".getUseDuration()I"));
    }

    @Test
    @DisplayName("the host read from jars, as the CLI reads --host-jar, registers the same helpers and redirects as the host read directly")
    void offlineHostMatchesTheDirectHost(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        Map<String, ClassNode> host = host1211();
        java.nio.file.Path jar = dir.resolve("host.jar");
        try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            for (ClassNode node : host.values()) {
                ClassWriter writer = new ClassWriter(0);
                node.version = Opcodes.V17;
                if (node.superName == null && (node.access & Opcodes.ACC_INTERFACE) == 0) node.superName = "java/lang/Object";
                node.accept(writer);
                out.putNextEntry(new java.util.jar.JarEntry(node.name + ".class"));
                out.write(writer.toByteArray());
            }
        }
        LegacyVanillaGapsOlderHosts.register(transformer, host::get);
        Map<String, byte[]> direct = new HashMap<>(transformer.getSyntheticClasses());
        List<String> directRefs = refs(transformer.transformClass(oldCalls(OLD_CALLS), "test/older/Direct"));

        transformer.clearRedirectsForTesting();
        transformer.setModSourceVersion("1.20.1");
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, null)) {
            com.retromod.core.ClassResourceInspector.setOfflineHost(loader);
            LegacyVanillaGapsOlderHosts.register(transformer);
        } finally {
            com.retromod.core.ClassResourceInspector.setOfflineHost(null);
        }

        assertEquals(direct.keySet(), transformer.getSyntheticClasses().keySet());
        for (String name : direct.keySet()) {
            assertArrayEquals(direct.get(name), transformer.getSyntheticClasses().get(name), name);
        }
        assertEquals(directRefs, refs(transformer.transformClass(oldCalls(OLD_CALLS), "test/older/Direct")));
    }

    @Test
    @DisplayName("pruning drops a method naming a missing class with every method that calls it, and a class whose static setup cannot link")
    void pruningFollowsCallers() {
        Map<String, ClassNode> host = linkedHost();
        host.remove("net/minecraft/world/entity/decoration/ItemFrame");

        RebuiltHostLinks.Pruned pruned = RebuiltHostLinks.prune(List.of(WORLD), host::get);

        assertFalse(pruned.has(WORLD, "getFrame", "(" + L_STACK + ")Lnet/minecraft/world/entity/decoration/ItemFrame;"));
        assertFalse(pruned.has(WORLD, "isFramed", "(" + L_STACK + ")Z"), "isFramed calls getFrame");
        assertTrue(pruned.has(WORLD, "getXpRepairRatio", "(" + L_STACK + ")F"));

        // A member the helper cannot reach, or calls the wrong way, counts as missing, as the JVM would find it.
        Map<String, ClassNode> strict = linkedHost();
        strict.get("net/minecraft/world/entity/EntityAttachments").methods.forEach(m -> m.access |= Opcodes.ACC_STATIC);
        strict.get("net/minecraft/CrashReport").methods.forEach(m -> m.access = Opcodes.ACC_PRIVATE);
        RebuiltHostLinks.Pruned strictly = RebuiltHostLinks.prune(List.of(WORLD), strict::get);
        assertFalse(strictly.has(WORLD, "getPassengersRidingOffset", "(" + L_ENTITY + ")D"), "the host's get is static");
        assertFalse(strictly.has(WORLD, "getFriendlyReport", "(L" + CRASH_REPORT + ";)Ljava/lang/String;"), "the host's report is private");

        host.remove(SLOT_ACCESS);
        assertFalse(RebuiltHostLinks.prune(List.of(WORLD), host::get).hasClass(WORLD), "its static slot implements SlotAccess");
        assertFalse(RebuiltHostLinks.linksOn(WORLD, host::get));
        assertTrue(RebuiltHostLinks.linksOn(WORLD, linkedHost()::get));
    }

    /** A host with every class and member the helpers name, so each links; tests take away what a host version lacks. */
    static Map<String, ClassNode> linkedHost() {
        Map<String, ClassNode> host = new HashMap<>();
        for (String root : List.of(WORLD, CLIENT, BEFORE26, CLIENT_BEFORE26)) {
            for (String name : RebuiltApiClasses.tree(root)) {
                ClassNode helper = node(RebuiltApiClasses.read(name));
                List<RebuiltHostLinks.Ref> refs = new ArrayList<>();
                for (MethodNode method : helper.methods) refs.addAll(RebuiltHostLinks.refs(method));
                for (String type : helper.interfaces) refs.add(RebuiltHostLinks.Ref.type(type));
                if (helper.superName != null) refs.add(RebuiltHostLinks.Ref.type(helper.superName));
                for (FieldNode field : helper.fields) refs.add(RebuiltHostLinks.Ref.type(Type.getType(field.desc).getSort() == Type.OBJECT
                        ? Type.getType(field.desc).getInternalName() : "java/lang/Object"));
                for (RebuiltHostLinks.Ref ref : refs) addRef(host, ref);
            }
        }
        return host;
    }

    private static void addRef(Map<String, ClassNode> host, RebuiltHostLinks.Ref ref) {
        if (!RebuiltHostLinks.isHost(ref.owner())) return;
        ClassNode node = host.computeIfAbsent(ref.owner(), LegacyVanillaGapsOlderHostsTest::named);
        if (ref.name() == null) return;
        if (ref.itf()) node.access |= Opcodes.ACC_INTERFACE;
        int access = Opcodes.ACC_PUBLIC | (ref.isStatic() ? Opcodes.ACC_STATIC : 0);
        if (ref.field()) {
            node.fields.add(new FieldNode(access, ref.name(), ref.desc(), null, null));
        } else {
            node.methods.add(new MethodNode(access, ref.name(), ref.desc(), null, null));
        }
        // A member's descriptor types are classes the host has too.
        Type type = ref.field() ? Type.getType(ref.desc()) : Type.getMethodType(ref.desc());
        List<Type> named = new ArrayList<>(ref.field() ? List.of(type) : List.of(type.getArgumentTypes()));
        if (!ref.field()) named.add(type.getReturnType());
        for (Type each : named) {
            Type element = each.getSort() == Type.ARRAY ? each.getElementType() : each;
            if (element.getSort() == Type.OBJECT && RebuiltHostLinks.isHost(element.getInternalName())) {
                host.computeIfAbsent(element.getInternalName(), LegacyVanillaGapsOlderHostsTest::named);
            }
        }
    }

    /** The 1.21.1 shape: no Identifier, typed entity data or 1.21.5 tag reads, and the members the bridge renames to. */
    static Map<String, ClassNode> host1211() {
        Map<String, ClassNode> host = linkedHost();
        for (String missing : List.of(IDENTIFIER, "net/minecraft/world/item/component/TypedEntityData", UTIL,
                "net/minecraft/world/level/storage/TagValueOutput", "net/minecraft/network/protocol/game/ServerboundAttackPacket")) {
            host.remove(missing);
        }
        host.get("net/minecraft/nbt/CompoundTag").methods.removeIf(m -> m.name.endsWith("Or"));
        withOldOwners(host);
        return host;
    }

    /** The 1.21.11 shape: Identifier, typed entity data and the 1.21.5 tag reads, with a registry that is its own lookup. */
    static Map<String, ClassNode> host12111() {
        Map<String, ClassNode> host = linkedHost();
        for (String missing : List.of(RESOURCE_LOCATION, "net/minecraft/world/item/component/CustomData")) host.remove(missing);
        host.get(GUI_GRAPHICS).methods.removeIf(m -> m.desc.startsWith("(" + L_RL));
        host.get(PLAYER).methods.removeIf(m -> m.name.equals("sendSystemMessage"));
        host.get("net/minecraft/nbt/Tag").methods.removeIf(m -> m.name.equals("getAsString"));
        host.get("net/minecraft/nbt/CompoundTag").methods.removeIf(m -> m.name.equals("getInt"));
        for (ClassNode node : host.values()) node.methods.removeIf(m -> m.name.equals("asLookup"));
        host.get("net/minecraft/core/Registry").interfaces.add(HOLDER_LOOKUP);
        host.get(BLOCK).methods.removeIf(m -> m.name.equals("getCloneItemStack"));
        withOldOwners(host);
        return host;
    }

    /** Every class an old call names, without the old member, and the members the bridge renames to. */
    private static void withOldOwners(Map<String, ClassNode> host) {
        for (Call call : OLD_CALLS) host.computeIfAbsent(call.owner(), LegacyVanillaGapsOlderHostsTest::named);
        for (String type : List.of(SERVER_PLAYER, STYLE, SCREEN, MESSAGE_SCREEN, JORBIS_STREAM, MINECRAFT)) {
            host.computeIfAbsent(type, LegacyVanillaGapsOlderHostsTest::named);
        }
        String id = host.containsKey(IDENTIFIER) ? IDENTIFIER : RESOURCE_LOCATION;
        host.get(id).methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bySeparator", "(Ljava/lang/String;C)L" + id + ";", null, null));
        host.get(DISPLAY_INFO).methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "getType", "()Lnet/minecraft/advancements/AdvancementType;", null, null));
        host.get(LOCAL_PLAYER).methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "entityInteractionRange", "()D", null, null));
        host.get(SCREEN).fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "MENU_BACKGROUND", "L" + id + ";", null, null));
    }

    private static ClassNode named(String name) {
        ClassNode node = new ClassNode();
        node.name = name;
        return node;
    }

    /** A class whose one method makes each call with default arguments and drops each result. */
    private static byte[] oldCalls(List<Call> calls) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/older/Old", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        for (Call call : calls) {
            boolean constructor = call.name().equals("<init>");
            if (constructor) {
                mv.visitTypeInsn(Opcodes.NEW, call.owner());
                mv.visitInsn(Opcodes.DUP);
            } else if (call.opcode() != Opcodes.INVOKESTATIC) {
                mv.visitInsn(Opcodes.ACONST_NULL);
            }
            for (Type argument : Type.getArgumentTypes(call.desc())) pushDefault(mv, argument);
            mv.visitMethodInsn(call.opcode(), call.owner(), call.name(), call.desc(), false);
            Type result = constructor ? Type.getObjectType(call.owner()) : Type.getReturnType(call.desc());
            if (result.getSize() > 0) mv.visitInsn(result.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Reads of the player's latency and the style codec, the dirt background, and the dirt screen and Ogg stream types. */
    private static byte[] oldFieldsAndClasses() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/older/Fields", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(L" + SERVER_PLAYER + ";Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, SERVER_PLAYER, "latency", "I");
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, STYLE, "FORMATTING_CODEC", "Lcom/mojang/serialization/Codec;");
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, SCREEN, "BACKGROUND_LOCATION", L_RL);
        mv.visitInsn(Opcodes.POP);
        for (String type : new String[]{DIRT_SCREEN, OGG_STREAM}) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, type);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A mod's mob that overrides the old riding offset and adds to its parent's, as MCreator writes it. */
    private static byte[] mobOverride() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/older/Mob", null, MONSTER, null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "getPassengersRidingOffset", "()D", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MONSTER, "getPassengersRidingOffset", "()D", false);
        mv.visitInsn(Opcodes.DCONST_1);
        mv.visitInsn(Opcodes.DADD);
        mv.visitInsn(Opcodes.DRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A mod's block that overrides the 1.20.1 clone stack and returns its parent's. */
    private static byte[] blockOverride() {
        String clone = "(Lnet/minecraft/world/level/BlockGetter;" + L_POS + L_STATE + ")" + L_STACK;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/older/Block", null, BLOCK, null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "getCloneItemStack", clone, null, null);
        mv.visitCode();
        for (int slot = 0; slot <= 3; slot++) mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BLOCK, "getCloneItemStack", clone, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A 1.20.1 pack supplier written as a lambda of the pack id, as Tectonic adds its built-in packs. */
    private static byte[] supplierLambda() {
        String supplier = LegacyPackSupplierLambdas.SUPPLIER;
        String open = LegacyPackSupplierLambdas.OLD_OPEN;
        String body = "(Ljava/nio/file/Path;Ljava/lang/String;)L" + LegacyPackSupplierLambdas.RESOURCES + ";";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/older/Packs", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "supplier", "(Ljava/nio/file/Path;)L" + supplier + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInvokeDynamicInsn("open", "(Ljava/nio/file/Path;)L" + supplier + ";",
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                Type.getMethodType(open), new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "test/older/Packs", "lambda$supplier$0", body, false),
                Type.getMethodType(open));
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        MethodVisitor lambda = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "lambda$supplier$0", body, null, null);
        lambda.visitCode();
        lambda.visitInsn(Opcodes.ACONST_NULL);
        lambda.visitInsn(Opcodes.ARETURN);
        lambda.visitMaxs(0, 0);
        lambda.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void pushDefault(MethodVisitor mv, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> mv.visitInsn(Opcodes.ICONST_0);
            case Type.FLOAT -> mv.visitInsn(Opcodes.FCONST_0);
            case Type.LONG -> mv.visitInsn(Opcodes.LCONST_0);
            case Type.DOUBLE -> mv.visitInsn(Opcodes.DCONST_0);
            default -> mv.visitInsn(Opcodes.ACONST_NULL);
        }
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> refs(byte[] bytes) {
        List<String> refs = new ArrayList<>();
        for (AbstractInsnNode insn : instructions(bytes)) {
            if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
        }
        return refs;
    }

    private static List<String> fields(byte[] bytes) {
        List<String> fields = new ArrayList<>();
        for (AbstractInsnNode insn : instructions(bytes)) {
            if (insn instanceof FieldInsnNode field) fields.add(field.owner + "." + field.name);
        }
        return fields;
    }

    private static Set<String> types(byte[] bytes) {
        Set<String> types = new java.util.HashSet<>();
        for (AbstractInsnNode insn : instructions(bytes)) {
            if (insn instanceof TypeInsnNode type) types.add(type.desc);
        }
        return types;
    }

    private static List<AbstractInsnNode> instructions(byte[] bytes) {
        List<AbstractInsnNode> all = new ArrayList<>();
        for (MethodNode method : node(bytes).methods) method.instructions.forEach(all::add);
        return all;
    }
}
