/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Function;

/** Bridges old sprite calls: the sprite and sprite-contents constructors, the missing texture, and sprite baking on NeoForge and Fabric. */
public final class LegacySpriteBridge {

    static final String SPRITE = "net/minecraft/client/renderer/texture/TextureAtlasSprite";
    static final String CONTENTS = "net/minecraft/client/renderer/texture/SpriteContents";
    static final String MISSING = "net/minecraft/client/renderer/texture/MissingTextureAtlasSprite";
    static final String MATERIAL_BAKED = "net/minecraft/client/resources/model/sprite/Material$Baked";
    static final String QUAD_BAKING = "net/neoforged/neoforge/client/model/pipeline/QuadBakingVertexConsumer";
    static final String QUAD_EMITTER = "net/fabricmc/fabric/api/client/renderer/v1/mesh/QuadEmitter";
    static final String MUTABLE_QUAD = "net/fabricmc/fabric/api/client/renderer/v1/mesh/MutableQuadView";
    static final String BUILT_IN_REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";

    static final String SPRITES = RebuiltApiClasses.ROOT + "minecraft/client/renderer/texture/LegacySprites";
    static final String QUAD_BAKING_HELPER = RebuiltApiClasses.ROOT + "neoforge/client/model/pipeline/LegacyQuadBaking";
    static final String EMITTERS = RebuiltApiClasses.ROOT + "fabric/client/renderer/LegacyQuadEmitters";

    static final String ID = "Lnet/minecraft/resources/Identifier;";
    static final String SPRITE_ARG = "L" + SPRITE + ";";
    static final String OLD_SPRITE_CTOR = "(" + ID + "L" + CONTENTS + ";IIII)V";
    static final String NEW_SPRITE_CTOR = "(" + ID + "L" + CONTENTS + ";IIIII)V";
    static final String METADATA = "Lnet/minecraft/server/packs/resources/ResourceMetadata;";
    static final String HEAD = "(" + ID + "Lnet/minecraft/client/resources/metadata/animation/FrameSize;Lcom/mojang/blaze3d/platform/NativeImage;";
    static final String OLD_CONTENTS_CTOR = HEAD + METADATA + ")V";
    static final String NEW_CONTENTS_CTOR = HEAD + "Ljava/util/Optional;Ljava/util/List;Ljava/util/Optional;)V";
    static final String DYNAMIC_TEXTURE = "Lnet/minecraft/client/renderer/texture/DynamicTexture;";

    private LegacySpriteBridge() {}

    /** Registers each part whose old member the host lacks; offline, the shim's gate and the target version decide. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read, ClassResourceInspector.read(BUILT_IN_REGISTRIES) != null);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host, boolean gameReadable) {
        ClassNode sprite = host.apply(SPRITE);
        ClassNode contents = host.apply(CONTENTS);
        ClassNode missing = host.apply(MISSING);
        if (replaced(sprite, OLD_SPRITE_CTOR, NEW_SPRITE_CTOR, gameReadable)) registerSpriteConstructor(t);
        if (replaced(contents, OLD_CONTENTS_CTOR, NEW_CONTENTS_CTOR, gameReadable)) registerContentsConstructor(t);
        if (missing == null ? !gameReadable : !hasMethod(missing, "getTexture", "()" + DYNAMIC_TEXTURE)) registerMissingTexture(t);
        // The baking calls take a Material.Baked, which exists from 26.1.
        boolean materials = host.apply(MATERIAL_BAKED) != null || !gameReadable && targetAtLeast("26.1");
        if (!materials) return;
        if (quadBakingTakesMaterial(host.apply(QUAD_BAKING))) registerQuadBaking(t);
        if (emitterTakesMaterial(host.apply(QUAD_EMITTER))) registerEmitters(t);
    }

    /** The old six-argument sprite constructor gains the padding argument, which 0 leaves as before. */
    static void registerSpriteConstructor(RetromodTransformer t) {
        // A stack adapter rather than inserted defaults: the old descriptor can still spell ResourceLocation here.
        t.registerSuperConstructorAdapter(SPRITE, OLD_SPRITE_CTOR, NEW_SPRITE_CTOR, mv -> mv.visitInsn(Opcodes.ICONST_0));
    }

