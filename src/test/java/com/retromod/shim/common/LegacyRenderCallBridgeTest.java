/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.polyfill.minecraft.RetroRenderTargets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.Printer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A 1.21.1 mod's render calls on NeoForge, Forge, and Fabric hosts from 1.21.1 to 26.1: each one
 * is bridged from the version that removed it and left alone below it.
 */
class LegacyRenderCallBridgeTest {

    private static final String HOST_DEFAULT = RetromodVersion.TARGET_MC_VERSION;
    private static final String[] VERSIONS = {"1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6",
            "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11", "26.1"};

    private static final String MOD = "test/mod/RenderCalls";
    private static final String RS = "com/mojang/blaze3d/systems/RenderSystem";
    private static final String TARGET = "com/mojang/blaze3d/pipeline/RenderTarget";
    private static final String DT = "net/minecraft/client/renderer/texture/DynamicTexture";
    private static final String IMAGE = "Lcom/mojang/blaze3d/platform/NativeImage;";
    private static final String FONT = "net/minecraft/client/gui/Font";
    private static final String ERD = "net/minecraft/client/renderer/entity/EntityRenderDispatcher";
    private static final String QUAT = "Lorg/joml/Quaternionf;";
    private static final String DRAW_TAIL = "FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;"
            + "Lnet/minecraft/client/gui/Font$DisplayMode;II)";
    private static final String COMPONENT = "Lnet/minecraft/network/chat/Component;";

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = HOST_DEFAULT;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("applyModelViewMatrix() is dropped from 1.21.2 on every loader and kept on 1.21.1")
    void modelViewApplyDroppedFrom1212() throws Exception {
        String call = "INVOKESTATIC " + RS + ".applyModelViewMatrix()V";
        for (String loader : new String[]{"neoforge", "forge", "fabric"}) {
            assertTrue(body(loader, "1.21.1", "modelView").contains(call), loader + " 1.21.1 still has it");
            for (String host : new String[]{"1.21.2", "1.21.11", "26.1"}) {
                assertFalse(body(loader, host, "modelView").contains(call), loader + " " + host + " must drop it");
            }
        }
    }

    @Test
    @DisplayName("InventoryMenu.BLOCK_ATLAS reads TextureAtlas.LOCATION_BLOCKS from 1.21.4 with the host's id type")
    void blockAtlasReadsLocationBlocksFrom1214() throws Exception {
        String old = "GETSTATIC net/minecraft/world/inventory/InventoryMenu.BLOCK_ATLAS:"
                + "Lnet/minecraft/resources/ResourceLocation;";
        String atlas = "GETSTATIC net/minecraft/client/renderer/texture/TextureAtlas.LOCATION_BLOCKS:";
        for (String loader : new String[]{"neoforge", "forge"}) {
            assertTrue(body(loader, "1.21.3", "atlas").contains(old), loader + " 1.21.3 still has the field");
            assertTrue(body(loader, "1.21.4", "atlas").contains(atlas + "Lnet/minecraft/resources/ResourceLocation;"),
                    loader + " 1.21.4 reads the atlas constant");
        }
        // 1.21.11 renamed ResourceLocation, and the read follows it.
        for (String host : new String[]{"1.21.11", "26.1"}) {
            assertTrue(body("neoforge", host, "atlas").contains(atlas + "Lnet/minecraft/resources/Identifier;"),
                    "NeoForge " + host + " reads the atlas constant as an Identifier");
        }
    }

    @Test
    @DisplayName("RenderTarget.bindWrite goes to the main-target helper from 1.21.5 and is kept on 1.21.4")
    void bindWriteBridgedFrom1215() throws Exception {
        String old = "INVOKEVIRTUAL " + TARGET + ".bindWrite(Z)V";
        String unbind = "INVOKEVIRTUAL " + TARGET + ".unbindWrite()V";
        String bridged = "INVOKESTATIC " + LegacyRenderCallBridge.TARGETS + ".bindWrite(Ljava/lang/Object;Z)V";
        for (String loader : new String[]{"neoforge", "forge"}) {
            List<String> below = body(loader, "1.21.4", "bind");
            assertTrue(below.contains(old) && below.contains(unbind), loader + " 1.21.4 still binds and unbinds");
            for (String host : new String[]{"1.21.5", "1.21.11", "26.1"}) {
                List<String> calls = body(loader, host, "bind");
                assertTrue(calls.contains(bridged), loader + " " + host + " must call the helper: " + calls);
                assertFalse(calls.contains(unbind), loader + " " + host + " must drop unbindWrite: " + calls);
                assertTrue(RetromodTransformer.getInstance().getSyntheticClasses()
                        .containsKey(LegacyRenderCallBridge.TARGETS), "the helper must be embeddable");
            }
        }
    }

