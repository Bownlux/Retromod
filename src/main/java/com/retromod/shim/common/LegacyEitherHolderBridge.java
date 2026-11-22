/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps a 1.21.x mod's {@code EitherHolder} values working on 26.1, which removed the class.
 *
 * <p>Through 1.21.11 an item could name a registry entry before the registries loaded by wrapping
 * its key: {@code properties.component(DataComponents.DAMAGE_TYPE, new EitherHolder<>(key))}. 26.1
 * deleted {@code EitherHolder}, typed {@code DAMAGE_TYPE} as a plain {@code Holder}, and added
 * {@code Item.Properties.delayedHolderComponent(type, key)} for the key case. The old class then
 * fails with {@code NoClassDefFoundError} while the mod's item class initializes, which stops its
 * entry point.
 *
 * <p>{@code EitherHolder} becomes a generated stand-in with the constructors and accessors of both
 * its 1.21.1 and its 1.21.11 shape. A stand-in handed to {@code Item.Properties.component} is
 * unwrapped there: a holder is stored as the holder, and a key goes through
 * {@code delayedHolderComponent}, so the host resolves it once the registries load. The jukebox,
 * instrument, and trim material components took an {@code EitherHolder} in 1.21.11 and take a
 * {@code Holder} on 26.1, so they accept a stand-in that holds a holder. A stand-in that holds only
 * a key cannot become a holder at that point, and it fails with a message naming the key. The same
 * goes for {@code EitherHolder.codec} and {@code streamCodec}, which have no 26.1 counterpart.
 * The jukebox component is the exception: a stand-in that holds only a song key makes a placeholder
 * record, and handing that placeholder to {@code Item.Properties.component} becomes 26.1's
 * {@code jukeboxPlayable(key)}, which resolves the song once the registries load. 1.21.1's
 * two-argument {@code JukeboxPlayable(EitherHolder, boolean)} takes the same path; its tooltip flag
 * has no counterpart in the record and is dropped.
 */
public final class LegacyEitherHolderBridge {

    public static final String STAND_IN = "com/retromod/generated/LegacyEitherHolder";

    static final String EITHER_HOLDER = "net/minecraft/world/item/EitherHolder";
    static final String PROPERTIES = "net/minecraft/world/item/Item$Properties";
    static final String COMPONENT_TYPE = "net/minecraft/core/component/DataComponentType";
    static final String HOLDER = "net/minecraft/core/Holder";
    static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";
    static final String EITHER = "com/mojang/datafixers/util/Either";
    static final String JUKEBOX_PLAYABLE = "net/minecraft/world/item/JukeboxPlayable";
    static final String INSTRUMENT = "net/minecraft/world/item/component/InstrumentComponent";
    static final String TRIM_MATERIAL = "net/minecraft/world/item/component/ProvidesTrimMaterial";

    static final String COMPONENT_DESC =
            "(L" + COMPONENT_TYPE + ";Ljava/lang/Object;)L" + PROPERTIES + ";";
    static final String HELPER_COMPONENT_DESC =
            "(L" + PROPERTIES + ";L" + COMPONENT_TYPE + ";Ljava/lang/Object;)L" + PROPERTIES + ";";

    private static final String L_STAND_IN = "L" + STAND_IN + ";";
    private static final String L_HOLDER = "L" + HOLDER + ";";
    private static final String L_KEY = "L" + RESOURCE_KEY + ";";
    private static final String OPTIONAL = "java/util/Optional";
    private static final byte[] STAND_IN_BYTES = STAND_IN.getBytes(StandardCharsets.UTF_8);
    private static final String JUKEBOX_FACTORY = "newJukeboxPlayable";
    private static final String MAP = "java/util/Map";
    // Placeholder jukebox records made from a song key, each waiting for Item.Properties.component.
    private static final String PENDING_SONGS = "PENDING_SONGS";

    /** A 1.21.11 component record that held an {@code EitherHolder}, and its accessor. */
    private record HolderRecord(String owner, String accessor) {
        String simpleName() {
            return owner.substring(owner.lastIndexOf('/') + 1);
        }
    }

    private static final HolderRecord[] HOLDER_RECORDS = {
            new HolderRecord(JUKEBOX_PLAYABLE, "song"),
            new HolderRecord(INSTRUMENT, "instrument"),
            new HolderRecord(TRIM_MATERIAL, "material")};

