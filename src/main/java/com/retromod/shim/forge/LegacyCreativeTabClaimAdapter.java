/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/**
 * Tells {@code LegacyCreativeTabContents} which item each tagged {@code Item.Properties} built.
 *
 * <p>An item constructor copies what it needs out of its properties and keeps no reference, so the
 * tab a 1.19.2 mod named with {@code .tab(TAB)} is lost the moment the item exists. This pass finds
 * every constructor call whose last parameter is {@code Item.Properties}, which is the shape of
 * {@code Item}, {@code BlockItem}, the tool and armor items and their subclasses, and reports the
 * finished object together with the properties it was given. Two call shapes qualify: a
 * {@code new X(..., properties)} whose {@code NEW} was duplicated, and a constructor's own
 * {@code super(..., properties)} call. Anything else is left alone.
 *
 * <p>The properties are copied into a fresh local just before the call. That slot is only live
 * between two adjacent instructions with no branch target between them, so existing stack map
 * frames stay valid.
 */
public final class LegacyCreativeTabClaimAdapter {

    private static final String PROPERTIES_DESC = "Lnet/minecraft/world/item/Item$Properties;";
    private static final byte[] PROPERTIES_NAME = "net/minecraft/world/item/Item$Properties"
            .getBytes(StandardCharsets.UTF_8);
    /** Only classes that call the bridged {@code tab(...)} are old enough to need this. */
    private static final byte[] LEGACY_TAB_CALL = CreativeModeTabBridge.GENERATED
            .getBytes(StandardCharsets.UTF_8);
    private static final String CLAIM_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";

    private LegacyCreativeTabClaimAdapter() {}

    /**
     * Returns {@code classBytes} unchanged unless the class calls the bridged {@code tab(...)} and
     * constructs something from item properties, so a mod built for the host passes through.
     */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !contains(classBytes, PROPERTIES_NAME)
                || !contains(classBytes, LEGACY_TAB_CALL)) {
            return classBytes;
        }
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        if (cn.name.startsWith("com/retromod/")) return classBytes;
        boolean changed = false;
        for (MethodNode method : cn.methods) {
            changed |= claimItems(method);
        }
        if (!changed) return classBytes;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static boolean claimItems(MethodNode method) {
        if (method.instructions == null || method.instructions.size() == 0) return false;
        List<MethodInsnNode> superCalls = new ArrayList<>();
        List<MethodInsnNode> newCalls = new ArrayList<>();
        Deque<TypeInsnNode> openNews = new ArrayDeque<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == NEW) {
                openNews.push((TypeInsnNode) insn);
            } else if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESPECIAL
                    && call.name.equals("<init>")) {
                TypeInsnNode allocation = openNews.poll();
                if (!takesPropertiesLast(call.desc) || alreadyClaimed(call)) continue;
                if (allocation == null) {
                    if (method.name.equals("<init>")) superCalls.add(call);
                } else if (allocation.desc.equals(call.owner) && isDup(allocation.getNext())) {
                    newCalls.add(call);
                }
            }
        }
        if (superCalls.isEmpty() && newCalls.isEmpty()) return false;
        int saved = method.maxLocals;
        method.maxLocals = saved + 1;
        for (MethodInsnNode call : superCalls) insertClaim(method, call, saved, true);
        for (MethodInsnNode call : newCalls) insertClaim(method, call, saved, false);
        return true;
    }

    private static void insertClaim(MethodNode method, MethodInsnNode call, int saved, boolean onThis) {
        InsnList before = new InsnList();
        before.add(new InsnNode(DUP));
        before.add(new VarInsnNode(ASTORE, saved));
        method.instructions.insertBefore(call, before);

        InsnList after = new InsnList();
        // A super call initialized "this"; a new X(...) left its own copy on the stack.
        after.add(onThis ? new VarInsnNode(ALOAD, 0) : new InsnNode(DUP));
        after.add(new VarInsnNode(ALOAD, saved));
        after.add(new MethodInsnNode(INVOKESTATIC, CreativeModeTabBridge.TAB_CONTENTS,
                "claim", CLAIM_DESC, false));
        method.instructions.insert(call, after);
    }

    /** A class transformed twice, such as an AOT jar loaded at runtime, keeps one claim per call. */
    private static boolean alreadyClaimed(MethodInsnNode call) {
        AbstractInsnNode third = call.getNext() == null || call.getNext().getNext() == null
                ? null : call.getNext().getNext().getNext();
        return third instanceof MethodInsnNode claim && claim.name.equals("claim")
                && claim.owner.endsWith("/LegacyCreativeTabContents");
    }

    static boolean takesPropertiesLast(String desc) {
        return desc.endsWith(PROPERTIES_DESC + ")V");
    }

    private static boolean isDup(AbstractInsnNode insn) {
        return insn != null && insn.getOpcode() == DUP;
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
}