    @Test
    @DisplayName("the bindWrite helper accepts the main target and fails on an off-screen one")
    void bindWriteHelperOnlyAcceptsTheMainTarget() throws Exception {
        ClassLoader blaze3d = new FakeTargets();
        Object main = blaze3d.loadClass("com.mojang.blaze3d.pipeline.MainTarget")
                .getDeclaredConstructor().newInstance();
        Object offscreen = blaze3d.loadClass("com.mojang.blaze3d.pipeline.TextureTarget")
                .getDeclaredConstructor().newInstance();

        assertDoesNotThrow(() -> RetroRenderTargets.bindWrite(main, true));
        NoSuchMethodError error = assertThrows(NoSuchMethodError.class,
                () -> RetroRenderTargets.bindWrite(offscreen, true));
        assertTrue(error.getMessage().contains("TextureTarget"), error.getMessage());
    }

    @Test
    @DisplayName("DynamicTexture constructors get a label from 1.21.5 and are kept on 1.21.4")
    void dynamicTextureLabelledFrom1215() throws Exception {
        String oldImage = "INVOKESPECIAL " + DT + ".<init>(" + IMAGE + ")V";
        String oldBlank = "INVOKESPECIAL " + DT + ".<init>(IIZ)V";
        String label = "INVOKESTATIC " + LegacyRenderCallBridge.TEXTURES + ".label()Ljava/util/function/Supplier;";
        String labelled = "INVOKESPECIAL " + DT + ".<init>(Ljava/util/function/Supplier;" + IMAGE + ")V";
        String blank = "INVOKESTATIC " + LegacyRenderCallBridge.TEXTURES + ".blank(IIZ)L" + DT + ";";
        for (String loader : new String[]{"neoforge", "forge"}) {
            assertTrue(body(loader, "1.21.4", "texture").contains(oldImage), loader + " 1.21.4 keeps (image)");
            assertTrue(body(loader, "1.21.4", "blank").contains(oldBlank), loader + " 1.21.4 keeps (w, h, clear)");
            for (String host : new String[]{"1.21.5", "1.21.11", "26.1"}) {
                List<String> image = body(loader, host, "texture");
                assertEquals(List.of(label, "SWAP", labelled),
                        image.subList(image.indexOf(label), image.indexOf(label) + 3), loader + " " + host);
                assertTrue(body(loader, host, "blank").contains(blank), loader + " " + host + " builds the blank form");
                // A subclass's super(image) gets the same label.
                List<String> sub = methodBody(transform(loader, host, subclassTexture(), "test/mod/MyTexture"), "<init>");
                assertEquals(List.of(label, "SWAP", labelled),
                        sub.subList(sub.indexOf(label), sub.indexOf(label) + 3), loader + " " + host + " super call");
            }
        }
    }

    @Test
    @DisplayName("Fabric gets the RenderTarget and DynamicTexture bridges only where its mods carry Mojang names")
    void fabricBridgesFollowTheNamespace() throws Exception {
        String oldImage = "INVOKESPECIAL " + DT + ".<init>(" + IMAGE + ")V";
        String oldBind = "INVOKEVIRTUAL " + TARGET + ".bindWrite(Z)V";
        // Before 26.1 a Fabric mod names these classes class_276 and class_1043, never these keys.
        for (String host : new String[]{"1.21.5", "1.21.11"}) {
            assertTrue(body("fabric", host, "texture").contains(oldImage), "Fabric " + host + " registers no texture key");
            assertTrue(body("fabric", host, "bind").contains(oldBind), "Fabric " + host + " registers no bind key");
        }
        assertFalse(body("fabric", "26.1", "texture").contains(oldImage), "26.1 Fabric mods are remapped to it");
        assertTrue(body("fabric", "26.1", "bind").contains(
                "INVOKESTATIC " + LegacyRenderCallBridge.TARGETS + ".bindWrite(Ljava/lang/Object;Z)V"));
    }

    @Test
    @DisplayName("the DynamicTexture helper labels both constructors with the host's own shapes")
    void dynamicTextureHelperShape() throws Exception {
        ClassNode helper = read(LegacyRenderCallBridge.generateTextureLabels());
        for (MethodNode method : helper.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);
        }

