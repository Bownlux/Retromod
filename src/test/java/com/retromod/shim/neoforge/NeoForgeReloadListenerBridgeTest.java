/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import com.retromod.core.SyntheticEmbedder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for #139 (Legendary Survival Overhaul on NeoForge 26.2): the removed
 * {@code AddReloadListenerEvent} is class-redirected to 26.2's {@code AddServerReloadListenersEvent}
 * (fixing the {@code NoClassDefFoundError} and retargeting the {@code @SubscribeEvent} parameter), and
 * the old one-arg {@code addListener(listener)} call is bridged to the id-requiring two-arg form via
 * {@link com.retromod.shim.neoforge.embedded.ReloadListenerEventShim}.
 */
class NeoForgeReloadListenerBridgeTest {

    private static final String OLD_EVENT = "net/neoforged/neoforge/event/AddReloadListenerEvent";
    private static final String NEW_EVENT = "net/neoforged/neoforge/event/AddServerReloadListenersEvent";
    private static final String SHIM = "com/retromod/shim/neoforge/embedded/ReloadListenerEventShim";
    private static final String LISTENER = "net/minecraft/server/packs/resources/PreparableReloadListener";
    private static final String OLD_CLIENT_EVENT =
            "net/neoforged/neoforge/client/event/RegisterClientReloadListenersEvent";
    private static final String NEW_CLIENT_EVENT =
            "net/neoforged/neoforge/client/event/AddClientReloadListenersEvent";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new NeoForge_1_21_11_to_26_1().registerRedirects(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("event class redirect + shim class declared")
    void classRedirectAndShimDeclared() {
        assertEquals(NEW_EVENT, transformer.getClassRedirects().get(OLD_EVENT),
                "AddReloadListenerEvent must redirect to AddServerReloadListenersEvent");
        assertTrue(Arrays.asList(new NeoForge_1_21_11_to_26_1().getShimClasses())
                        .contains("com.retromod.shim.neoforge.embedded.ReloadListenerEventShim"),
                "the reload-listener bridge shim must be declared for embedding");
        assertTrue(transformer.getSyntheticClasses().containsKey(SHIM),
                "the compiled reload bridge must be registered with the per-mod embedder");
    }

    @Test
    @DisplayName("event.addListener(listener) is bridged to ReloadListenerEventShim.addListener(event, listener)")
    void addListenerCallBridged() {
        byte[] out = transformer.transformClass(subscriber(), "test/mod/LsoSub.class");
        assertNotNull(out);

        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        MethodNode on = cn.methods.stream().filter(m -> m.name.equals("on")).findFirst().orElseThrow();

        boolean bridged = false, oldCallSurvives = false;
        for (AbstractInsnNode insn : on.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode mi)) continue;
            if (mi.getOpcode() == Opcodes.INVOKESTATIC && mi.owner.equals(SHIM) && mi.name.equals("addListener")) {
                bridged = true;
            }
            if (mi.getOpcode() == Opcodes.INVOKEVIRTUAL && mi.name.equals("addListener")) {
                oldCallSurvives = true;
            }
        }
        assertTrue(bridged, "the addListener call must become INVOKESTATIC ReloadListenerEventShim.addListener");
        assertFalse(oldCallSurvives, "the old one-arg INVOKEVIRTUAL addListener must be gone");

        // and the @SubscribeEvent parameter type must have been retargeted to the new event
        assertTrue(on.desc.contains(NEW_EVENT), "handler param must be remapped to the new event: " + on.desc);
        assertFalse(on.desc.contains(OLD_EVENT), "the old event type must not survive in the descriptor");
    }

    @Test
    @DisplayName("#188: client reload event and one-argument registration move together")
    void clientReloadListenerCallBridged() {
        byte[] out = transformer.transformClass(clientSubscriber(), "test/mod/CaelumClient.class");
        assertNotNull(out);

        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        MethodNode on = cn.methods.stream().filter(m -> m.name.equals("onClient")).findFirst().orElseThrow();
        assertTrue(on.desc.contains(NEW_CLIENT_EVENT),
                "the deleted client event must become AddClientReloadListenersEvent: " + on.desc);
        assertFalse(on.desc.contains(OLD_CLIENT_EVENT));

        boolean bridged = false;
        for (AbstractInsnNode insn : on.instructions.toArray()) {
            if (insn instanceof MethodInsnNode method
                    && method.getOpcode() == Opcodes.INVOKESTATIC
                    && method.owner.equals(SHIM)
                    && method.name.equals("addListener")) {
                bridged = true;
            }
        }
        assertTrue(bridged,
                "registerReloadListener(listener) must use the id-synthesizing reload bridge");
    }

    @Test
    @DisplayName("#188: the reload bridge is relocated into an offline transformed jar")
    void clientReloadBridgeEmbedded(@TempDir Path tempDir) throws Exception {
        byte[] transformed = transformer.transformClass(
                clientSubscriber(), "test/mod/CaelumClient.class");
        Path jar = tempDir.resolve("caelum.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("test/mod/CaelumClient.class"));
            out.write(transformed);
            out.closeEntry();
        }

        assertEquals(1, SyntheticEmbedder.embedIntoJar(jar, "caelum.jar", transformer));
        String embedded = SyntheticEmbedder.embeddedBase("caelum.jar") + SHIM;
        try (var zip = new ZipFile(jar.toFile())) {
            assertNotNull(zip.getEntry(embedded + ".class"),
                    "the output jar must contain its private reload-listener bridge");
            byte[] caller = zip.getInputStream(zip.getEntry("test/mod/CaelumClient.class"))
                    .readAllBytes();
            ClassNode node = new ClassNode();
            new ClassReader(caller).accept(node, 0);
            assertTrue(node.methods.stream()
                    .flatMap(method -> Arrays.stream(method.instructions.toArray()))
                    .filter(MethodInsnNode.class::isInstance)
                    .map(MethodInsnNode.class::cast)
                    .anyMatch(call -> call.owner.equals(embedded) && call.name.equals("addListener")),
                    "the caller must use its relocated bridge, not Retromod's package");
        }
    }

    /** A mod handler `void on(AddReloadListenerEvent event, PreparableReloadListener listener)` calling event.addListener(listener). */
    private static byte[] subscriber() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/LsoSub", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "on",
                "(L" + OLD_EVENT + ";L" + LISTENER + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1); // event
        mv.visitVarInsn(Opcodes.ALOAD, 2); // listener
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OLD_EVENT, "addListener",
                "(L" + LISTENER + ";)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 3);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] clientSubscriber() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/CaelumClient", null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onClient",
                "(L" + OLD_CLIENT_EVENT + ";L" + LISTENER + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OLD_CLIENT_EVENT, "registerReloadListener",
                "(L" + LISTENER + ";)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 3);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