    /** The old metadata argument becomes the animation section, an empty list of extra sections, and the texture section. */
    static void registerContentsConstructor(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, SPRITES);
        t.registerSuperConstructorAdapter(CONTENTS, OLD_CONTENTS_CTOR, NEW_CONTENTS_CTOR, LegacySpriteBridge::splitMetadata);
    }

    /** MissingTextureAtlasSprite.getTexture(), rebuilt from the missing image. */
    static void registerMissingTexture(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, SPRITES);
        t.registerMethodRedirect(MISSING, "getTexture", "()" + DYNAMIC_TEXTURE, SPRITES, "missingTexture", "()" + DYNAMIC_TEXTURE);
    }

    /** NeoForge's setSprite(sprite) becomes setSprite(material). */
    static void registerQuadBaking(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, QUAD_BAKING_HELPER);
        t.registerMethodRedirect(QUAD_BAKING, "setSprite", "(" + SPRITE_ARG + ")V", QUAD_BAKING_HELPER, "setSprite",
                "(L" + QUAD_BAKING + ";" + SPRITE_ARG + ")V", true);
    }

    /** Fabric's spriteBake overloads on QuadEmitter and MutableQuadView become materialBake. */
    static void registerEmitters(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, EMITTERS);
        for (String owner : new String[]{QUAD_EMITTER, MUTABLE_QUAD}) {
            String self = "L" + owner + ";";
            t.registerMethodRedirect(owner, "spriteBake", "(" + SPRITE_ARG + "I)" + self, EMITTERS, "spriteBake",
                    "(" + self + SPRITE_ARG + "I)" + self, true);
            t.registerMethodRedirect(owner, "spriteBake", "(I" + SPRITE_ARG + "I)" + self, EMITTERS, "spriteBake",
                    "(" + self + "I" + SPRITE_ARG + "I)" + self, true);
        }
    }

    /** Turns [metadata] into [animation, extra sections, texture] on the stack before the new constructor runs. */
    static void splitMetadata(MethodVisitor mv) {
        String optional = "Ljava/util/Optional;";
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SPRITES, "animation", "(" + METADATA + ")" + optional, false);
        mv.visitInsn(Opcodes.SWAP);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SPRITES, "texture", "(" + METADATA + ")" + optional, false);
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SPRITES, "additionalSections", "(" + METADATA + ")Ljava/util/List;", false);
        mv.visitInsn(Opcodes.SWAP);
    }

    /** Whether the host has the new constructor and not the old one; offline, true from 1.21.11, where both changes are in place. */
    static boolean replaced(ClassNode owner, String oldDesc, String newDesc, boolean gameReadable) {
        if (owner == null) return !gameReadable && targetAtLeast("1.21.11");
        return !hasMethod(owner, "<init>", oldDesc) && hasMethod(owner, "<init>", newDesc);
    }

    /** NeoForge 26.1 and newer: setSprite takes a Material.Baked and no longer a sprite alone. */
    static boolean quadBakingTakesMaterial(ClassNode consumer) {
        if (consumer == null) return true;
        return !hasMethod(consumer, "setSprite", "(" + SPRITE_ARG + ")V")
                && hasMethod(consumer, "setSprite", "(L" + MATERIAL_BAKED + ";)V");
    }

    /** Fabric API on 26.x: QuadEmitter has materialBake and no spriteBake. */
    static boolean emitterTakesMaterial(ClassNode emitter) {
        if (emitter == null) return true;
        return hasMethodNamed(emitter, "materialBake") && !hasMethodNamed(emitter, "spriteBake");
    }

    private static boolean targetAtLeast(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) >= 0;
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static boolean hasMethodNamed(ClassNode owner, String name) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name)) return true;
        }
        return false;
    }
}
