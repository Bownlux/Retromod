/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * 26.3 removed {@code LivingEntity.swinging}. A mod built for 26.2 that reads it, as a physics mod
 * does to react to the arm swing, failed with {@code NoSuchFieldError} on its first tick. The read
 * becomes {@code isSwinging()}, whichever class the bytecode names as the owner, while a write
 * and a mod's own {@code swinging} field are left as written.
 */
class LegacySwingingReadTest {

    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String PLAYER = "net/minecraft/world/entity/player/Player";
    private static final String CALLER = "test/mod/SwingReader";
    private static final String OWN_FIELD_MOB = "test/mod/OwnSwingingMob";
    private static final String OWN_FIELD_BABY = "test/mod/OwnSwingingBaby";
    private static final String PLAIN_MOB = "test/mod/PlainMob";

    private RetromodTransformer transformer;
    private final Map<String, byte[]> jar = new HashMap<>();

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacySwingBridge.registerSwingingRead(transformer);
        jar.put(PLAYER, HostStubs.plainClass(PLAYER, LIVING));
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a read through LivingEntity asks isSwinging()")
    void readThroughLivingEntity() {
        jar.put(CALLER, reader(CALLER, LIVING));
        assertEquals(List.of("call " + LIVING + ".isSwinging()Z"), accesses(transform(CALLER), "read"));
    }

    @Test
    @DisplayName("a read through Player, which javac names as the owner, asks isSwinging() too")
    void readThroughPlayer() {
        jar.put(CALLER, reader(CALLER, PLAYER));
        assertEquals(List.of("call " + LIVING + ".isSwinging()Z"), accesses(transform(CALLER), "read"));
    }

    @Test
    @DisplayName("a read through the mod's own entity, which inherits the flag, asks isSwinging()")
    void readThroughModEntity() {
        jar.put(PLAIN_MOB, HostStubs.plainClass(PLAIN_MOB, LIVING));
        jar.put(CALLER, reader(CALLER, PLAIN_MOB));
        assertEquals(List.of("call " + LIVING + ".isSwinging()Z"), accesses(transform(CALLER), "read"));
    }

    @Test
    @DisplayName("an entity's own swinging field keeps its reads, also from a subclass")
    void ownFieldKeepsItsReads() {
        jar.put(OWN_FIELD_MOB, entityWithOwnFlag(OWN_FIELD_MOB));
        jar.put(OWN_FIELD_BABY, HostStubs.plainClass(OWN_FIELD_BABY, OWN_FIELD_MOB));
        jar.put(CALLER, reader(CALLER, OWN_FIELD_BABY));

        assertEquals(List.of("get " + OWN_FIELD_MOB + ".swinging:Z"), accesses(transform(OWN_FIELD_MOB), "own"),
                "the entity reads the field it declares");
        assertEquals(List.of("get " + OWN_FIELD_BABY + ".swinging:Z"), accesses(transform(CALLER), "read"),
                "the read links to the declared field, not to the host's swing state");
    }

    @Test
    @DisplayName("a write stays as written, since the host has no way to set a swing from a flag")
    void writeIsLeftAlone() {
        jar.put(CALLER, writer(CALLER, PLAYER));
        assertEquals(List.of("put " + PLAYER + ".swinging:Z"), accesses(transform(CALLER), "write"));
    }

    @Test
    @DisplayName("the read runs: a player's isSwinging() answers it")
    void readRunsAgainstTheHost() throws Exception {
        jar.put(CALLER, reader(CALLER, PLAYER));
        HostStubs stubs = new HostStubs()
                .add(swingingLivingEntity())
                .add(PLAYER, HostStubs.plainClass(PLAYER, LIVING))
                .add(CALLER, transform(CALLER));

        Object player = stubs.load(PLAYER).getConstructor().newInstance();
        stubs.load(LIVING).getField("swingingNow").set(player, true);
        assertEquals(true, stubs.method(CALLER, "read").invoke(null, player));
        stubs.load(LIVING).getField("swingingNow").set(player, false);
        assertEquals(false, stubs.method(CALLER, "read").invoke(null, player));
    }

    @Test
    @DisplayName("the 26.3 shim registers the read bridge, and offline the host version decides")
    void registeredByTheTwentySixThreeShim() {
        jar.put(CALLER, reader(CALLER, PLAYER));
        String savedTarget = RetromodVersion.TARGET_MC_VERSION;
        try {
            assertEquals(List.of("get " + PLAYER + ".swinging:Z"), readsAfterShimFor("26.2"), "26.2 keeps the field");
            assertEquals(List.of("call " + LIVING + ".isSwinging()Z"), readsAfterShimFor("26.3"));
        } finally {
            RetromodVersion.TARGET_MC_VERSION = savedTarget;
        }
    }

    @Test
    @DisplayName("only a host with isSwinging() and without the field gets the bridge")
    void hostGate() {
        assertTrue(LegacySwingBridge.lostSwingingFlag(living(false, true)), "26.3");
        assertFalse(LegacySwingBridge.lostSwingingFlag(living(true, false)), "26.2 still has the field");
        assertFalse(LegacySwingBridge.lostSwingingFlag(living(false, false)),
                "a host with neither has nothing to read the flag from");
    }

