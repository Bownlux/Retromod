package com.retromod.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.embedder.ModVersionInfo;

/**
 * Regression for the {@code batch}/{@code AOT} aux-redirects gap and its loader gating (#NN).
 *
 * <p>{@code batch} and {@code AotCompiler} once registered only the version-shim chain, so
 * AOT-prepped 26.x mods kept pre-26.x class names and a 1.21.x mod's mixin {@code @Shadow}/
 * {@code @Inject} failed to apply. {@link RetromodCli#registerAuxiliaryRedirects} now also
 * layers the vanilla class-move table plus the Fabric-only member mappings.
 *
 * <p>Vanilla class moves apply on every loader; the Fabric intermediary-&gt;Mojang member
 * mappings are Fabric-only. Applying them to a Mojang-named NeoForge mod clobbered correct
 * fields ({@code Blocks.WHITE_CANDLE} renamed to a field 26.2 lacks, crashing construction).
 */
class RetromodCliAuxRedirectsTest {

    private static ModVersionInfo info(String loader) {
        return new ModVersionInfo("testmod", "1.0.0", "1.21.4", loader, "1.0.0",
                Set.of(), Set.of(), false);
    }

    /** The summary ends with "... N member mapping(s)." Extract N. */
    private static int memberCount(String summary) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d+) member mapping").matcher(summary);
        assertTrue(m.find(), "summary should report a member-mapping count: " + summary);
        return Integer.parseInt(m.group(1));
    }

    private static int classMoveCount(String summary) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d+) class move").matcher(summary);
        assertTrue(m.find(), "summary should report a class-move count: " + summary);
        return Integer.parseInt(m.group(1));
    }

    @Test
    void classMovesApplyToEveryLoaderButMemberMappingsAreFabricOnly() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();

        String neoforge = RetromodCli.registerAuxiliaryRedirects(transformer, info("neoforge"), List.of());
        String forge = RetromodCli.registerAuxiliaryRedirects(transformer, info("forge"), List.of());
        String fabric = RetromodCli.registerAuxiliaryRedirects(transformer, info("fabric"), List.of());

        assertNotNull(neoforge, "26.1 target must register at least the class-move table");
        assertNotNull(forge);
        assertNotNull(fabric);

        // class moves apply on every loader
        assertTrue(classMoveCount(neoforge) > 0, "NeoForge must get the vanilla class moves");
        assertTrue(classMoveCount(fabric) > 0, "Fabric must get the vanilla class moves");

        // member mappings are Fabric-only (the WHITE_CANDLE clobber guard)
        assertTrue(memberCount(neoforge) == 0, "NeoForge must NOT get Fabric member mappings");
        assertTrue(memberCount(forge) == 0, "Forge must NOT get Fabric member mappings");
        assertFalse(memberCount(fabric) == 0, "Fabric MUST get the intermediary member mappings");
    }

    @Test
    void offlineForgeTransformsApplySrgMemberNames() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            String summary = RetromodCli.registerAuxiliaryRedirects(
                    transformer, info("forge"), List.of());
            assertTrue(summary.matches(".* [1-9][0-9]* SRG mapping\\(s\\).*"), summary);

            byte[] output = transformer.transformClass(oldPropertiesFactory(),
                    "test/OldPropertiesFactory");
            ClassNode node = new ClassNode();
            new ClassReader(output).accept(node, 0);
            List<MethodInsnNode> calls = node.methods.stream()
                    .flatMap(method -> Arrays.stream(method.instructions.toArray()))
                    .filter(MethodInsnNode.class::isInstance)
                    .map(MethodInsnNode.class::cast)
                    .toList();
            assertTrue(calls.stream().anyMatch(call -> "of".equals(call.name)));
            assertFalse(calls.stream().anyMatch(call -> "func_200945_a".equals(call.name)));
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    /**
     * CLI == runtime: the CLI/AOT paths must apply the ResourceLocation/Identifier ctor -> factory
     * redirect the in-game boot applies. Before this was wired, CLI/AOT emitted a raw
     * {@code new Identifier(String)} (a 26.1-removed constructor), so a mod tested "diamond with CLI"
     * could still crash when loaded without the CLI. This drives the actual CLI registration path.
     */
    @Test
    void cliAppliesIdentifierCtorRedirectLikeRuntime() {
        String prev = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        try {
            RetromodCli.registerAuxiliaryRedirects(t, info("fabric"), List.of());
            byte[] out = t.transformClass(identifierCtorClass(), "test/IdFixture");

            ClassNode cn = new ClassNode();
            new ClassReader(out).accept(cn, 0);
            List<MethodInsnNode> idCalls = cn.methods.stream()
                    .flatMap(m -> Arrays.stream(m.instructions.toArray()))
                    .filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i)
                    .filter(mi -> mi.owner.equals("net/minecraft/resources/Identifier")).toList();

            assertTrue(idCalls.stream().anyMatch(mi -> mi.name.equals("parse")
                            && mi.getOpcode() == Opcodes.INVOKESTATIC),
                    "new Identifier(String) must become Identifier.parse(String)");
            assertTrue(idCalls.stream().anyMatch(mi -> mi.name.equals("fromNamespaceAndPath")
                            && mi.getOpcode() == Opcodes.INVOKESTATIC),
                    "new Identifier(String,String) must become Identifier.fromNamespaceAndPath");
            assertFalse(idCalls.stream().anyMatch(mi -> mi.name.equals("<init>")),
                    "no raw Identifier.<init> may remain (it was removed in 26.1)");
        } finally {
            t.clearRedirectsForTesting();
            RetromodVersion.TARGET_MC_VERSION = prev;
        }
    }

    /** A class that does {@code new Identifier("x")} and {@code new Identifier("a","b")}. */
    private static byte[] identifierCtorClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/IdFixture", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "net/minecraft/resources/Identifier");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("x");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/resources/Identifier",
                "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, "net/minecraft/resources/Identifier");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("a");
        mv.visitLdcInsn("b");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/resources/Identifier",
                "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] oldPropertiesFactory() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/OldPropertiesFactory", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "create", "(Lnet/minecraft/block/material/Material;)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "net/minecraft/world/level/block/state/BlockBehaviour$Properties",
                "func_200945_a",
                "(Lnet/minecraft/block/material/Material;)"
                        + "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;",
                false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
