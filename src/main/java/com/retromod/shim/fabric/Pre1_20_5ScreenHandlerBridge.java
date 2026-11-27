/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Bridges Fabric API's extended screen handlers from the buffer form older mods use to the
 * typed-data form Fabric API adopted with Minecraft 1.20.5, for intermediary-namespace Fabric
 * mods on pre-26.1 hosts.
 *
 * <p>Before 1.20.5 a mod built {@code new ExtendedScreenHandlerType(factory)}, its factory read
 * the opening data from a {@code PacketByteBuf}, and its server-side
 * {@code ExtendedScreenHandlerFactory} wrote that data with
 * {@code writeScreenOpeningData(player, buf)}. Fabric API now needs a {@code StreamCodec} for a
 * data object, calls {@code ExtendedFactory.create(int, Inventory, Object)}, and asks the menu
 * provider for {@code getScreenOpeningData(player)}. A mod that registers a menu type died with
 * {@code NoSuchMethodError} in its first registry class, which ended its whole initializer.
 *
 * <p>The bridge keeps the buffer as the data object. The old constructor gains a generated codec
 * that copies the buffer's bytes into the open-screen packet and hands the client a registry
 * buffer over them. Old factory lambdas are retyped to the erased method, so the metafactory
 * casts the data back to a buffer. Old menu providers get a generated subinterface whose default
 * {@code getScreenOpeningData} fills a fresh registry buffer through the mod's own writer. Every
 * rewrite is keyed on the old shape, so mods written for the typed form are unchanged.
 */
