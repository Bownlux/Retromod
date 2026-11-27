/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.SyntheticLibraries;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.20.3 folded {@code SandBlock} and {@code GravelBlock} into {@code ColoredFallingBlock}, which
 * takes a {@code ColorRGBA}. Old mods still build sand with an {@code int} color and subclass both.
 */
class LegacyFallingBlockBridgeTest {

    private static final String L_PROPERTIES = "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;";
    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().setModSourceVersion(null);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void plainFallingBlockIsBuiltAsTheGeneratedBlock() {
        // 1.20.3 made FallingBlock abstract, so new FallingBlock(properties) is an InstantiationError (#415).
        RetromodTransformer transformer = registeredOn("1.21.1");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/PlainBlocks", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "(" + L_PROPERTIES + ")Ljava/lang/Object;", null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, LegacyFallingBlockBridge.FALLING);
        m.visitInsn(Opcodes.DUP);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyFallingBlockBridge.FALLING, "<init>", "(" + L_PROPERTIES + ")V", false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();

        ClassNode mod = read(transformer.transformClass(cw.toByteArray(), "com/example/PlainBlocks"));
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : mod.methods.stream().filter(mn -> mn.name.equals("make")).findFirst().orElseThrow().instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
            assertFalse(insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW, "no new of the abstract block is left");
        }
        assertEquals(1, calls.size());
        assertEquals(LegacyFallingBlockBridge.GENERATED, calls.get(0).owner);
        assertEquals("plain", calls.get(0).name);
        ClassNode generated = read(transformer.getSyntheticClasses().get(LegacyFallingBlockBridge.GENERATED));
        assertTrue(generated.methods.stream().anyMatch(mn -> mn.name.equals("plain")));
    }

    @Test
    void oldFallingBlockSubclassGetsThePlainDustColor() {
        // 1.21.11 and 26.3 left getDustColor abstract, so the client's falling dust threw AbstractMethodError.
        LegacyFallingBlockBridge.Names names = LegacyFallingBlockBridge.Names.MOJANG;
        ClassNode bridged = read(LegacyFallingBlockBridge.addDustColor(fallingSubclass("com/example/AshBlock", names.falling(), null)));
        MethodNode dust = bridged.methods.stream().filter(m -> m.name.equals("getDustColor")).findFirst().orElseThrow();
        assertEquals(names.dustDesc(), dust.desc);
        assertEquals(LegacyFallingBlockBridge.PLAIN_DUST_COLOR, ((org.objectweb.asm.tree.LdcInsnNode) dust.instructions.getFirst()).cst);

        byte[] own = fallingSubclass("com/example/RedSandBlock", names.falling(), names);
        assertSame(own, LegacyFallingBlockBridge.addDustColor(own), "a block with its own color keeps it");
        byte[] colored = fallingSubclass("com/example/Tinted", names.colored(), null);
        assertSame(colored, LegacyFallingBlockBridge.addDustColor(colored), "only a direct FallingBlock subclass lacks one");
    }

    @Test
    void intermediaryFallingBlockSubclassGetsTheIntermediaryMethod() {
        LegacyFallingBlockBridge.Names names = LegacyFallingBlockBridge.Names.INTERMEDIARY;
        ClassNode bridged = read(LegacyFallingBlockBridge.addDustColor(fallingSubclass("com/example/AshBlock", names.falling(), null)));
        assertTrue(bridged.methods.stream().anyMatch(m -> m.name.equals("method_10130") && m.desc.equals(names.dustDesc())));
        ClassNode generated = read(LegacyFallingBlockBridge.generate(names));
        assertEquals("net/minecraft/class_8812", generated.superName);
        assertTrue(declares(generated, "(I" + names.propertiesDesc() + ")V"));
    }

    @Test
    void dustColorFollowsTheHost() {
        LegacyFallingBlockBridge.Names names = LegacyFallingBlockBridge.Names.MOJANG;
        ClassNode host = new ClassNode();
        host.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, names.dustName(), names.dustDesc(), null, null));
        assertTrue(LegacyFallingBlockBridge.hostLeavesDustAbstract(host, names), "1.21.11 and 26.3");
        host.methods.get(0).access = Opcodes.ACC_PUBLIC;
        assertFalse(LegacyFallingBlockBridge.hostLeavesDustAbstract(host, names), "1.21.1 still has the plain color");
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        assertFalse(LegacyFallingBlockBridge.hostLeavesDustAbstract(null, names));
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        assertTrue(LegacyFallingBlockBridge.hostLeavesDustAbstract(null, names));
    }

    /** {@code class name extends parent}, with a dust color in {@code names}' shape when given. */
    private static byte[] fallingSubclass(String name, String parent, LegacyFallingBlockBridge.Names names) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, parent, null);
        if (names != null) {
            MethodVisitor dust = cw.visitMethod(Opcodes.ACC_PUBLIC, names.dustName(), names.dustDesc(), null, null);
            dust.visitCode();
            dust.visitLdcInsn(0xFFDBD3A0);
            dust.visitInsn(Opcodes.IRETURN);
            dust.visitMaxs(0, 0);
            dust.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void oldSandAndGravelConstructorsLinkAgainstTheGeneratedBlock() {
        RetromodTransformer transformer = registeredOn("1.21.1");
        ClassNode generated = read(transformer.getSyntheticClasses().get(LegacyFallingBlockBridge.GENERATED));
        assertEquals("net/minecraft/world/level/block/ColoredFallingBlock", generated.superName);

        ClassNode mod = read(transformer.transformClass(blockFactory(), "com/example/ModBlocks"));
        int constructors = 0;
        for (MethodNode method : mod.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals("<init>")
                        && !call.owner.equals("java/lang/Object")) {
                    assertEquals(LegacyFallingBlockBridge.GENERATED, call.owner);
                    assertTrue(declares(generated, call.desc),
                            "the generated block must declare " + call.desc);
                    constructors++;
                }
            }
        }
        assertEquals(2, constructors, "the sand and the gravel constructor");
    }

    @Test
    void sandConstructorWrapsTheColorInTheRecord() {
        ClassNode generated = read(LegacyFallingBlockBridge.generate());
        MethodNode sand = generated.methods.stream()
                .filter(m -> m.desc.equals("(I" + L_PROPERTIES + ")V")).findFirst().orElseThrow();
        MethodInsnNode wrap = null;
        for (AbstractInsnNode insn : sand.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals("net/minecraft/util/ColorRGBA")) wrap = call;
        }
        assertNotNull(wrap, "the int color must become a ColorRGBA");
        assertEquals("(I)V", wrap.desc);
    }

    @Test
    void registersNothingBefore1203() {
        RetromodTransformer transformer = registeredOn("1.20.2");
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyFallingBlockBridge.GENERATED));
        assertNull(transformer.getClassRedirects().get(LegacyFallingBlockBridge.SAND),
                "1.20.2 still has SandBlock");
    }

    @Test
    void newerModKeepsTheHostSandBlock() {
        RetromodTransformer transformer = registeredOn("26.3");
        transformer.setModSourceVersion("1.21.11");

        List<String> user = references(transformer.transformClass(newSandUser(), "com/example/NewSand"));
        ClassNode subclass = read(transformer.transformClass(sandSubclass(), "com/example/DesertSand"));

        assertEquals(List.of("NEW " + SAND, "<init> " + SAND + HOST_CTOR, "INSTANCEOF " + SAND, "CHECKCAST " + SAND),
                user, "1.21.5 brought SandBlock back, so a mod built for it must keep naming it");
        assertEquals(SAND, subclass.superName);
        assertEquals(List.of("<init> " + SAND + HOST_CTOR), references(subclass),
                "the subclass's super call keeps SandBlock");
    }

    @Test
    void olderModStillTakesTheGeneratedBlock() {
        RetromodTransformer transformer = registeredOn("26.3");
        transformer.setModSourceVersion("1.20.1");

        ClassNode mod = read(transformer.transformClass(blockFactory(), "com/example/ModBlocks"));

        assertTrue(references(mod).stream().filter(r -> r.startsWith("NEW "))
                .allMatch(r -> r.equals("NEW " + LegacyFallingBlockBridge.GENERATED)), references(mod).toString());
    }

    @Test
    void newerModKeepsTheHostSandBlockThroughTheEmbed(@TempDir Path dir) throws Exception {
        RetromodTransformer transformer = registeredOn("26.3");
        transformer.setModSourceVersion("1.21.11");
        // The gravel makes the embed carry LegacyColoredFallingBlock and rewrite the jar's classes.
        byte[] blocks = transformer.transformClass(newSandAndGravel(), "com/example/NewBlocks");

        List<String> refs = references(embedded(dir, transformer, "com/example/NewBlocks", blocks));

        assertTrue(refs.contains("NEW " + SAND) && refs.contains("<init> " + SAND + HOST_CTOR),
                "1.21.5 brought SandBlock back, so the embed must not move it to the stand-in: " + refs);
        assertTrue(refs.contains("NEW " + SyntheticEmbedder.embeddedBase("blocks.jar")
                + LegacyFallingBlockBridge.GENERATED), "gravel still takes the embedded stand-in: " + refs);
    }

    @Test
    void olderModStillTakesTheEmbeddedBlock(@TempDir Path dir) throws Exception {
        RetromodTransformer transformer = registeredOn("26.3");
        transformer.setModSourceVersion("1.20.1");
        byte[] blocks = transformer.transformClass(blockFactory(), "com/example/ModBlocks");

        List<String> refs = references(embedded(dir, transformer, "com/example/ModBlocks", blocks));

        String embeddedBlock = SyntheticEmbedder.embeddedBase("blocks.jar") + LegacyFallingBlockBridge.GENERATED;
        assertTrue(refs.stream().filter(r -> r.startsWith("NEW ")).allMatch(r -> r.equals("NEW " + embeddedBlock)),
                refs.toString());
    }

    /** {@code classBytes} after the CLI's jar embed, as the class it was. */
    private static ClassNode embedded(Path dir, RetromodTransformer transformer, String name,
            byte[] classBytes) throws Exception {
        Path jar = dir.resolve("blocks.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(name + ".class"));
            out.write(classBytes);
            out.closeEntry();
        }
        SyntheticEmbedder.embedIntoJarOrDiscard(jar, "blocks.jar", transformer, SyntheticLibraries.NONE);
        try (JarFile file = new JarFile(jar.toFile())) {
            return read(file.getInputStream(file.getEntry(name + ".class")).readAllBytes());
        }
    }

    // A 1.21.5+ mod: new SandBlock(color, properties) and new GravelBlock(properties).
    private static byte[] newSandAndGravel() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/NewBlocks", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make",
                "(Lnet/minecraft/util/ColorRGBA;" + L_PROPERTIES + ")V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, SAND);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SAND, "<init>", HOST_CTOR, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, LegacyFallingBlockBridge.GRAVEL);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyFallingBlockBridge.GRAVEL, "<init>", "(" + L_PROPERTIES + ")V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void gravelStaysRedirectedForNewerMods() {
        RetromodTransformer transformer = registeredOn("26.3");
        transformer.setModSourceVersion("1.21.11");

        List<String> refs = references(read(transformer.transformClass(blockFactory(), "com/example/ModBlocks")));

        assertTrue(refs.contains("NEW " + LegacyFallingBlockBridge.GENERATED),
                "GravelBlock never came back, so even a newer mod's reference is redirected: " + refs);
    }

    private static final String SAND = LegacyFallingBlockBridge.SAND;
    private static final String HOST_CTOR = "(Lnet/minecraft/util/ColorRGBA;" + L_PROPERTIES + ")V";

    /** The NEW, constructor, INSTANCEOF and CHECKCAST references a class makes, in order. */
    private static List<String> references(ClassNode node) {
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof TypeInsnNode type) {
                    String op = switch (type.getOpcode()) {
                        case Opcodes.NEW -> "NEW ";
                        case Opcodes.INSTANCEOF -> "INSTANCEOF ";
                        default -> "CHECKCAST ";
                    };
                    refs.add(op + type.desc);
                } else if (insn instanceof MethodInsnNode call && call.name.equals("<init>")
                        && !call.owner.equals("java/lang/Object")) {
                    refs.add("<init> " + call.owner + call.desc);
                }
            }
        }
        return refs;
    }

    private static List<String> references(byte[] bytes) {
        return references(read(bytes));
    }

    // A 1.21.5+ mod: new SandBlock(color, properties), then instanceof and a cast against it.
    private static byte[] newSandUser() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/NewSand", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make",
                "(Lnet/minecraft/util/ColorRGBA;" + L_PROPERTIES + "Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, SAND);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SAND, "<init>", HOST_CTOR, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, SAND);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.CHECKCAST, SAND);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // class DesertSand extends SandBlock { DesertSand(ColorRGBA c, Properties p) { super(c, p); } }
    private static byte[] sandSubclass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/DesertSand", null, SAND, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", HOST_CTOR, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SAND, "<init>", HOST_CTOR, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static RetromodTransformer registeredOn(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyFallingBlockBridge.register(transformer);
        return transformer;
    }

    // new SandBlock(color, properties); new GravelBlock(properties)
    private static byte[] blockFactory() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/ModBlocks", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make",
                "(" + L_PROPERTIES + ")V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, LegacyFallingBlockBridge.SAND);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(10708917);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyFallingBlockBridge.SAND, "<init>", "(I" + L_PROPERTIES + ")V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, LegacyFallingBlockBridge.GRAVEL);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyFallingBlockBridge.GRAVEL, "<init>", "(" + L_PROPERTIES + ")V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static boolean declares(ClassNode node, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals(desc));
    }
}
