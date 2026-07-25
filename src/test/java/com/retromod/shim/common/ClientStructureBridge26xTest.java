/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.minecraft.RetroClientEnv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * 26.x client-structure bridges for the top structural residuals of the 87/90-mod corpus audits:
 * {@code Minecraft.screen} (31 mods read / 6 write; moved to {@code Gui.screen} with public
 * {@code screen()}/{@code setScreen}, reached via the public final {@code Minecraft.gui} field),
 * {@code Options.hideGui} (12 read / 1 write; moved to {@code Hud.isHidden} with public
 * {@code isHidden()}/{@code toggle()}, no absolute setter), and {@code Minecraft.ON_OSX} (10 mods,
 * GETSTATIC-only; successor {@code InputQuirks.ON_OSX} is private). Ground truth: javap of the real
 * 26.2 and 26.1-snapshot-10 jars. screen/hideGui changed AT 26.2 (both still public on
 * 26.1-snapshot-10) so they live in {@link Mc26_1To26_2CoreMoves}; ON_OSX was already gone at 26.1
 * so it lives in {@link Common_1_21_11_to_26_1_ClassMoves}.
 */
public class ClientStructureBridge26xTest {

    private static final String MC = "net/minecraft/client/Minecraft";
    private static final String GUI = "net/minecraft/client/gui/Gui";
    private static final String OPTIONS = "net/minecraft/client/Options";
    private static final String SCREEN_DESC = "Lnet/minecraft/client/gui/screens/Screen;";
    private static final String ENV = "com/retromod/polyfill/minecraft/RetroClientEnv";

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