    @Test
    @DisplayName("the real 26.2 and 26.3 jars: the field went, isSwinging() came, and no 26.3 class declares the field")
    void matchesTheClientJars() throws Exception {
        Path v262 = clientJar("26.2");
        Path v263 = clientJar("26.3");
        Assumptions.assumeTrue(v262 != null && v263 != null, "the 26.2 and 26.3 client jars are present");

        assertFalse(LegacySwingBridge.lostSwingingFlag(read(v262, LIVING)), "26.2 has the flag");
        assertTrue(LegacySwingBridge.lostSwingingFlag(read(v263, LIVING)), "26.3 reads it with isSwinging()");
        assertEquals(List.of(), classesDeclaringSwinging(v263),
                "a 26.3 class with its own swinging field would make the hierarchy match capture a host field");

        Path neoForge = Path.of(System.getProperty("user.home", ""), "Library/Application Support/PrismLauncher/"
                + "libraries/net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar");
        if (Files.isRegularFile(neoForge)) {
            assertTrue(LegacySwingBridge.lostSwingingFlag(read(neoForge, LIVING)), "NeoForge's patched 26.3 too");
        }
    }

    // --- classes under test -----------------------------------------------------------------

    /** The caller's read after the whole 26.2 to 26.3 shim, offline, for a host of {@code version}. */
    private List<String> readsAfterShimFor(String version) {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = version;
        Mc26_2To26_3CoreMoves.register(transformer);
        return accesses(transform(CALLER), "read");
    }

    private byte[] transform(String name) {
        try (var scope = transformer.pushJarClassBytesProvider(jar::get)) {
            return transformer.transformClass(jar.get(name), name + ".class");
        }
    }

    /** {@code static boolean read(owner entity) { return entity.swinging; }} */
    private static byte[] reader(String name, String owner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "(L" + owner + ";)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, owner, "swinging", "Z");
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static void write(owner entity) { entity.swinging = true; }} */
    private static byte[] writer(String name, String owner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "write", "(L" + owner + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ICONST_1);
        mv.visitFieldInsn(PUTFIELD, owner, "swinging", "Z");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An entity declaring {@code public boolean swinging} and reading it in {@code own()}. */
    private static byte[] entityWithOwnFlag(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, LIVING, null);
        cw.visitField(ACC_PUBLIC, "swinging", "Z", null, null).visitEnd();
        HostStubs.defaultConstructor(cw, LIVING);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "own", "()Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, name, "swinging", "Z");
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A 26.3-shaped LivingEntity whose {@code isSwinging()} returns its {@code swingingNow} field. */
    private static byte[] swingingLivingEntity() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, LIVING, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC, "swingingNow", "Z", null, null).visitEnd();
        HostStubs.defaultConstructor(cw, "java/lang/Object");
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "isSwinging", "()Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, LIVING, "swingingNow", "Z");
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode living(boolean flag, boolean query) {
        ClassNode node = new ClassNode();
        node.name = LIVING;
        if (flag) node.fields.add(new FieldNode(ACC_PUBLIC, "swinging", "Z", null, null));
        if (query) node.methods.add(new MethodNode(ACC_PUBLIC, "isSwinging", "()Z", null, null));
        return node;
    }

    /** Field reads, writes and calls in {@code method}, as {@code get/put/call owner.name...}. */
    private static List<String> accesses(byte[] bytes, String method) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> found = new ArrayList<>();
        for (MethodNode candidate : node.methods) {
            if (!candidate.name.equals(method)) continue;
            for (AbstractInsnNode insn : candidate.instructions) {
                if (insn instanceof FieldInsnNode field && field.getOpcode() != GETSTATIC && field.getOpcode() != PUTSTATIC) {
                    found.add((field.getOpcode() == GETFIELD ? "get " : "put ") + field.owner + "." + field.name + ":" + field.desc);
                } else if (insn instanceof MethodInsnNode call) {
                    found.add("call " + call.owner + "." + call.name + call.desc);
                }
            }
        }
        return found;
    }

    private static Path clientJar(String version) {
        Path jar = Path.of(System.getProperty("user.home", ""),
                "Library/Application Support/PrismLauncher/libraries/com/mojang/minecraft",
                version, "minecraft-" + version + "-client.jar");
        return Files.isRegularFile(jar) ? jar : null;
    }

    private static ClassNode read(Path jar, String owner) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(zip.getEntry(owner + ".class")).readAllBytes())
                    .accept(node, ClassReader.SKIP_CODE);
            return node;
        }
    }

    private static List<String> classesDeclaringSwinging(Path jar) throws Exception {
        List<String> declaring = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                ClassNode node = new ClassNode();
                new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node,
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                for (FieldNode field : node.fields) {
                    if (field.name.equals("swinging") && field.desc.equals("Z")) declaring.add(node.name);
                }
            }
        }
        return declaring;
    }
}
