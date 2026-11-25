/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyLog;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Function;

/** Old text, graphics and sprite calls that took or made a buffer source, each rebuilt where the host removed it. */
public final class LegacyStandInCallsBridge {

    static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyStandInCalls";
    static final String FONT = "net/minecraft/client/gui/Font";
    static final String SPRITE_ID = "net/minecraft/client/resources/model/sprite/SpriteId";
    static final String OLD_MATERIAL = "net/minecraft/client/resources/model/Material";
    static final String EXTRACTOR = "net/minecraft/client/gui/GuiGraphicsExtractor";
    static final String OLD_GRAPHICS = "net/minecraft/client/gui/GuiGraphics";
    static final String HOST_BUFFERS = "net/minecraft/client/renderer/MultiBufferSource";
    static final String HOST_BUFFER_SOURCE = HOST_BUFFERS + "$BufferSource";
    static final String MINECRAFT = "Lnet/minecraft/client/Minecraft;";
    static final String OBJECT = "Ljava/lang/Object;";
    static final String VERTEX_CONSUMER = "Lcom/mojang/blaze3d/vertex/VertexConsumer;";
    static final String SPRITE = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;";
    static final String RENDER_STATE_CTOR = "(" + MINECRAFT + "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;II)V";
    static final String ITEM_IN_HAND = "net/minecraft/client/renderer/ItemInHandRenderer";
    static final String LEGACY_ITEM_IN_HAND = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyItemInHandRenderer";
    static final String HOST_ITEM_IN_HAND = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyHostItemInHand";
    static final String RENDERER_CONTEXT = "net/minecraft/client/renderer/entity/EntityRendererProvider$Context";
    static final String ENTITY_DISPATCHER = "net/minecraft/client/renderer/entity/EntityRenderDispatcher";
    static final String CONTEXTS = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyRendererContexts";
    static final String HEAD_LAYER = "net/minecraft/client/renderer/entity/layers/CustomHeadLayer";
    static final String[] HELD_ITEM_LAYERS = {"net/minecraft/client/renderer/entity/layers/ItemInHandLayer",
            "net/minecraft/client/renderer/entity/layers/PlayerItemInHandLayer", "net/minecraft/client/renderer/entity/layers/CrossedArmsItemLayer"};
    static final String RENDER_ITEM_ARGS = "Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;";
    static final String[] TEXTS = {"Ljava/lang/String;", "Lnet/minecraft/network/chat/Component;",
            "Lnet/minecraft/util/FormattedCharSequence;"};
    // 1.21.1's draw: text, x, y, color, shadow, matrix, buffers, display mode, background and light, returning the end x.
    static final String DRAW_TAIL = "FFIZLorg/joml/Matrix4f;L%s;Lnet/minecraft/client/gui/Font$DisplayMode;II)";

    private LegacyStandInCallsBridge() {}

    /** Registers each call the host lacks; the helper names 26.x classes, so it runs from the 26.1 shims on every loader. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        boolean keepsBuffers = HostBuffers.keeps(host);
        // The helper takes the host's MultiBufferSource on 26.1 and the stand-in from 26.2.
        String helperBuffers = keepsBuffers ? HOST_BUFFERS : RenderBufferSynthetics.MBS;
        registerSprites(t, host, helperBuffers);
        registerTextDraws(t, host, keepsBuffers);
        registerGraphics(t, host);
        registerItemInHand(t, host, helperBuffers);
    }

    /** 1.21.2 dropped the context's item-in-hand getter, 1.21.5 the buffer-source renderItem, and 26.3 the whole class. */
    static void registerItemInHand(RetromodTransformer t, Function<String, ClassNode> host, String helperBuffers) {
        boolean classGone = host.apply(HostBuffers.CLIENT_ANCHOR) != null ? host.apply(ITEM_IN_HAND) == null : targetAtLeast("26.3");
        String getter = "()L" + ITEM_IN_HAND + ";";
        RebuiltApiClasses.registerTree(t, LEGACY_ITEM_IN_HAND, HostBuffers.names(host));
        registerItemInHandConstructors(t, host);
        if (classGone) {
            // The stand-in keeps the old renderItem as its own method, so only the getters need routing.
            t.registerClassRedirect(ITEM_IN_HAND, LEGACY_ITEM_IN_HAND);
            String get = "(" + OBJECT + ")L" + LEGACY_ITEM_IN_HAND + ";";
            for (String owner : new String[]{RENDERER_CONTEXT, ENTITY_DISPATCHER}) {
                t.registerMethodRedirect(owner, "getItemInHandRenderer", getter, LEGACY_ITEM_IN_HAND, "get", get, true);
            }
            t.registerStandInType(LEGACY_ITEM_IN_HAND, LEGACY_ITEM_IN_HAND + ".get" + get);
            return;
        }
        // The host's renderer draws through a submit collector, so the old buffer-source draw goes to the rebuilt one.
        String helperDesc = "(" + OBJECT + RENDER_ITEM_ARGS + "L" + helperBuffers + ";I)V";
        for (String buffers : new String[]{HOST_BUFFERS, RenderBufferSynthetics.MBS}) {
            t.registerMethodRedirect(ITEM_IN_HAND, "renderItem", "(" + RENDER_ITEM_ARGS + "L" + buffers + ";I)V",
                    LEGACY_ITEM_IN_HAND, "renderItem", helperDesc, true);
        }
        if (lacks(host.apply(RENDERER_CONTEXT), "getItemInHandRenderer", getter, "26.1")) {
            RebuiltApiClasses.registerTree(t, HOST_ITEM_IN_HAND);
            t.registerMethodRedirect(RENDERER_CONTEXT, "getItemInHandRenderer", getter, HOST_ITEM_IN_HAND, "get",
                    "(" + OBJECT + ")L" + ITEM_IN_HAND + ";", true);
        }
    }