    private LegacyEitherHolderBridge() {}

    /** Registers on a host from 26.1 on, through each loader's 1.21.11 to 26.1 shim. */
    public static void register(RetromodTransformer t) {
        t.registerSyntheticClass(STAND_IN, generateStandIn());
        t.registerClassRedirect(EITHER_HOLDER, STAND_IN);
        // Keys are post-remap. A Fabric mod's intermediary holder can resolve only as far as EitherHolder
        // when the call is matched, so both spellings are keyed.
        for (String holder : List.of(L_STAND_IN, "L" + EITHER_HOLDER + ";")) {
            for (HolderRecord record : HOLDER_RECORDS) {
                t.registerConstructorRedirect(record.owner(), "(" + holder + ")V",
                        STAND_IN, factoryName(record), "(" + L_STAND_IN + ")L" + record.owner() + ";");
                t.registerMethodRedirect(record.owner(), record.accessor(), "()" + holder,
                        STAND_IN, record.accessor(), "(L" + record.owner() + ";)" + L_STAND_IN, true);
            }
        }
        for (String holder : List.of(L_STAND_IN, "L" + EITHER_HOLDER + ";")) {
            t.registerConstructorRedirect(JUKEBOX_PLAYABLE, "(" + holder + "Z)V",
                    STAND_IN, JUKEBOX_FACTORY, "(" + L_STAND_IN + "Z)L" + JUKEBOX_PLAYABLE + ";");
        }
    }

