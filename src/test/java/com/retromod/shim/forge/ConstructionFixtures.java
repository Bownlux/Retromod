/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/** A Forge 1.20.1 mod, SRG named, that calls the buffer, ingredient and event channel APIs Forge 26.2 changed. */
final class ConstructionFixtures {

    static final String BUF = "net/minecraft/network/FriendlyByteBuf";
    static final String OLD_ID = "net/minecraft/resources/ResourceLocation";
    static final String INGREDIENT = "net/minecraft/world/item/crafting/Ingredient";
    static final String CRAFTING = "net/minecraftforge/common/crafting/";
    static final String NETWORK = "net/minecraftforge/network/";
    // Forge 1.20.1's SRG names for FriendlyByteBuf.writeResourceLocation and readResourceLocation.
    static final String WRITE_SRG = "m_130085_";
    static final String READ_SRG = "m_130281_";

    private ConstructionFixtures() {}

    /** The mod jar, with the same classes again in a nested library mod. */
    static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("cfixlib"));
        addClasses(lib, "cfixlib/", "cfixlib");
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("cfixmod"));
        addClasses(mod, "cfix/", "cfixmod");
        mod.put("META-INF/jarjar/lib.jar", LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    /** What RetromodForge registers on the host, its SRG names included, then one runtime transform into {@code dir}/runtime. */
    static Path forgeRuntime(RetromodTransformer transformer, Path input, Path dir, String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        SrgToMojangMapper.getInstance().applyTo(transformer);
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            boolean forgeShim = Set.of("forge", "common").contains(shim.getModLoaderType());
            if (forgeShim && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        Path out = new ForgeModTransformer(host).transformMod(input, Files.createDirectory(dir.resolve("runtime")));
        if (out == null) throw new AssertionError("the Forge runtime wrote no jar");
        return out;
    }

    static void addClasses(Map<String, byte[]> jar, String pkg, String modId) {
        jar.put(pkg + "TheMod.class", ModConstructorHooksTest.modClass(pkg + "TheMod", modId,
                LegacyAutoSubscribeBridgeTest.OBJECT, (writer, owner) -> { }));
        jar.put(pkg + "Buffers.class", buffers(pkg + "Buffers"));
        jar.put(pkg + "FoodSetup$2.class", compoundSubclass(pkg + "FoodSetup$2"));
        jar.put(pkg + "Combined.class", combined(pkg + "Combined"));
        jar.put(pkg + "Net.class", net(pkg + "Net"));
    }

    /** Writes and reads an id through the buffer, by call and as Balm's ByteBufCodecs does, by method reference. */
    static byte[] buffers(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        String writeDesc = "(L" + OLD_ID + ";)L" + BUF + ";";
        MethodVisitor write = method(cw, "write", "(L" + BUF + ";L" + OLD_ID + ";)V");
        write.visitVarInsn(ALOAD, 0);
        write.visitVarInsn(ALOAD, 1);
        write.visitMethodInsn(INVOKEVIRTUAL, BUF, WRITE_SRG, writeDesc, false);
        write.visitInsn(POP);
        write.visitInsn(RETURN);
        end(write);

        MethodVisitor read = method(cw, "read", "(L" + BUF + ";)L" + OLD_ID + ";");
        read.visitVarInsn(ALOAD, 0);
        read.visitMethodInsn(INVOKEVIRTUAL, BUF, READ_SRG, "()L" + OLD_ID + ";", false);
        read.visitInsn(ARETURN);
        end(read);

        MethodVisitor writer = method(cw, "writer", "()Ljava/util/function/BiConsumer;");
        writer.visitInvokeDynamicInsn("accept", "()Ljava/util/function/BiConsumer;", metafactory(),
                Type.getType("(Ljava/lang/Object;Ljava/lang/Object;)V"),
                new Handle(H_INVOKEVIRTUAL, BUF, WRITE_SRG, writeDesc, false),
                Type.getType("(L" + BUF + ";L" + OLD_ID + ";)V"));
        writer.visitInsn(ARETURN);
        end(writer);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Farmer's Delight's {@code new CompoundIngredient(list) {}}, an anonymous class with the protected constructor. */
    static byte[] compoundSubclass(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_SUPER, name, null, CRAFTING + "CompoundIngredient", null);
        MethodVisitor init = cw.visitMethod(0, "<init>", "(Ljava/util/List;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitMethodInsn(INVOKESPECIAL, CRAFTING + "CompoundIngredient", "<init>", "(Ljava/util/List;)V", false);
        init.visitInsn(RETURN);
        end(init);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Each old factory, as Puzzles Lib and KubeJS call them. */
    static byte[] combined(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        String ing = "L" + INGREDIENT + ";";
        String array = "([" + ing + ")" + ing;
        factory(cw, "compound", array, CRAFTING + "CompoundIngredient", array, 1);
        factory(cw, "intersection", array, CRAFTING + "IntersectionIngredient", array, 1);
        factory(cw, "difference", "(" + ing + ing + ")" + ing, CRAFTING + "DifferenceIngredient",
                "(" + ing + ing + ")L" + CRAFTING + "DifferenceIngredient;", 2);
        factory(cw, "strict", "(Lnet/minecraft/world/item/ItemStack;)" + ing, CRAFTING + "StrictNBTIngredient",
                "(Lnet/minecraft/world/item/ItemStack;)L" + CRAFTING + "StrictNBTIngredient;", 1);
        factory(cw, "partial", "(Lnet/minecraft/nbt/CompoundTag;[Lnet/minecraft/world/level/ItemLike;)" + ing,
                CRAFTING + "PartialNBTIngredient", "(Lnet/minecraft/nbt/CompoundTag;[Lnet/minecraft/world/level/ItemLike;)L"
                        + CRAFTING + "PartialNBTIngredient;", 2);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** JEI's event channel: opened through NetworkRegistry, listened to, read and sent through. */
    static byte[] net(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        String channel = "L" + NETWORK + "event/EventNetworkChannel;";
        MethodVisitor open = method(cw, "open", "(L" + OLD_ID + ";)" + channel);
        open.visitVarInsn(ALOAD, 0);
        open.visitInsn(ACONST_NULL);
        open.visitInsn(ACONST_NULL);
        open.visitInsn(ACONST_NULL);
        open.visitMethodInsn(INVOKESTATIC, NETWORK + "NetworkRegistry", "newEventChannel", "(L" + OLD_ID
                + ";Ljava/util/function/Supplier;Ljava/util/function/Predicate;Ljava/util/function/Predicate;)" + channel,
                false);
        open.visitInsn(ARETURN);
        end(open);

        MethodVisitor listen = method(cw, "listen", "(" + channel + "Ljava/util/function/Consumer;)V");
        listen.visitVarInsn(ALOAD, 0);
        listen.visitVarInsn(ALOAD, 1);
        listen.visitMethodInsn(INVOKEVIRTUAL, NETWORK + "event/EventNetworkChannel", "addListener",
                "(Ljava/util/function/Consumer;)V", false);
        listen.visitInsn(RETURN);
        end(listen);

        String event = NETWORK + "NetworkEvent$ServerCustomPayloadEvent";
        MethodVisitor payload = method(cw, "payload", "(L" + event + ";)L" + BUF + ";");
        payload.visitVarInsn(ALOAD, 0);
        payload.visitMethodInsn(INVOKEVIRTUAL, event, "getPayload", "()L" + BUF + ";", false);
        payload.visitInsn(ARETURN);
        end(payload);

        String packet = "Lnet/minecraft/network/protocol/Packet;";
        MethodVisitor send = method(cw, "send", "(Lorg/apache/commons/lang3/tuple/Pair;L" + OLD_ID + ";)" + packet);
        send.visitFieldInsn(GETSTATIC, NETWORK + "NetworkDirection", "PLAY_TO_SERVER", "L" + NETWORK + "NetworkDirection;");
        send.visitVarInsn(ALOAD, 0);
        send.visitVarInsn(ALOAD, 1);
        send.visitMethodInsn(INVOKEVIRTUAL, NETWORK + "NetworkDirection", "buildPacket",
                "(Lorg/apache/commons/lang3/tuple/Pair;L" + OLD_ID + ";)L" + NETWORK + "ICustomPacket;", false);
        send.visitMethodInsn(INVOKEINTERFACE, NETWORK + "ICustomPacket", "getThis", "()" + packet, true);
        send.visitInsn(ARETURN);
        end(send);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void factory(ClassWriter cw, String name, String desc, String owner, String callDesc, int args) {
        MethodVisitor mv = method(cw, name, desc);
        for (int i = 0; i < args; i++) mv.visitVarInsn(ALOAD, i);
        mv.visitMethodInsn(INVOKESTATIC, owner, "of", callDesc, false);
        mv.visitInsn(ARETURN);
        end(mv);
    }

    private static MethodVisitor method(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        return mv;
    }

    private static void end(MethodVisitor mv) {
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static Handle metafactory() {
        return new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
    }
}
