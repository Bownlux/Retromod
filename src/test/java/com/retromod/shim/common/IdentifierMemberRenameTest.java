/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.retromod.shim.forge.Forge_1_21_11_to_26_1;
import com.retromod.shim.neoforge.NeoForge_1_21_10_to_1_21_11;
import com.retromod.shim.neoforge.NeoForge_1_21_11_to_26_1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** 1.21.11 renamed the buffer's ResourceLocation members with the class, so old calls and method references take the new names. */
class IdentifierMemberRenameTest {

    private static final String BUF = "net/minecraft/network/FriendlyByteBuf";
    private static final String REGISTRY_BUF = "net/minecraft/network/RegistryFriendlyByteBuf";
    private static final String OLD_ID = "net/minecraft/resources/ResourceLocation";
    private static final String ID = "net/minecraft/resources/Identifier";
    private static final String WRITE = BUF + ".writeIdentifier(L" + ID + ";)L" + BUF + ";";
    private static final String READ = BUF + ".readIdentifier()L" + ID + ";";

    private RetromodTransformer transformer;
    private String previousTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        transformer.clearSrgNameMappings();
        transformer.clearIntermediaryNameMappings();
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
    }

    @Test
    @DisplayName("A Forge 1.20.1 mod's SRG buffer calls and method reference reach writeIdentifier and readIdentifier on 26.x")
    void forgeSrgNames() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        SrgToMojangMapper.getInstance().applyTo(transformer);
        new Forge_1_21_11_to_26_1().registerRedirects(transformer);

        ClassNode out = transform(bufferUser(BUF, OLD_ID, "m_130085_", "m_130281_"));
        assertEquals(List.of(WRITE, READ), calls(out), "Balm's ByteBufCodecs failed here with NoSuchMethodError");
        assertEquals(WRITE, handle(out), "a method reference is renamed too, as in Balm's stream codec");
    }

    @Test
    @DisplayName("A 1.21.1 NeoForge mod's Mojang-named calls through RegistryFriendlyByteBuf take the new names on 26.x")
    void neoForgeMojangNames() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        // The chain a 1.21.1 mod takes: the class rename at 1.21.11, then the 26.1 set.
        new NeoForge_1_21_10_to_1_21_11().registerRedirects(transformer);
        new NeoForge_1_21_11_to_26_1().registerRedirects(transformer);

        ClassNode out = transform(bufferUser(REGISTRY_BUF, OLD_ID, "writeResourceLocation", "readResourceLocation"));
        assertEquals(List.of(REGISTRY_BUF + ".writeIdentifier(L" + ID + ";)L" + BUF + ";",
                REGISTRY_BUF + ".readIdentifier()L" + ID + ";"), calls(out));
        assertEquals(REGISTRY_BUF + ".writeIdentifier(L" + ID + ";)L" + BUF + ";", handle(out));
    }

    @Test
    @DisplayName("NeoForge 1.21.11, where the rename first shipped, takes the new names as well")
    void neoForge12111Host() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        new NeoForge_1_21_10_to_1_21_11().registerRedirects(transformer);

        ClassNode out = transform(bufferUser(BUF, OLD_ID, "writeResourceLocation", "readResourceLocation"));
        assertEquals(List.of(WRITE, READ), calls(out));
    }

    @Test
    @DisplayName("A Fabric mod's intermediary buffer calls reach the 26.x names after the intermediary remap")
    void fabricIntermediaryNames() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        IntermediaryToMojangMapper.applyTo(transformer);
        new Fabric_1_21_11_to_26_1().registerRedirects(transformer);

        ClassNode out = transform(bufferUser("net/minecraft/class_2540", "net/minecraft/class_2960",
                "method_10812", "method_10810"));
        assertEquals(List.of(WRITE, READ), calls(out));
        assertEquals(WRITE, handle(out));
    }

    @Test
    @DisplayName("The id's character check keeps its job under isAllowedInIdentifier")
    void characterCheck() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        new Forge_1_21_11_to_26_1().registerRedirects(transformer);
        assertEquals("isAllowedInIdentifier",
                transformer.registeredMethodRename(OLD_ID, "isAllowedInResourceLocation"));
    }

    /** Writes and reads an id through {@code buffer}, by call and by method reference. */
    private static byte[] bufferUser(String buffer, String id, String write, String read) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/mod/Codecs", null, "java/lang/Object", null);
        String bufferBase = buffer.equals(REGISTRY_BUF) ? BUF : buffer;
        String writeDesc = "(L" + id + ";)L" + bufferBase + ";";
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "roundTrip", "(L" + buffer + ";L" + id + ";)L" + id + ";",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, buffer, write, writeDesc, false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, buffer, read, "()L" + id + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor ref = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "writer", "()Ljava/util/function/BiConsumer;", null, null);
        ref.visitCode();
        ref.visitInvokeDynamicInsn("accept", "()Ljava/util/function/BiConsumer;",
                new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                + "Ljava/lang/invoke/CallSite;", false),
                Type.getType("(Ljava/lang/Object;Ljava/lang/Object;)V"),
                new Handle(H_INVOKEVIRTUAL, buffer, write, writeDesc, false),
                Type.getType("(L" + buffer + ";L" + id + ";)V"));
        ref.visitInsn(ARETURN);
        ref.visitMaxs(0, 0);
        ref.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private ClassNode transform(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, "test/mod/Codecs")).accept(node, 0);
        return node;
    }

    private static List<String> calls(ClassNode node) {
        List<String> found = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) found.add(call.owner + "." + call.name + call.desc);
            }
        }
        return found;
    }

    private static String handle(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs[1] instanceof Handle target) {
                    return target.getOwner() + "." + target.getName() + target.getDesc();
                }
            }
        }
        return "no method reference";
    }
}