    /**
     * Sends {@code Item.Properties.component} calls through the stand-in's unwrapping helper, in a
     * class that uses the stand-in. Other classes keep the direct call, because only a stand-in
     * needs unwrapping and the helper passes every other value through unchanged.
     *
     * @param classBytes a class after the remap and redirects
     * @return the repaired class, or the same array when nothing matched
     */
    public static byte[] adaptComponentCalls(byte[] classBytes) {
        if (classBytes == null || !contains(classBytes, STAND_IN_BYTES)) return classBytes;
        ClassNode cn = new ClassNode();
        try {
            new ClassReader(classBytes).accept(cn, 0);
        } catch (RuntimeException unreadable) {
            return classBytes;
        }
        int changes = 0;
        for (MethodNode method : cn.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKEVIRTUAL
                        && PROPERTIES.equals(call.owner) && "component".equals(call.name)
                        && COMPONENT_DESC.equals(call.desc)) {
                    call.setOpcode(INVOKESTATIC);
                    call.owner = STAND_IN;
                    call.desc = HELPER_COMPONENT_DESC;
                    call.itf = false;
                    changes++;
                }
            }
        }
        if (changes == 0) return classBytes;
        // The helper takes the receiver and both arguments and returns the same type, so the
        // stack and the frames are unchanged.
        try {
            ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException failed) {
            return classBytes; // never ship a half-rewritten class
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static String factoryName(HolderRecord record) {
        return "new" + record.simpleName();
    }

    /**
     * The stand-in keeps the holder or the key in its own field rather than in an {@code Either},
     * so the common paths need nothing beyond the two Minecraft types. Generated because its
     * signatures name Minecraft types.
     */
    static byte[] generateStandIn() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, STAND_IN, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "holder", L_HOLDER, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "key", L_KEY, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, PENDING_SONGS, "L" + MAP + ";", null, null).visitEnd();
        emitStaticInit(cw);

        emitConstructor(cw, "holder", L_HOLDER);
        emitConstructor(cw, "key", L_KEY);
        emitEitherConstructor(cw);
        emitContents(cw);
        emitKey(cw);
        emitUnwrapValue(cw);
        emitUnwrapHolder(cw);
        // 1.21.1 stored an optional holder beside the key and named its members differently.
        emitOptionalConstructor(cw);
        emitHolder(cw);
        emitBareKey(cw);
        emitEitherViews(cw);
        emitComponent(cw);
        emitRequireHolder(cw);
        for (HolderRecord record : HOLDER_RECORDS) {
            if (record.owner().equals(JUKEBOX_PLAYABLE)) {
                emitJukeboxFactory(cw);
            } else {
                emitRecordFactory(cw, record);
            }
            emitRecordAccessor(cw, record);
        }
        emitOldJukeboxFactory(cw);
        emitUnsupported(cw, "codec", "(" + L_KEY + "Lcom/mojang/serialization/Codec;)"
                + "Lcom/mojang/serialization/Codec;");
        emitUnsupported(cw, "streamCodec", "(" + L_KEY + "Lnet/minecraft/network/codec/StreamCodec;)"
                + "Lnet/minecraft/network/codec/StreamCodec;");
        emitEquals(cw);
        emitHashCode(cw);
        emitToString(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code new EitherHolder(holder)} or {@code new EitherHolder(key)}; the other field stays null. */
    private static void emitConstructor(ClassWriter cw, String field, String argDesc) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + argDesc + ")V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitFieldInsn(PUTFIELD, STAND_IN, field, argDesc);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code new EitherHolder(either)}: the left side is a holder and the right side a key. */
    private static void emitEitherConstructor(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + EITHER + ";)V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        emitEitherSide(m, "left", "holder", HOLDER, L_HOLDER);
        emitEitherSide(m, "right", "key", RESOURCE_KEY, L_KEY);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void emitEitherSide(MethodVisitor m, String side, String field, String type,
            String desc) {
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, EITHER, side, "()Ljava/util/Optional;", false);
        m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKEVIRTUAL, OPTIONAL, "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        m.visitTypeInsn(CHECKCAST, type);
        m.visitFieldInsn(PUTFIELD, STAND_IN, field, desc);
    }

    /** {@code contents()}: the holder on the left or the key on the right, as 1.21.11 stored them. */
    private static void emitContents(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "contents", "()L" + EITHER + ";", null, null);
        m.visitCode();
        Label byKey = new Label();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitJumpInsn(IFNULL, byKey);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitMethodInsn(INVOKESTATIC, EITHER, "left", "(Ljava/lang/Object;)L" + EITHER + ";", false);
        m.visitInsn(ARETURN);
        m.visitLabel(byKey);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "key", L_KEY);
        m.visitMethodInsn(INVOKESTATIC, EITHER, "right", "(Ljava/lang/Object;)L" + EITHER + ";", false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code key()}: the holder's own key, or the stored key. */
    private static void emitKey(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "key", "()Ljava/util/Optional;", null, null);
        m.visitCode();
        Label byKey = new Label();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitJumpInsn(IFNULL, byKey);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitMethodInsn(INVOKEINTERFACE, HOLDER, "unwrapKey", "()Ljava/util/Optional;", true);
        m.visitInsn(ARETURN);
        m.visitLabel(byKey);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "key", L_KEY);
        m.visitMethodInsn(INVOKESTATIC, OPTIONAL, "of", "(Ljava/lang/Object;)Ljava/util/Optional;", false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /**
     * {@code unwrap(registry)}: the registered value, not a holder, as both 1.21.1 and 1.21.11
     * returned it.
     */
    private static void emitUnwrapValue(ClassWriter cw) {
        String registry = "net/minecraft/core/Registry";
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "unwrap", "(L" + registry + ";)Ljava/util/Optional;",
                null, null);
        m.visitCode();
        Label byKey = new Label();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitJumpInsn(IFNULL, byKey);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitMethodInsn(INVOKEINTERFACE, HOLDER, "value", "()Ljava/lang/Object;", true);
        m.visitMethodInsn(INVOKESTATIC, OPTIONAL, "of", "(Ljava/lang/Object;)Ljava/util/Optional;", false);
        m.visitInsn(ARETURN);
        m.visitLabel(byKey);
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "key", L_KEY);
        m.visitMethodInsn(INVOKEINTERFACE, registry, "getOptional", "(" + L_KEY + ")Ljava/util/Optional;",
                true);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code unwrap(provider)}: the holder itself, or the provider's entry for the stored key. */
    private static void emitUnwrapHolder(ClassWriter cw) {
        String provider = "net/minecraft/core/HolderLookup$Provider";
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "unwrap", "(L" + provider + ";)Ljava/util/Optional;",
                null, null);
        m.visitCode();
        Label byKey = new Label();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitJumpInsn(IFNULL, byKey);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitMethodInsn(INVOKESTATIC, OPTIONAL, "of", "(Ljava/lang/Object;)Ljava/util/Optional;", false);
        m.visitInsn(ARETURN);
        m.visitLabel(byKey);
        // The provider inherits get(ResourceKey) from a superinterface. Naming the provider's own
        // type lets resolution find it there and spares the verifier loading that interface.
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "key", L_KEY);
        m.visitMethodInsn(INVOKEINTERFACE, provider, "get", "(" + L_KEY + ")Ljava/util/Optional;", true);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** 1.21.1's {@code new EitherHolder(Optional<Holder>, key)}. */
    private static void emitOptionalConstructor(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/util/Optional;" + L_KEY + ")V",
                null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKEVIRTUAL, OPTIONAL, "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        m.visitTypeInsn(CHECKCAST, HOLDER);
        m.visitFieldInsn(PUTFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 2);
        m.visitFieldInsn(PUTFIELD, STAND_IN, "key", L_KEY);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** 1.21.1's {@code holder()}: the holder if there is one. */
    private static void emitHolder(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "holder", "()Ljava/util/Optional;", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitMethodInsn(INVOKESTATIC, OPTIONAL, "ofNullable", "(Ljava/lang/Object;)Ljava/util/Optional;",
                false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /**
     * 1.21.1's {@code key()}, which returned the key itself. A class file may hold it beside the
     * 1.21.11 {@code key()} because their return types differ.
     */
    private static void emitBareKey(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "key", "()" + L_KEY, null, null);
        m.visitCode();
        Label fromHolder = new Label();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "key", L_KEY);
        m.visitJumpInsn(IFNULL, fromHolder);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "key", L_KEY);
        m.visitInsn(ARETURN);
        m.visitLabel(fromHolder);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitMethodInsn(INVOKEINTERFACE, HOLDER, "unwrapKey", "()Ljava/util/Optional;", true);
        m.visitMethodInsn(INVOKEVIRTUAL, OPTIONAL, "orElseThrow", "()Ljava/lang/Object;", false);
        m.visitTypeInsn(CHECKCAST, RESOURCE_KEY);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** 1.21.1's {@code asEither()} and {@code fromEither(either)}, the names it gave contents. */
    private static void emitEitherViews(ClassWriter cw) {
        MethodVisitor as = cw.visitMethod(ACC_PUBLIC, "asEither", "()L" + EITHER + ";", null, null);
        as.visitCode();
        as.visitVarInsn(ALOAD, 0);
        as.visitMethodInsn(INVOKEVIRTUAL, STAND_IN, "contents", "()L" + EITHER + ";", false);
        as.visitInsn(ARETURN);
        as.visitMaxs(0, 0);
        as.visitEnd();
        MethodVisitor from = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "fromEither",
                "(L" + EITHER + ";)" + L_STAND_IN, null, null);
        from.visitCode();
        from.visitTypeInsn(NEW, STAND_IN);
        from.visitInsn(DUP);
        from.visitVarInsn(ALOAD, 0);
        from.visitMethodInsn(INVOKESPECIAL, STAND_IN, "<init>", "(L" + EITHER + ";)V", false);
        from.visitInsn(ARETURN);
        from.visitMaxs(0, 0);
        from.visitEnd();
    }

    /**
     * {@code component(properties, type, value)}: a stand-in holding a holder stores the holder, one
     * holding a key defers to the host's {@code delayedHolderComponent}, and any other value is
     * stored as given.
     */
    private static void emitComponent(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "component", HELPER_COMPONENT_DESC,
                null, null);
        m.visitCode();
        Label plain = new Label();
        Label byKey = new Label();
        m.visitVarInsn(ALOAD, 2);
        m.visitTypeInsn(INSTANCEOF, STAND_IN);
        m.visitJumpInsn(IFEQ, plain);
        m.visitVarInsn(ALOAD, 2);
        m.visitTypeInsn(CHECKCAST, STAND_IN);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitJumpInsn(IFNULL, byKey);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 2);
        m.visitTypeInsn(CHECKCAST, STAND_IN);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitMethodInsn(INVOKEVIRTUAL, PROPERTIES, "component", COMPONENT_DESC, false);
        m.visitInsn(ARETURN);
        m.visitLabel(byKey);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 2);
        m.visitTypeInsn(CHECKCAST, STAND_IN);
        m.visitFieldInsn(GETFIELD, STAND_IN, "key", L_KEY);
        m.visitMethodInsn(INVOKEVIRTUAL, PROPERTIES, "delayedHolderComponent",
                "(L" + COMPONENT_TYPE + ";" + L_KEY + ")L" + PROPERTIES + ";", false);
        m.visitInsn(ARETURN);
        m.visitLabel(plain);
        // A placeholder jukebox record made from a song key becomes the host's own jukebox property.
        Label notPending = new Label();
        m.visitVarInsn(ALOAD, 2);
        m.visitTypeInsn(INSTANCEOF, JUKEBOX_PLAYABLE);
        m.visitJumpInsn(IFEQ, notPending);
        m.visitFieldInsn(GETSTATIC, STAND_IN, PENDING_SONGS, "L" + MAP + ";");
        m.visitVarInsn(ALOAD, 2);
        m.visitMethodInsn(INVOKEINTERFACE, MAP, "remove", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        m.visitVarInsn(ASTORE, 3);
        m.visitVarInsn(ALOAD, 3);
        m.visitJumpInsn(IFNULL, notPending);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 3);
        m.visitTypeInsn(CHECKCAST, RESOURCE_KEY);
        m.visitMethodInsn(INVOKEVIRTUAL, PROPERTIES, "jukeboxPlayable", "(" + L_KEY + ")L" + PROPERTIES + ";", false);
        m.visitInsn(ARETURN);
        m.visitLabel(notPending);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 2);
        m.visitMethodInsn(INVOKEVIRTUAL, PROPERTIES, "component", COMPONENT_DESC, false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code requireHolder(value, component)}: the holder, or an error naming the unresolved key. */
    private static void emitRequireHolder(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "requireHolder",
                "(" + L_STAND_IN + "Ljava/lang/String;)" + L_HOLDER, null, null);
        m.visitCode();
        Label missing = new Label();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitJumpInsn(IFNULL, missing);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitInsn(ARETURN);
        m.visitLabel(missing);
        m.visitTypeInsn(NEW, "java/lang/IllegalStateException");
        m.visitInsn(DUP);
        m.visitLdcInsn("Retromod cannot build ");
        m.visitVarInsn(ALOAD, 1);
        concat(m);
        m.visitLdcInsn(" from the registry key ");
        concat(m);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "key", L_KEY);
        m.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf",
                "(Ljava/lang/Object;)Ljava/lang/String;", false);
        concat(m);
        m.visitLdcInsn(": Minecraft 26.1 removed EitherHolder and this component now needs a loaded"
                + " registry entry. Retromod has no replacement for this call yet. Report it with the"
                + " mod and its version.");
        concat(m);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
                "(Ljava/lang/String;)V", false);
        m.visitInsn(ATHROW);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** The placeholder map: identity keys, because two placeholders are equal records with a null song. */
    private static void emitStaticInit(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        m.visitCode();
        m.visitTypeInsn(NEW, "java/util/IdentityHashMap");
        m.visitInsn(DUP);
        m.visitMethodInsn(INVOKESPECIAL, "java/util/IdentityHashMap", "<init>", "()V", false);
        m.visitMethodInsn(INVOKESTATIC, "java/util/Collections", "synchronizedMap",
                "(L" + MAP + ";)L" + MAP + ";", false);
        m.visitFieldInsn(PUTSTATIC, STAND_IN, PENDING_SONGS, "L" + MAP + ";");
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code newJukeboxPlayable(value)}: the record from a holder, or a placeholder that remembers a song key. */
    private static void emitJukeboxFactory(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, JUKEBOX_FACTORY,
                "(" + L_STAND_IN + ")L" + JUKEBOX_PLAYABLE + ";", null, null);
        m.visitCode();
        Label byKey = new Label();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitJumpInsn(IFNULL, byKey);
        m.visitTypeInsn(NEW, JUKEBOX_PLAYABLE);
        m.visitInsn(DUP);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitMethodInsn(INVOKESPECIAL, JUKEBOX_PLAYABLE, "<init>", "(" + L_HOLDER + ")V", false);
        m.visitInsn(ARETURN);
        m.visitLabel(byKey);
        m.visitTypeInsn(NEW, JUKEBOX_PLAYABLE);
        m.visitInsn(DUP);
        m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKESPECIAL, JUKEBOX_PLAYABLE, "<init>", "(" + L_HOLDER + ")V", false);
        m.visitVarInsn(ASTORE, 1);
        m.visitFieldInsn(GETSTATIC, STAND_IN, PENDING_SONGS, "L" + MAP + ";");
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "key", L_KEY);
        m.visitMethodInsn(INVOKEINTERFACE, MAP, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        m.visitInsn(POP);
        m.visitVarInsn(ALOAD, 1);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** 1.21.1's {@code JukeboxPlayable(song, showInTooltip)}: the flag has no counterpart and is dropped. */
    private static void emitOldJukeboxFactory(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, JUKEBOX_FACTORY,
                "(" + L_STAND_IN + "Z)L" + JUKEBOX_PLAYABLE + ";", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESTATIC, STAND_IN, JUKEBOX_FACTORY, "(" + L_STAND_IN + ")L" + JUKEBOX_PLAYABLE + ";", false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code newJukeboxPlayable(value)} and its siblings: the 26.1 record built from the holder. */
    private static void emitRecordFactory(ClassWriter cw, HolderRecord record) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, factoryName(record),
                "(" + L_STAND_IN + ")L" + record.owner() + ";", null, null);
        m.visitCode();
        m.visitTypeInsn(NEW, record.owner());
        m.visitInsn(DUP);
        m.visitVarInsn(ALOAD, 0);
        m.visitLdcInsn(record.simpleName());
        m.visitMethodInsn(INVOKESTATIC, STAND_IN, "requireHolder",
                "(" + L_STAND_IN + "Ljava/lang/String;)" + L_HOLDER, false);
        m.visitMethodInsn(INVOKESPECIAL, record.owner(), "<init>", "(" + L_HOLDER + ")V", false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code song(record)} and its siblings: the record's 26.1 holder, wrapped for the old caller. */
    private static void emitRecordAccessor(ClassWriter cw, HolderRecord record) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, record.accessor(),
                "(L" + record.owner() + ";)" + L_STAND_IN, null, null);
        m.visitCode();
        m.visitTypeInsn(NEW, STAND_IN);
        m.visitInsn(DUP);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, record.owner(), record.accessor(), "()" + L_HOLDER, false);
        m.visitMethodInsn(INVOKESPECIAL, STAND_IN, "<init>", "(" + L_HOLDER + ")V", false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void emitUnsupported(ClassWriter cw, String name, String desc) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        m.visitCode();
        m.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
        m.visitInsn(DUP);
        m.visitLdcInsn("Retromod cannot provide EitherHolder." + name + ", which Minecraft 26.1 removed"
                + " with no replacement. Retromod has no replacement for this call yet. Report it with"
                + " the mod and its version.");
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>",
                "(Ljava/lang/String;)V", false);
        m.visitInsn(ATHROW);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void emitEquals(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
        m.visitCode();
        Label different = new Label();
        m.visitVarInsn(ALOAD, 1);
        m.visitTypeInsn(INSTANCEOF, STAND_IN);
        m.visitJumpInsn(IFEQ, different);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitVarInsn(ALOAD, 1);
        m.visitTypeInsn(CHECKCAST, STAND_IN);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitMethodInsn(INVOKESTATIC, "java/util/Objects", "equals",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
        m.visitJumpInsn(IFEQ, different);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "key", L_KEY);
        m.visitVarInsn(ALOAD, 1);
        m.visitTypeInsn(CHECKCAST, STAND_IN);
        m.visitFieldInsn(GETFIELD, STAND_IN, "key", L_KEY);
        m.visitMethodInsn(INVOKESTATIC, "java/util/Objects", "equals",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
        m.visitInsn(IRETURN);
        m.visitLabel(different);
        m.visitInsn(ICONST_0);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void emitHashCode(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "hashCode", "()I", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "holder", L_HOLDER);
        m.visitMethodInsn(INVOKESTATIC, "java/util/Objects", "hashCode", "(Ljava/lang/Object;)I", false);
        m.visitIntInsn(BIPUSH, 31);
        m.visitInsn(IMUL);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, STAND_IN, "key", L_KEY);
        m.visitMethodInsn(INVOKESTATIC, "java/util/Objects", "hashCode", "(Ljava/lang/Object;)I", false);
        m.visitInsn(IADD);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void emitToString(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
        m.visitCode();
        m.visitLdcInsn("EitherHolder[contents=");
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, STAND_IN, "contents", "()L" + EITHER + ";", false);
        m.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf",
                "(Ljava/lang/Object;)Ljava/lang/String;", false);
        concat(m);
        m.visitLdcInsn("]");
        concat(m);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void concat(MethodVisitor m) {
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
    }
}