        assertEquals(List.of("java/util/function/Supplier"), helper.interfaces);
        assertTrue(methodBody(helper, "get").contains("LDC " + LegacyRenderCallBridge.TEXTURE_LABEL));
        assertTrue(methodBody(helper, "blank").contains(
                "INVOKESPECIAL " + DT + ".<init>(Ljava/lang/String;IIZ)V"), "1.21.5 kept a String-labelled blank form");
    }

    @Test
    @DisplayName("Font.drawInBatch keeps its old int on 1.21.6 to 1.21.11 and is left alone elsewhere")
    void drawInBatchVoidFrom1216() throws Exception {
        String old = "INVOKEVIRTUAL " + FONT + ".drawInBatch(" + COMPONENT + DRAW_TAIL + "I";
        String bridged = "INVOKEVIRTUAL " + FONT + ".drawInBatch(" + COMPONENT + DRAW_TAIL + "V";
        for (String loader : new String[]{"neoforge", "forge"}) {
            assertTrue(body(loader, "1.21.5", "text").contains(old), loader + " 1.21.5 still returns the end x");
            for (String host : new String[]{"1.21.6", "1.21.11"}) {
                List<String> calls = body(loader, host, "text");
                assertEquals(List.of(bridged, "ICONST_0", "POP"),
                        calls.subList(calls.indexOf(bridged), calls.indexOf(bridged) + 3), loader + " " + host);
            }
            // 26.1 took a Matrix4fc as well, so this bridge stays off there and the call is unchanged.
            assertTrue(body(loader, "26.1", "text").contains(old), loader + " 26.1 is not this bridge's shape");
        }
    }

    @Test
    @DisplayName("cameraOrientation() reads the camera from 1.21.9 and keeps the 26.x helper on 26.1")
    void cameraOrientationFrom1219() throws Exception {
        String old = "INVOKEVIRTUAL " + ERD + ".cameraOrientation()" + QUAT;
        String desc = "(L" + ERD + ";)" + QUAT;
        String bridged = "INVOKESTATIC " + LegacyRenderCallBridge.CAMERA + ".cameraOrientation" + desc;
        String helper26x = "INVOKESTATIC " + IsOverloadBridgeSynthetic.INTERNAL + ".cameraOrientation" + desc;
        for (String loader : new String[]{"neoforge", "forge"}) {
            assertTrue(body(loader, "1.21.8", "orientation").contains(old), loader + " 1.21.8 still caches it");
            for (String host : new String[]{"1.21.9", "1.21.11"}) {
                assertTrue(body(loader, host, "orientation").contains(bridged), loader + " " + host);
            }
            List<String> on26 = body(loader, "26.1", "orientation");
            assertTrue(on26.contains(helper26x), loader + " 26.1 keeps the 26.x helper: " + on26);
            assertFalse(RetromodTransformer.getInstance().getSyntheticClasses()
                    .containsKey(LegacyRenderCallBridge.CAMERA), "26.1 never registers the 1.21.9 helper");
        }
    }

    @Test
    @DisplayName("the 1.21.9 camera helper is the 26.x helper's own method")
    void cameraHelperMatchesThe26xHelper() {
        List<String> carried = methodBody(read(LegacyRenderCallBridge.generateCameraOrientation()), "cameraOrientation");
        List<String> original = methodBody(read(IsOverloadBridgeSynthetic.generate()), "cameraOrientation");

        assertEquals(original, carried);
        assertTrue(carried.contains("GETFIELD " + ERD + ".camera:Lnet/minecraft/client/Camera;"), carried.toString());
    }

    // ---- the chain a 1.21.1 mod gets on a host ----

    /** Registers every shim of the loader from 1.21.1 up to the host, as the shim registry chains them. */
    private static RetromodTransformer chain(String loader, String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        String prefix = switch (loader) {
            case "neoforge" -> "com.retromod.shim.neoforge.NeoForge_";
            case "forge" -> "com.retromod.shim.forge.Forge_";
            default -> "com.retromod.shim.fabric.Fabric_";
        };
        for (int i = 0; i + 1 < VERSIONS.length
                && RetromodVersion.compareMcVersions(VERSIONS[i + 1], host) <= 0; i++) {
            String shim = prefix + VERSIONS[i].replace('.', '_') + "_to_" + VERSIONS[i + 1].replace('.', '_');
            ((VersionShim) Class.forName(shim).getDeclaredConstructor().newInstance()).registerRedirects(transformer);
        }
        return transformer;
    }

    private static List<String> body(String loader, String host, String method) throws Exception {
        return methodBody(transform(loader, host, renderCalls(), MOD), method);
    }

    private static ClassNode transform(String loader, String host, byte[] mod, String name) throws Exception {
        ClassNode node = read(chain(loader, host).transformClass(mod, name));
        // A rewritten call must leave a stack the JVM accepts, label and swap included.
        for (MethodNode method : node.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
        return node;
    }

    // ---- the old mod ----

    /** One method per removed call, written the way a 1.21.1 mod compiles it. */
    private static byte[] renderCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, "java/lang/Object", null);

        MethodVisitor mv = method(cw, "modelView", "()V");
        mv.visitMethodInsn(INVOKESTATIC, RS, "applyModelViewMatrix", "()V", false);
        end(mv, RETURN);

        mv = method(cw, "atlas", "()Ljava/lang/Object;");
        mv.visitFieldInsn(GETSTATIC, "net/minecraft/world/inventory/InventoryMenu", "BLOCK_ATLAS",
                "Lnet/minecraft/resources/ResourceLocation;");
        end(mv, ARETURN);

        mv = method(cw, "bind", "(L" + TARGET + ";)V");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKEVIRTUAL, TARGET, "bindWrite", "(Z)V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, TARGET, "unbindWrite", "()V", false);
        end(mv, RETURN);

        mv = method(cw, "texture", "(" + IMAGE + ")Ljava/lang/Object;");
        mv.visitTypeInsn(NEW, DT);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, DT, "<init>", "(" + IMAGE + ")V", false);
        end(mv, ARETURN);

        mv = method(cw, "blank", "()Ljava/lang/Object;");
        mv.visitTypeInsn(NEW, DT);
        mv.visitInsn(DUP);
        mv.visitIntInsn(BIPUSH, 16);
        mv.visitIntInsn(BIPUSH, 16);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKESPECIAL, DT, "<init>", "(IIZ)V", false);
        end(mv, ARETURN);

        mv = method(cw, "text", "(L" + FONT + ";" + COMPONENT
                + "Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;)V");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(FCONST_0);
        mv.visitInsn(FCONST_0);
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitFieldInsn(GETSTATIC, FONT + "$DisplayMode", "NORMAL", "L" + FONT + "$DisplayMode;");
        mv.visitInsn(ICONST_0);
        mv.visitLdcInsn(15728880);
        mv.visitMethodInsn(INVOKEVIRTUAL, FONT, "drawInBatch", "(" + COMPONENT + DRAW_TAIL + "I", false);
        mv.visitInsn(POP);
        end(mv, RETURN);

        mv = method(cw, "orientation", "(L" + ERD + ";)" + QUAT);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, ERD, "cameraOrientation", "()" + QUAT, false);
        end(mv, ARETURN);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code class MyTexture extends DynamicTexture { MyTexture(NativeImage image) { super(image); } }}. */
    private static byte[] subclassTexture() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/MyTexture", null, DT, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + IMAGE + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, DT, "<init>", "(" + IMAGE + ")V", false);
        end(mv, RETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodVisitor method(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        return mv;
    }

    private static void end(MethodVisitor mv, int returnOpcode) {
        mv.visitInsn(returnOpcode);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ---- reading results ----

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** The method's instructions as "OPCODE owner.name desc" lines, labels and frames left out. */
    private static List<String> methodBody(ClassNode node, String name) {
        for (MethodNode method : node.methods) {
            if (!method.name.equals(name)) continue;
            List<String> lines = new ArrayList<>();
            for (AbstractInsnNode insn : method.instructions) {
                if (insn.getOpcode() < 0) continue;
                String op = Printer.OPCODES[insn.getOpcode()];
                if (insn instanceof MethodInsnNode call) {
                    lines.add(op + " " + call.owner + "." + call.name + call.desc);
                } else if (insn instanceof FieldInsnNode field) {
                    lines.add(op + " " + field.owner + "." + field.name + ":" + field.desc);
                } else if (insn instanceof TypeInsnNode type) {
                    lines.add(op + " " + type.desc);
                } else if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc) {
                    lines.add(op + " " + ldc.cst);
                } else {
                    lines.add(op);
                }
            }
            return lines;
        }
        throw new AssertionError("no method " + name + " in " + node.name);
    }

    /** Defines a stand-in MainTarget and TextureTarget under their blaze3d names. */
    private static final class FakeTargets extends ClassLoader {
        FakeTargets() {
            super(LegacyRenderCallBridgeTest.class.getClassLoader());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.startsWith("com.mojang.blaze3d.pipeline.")) throw new ClassNotFoundException(name);
            String internal = name.replace('.', '/');
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, internal, null, "java/lang/Object", null);
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            end(init, RETURN);
            cw.visitEnd();
            byte[] bytes = cw.toByteArray();
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