public final class Pre1_20_5ScreenHandlerBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String API = "net/fabricmc/fabric/api/screenhandler/v1/";
    static final String HANDLER_TYPE = API + "ExtendedScreenHandlerType";
    static final String FACTORY_IFACE = HANDLER_TYPE + "$ExtendedFactory";
    static final String MENU_PROVIDER = API + "ExtendedScreenHandlerFactory";
    static final String STREAM_CODEC = "net/minecraft/class_9139";
    static final String BUF = "net/minecraft/class_2540";
    static final String REGISTRY_BUF = "net/minecraft/class_9129";
    static final String REGISTRY_ACCESS = "net/minecraft/class_5455";
    static final String SERVER_PLAYER = "net/minecraft/class_3222";
    static final String ENTITY = "net/minecraft/class_1297";
    private static final String INVENTORY = "net/minecraft/class_1661";
    private static final String MENU = "net/minecraft/class_1703";
    private static final String ENTITY_REGISTRY_ACCESS = "method_56673";
    private static final String BUF_REGISTRY_ACCESS = "method_56349";

    static final String FACTORY = "com/retromod/generated/LegacyScreenHandlerTypes";
    static final String CODEC = "com/retromod/generated/LegacyScreenOpeningDataCodec";
    static final String LEGACY_MENU_PROVIDER = "com/retromod/generated/LegacyExtendedScreenHandlerFactory";

    static final String OLD_CTOR = "(L" + FACTORY_IFACE + ";)V";
    static final String MODERN_CTOR = "(L" + FACTORY_IFACE + ";L" + STREAM_CODEC + ";)V";
    static final String OLD_CREATE = "(IL" + INVENTORY + ";L" + BUF + ";)L" + MENU + ";";
    static final String ERASED_CREATE = "(IL" + INVENTORY + ";Ljava/lang/Object;)L" + MENU + ";";
    static final String WRITE_DATA = "writeScreenOpeningData";
    static final String WRITE_DATA_DESC = "(L" + SERVER_PLAYER + ";L" + BUF + ";)V";
    static final String GET_DATA = "getScreenOpeningData";
    static final String GET_DATA_DESC = "(L" + SERVER_PLAYER + ";)Ljava/lang/Object;";

    private Pre1_20_5ScreenHandlerBridge() {}

    /** Registers the bridge only on a Fabric API that has the typed form and not the buffer form. */
    public static void register(RetromodTransformer transformer) {
        ClassNode type = ClassResourceInspector.read(HANDLER_TYPE);
        ClassNode provider = ClassResourceInspector.read(MENU_PROVIDER);
        if (type == null || provider == null) return;
        boolean typedForm = hasMethod(type, "<init>", MODERN_CTOR) && !hasMethod(type, "<init>", OLD_CTOR)
                && hasMethod(provider, GET_DATA, GET_DATA_DESC)
                && !hasMethod(provider, WRITE_DATA, WRITE_DATA_DESC);
        if (!typedForm) {
            LOGGER.debug("Fabric API still has the buffer form of extended screen handlers");
            return;
        }
        registerRedirects(transformer);
        LOGGER.debug("Added support for extended screen handlers that use opening-data buffers");
    }

    /** Unconditional registration for offline transforms and transform-shape tests. */
    public static void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(FACTORY, generateFactory());
        transformer.registerSyntheticClass(CODEC, generateCodec());
        transformer.registerSyntheticClass(LEGACY_MENU_PROVIDER, generateMenuProvider());
        transformer.registerConstructorRedirect(HANDLER_TYPE, OLD_CTOR,
                FACTORY, "create", "(L" + FACTORY_IFACE + ";)L" + HANDLER_TYPE + ";");
        transformer.registerLambdaSamRetype(FACTORY_IFACE, "create", OLD_CREATE, ERASED_CREATE);
        transformer.registerInterfaceReplacement(MENU_PROVIDER, LEGACY_MENU_PROVIDER);
        registerSubclassConstructor(transformer, HANDLER_TYPE, OLD_CTOR, MODERN_CTOR, CODEC);
    }

    /** A mod's own subclass calls {@code super(factory)}, which a constructor redirect cannot replace, so the codec is added there. */
    static void registerSubclassConstructor(RetromodTransformer transformer, String type, String oldCtor, String modernCtor, String codec) {
        transformer.registerSuperConstructorAdapter(type, oldCtor, modernCtor, mv -> {
            mv.visitTypeInsn(Opcodes.NEW, codec);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, codec, "<init>", "()V", false);
        });
    }

    // ---- the same bridge in the names Fabric API for 26.1 and newer uses ----

    static final String MENU_API = "net/fabricmc/fabric/api/menu/v1/";
    static final String MENU_TYPE = MENU_API + "ExtendedMenuType";
    static final String MENU_FACTORY = MENU_TYPE + "$ExtendedFactory";
    static final String MENU_PROVIDER_26 = MENU_API + "ExtendedMenuProvider";
    static final String FIRST_MOJANG_HOST = "26.1";
    /** Each intermediary name the generated classes use, and its 26.x name; the synthetics get names of their own. */
    static final Map<String, String> MOJANG_NAMES = Map.ofEntries(
            Map.entry(HANDLER_TYPE, MENU_TYPE), Map.entry(FACTORY_IFACE, MENU_FACTORY), Map.entry(MENU_PROVIDER, MENU_PROVIDER_26),
            Map.entry(STREAM_CODEC, "net/minecraft/network/codec/StreamCodec"), Map.entry(BUF, "net/minecraft/network/FriendlyByteBuf"),
            Map.entry(REGISTRY_BUF, "net/minecraft/network/RegistryFriendlyByteBuf"), Map.entry(REGISTRY_ACCESS, "net/minecraft/core/RegistryAccess"),
            Map.entry(SERVER_PLAYER, "net/minecraft/server/level/ServerPlayer"), Map.entry(ENTITY, "net/minecraft/world/entity/Entity"),
            Map.entry(INVENTORY, "net/minecraft/world/entity/player/Inventory"), Map.entry(MENU, "net/minecraft/world/inventory/AbstractContainerMenu"),
            Map.entry(FACTORY, "com/retromod/generated/LegacyMenuTypes"), Map.entry(CODEC, "com/retromod/generated/LegacyMenuOpeningDataCodec"),
            Map.entry(LEGACY_MENU_PROVIDER, "com/retromod/generated/LegacyExtendedMenuProvider"),
            Map.entry(ENTITY + "." + ENTITY_REGISTRY_ACCESS + "()L" + REGISTRY_ACCESS + ";", "registryAccess"),
            Map.entry(REGISTRY_BUF + "." + BUF_REGISTRY_ACCESS + "()L" + REGISTRY_ACCESS + ";", "registryAccess"));

    /**
     * The bridge for 1.20.1 Fabric mods on Fabric API for 26.x, which renamed the screen handler API to menus and runs Mojang
     * names. Offline the menu classes cannot be read, so the target decides.
     */
    public static void registerMojang(RetromodTransformer transformer) {
        ClassNode type = ClassResourceInspector.read(MENU_TYPE);
        String oldCtor = "(L" + MENU_FACTORY + ";)V";
        if (type != null && (hasMethod(type, "<init>", oldCtor) || !hasMethod(type, "<init>", "(L" + MENU_FACTORY + ";L"
                + MOJANG_NAMES.get(STREAM_CODEC) + ";)V"))) {
            return;
        }
        if (type == null && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_MOJANG_HOST) < 0) return;
        SimpleRemapper names = new SimpleRemapper(MOJANG_NAMES);
        String factory = MOJANG_NAMES.get(FACTORY);
        transformer.registerSyntheticClass(factory, rename(generateFactory(), names));
        transformer.registerSyntheticClass(MOJANG_NAMES.get(CODEC), rename(generateCodec(), names));
        transformer.registerSyntheticClass(MOJANG_NAMES.get(LEGACY_MENU_PROVIDER), rename(generateMenuProvider(), names));
        transformer.registerConstructorRedirect(MENU_TYPE, oldCtor, factory, "create", "(L" + MENU_FACTORY + ";)L" + MENU_TYPE + ";");
        transformer.registerLambdaSamRetype(MENU_FACTORY, "create", names.mapMethodDesc(OLD_CREATE), names.mapMethodDesc(ERASED_CREATE));
        transformer.registerInterfaceReplacement(MENU_PROVIDER_26, MOJANG_NAMES.get(LEGACY_MENU_PROVIDER));
        registerSubclassConstructor(transformer, MENU_TYPE, oldCtor, "(L" + MENU_FACTORY + ";L" + MOJANG_NAMES.get(STREAM_CODEC) + ";)V",
                MOJANG_NAMES.get(CODEC));
    }

    static byte[] rename(byte[] bytes, SimpleRemapper names) {
        ClassWriter out = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(out, names), 0);
        return out.toByteArray();
    }

    private static boolean hasMethod(ClassNode owner, String name, String descriptor) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return true;
        }
        return false;
    }

    /** {@code create(factory)} returns {@code new ExtendedScreenHandlerType(factory, codec)}. */
    static byte[] generateFactory() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                FACTORY, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create",
                "(L" + FACTORY_IFACE + ";)L" + HANDLER_TYPE + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, HANDLER_TYPE);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.NEW, CODEC);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, CODEC, "<init>", "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, HANDLER_TYPE, "<init>", MODERN_CTOR, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A codec that carries the readable bytes of the mod's buffer, length first. The client side
     * gets a registry buffer so that item stacks and other registry-backed reads still work.
     */
    static byte[] generateCodec() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                CODEC, null, "java/lang/Object", new String[] {STREAM_CODEC});

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        // encode(out, data): out.writeInt(data.readableBytes()); out.writeBytes(data, start, length)
        MethodVisitor encode = cw.visitMethod(Opcodes.ACC_PUBLIC, "encode",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        encode.visitCode();
        encode.visitVarInsn(Opcodes.ALOAD, 2);
        encode.visitTypeInsn(Opcodes.CHECKCAST, "io/netty/buffer/ByteBuf");
        encode.visitVarInsn(Opcodes.ASTORE, 3);
        encode.visitVarInsn(Opcodes.ALOAD, 1);
        encode.visitTypeInsn(Opcodes.CHECKCAST, "io/netty/buffer/ByteBuf");
        encode.visitInsn(Opcodes.DUP);
        encode.visitVarInsn(Opcodes.ALOAD, 3);
        encode.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "io/netty/buffer/ByteBuf", "readableBytes", "()I", false);
        encode.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "io/netty/buffer/ByteBuf", "writeInt",
                "(I)Lio/netty/buffer/ByteBuf;", false);
        encode.visitInsn(Opcodes.POP);
        encode.visitVarInsn(Opcodes.ALOAD, 3);
        encode.visitVarInsn(Opcodes.ALOAD, 3);
        encode.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "io/netty/buffer/ByteBuf", "readerIndex", "()I", false);
        encode.visitVarInsn(Opcodes.ALOAD, 3);
        encode.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "io/netty/buffer/ByteBuf", "readableBytes", "()I", false);
        encode.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "io/netty/buffer/ByteBuf", "writeBytes",
                "(Lio/netty/buffer/ByteBuf;II)Lio/netty/buffer/ByteBuf;", false);
        encode.visitInsn(Opcodes.POP);
        encode.visitInsn(Opcodes.RETURN);
        encode.visitMaxs(0, 0);
        encode.visitEnd();

        // decode(in): bytes = new byte[in.readInt()]; in.readBytes(bytes);
        //             return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), in.registryAccess())
        MethodVisitor decode = cw.visitMethod(Opcodes.ACC_PUBLIC, "decode",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        decode.visitCode();
        decode.visitVarInsn(Opcodes.ALOAD, 1);
        decode.visitTypeInsn(Opcodes.CHECKCAST, REGISTRY_BUF);
        decode.visitVarInsn(Opcodes.ASTORE, 2);
        decode.visitVarInsn(Opcodes.ALOAD, 2);
        decode.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "io/netty/buffer/ByteBuf", "readInt", "()I", false);
        decode.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE);
        decode.visitVarInsn(Opcodes.ASTORE, 3);
        decode.visitVarInsn(Opcodes.ALOAD, 2);
        decode.visitVarInsn(Opcodes.ALOAD, 3);
        decode.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "io/netty/buffer/ByteBuf", "readBytes",
                "([B)Lio/netty/buffer/ByteBuf;", false);
        decode.visitInsn(Opcodes.POP);
        decode.visitTypeInsn(Opcodes.NEW, REGISTRY_BUF);
        decode.visitInsn(Opcodes.DUP);
        decode.visitVarInsn(Opcodes.ALOAD, 3);
        decode.visitMethodInsn(Opcodes.INVOKESTATIC, "io/netty/buffer/Unpooled", "wrappedBuffer",
                "([B)Lio/netty/buffer/ByteBuf;", false);
        decode.visitVarInsn(Opcodes.ALOAD, 2);
        decode.visitMethodInsn(Opcodes.INVOKEVIRTUAL, REGISTRY_BUF, BUF_REGISTRY_ACCESS,
                "()L" + REGISTRY_ACCESS + ";", false);
        decode.visitMethodInsn(Opcodes.INVOKESPECIAL, REGISTRY_BUF, "<init>",
                "(Lio/netty/buffer/ByteBuf;L" + REGISTRY_ACCESS + ";)V", false);
        decode.visitInsn(Opcodes.ARETURN);
        decode.visitMaxs(0, 0);
        decode.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * The old menu provider shape on top of the new interface. A typed provider overrides
     * {@code getScreenOpeningData} and never reaches the empty writer.
     */
    static byte[] generateMenuProvider() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                LEGACY_MENU_PROVIDER, null, "java/lang/Object", new String[] {MENU_PROVIDER});

        MethodVisitor write = cw.visitMethod(Opcodes.ACC_PUBLIC, WRITE_DATA, WRITE_DATA_DESC, null, null);
        write.visitCode();
        write.visitInsn(Opcodes.RETURN);
        write.visitMaxs(0, 0);
        write.visitEnd();

        MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC, GET_DATA, GET_DATA_DESC, null, null);
        get.visitCode();
        get.visitTypeInsn(Opcodes.NEW, REGISTRY_BUF);
        get.visitInsn(Opcodes.DUP);
        get.visitMethodInsn(Opcodes.INVOKESTATIC, "io/netty/buffer/Unpooled", "buffer",
                "()Lio/netty/buffer/ByteBuf;", false);
        get.visitVarInsn(Opcodes.ALOAD, 1);
        get.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, ENTITY_REGISTRY_ACCESS,
                "()L" + REGISTRY_ACCESS + ";", false);
        get.visitMethodInsn(Opcodes.INVOKESPECIAL, REGISTRY_BUF, "<init>",
                "(Lio/netty/buffer/ByteBuf;L" + REGISTRY_ACCESS + ";)V", false);
        get.visitVarInsn(Opcodes.ASTORE, 2);
        get.visitVarInsn(Opcodes.ALOAD, 0);
        get.visitVarInsn(Opcodes.ALOAD, 1);
        get.visitVarInsn(Opcodes.ALOAD, 2);
        get.visitMethodInsn(Opcodes.INVOKEINTERFACE, LEGACY_MENU_PROVIDER, WRITE_DATA, WRITE_DATA_DESC, true);
        get.visitVarInsn(Opcodes.ALOAD, 2);
        get.visitInsn(Opcodes.ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