    /** Build test/Caller with one static method body, transform, return that method's insns. */
    private List<AbstractInsnNode> transformBody(String methodDesc,
            java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/Caller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "call", methodDesc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = transformer.transformClass(cw.toByteArray(), "test/Caller");
        ClassNode cn = new ClassNode();
        assertDoesNotThrow(() -> new ClassReader(out).accept(cn, 0));
        List<AbstractInsnNode> insns = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (!m.name.equals("call")) continue;
            for (AbstractInsnNode i : m.instructions.toArray()) insns.add(i);
        }
        return insns;
    }

    private static FieldInsnNode firstField(List<AbstractInsnNode> insns, String name) {
        for (AbstractInsnNode i : insns) {
            if (i instanceof FieldInsnNode fi && fi.name.equals(name)) return fi;
        }
        return null;
    }

    private static MethodInsnNode firstCall(List<AbstractInsnNode> insns, String owner, String name) {
        for (AbstractInsnNode i : insns) {
            if (i instanceof MethodInsnNode mi && mi.owner.equals(owner) && mi.name.equals(name)) {
                return mi;
            }
        }
        return null;
    }

    @Test
    @DisplayName("GETFIELD Minecraft.screen hops through mc.gui to Gui.screen()")
    void screenReadHopsThroughGui() {
        Mc26_1To26_2CoreMoves.register(transformer);
        List<AbstractInsnNode> insns = transformBody("(L" + MC + ";)" + SCREEN_DESC, mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, MC, "screen", SCREEN_DESC);
            mv.visitInsn(ARETURN);
        });

        assertNull(firstField(insns, "screen"), "the raw field read must be gone");
        FieldInsnNode hop = firstField(insns, "gui");
        assertNotNull(hop, "expected the GETFIELD Minecraft.gui hop");
        assertEquals(GETFIELD, hop.getOpcode());
        assertEquals(MC, hop.owner);
        MethodInsnNode getter = firstCall(insns, GUI, "screen");
        assertNotNull(getter, "expected INVOKEVIRTUAL Gui.screen()");
        assertEquals(INVOKEVIRTUAL, getter.getOpcode());
        assertEquals("()" + SCREEN_DESC, getter.desc);
    }

    @Test
    @DisplayName("PUTFIELD Minecraft.screen becomes SWAP + gui hop + SWAP + Gui.setScreen(Screen)")
    void screenWriteRoutesThroughSetScreen() {
        Mc26_1To26_2CoreMoves.register(transformer);
        List<AbstractInsnNode> insns = transformBody("(L" + MC + ";" + SCREEN_DESC + ")V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(PUTFIELD, MC, "screen", SCREEN_DESC);
            mv.visitInsn(RETURN);
        });

        assertNull(firstField(insns, "screen"), "the raw field write must be gone");
        MethodInsnNode setter = firstCall(insns, GUI, "setScreen");
        assertNotNull(setter, "expected INVOKEVIRTUAL Gui.setScreen");
        assertEquals("(" + SCREEN_DESC + ")V", setter.desc);
        // The exact splice: SWAP, GETFIELD gui, SWAP, INVOKEVIRTUAL (receiver under value fixed up).
        long swaps = insns.stream().filter(i -> i instanceof InsnNode && i.getOpcode() == SWAP).count();
        assertEquals(2, swaps, "the receiver/value SWAP pair must be spliced in");
        FieldInsnNode hop = firstField(insns, "gui");
        assertNotNull(hop);
        assertEquals(GETFIELD, hop.getOpcode());
    }

    @Test
    @DisplayName("GETFIELD Options.hideGui becomes INVOKESTATIC RetroClientEnv.isHideGui (receiver consumed)")
    void hideGuiReadBecomesStaticBridge() {
        Mc26_1To26_2CoreMoves.register(transformer);
        List<AbstractInsnNode> insns = transformBody("(L" + OPTIONS + ";)Z", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, OPTIONS, "hideGui", "Z");
            mv.visitInsn(IRETURN);
        });

        assertNull(firstField(insns, "hideGui"), "the raw field read must be gone");
        MethodInsnNode bridge = firstCall(insns, ENV, "isHideGui");
        assertNotNull(bridge, "expected INVOKESTATIC RetroClientEnv.isHideGui");
        assertEquals(INVOKESTATIC, bridge.getOpcode());
        assertEquals("(Ljava/lang/Object;)Z", bridge.desc);
    }

    @Test
    @DisplayName("PUTFIELD Options.hideGui becomes INVOKESTATIC RetroClientEnv.setHideGui (opcode-aware)")
    void hideGuiWriteBecomesStaticBridge() {
        Mc26_1To26_2CoreMoves.register(transformer);
        List<AbstractInsnNode> insns = transformBody("(L" + OPTIONS + ";Z)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ILOAD, 1);
            mv.visitFieldInsn(PUTFIELD, OPTIONS, "hideGui", "Z");
            mv.visitInsn(RETURN);
        });

        assertNull(firstField(insns, "hideGui"), "the raw field write must be gone");
        MethodInsnNode bridge = firstCall(insns, ENV, "setHideGui");
        assertNotNull(bridge, "expected INVOKESTATIC RetroClientEnv.setHideGui");
        assertEquals("(Ljava/lang/Object;Z)V", bridge.desc);
        // The synthetic must be registered so the per-mod embedder can carry the bridge class.
        assertTrue(transformer.getSyntheticClasses().containsKey(ENV),
                "RetroClientEnv must be registered as an embeddable synthetic");
    }

    @Test
    @DisplayName("GETSTATIC Minecraft.ON_OSX becomes INVOKESTATIC RetroClientEnv.isOsx (26.1 epoch)")
    void onOsxReadBecomesIsOsx() {
        Common_1_21_11_to_26_1_ClassMoves.register(transformer);
        List<AbstractInsnNode> insns = transformBody("()Z", mv -> {
            mv.visitFieldInsn(GETSTATIC, MC, "ON_OSX", "Z");
            mv.visitInsn(IRETURN);
        });

        assertNull(firstField(insns, "ON_OSX"), "the raw static read must be gone");
        MethodInsnNode bridge = firstCall(insns, ENV, "isOsx");
        assertNotNull(bridge, "expected INVOKESTATIC RetroClientEnv.isOsx");
        assertEquals("()Z", bridge.desc);
    }

    @Test
    @DisplayName("epoch gate: the 26.1 common shim alone leaves screen/hideGui untouched (still public on 26.1)")
    void epochGatingLeaves261FieldsAlone() {
        Common_1_21_11_to_26_1_ClassMoves.register(transformer);
        List<AbstractInsnNode> read = transformBody("(L" + MC + ";)" + SCREEN_DESC, mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, MC, "screen", SCREEN_DESC);
            mv.visitInsn(ARETURN);
        });
        FieldInsnNode screen = firstField(read, "screen");
        assertNotNull(screen, "on a 26.1 target the public Minecraft.screen field still exists");
        assertEquals(MC, screen.owner);

        List<AbstractInsnNode> hide = transformBody("(L" + OPTIONS + ";)Z", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, OPTIONS, "hideGui", "Z");
            mv.visitInsn(IRETURN);
        });
        assertNotNull(firstField(hide, "hideGui"),
                "on a 26.1 target the public Options.hideGui field still exists");
    }

    @Test
    @DisplayName("hop-only / bridge-only registration still transforms (early-exit regression)")
    void hopOnlyRegistrationTransforms() {
        // The no-redirects fast path in transformClass must know about the two new maps; a hop-only
        // or bridge-only registration previously returned byte-identical input (review finding).
        transformer.registerFieldHopAccessor(MC, "screen",
                "gui", "Lnet/minecraft/client/gui/Gui;",
                "screen", "()" + SCREEN_DESC, "setScreen", "(" + SCREEN_DESC + ")V");
        List<AbstractInsnNode> insns = transformBody("(L" + MC + ";)" + SCREEN_DESC, mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, MC, "screen", SCREEN_DESC);
            mv.visitInsn(ARETURN);
        });
        assertNotNull(firstCall(insns, GUI, "screen"),
                "a hop-only redirect set must not hit the no-redirects early exit");

        transformer.clearRedirectsForTesting();
        transformer.registerFieldStaticBridge(OPTIONS, "hideGui", ENV,
                "isHideGui", "(Ljava/lang/Object;)Z", "setHideGui", "(Ljava/lang/Object;Z)V");
        List<AbstractInsnNode> bridge = transformBody("(L" + OPTIONS + ";)Z", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, OPTIONS, "hideGui", "Z");
            mv.visitInsn(IRETURN);
        });
        assertNotNull(firstCall(bridge, ENV, "isHideGui"),
                "a bridge-only redirect set must not hit the no-redirects early exit");
    }

    @Test
    @DisplayName("Gui->Hud family: INVOKEVIRTUAL Gui.getChat/setTitle route through the generated GuiToHudHop forwarder")
    void guiToHudFamilyHops() {
        Mc26_1To26_2CoreMoves.register(transformer);
        String hop = "com/retromod/generated/GuiToHudHop";
        assertTrue(transformer.getSyntheticClasses().containsKey(hop),
                "the forwarder synthetic must be registered for per-mod embedding");

        // Zero-arg accessor: mc.gui.getChat() (the way client mods print chat messages).
        List<AbstractInsnNode> chat = transformBody(
                "(L" + GUI + ";)Lnet/minecraft/client/gui/components/ChatComponent;", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, GUI, "getChat",
                    "()Lnet/minecraft/client/gui/components/ChatComponent;", false);
            mv.visitInsn(ARETURN);
        });
        MethodInsnNode call = firstCall(chat, hop, "getChat");
        assertNotNull(call, "getChat must retarget to the forwarder");
        assertEquals(INVOKESTATIC, call.getOpcode(), "receiver-as-arg0 must auto-devirtualize");
        assertEquals("(L" + GUI + ";)Lnet/minecraft/client/gui/components/ChatComponent;", call.desc);

        // Arg-carrying: gui.setTimes(int,int,int) (title timing API).
        List<AbstractInsnNode> times = transformBody("(L" + GUI + ";III)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ILOAD, 1);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitVarInsn(ILOAD, 3);
            mv.visitMethodInsn(INVOKEVIRTUAL, GUI, "setTimes", "(III)V", false);
            mv.visitInsn(RETURN);
        });
        MethodInsnNode st = firstCall(times, hop, "setTimes");
        assertNotNull(st);
        assertEquals(INVOKESTATIC, st.getOpcode());
        assertEquals("(L" + GUI + ";III)V", st.desc);

        // The generated forwarder itself must be well-formed: parse it and check one body shape.
        ClassNode cn = new ClassNode();
        new ClassReader(transformer.getSyntheticClasses().get(hop)).accept(cn, 0);
        MethodNode fwd = cn.methods.stream().filter(m -> m.name.equals("setTimes")).findFirst().orElse(null);
        assertNotNull(fwd);
        boolean getsHud = false, callsHud = false;
        for (AbstractInsnNode i : fwd.instructions.toArray()) {
            if (i instanceof FieldInsnNode fi && fi.name.equals("hud")) getsHud = true;
            if (i instanceof MethodInsnNode mi && mi.owner.equals("net/minecraft/client/gui/Hud")
                    && mi.name.equals("setTimes")) callsHud = true;
        }
        assertTrue(getsHud && callsHud, "forwarder body must be g.hud.setTimes(...)");
    }

    @Test
    @DisplayName("BlockItemTags family: deleted BlockTags/ItemTags constants become BlockItemTags.X.block()/item()")
    void blockItemTagsFamily() {
        Mc26_1To26_2CoreMoves.register(transformer);
        String tagKey = "Lnet/minecraft/tags/TagKey;";

        List<AbstractInsnNode> logs = transformBody("()" + tagKey, mv -> {
            mv.visitFieldInsn(GETSTATIC, "net/minecraft/tags/BlockTags", "LOGS_THAT_BURN", tagKey);
            mv.visitInsn(ARETURN);
        });
        FieldInsnNode moved = firstField(logs, "LOGS_THAT_BURN");
        assertNotNull(moved, "the constant read must retarget to BlockItemTags");
        assertEquals("net/minecraft/tags/BlockItemTags", moved.owner);
        MethodInsnNode block = firstCall(logs, "net/minecraft/tags/BlockItemTagId", "block");
        assertNotNull(block, "the .block() accessor must be appended");

        List<AbstractInsnNode> doors = transformBody("()" + tagKey, mv -> {
            mv.visitFieldInsn(GETSTATIC, "net/minecraft/tags/ItemTags", "DOORS", tagKey);
            mv.visitInsn(ARETURN);
        });
        assertEquals("net/minecraft/tags/BlockItemTags", firstField(doors, "DOORS").owner);
        assertNotNull(firstCall(doors, "net/minecraft/tags/BlockItemTagId", "item"),
                "an ItemTags constant must use .item()");

        // A constant that SURVIVED on 26.2 BlockTags (LOGS) must NOT be redirected.
        List<AbstractInsnNode> survived = transformBody("()" + tagKey, mv -> {
            mv.visitFieldInsn(GETSTATIC, "net/minecraft/tags/BlockTags", "LOGS", tagKey);
            mv.visitInsn(ARETURN);
        });
        assertEquals("net/minecraft/tags/BlockTags", firstField(survived, "LOGS").owner,
                "surviving constants stay put");
    }

    @Test
    @DisplayName("PlayerSkin accessors: texture() -> body() + interface-aware texturePath() unwrap")
    void playerSkinAccessorUnwrap() {
        Common_1_21_11_to_26_1_ClassMoves.register(transformer);
        String skin = "net/minecraft/world/entity/player/PlayerSkin";
        String asset = "net/minecraft/core/ClientAsset$Texture";
        List<AbstractInsnNode> insns = transformBody(
                "(L" + skin + ";)Lnet/minecraft/resources/ResourceLocation;", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, skin, "texture",
                    "()Lnet/minecraft/resources/ResourceLocation;", false);
            mv.visitInsn(ARETURN);
        });
        MethodInsnNode body = firstCall(insns, skin, "body");
        assertNotNull(body, "texture() must retarget to body()");
        MethodInsnNode unwrap = firstCall(insns, asset, "texturePath");
        assertNotNull(unwrap, "the ClientAsset$Texture wrapper must be unwrapped");
        assertEquals(INVOKEINTERFACE, unwrap.getOpcode(),
                "ClientAsset$Texture is an interface: the unwrap must be INVOKEINTERFACE");
        assertTrue(unwrap.itf);
    }

    @Test
    @DisplayName("class-move tsv: PlayerSkin move, $Model->PlayerModelType, ReceivingLevelScreen, GlStateManager hybrids")
    void newClassMovesPresent() {
        var moves = com.retromod.mapping.IntermediaryToMojangMapper.getInstance().getClassMoves();
        assertEquals("net/minecraft/world/entity/player/PlayerSkin",
                moves.get("net/minecraft/client/resources/PlayerSkin"));
        assertEquals("net/minecraft/world/entity/player/PlayerModelType",
                moves.get("net/minecraft/client/resources/PlayerSkin$Model"));
        assertEquals("net/minecraft/client/gui/screens/LevelLoadingScreen",
                moves.get("net/minecraft/client/gui/screens/ReceivingLevelScreen"));
        // The literal intermediary-inner hybrids distributed Fabric mods carry (blaze3d outers are
        // plain in intermediary, the inners are not).
        assertEquals("com/mojang/blaze3d/platform/SourceFactor",
                moves.get("com/mojang/blaze3d/platform/GlStateManager$class_4535"));
        assertEquals("com/mojang/blaze3d/platform/DestFactor",
                moves.get("com/mojang/blaze3d/platform/GlStateManager$class_4534"));
    }

    @Test
    @DisplayName("26.2 blend-factor teardown: enum blendFunc neutralized + enum GETSTATIC nulled")
    void blendFactorTeardown26_2() {
        Mc26_1To26_2CoreMoves.register(transformer);
        RemovedRenderStateNeutralize.register(transformer);
        String src = "com/mojang/blaze3d/platform/SourceFactor";
        String dst = "com/mojang/blaze3d/platform/DestFactor";
        List<AbstractInsnNode> insns = transformBody("()V", mv -> {
            mv.visitFieldInsn(GETSTATIC, src, "SRC_ALPHA", "L" + src + ";");
            mv.visitFieldInsn(GETSTATIC, dst, "ONE_MINUS_SRC_ALPHA", "L" + dst + ";");
            mv.visitMethodInsn(INVOKESTATIC, "com/mojang/blaze3d/systems/RenderSystem",
                    "blendFunc", "(L" + src + ";L" + dst + ";)V", false);
            mv.visitInsn(RETURN);
        });
        assertNull(firstField(insns, "SRC_ALPHA"), "the enum GETSTATIC must be nulled, not kept");
        assertNull(firstCall(insns, "com/mojang/blaze3d/systems/RenderSystem", "blendFunc"),
                "the enum blendFunc overload must be neutralized");
    }

    @Test
    @DisplayName("RetroClientEnv: isOsx matches os.name; hideGui accessors soft-fail without Minecraft")
    void retroClientEnvFailSafe() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        boolean expected = os.contains("mac") || os.contains("darwin") || os.contains("os x");
        assertEquals(expected, RetroClientEnv.isOsx(),
                "isOsx must mirror the os.name computation vanilla's Util.getPlatform used");

        // No Minecraft on the test classpath: read soft-fails to false, write is a no-op, no throw.
        assertFalse(RetroClientEnv.isHideGui(new Object()));
        assertDoesNotThrow(() -> RetroClientEnv.setHideGui(new Object(), true));
    }
}