    /**
     * 1.21.4 dropped the renderer argument of the held-item layers and 1.21.2 rebuilt the renderer context, so constructors
     * that took the old renderers under either name map onto the host's.
     */
    static void registerItemInHandConstructors(RetromodTransformer t, Function<String, ClassNode> host) {
        String parent = "Lnet/minecraft/client/renderer/entity/RenderLayerParent;";
        String[] inHands = {ITEM_IN_HAND, LEGACY_ITEM_IN_HAND};
        // A held-item layer builds the same with only its parent: the renderer argument is dropped.
        for (String layer : HELD_ITEM_LAYERS) {
            if (!has(host.apply(layer), "<init>", "(" + parent + ")V", "26.1")) continue;
            for (String inHand : inHands) {
                t.registerSuperConstructorAdapter(layer, "(" + parent + "L" + inHand + ";)V", "(" + parent + ")V",
                        mv -> mv.visitInsn(Opcodes.POP));
            }
        }
        // A custom head layer takes the player skin cache where it took the renderer.
        String models = "Lnet/minecraft/client/model/geom/EntityModelSet;";
        String skins = "Lnet/minecraft/client/renderer/PlayerSkinRenderCache;";
        if (has(host.apply(HEAD_LAYER), "<init>", "(" + parent + models + skins + ")V", "26.1")) {
            registerContexts(t);
            for (String inHand : inHands) {
                t.registerSuperConstructorAdapter(HEAD_LAYER, "(" + parent + models + "L" + inHand + ";)V", "(" + parent + models + skins + ")V",
                        mv -> {
                            mv.visitInsn(Opcodes.POP);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXTS, "skinCache", "()" + skins, false);
                        });
            }
        }
        // The 1.20.1 and 1.21.1 renderer context is built as the host's, from the game's own parts.
        String oldContext = "(Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;L%s;L%s;L%s;"
                + "Lnet/minecraft/server/packs/resources/ResourceManager;" + models + "Lnet/minecraft/client/gui/Font;)V";
        if (!lacks(host.apply(RENDERER_CONTEXT), "<init>", oldContext.formatted(LegacyItemDrawBridge.OLD_ITEM_RENDERER,
                LegacyBakedItemBridge.OLD_BLOCK_DISPATCHER, ITEM_IN_HAND), "26.1")) return;
        registerContexts(t);
        String factory = "(" + OBJECT.repeat(7) + ")L" + RENDERER_CONTEXT + ";";
        for (String items : new String[]{LegacyItemDrawBridge.OLD_ITEM_RENDERER, LegacyItemDrawBridge.ITEMS}) {
            for (String blocks : new String[]{LegacyBakedItemBridge.OLD_BLOCK_DISPATCHER, LegacyBakedItemBridge.BLOCKS}) {
                for (String inHand : inHands) {
                    t.registerConstructorRedirect(RENDERER_CONTEXT, oldContext.formatted(items, blocks, inHand), CONTEXTS, "create", factory);
                }
            }
        }
    }

    // The context helper warns through the shared log when a part is missing, so the log class travels with it.
    private static void registerContexts(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, CONTEXTS);
        SyntheticEmbedder.registerClassResource(t, LegacyLog.class.getName().replace('.', '/'), LegacyLog.class);
    }

    /** 1.21.9 dropped Material.sprite() and buffer(buffers, types); 26.1 renamed the class SpriteId. */
    static void registerSprites(RetromodTransformer t, Function<String, ClassNode> host, String helperBuffers) {
        ClassNode spriteId = host.apply(SPRITE_ID);
        if (!lacks(spriteId, "sprite", "()" + SPRITE, "26.1")) return;
        registerHelper(t, host);
        String spriteArg = "(L" + SPRITE_ID + ";";
        String bufferHelper = spriteArg + "L" + helperBuffers + ";Ljava/util/function/Function;)" + VERTEX_CONSUMER;
        // Forge and NeoForge mods name the old class until the remap renames it.
        for (String owner : new String[]{SPRITE_ID, OLD_MATERIAL}) {
            t.registerMethodRedirect(owner, "sprite", "()" + SPRITE, HELPER, "sprite", spriteArg + ")" + SPRITE, true);
            for (String buffers : new String[]{HOST_BUFFERS, RenderBufferSynthetics.MBS}) {
                t.registerMethodRedirect(owner, "buffer", "(L" + buffers + ";Ljava/util/function/Function;)" + VERTEX_CONSUMER,
                        HELPER, "buffer", bufferHelper, true);
            }
        }
    }

    /** 26.1 made Font.drawInBatch return nothing and take a Matrix4fc; 26.2 removed it with the buffer source. */
    static void registerTextDraws(RetromodTransformer t, Function<String, ClassNode> host, boolean keepsBuffers) {
        ClassNode font = host.apply(FONT);
        for (String text : TEXTS) {
            String oldDraw = "(" + text + DRAW_TAIL.formatted(HOST_BUFFERS) + "I";
            String voidDraw = "(" + text + DRAW_TAIL.formatted(HOST_BUFFERS).replace("Matrix4f;", "Matrix4fc;") + "V";
            if (keepsBuffers) {
                // 26.1 still draws; the old result was the end x, which 0 stands in for, as on 1.21.6 to 1.21.11.
                if (font == null || has(font, "drawInBatch", voidDraw)) {
                    t.registerConvertingRedirect(FONT, "drawInBatch", oldDraw, FONT, "drawInBatch", voidDraw, 0, Opcodes.ICONST_0);
                }
                continue;
            }
            if (font != null && hasNamed(font, "drawInBatch")) continue;
            registerHelper(t, host);
            String helperDesc = "(L" + FONT + ";" + text + "FFIZ" + OBJECT + OBJECT + OBJECT + "II)I";
            for (String buffers : new String[]{HOST_BUFFERS, RenderBufferSynthetics.MBS}) {
                t.registerMethodRedirect(FONT, "drawInBatch", "(" + text + DRAW_TAIL.formatted(buffers) + "I",
                        HELPER, "drawInBatch", helperDesc, true);
            }
        }
    }

    /** 1.21.6 replaced the buffer-source constructors of GuiGraphics with a render state; 26.1 renamed the class. */
    static void registerGraphics(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode extractor = host.apply(EXTRACTOR);
        boolean renderStateHost = extractor != null ? has(extractor, "<init>", RENDER_STATE_CTOR) : targetAtLeast("26.1");
        if (!renderStateHost) return;
        registerHelper(t, host);
        String factory = "(" + MINECRAFT + OBJECT + ")L" + EXTRACTOR + ";";
        String poseFactory = "(" + MINECRAFT + OBJECT + OBJECT + ")L" + EXTRACTOR + ";";
        for (String source : new String[]{HOST_BUFFER_SOURCE, RenderBufferSynthetics.BS}) {
            for (String owner : new String[]{EXTRACTOR, OLD_GRAPHICS}) {
                t.registerConstructorRedirect(owner, "(" + MINECRAFT + "L" + source + ";)V", HELPER, "graphics", factory);
                t.registerConstructorRedirect(owner, "(" + MINECRAFT + "Lcom/mojang/blaze3d/vertex/PoseStack;L" + source + ";)V",
                        HELPER, "graphics", poseFactory);
            }
        }
    }

    private static void registerHelper(RetromodTransformer t, Function<String, ClassNode> host) {
        RebuiltApiClasses.registerTree(t, HELPER, HostBuffers.names(host));
    }

    private static boolean lacks(ClassNode owner, String name, String desc, String since) {
        return owner == null ? targetAtLeast(since) : !has(owner, name, desc);
    }

    // An unread class answers by the target, as lacks does.
    private static boolean has(ClassNode owner, String name, String desc, String since) {
        return owner == null ? targetAtLeast(since) : has(owner, name, desc);
    }

    private static boolean has(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static boolean hasNamed(ClassNode owner, String name) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name)) return true;
        }
        return false;
    }

    private static boolean targetAtLeast(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) >= 0;
    }
}
