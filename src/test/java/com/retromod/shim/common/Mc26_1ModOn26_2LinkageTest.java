/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.forge.Forge_1_21_8_to_1_21_9;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Calls a mod built for 26.1 makes that no longer link on 26.2, taken from Immersive Vehicles
 * (#251): its render class builds a {@code BlendFunction} from the 26.1 blend-factor enums in a
 * static initializer, its tooltip code filters {@code ChatFormatting.isColor()}, its renderer
 * grabs {@code Minecraft.renderBuffers().bufferSource()}, and it reads {@code Entity.level()}.
 */
public class Mc26_1ModOn26_2LinkageTest {

    private static final String BLEND_FUNCTION = "com/mojang/blaze3d/pipeline/BlendFunction";
    private static final String SOURCE_FACTOR = "com/mojang/blaze3d/platform/SourceFactor";
    private static final String DEST_FACTOR = "com/mojang/blaze3d/platform/DestFactor";
    private static final String BLEND_FACTOR = "com/mojang/blaze3d/platform/BlendFactor";
    private static final String CHAT_FORMATTING = "net/minecraft/ChatFormatting";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String RENDER_BUFFERS = "net/minecraft/client/renderer/RenderBuffers";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String LEVEL_DESC = "()Lnet/minecraft/world/level/Level;";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A BlendFunction built from 26.1 blend-factor enums links against 26.2 BlendFactor")
    void blendFunctionFromLegacyFactorsUsesBlendFactorConstructor() {
        Mc26_1To26_2CoreMoves.register(transformer);
        List<AbstractInsnNode> insns = transformBody(mv -> {
            mv.visitTypeInsn(NEW, BLEND_FUNCTION);
            mv.visitInsn(DUP);
            mv.visitFieldInsn(GETSTATIC, SOURCE_FACTOR, "SRC_ALPHA", "L" + SOURCE_FACTOR + ";");
            mv.visitFieldInsn(GETSTATIC, DEST_FACTOR, "ONE", "L" + DEST_FACTOR + ";");
            mv.visitMethodInsn(INVOKESPECIAL, BLEND_FUNCTION, "<init>",
                    "(L" + SOURCE_FACTOR + ";L" + DEST_FACTOR + ";)V", false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        });

        MethodInsnNode init = firstCall(insns, BLEND_FUNCTION, "<init>");
        assertNotNull(init, "the BlendFunction construction must survive the transform");
        assertEquals("(L" + BLEND_FACTOR + ";L" + BLEND_FACTOR + ";)V", init.desc,
                "26.2 only has the (BlendFactor, BlendFactor) constructor");
        assertTrue(insns.stream().noneMatch(i -> i.getOpcode() == ACONST_NULL),
                "the blend factors must be real constants, a null crashes the static initializer");
    }

    @Test
    @DisplayName("ChatFormatting.isColor() becomes a static call to the generated bridge")
    void removedChatFormattingAccessorIsForwarded() {
        Mc26_1To26_2CoreMoves.register(transformer);
        List<AbstractInsnNode> insns = transformBody(mv -> {
            mv.visitFieldInsn(GETSTATIC, CHAT_FORMATTING, "RED", "L" + CHAT_FORMATTING + ";");
            mv.visitMethodInsn(INVOKEVIRTUAL, CHAT_FORMATTING, "isColor", "()Z", false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        });

        MethodInsnNode call = firstCall(insns, LegacyChatFormattingBridge.INTERNAL, "isColor");
        assertNotNull(call, "isColor() must be routed to the bridge, 26.2 removed it");
        assertEquals(INVOKESTATIC, call.getOpcode());
        assertEquals("(L" + CHAT_FORMATTING + ";)Z", call.desc);
    }

    @Test
    @DisplayName("The ChatFormatting bridge answers the way 26.1 ChatFormatting did")
    void chatFormattingBridgeMatches26_1Answers() throws Exception {
        Class<?> bridge = loadBridgeAgainstStubs();
        Method isColor = bridge.getMethod("isColor", StubFormatting.class);
        Method isFormat = bridge.getMethod("isFormat", StubFormatting.class);
        Method getColor = bridge.getMethod("getColor", StubFormatting.class);
        Method getChar = bridge.getMethod("getChar", StubFormatting.class);
        Method getName = bridge.getMethod("getName", StubFormatting.class);

        assertEquals(true, isColor.invoke(null, StubFormatting.WHITE), "WHITE is a color");
        assertEquals(false, isColor.invoke(null, StubFormatting.BOLD), "BOLD is a format");
        assertEquals(false, isColor.invoke(null, StubFormatting.RESET), "RESET is neither");
        assertEquals(true, isFormat.invoke(null, StubFormatting.ITALIC), "ITALIC is a format");
        assertEquals(false, isFormat.invoke(null, StubFormatting.RESET), "RESET is not a format");
        assertEquals(false, isFormat.invoke(null, StubFormatting.BLACK), "BLACK is a color");
        assertEquals(StubColor.valueOf(StubFormatting.RED),
                getColor.invoke(null, StubFormatting.RED), "a color reports its RGB value");
        assertNull(getColor.invoke(null, StubFormatting.UNDERLINE), "a format has no color");
        assertEquals('c', getChar.invoke(null, StubFormatting.RED));
        assertEquals("light_purple", getName.invoke(null, StubFormatting.LIGHT_PURPLE));
    }

    @Test
    @DisplayName("renderBuffers().bufferSource() is routed to the real buffers and the stand-in")
    void renderBufferChainIsRouted() {
        Mc26_1To26_2CoreMoves.register(transformer);
        String oldBufferSource = "net/minecraft/client/renderer/MultiBufferSource$BufferSource";
        List<AbstractInsnNode> insns = transformBody(mv -> {
            mv.visitMethodInsn(INVOKESTATIC, MINECRAFT, "getInstance", "()L" + MINECRAFT + ";",
                    false);
            mv.visitMethodInsn(INVOKEVIRTUAL, MINECRAFT, "renderBuffers",
                    "()L" + RENDER_BUFFERS + ";", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, RENDER_BUFFERS, "bufferSource",
                    "()L" + oldBufferSource + ";", false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        });

        MethodInsnNode buffers = firstCall(insns, LegacyRenderBuffersBridge.INTERNAL,
                "renderBuffers");
        assertNotNull(buffers, "Minecraft.renderBuffers() is gone on 26.2 and must be forwarded");
        assertEquals(INVOKESTATIC, buffers.getOpcode());
        MethodInsnNode source = firstCall(insns, LegacyRenderBuffersBridge.INTERNAL,
                "sharedBufferSource");
        assertNotNull(source, "RenderBuffers.bufferSource() is gone on 26.2 and must be forwarded");
        assertEquals("(L" + RENDER_BUFFERS + ";)L" + RenderBufferSynthetics.BS + ";", source.desc,
                "the forwarder must hand back the BufferSource stand-in type");
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyRenderBuffersBridge.INTERNAL),
                "the forwarder class must be registered for embedding");
    }

    @Test
    @DisplayName("Forge 1.21.9 shim leaves Entity.level() alone on Mojang-named hosts")
    void entityLevelIsNotRenamedToYarnName() {
        new Forge_1_21_8_to_1_21_9().registerRedirects(transformer);
        List<AbstractInsnNode> insns = transformBody(mv -> {
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKEVIRTUAL, ENTITY, "level", LEVEL_DESC, false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        });

        assertNotNull(firstCall(insns, ENTITY, "level"),
                "Entity.level() exists on every Mojang-named host and must not be rewritten");
        assertNull(firstCall(insns, ENTITY, "getEntityWorld"),
                "getEntityWorld is a Yarn name that no Mojang-named host declares");
    }

    /** Stand-in for the 26.2 ChatFormatting: same constant order, toString is the section code. */
    public enum StubFormatting {
        BLACK('0'), DARK_BLUE('1'), DARK_GREEN('2'), DARK_AQUA('3'), DARK_RED('4'),
        DARK_PURPLE('5'), GOLD('6'), GRAY('7'), DARK_GRAY('8'), BLUE('9'), GREEN('a'), AQUA('b'),
        RED('c'), LIGHT_PURPLE('d'), YELLOW('e'), WHITE('f'), OBFUSCATED('k'), BOLD('l'),
        STRIKETHROUGH('m'), UNDERLINE('n'), ITALIC('o'), RESET('r');

        private final char code;

        StubFormatting(char code) {
            this.code = code;
        }

        @Override
        public String toString() {
            return "§" + code;
        }
    }

    /** Stand-in for the 26.2 TextColor: colors map to a value, everything else to null. */
    public static final class StubColor {
        private final int value;

        private StubColor(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        static Integer valueOf(StubFormatting formatting) {
            return 0x100000 + formatting.ordinal();
        }

        public static StubColor fromLegacyFormat(StubFormatting formatting) {
            return formatting.ordinal() < 16 ? new StubColor(valueOf(formatting)) : null;
        }
    }

    private Class<?> loadBridgeAgainstStubs() {
        SimpleRemapper toStubs = new SimpleRemapper(Map.of(
                CHAT_FORMATTING, StubFormatting.class.getName().replace('.', '/'),
                "net/minecraft/network/chat/TextColor", StubColor.class.getName().replace('.', '/')));
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(LegacyChatFormattingBridge.generate())
                .accept(new ClassRemapper(writer, toStubs), 0);
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(LegacyChatFormattingBridge.INTERNAL.replace('/', '.'),
                        bytes, 0, bytes.length);
            }
        }.define();
    }

    private List<AbstractInsnNode> transformBody(java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, "test/Caller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "call", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = transformer.transformClass(cw.toByteArray(), "test/Caller");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        List<AbstractInsnNode> insns = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (m.name.equals("call")) m.instructions.forEach(insns::add);
        }
        return insns;
    }

    private static MethodInsnNode firstCall(List<AbstractInsnNode> insns, String owner,
            String name) {
        for (AbstractInsnNode insn : insns) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name)) {
                return call;
            }
        }
        return null;
    }
}
