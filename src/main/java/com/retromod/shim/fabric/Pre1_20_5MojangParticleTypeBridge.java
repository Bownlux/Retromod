/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.fabric.embedded.LegacyParticleSupport;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Bridges the pre-1.20.5 custom particle API for Mojang-named mods, such as a Forge 1.20.1 mod
 * after its SRG remap on a NeoForge host.
 *
 * <p>The change and the fix are the ones {@link Pre1_20_5ParticleTypeBridge} describes: the removed
 * {@code ParticleOptions.Deserializer} becomes a generated stand-in, and the mod's particle types
 * are rebased onto a generated base that turns the old codec and network methods into the 1.20.5
 * codecs. This class reuses that bridge's generated classes with Mojang names. Its limits are the
 * same: command-style options only parse for the mod's own legacy particles and simple particles.
 */
public final class Pre1_20_5MojangParticleTypeBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String PARTICLE_TYPE = "net/minecraft/core/particles/ParticleType";
    static final String OLD_DESERIALIZER = "net/minecraft/core/particles/ParticleOptions$Deserializer";
    static final String SIMPLE_TYPE = "net/minecraft/core/particles/SimpleParticleType";

    private static final Map<String, String> CLASSES = Map.of(
            Pre1_20_5ParticleTypeBridge.PARTICLE_TYPE, PARTICLE_TYPE,
            Pre1_20_5ParticleTypeBridge.PARTICLE_OPTIONS, "net/minecraft/core/particles/ParticleOptions",
            Pre1_20_5ParticleTypeBridge.OLD_DESERIALIZER, OLD_DESERIALIZER,
            Pre1_20_5ParticleTypeBridge.STREAM_CODEC, "net/minecraft/network/codec/StreamCodec",
            Pre1_20_5ParticleTypeBridge.FRIENDLY_BYTE_BUF, "net/minecraft/network/FriendlyByteBuf",
            Pre1_20_5ParticleTypeBridge.SIMPLE_TYPE, SIMPLE_TYPE);

    /** The generated classes declare and call these under their intermediary names. */
    private static final Map<String, String> MEMBERS = Map.of(
            Pre1_20_5ParticleTypeBridge.DESERIALIZER + "." + Pre1_20_5ParticleTypeBridge.FROM_COMMAND, "fromCommand",
            Pre1_20_5ParticleTypeBridge.DESERIALIZER + "." + Pre1_20_5ParticleTypeBridge.FROM_NETWORK, "fromNetwork",
            Pre1_20_5ParticleTypeBridge.DESERIALIZER_VIEW + "." + Pre1_20_5ParticleTypeBridge.FROM_COMMAND, "fromCommand",
            Pre1_20_5ParticleTypeBridge.DESERIALIZER_VIEW + "." + Pre1_20_5ParticleTypeBridge.FROM_NETWORK, "fromNetwork",
            Pre1_20_5ParticleTypeBridge.LEGACY_TYPE + "." + Pre1_20_5ParticleTypeBridge.CODEC_METHOD, "codec",
            Pre1_20_5ParticleTypeBridge.LEGACY_TYPE + "." + Pre1_20_5ParticleTypeBridge.STREAM_CODEC_METHOD, "streamCodec");

    /** Names {@link LegacyParticleSupport} looks up reflectively. */
    private static final Map<String, String> STRINGS = Map.of(
            "net.minecraft.class_2394", "net.minecraft.core.particles.ParticleOptions",
            "method_10294", "writeToNetwork",
            Pre1_20_5ParticleTypeBridge.FROM_COMMAND, "fromCommand",
            Pre1_20_5ParticleTypeBridge.FROM_NETWORK, "fromNetwork");

    private Pre1_20_5MojangParticleTypeBridge() {}

    /** Registers only on a Mojang-named host whose ParticleType has the 1.20.5 codec shape. */
    public static void register(RetromodTransformer transformer) {
        ClassNode particleType = ClassResourceInspector.read(PARTICLE_TYPE);
        if (particleType == null) return;
        if (hasMethod(particleType, "getDeserializer") || !hasMethod(particleType, "streamCodec")
                || ClassResourceInspector.exists(OLD_DESERIALIZER)) {
            LOGGER.debug("The host still supports ParticleOptions.Deserializer");
            return;
        }
        registerRedirects(transformer, simpleTypeConstructorIsHidden(ClassResourceInspector.read(SIMPLE_TYPE)));
        LOGGER.debug("Added Mojang-named support for pre-1.20.5 particle deserializers");
    }

    static void registerRedirects(RetromodTransformer transformer, boolean simpleTypeConstructorHidden) {
        transformer.registerSyntheticClass(Pre1_20_5ParticleTypeBridge.SUPPORT,
                IntermediaryToMojangCopy.translateResource(LegacyParticleSupport.class, CLASSES, MEMBERS, STRINGS));
        transformer.registerSyntheticClass(Pre1_20_5ParticleTypeBridge.DESERIALIZER,
                toMojang(Pre1_20_5ParticleTypeBridge.generateDeserializerInterface()));
        transformer.registerSyntheticClass(Pre1_20_5ParticleTypeBridge.DESERIALIZER_VIEW,
                toMojang(Pre1_20_5ParticleTypeBridge.generateDeserializerView()));
        transformer.registerSyntheticClass(Pre1_20_5ParticleTypeBridge.LEGACY_TYPE,
                toMojang(Pre1_20_5ParticleTypeBridge.generateLegacyParticleType()));

        transformer.registerClassRedirect(OLD_DESERIALIZER, Pre1_20_5ParticleTypeBridge.DESERIALIZER);
        transformer.registerSuperclassRebase(PARTICLE_TYPE, Pre1_20_5ParticleTypeBridge.LEGACY_TYPE);
        transformer.registerMethodRedirect(PARTICLE_TYPE, "getDeserializer", "()L" + OLD_DESERIALIZER + ";",
                Pre1_20_5ParticleTypeBridge.DESERIALIZER_VIEW, "of",
                "(L" + PARTICLE_TYPE + ";)L" + Pre1_20_5ParticleTypeBridge.DESERIALIZER + ";", true);

        if (simpleTypeConstructorHidden) {
            transformer.registerSyntheticClass(Pre1_20_5ParticleTypeBridge.LEGACY_SIMPLE_TYPE,
                    toMojang(Pre1_20_5ParticleTypeBridge.generateLegacySimpleType()));
            transformer.registerConstructorRedirect(SIMPLE_TYPE, "(Z)V",
                    Pre1_20_5ParticleTypeBridge.LEGACY_SIMPLE_TYPE, "create", "(Z)L" + SIMPLE_TYPE + ";");
        }
    }

    static byte[] toMojang(byte[] generated) {
        return IntermediaryToMojangCopy.translate(generated, CLASSES, MEMBERS, STRINGS);
    }

    /** 1.20.5 made {@code SimpleParticleType(boolean)} protected. */
    private static boolean simpleTypeConstructorIsHidden(ClassNode simpleType) {
        if (simpleType == null) return false;
        for (MethodNode method : simpleType.methods) {
            if (method.name.equals("<init>") && method.desc.equals("(Z)V")) {
                return (method.access & Opcodes.ACC_PUBLIC) == 0;
            }
        }
        return false;
    }

    private static boolean hasMethod(ClassNode owner, String name) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name)) return true;
        }
        return false;
    }
}
