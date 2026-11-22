/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.invoke.LambdaMetafactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/** Turns a constructor reference such as {@code Foo::new} into a call to a factory in the same class, so call-site repairs reach it. */
public final class ConstructorReferenceExpander {

    static final String FACTORY_PREFIX = "retromod$new$";
    private static final String METAFACTORY = "java/lang/invoke/LambdaMetafactory";

    private ConstructorReferenceExpander() {}

    /**
     * Points each reference to a constructor {@code covers} accepts at a private static factory whose body is a plain
     * {@code new Owner(args)}, and adds those factories to the class. Returns how many references moved.
     */
    public static int expand(ClassNode node, BiPredicate<String, String> covers) {
        // An interface holds static methods only from Java 8 class files on.
        if ((node.access & Opcodes.ACC_INTERFACE) != 0 && (node.version & 0xFFFF) < Opcodes.V1_8) return 0;
        // A mixin's methods are merged into Minecraft's class, which a new synthetic method would not follow safely.
        if (LegacyRenderLayerAdapter.isMixin(node)) return 0;
        Map<String, MethodNode> factories = new HashMap<>();
        List<MethodNode> added = new ArrayList<>();
        int moved = 0;
        for (MethodNode method : node.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof InvokeDynamicInsnNode indy) || !expandable(indy)) continue;
                for (int i = 0; i < indy.bsmArgs.length; i++) {
                    if (!(indy.bsmArgs[i] instanceof Handle handle) || handle.getTag() != Opcodes.H_NEWINVOKESPECIAL
                            || !covers.test(handle.getOwner(), handle.getDesc())) {
                        continue;
                    }
                    MethodNode factory = factories.computeIfAbsent(handle.getOwner() + handle.getDesc(),
                            key -> addFactory(node, added, handle.getOwner(), handle.getDesc()));
                    indy.bsmArgs[i] = new Handle(Opcodes.H_INVOKESTATIC, node.name, factory.name, factory.desc,
                            (node.access & Opcodes.ACC_INTERFACE) != 0);
                    moved++;
                }
            }
        }
        node.methods.addAll(added);
        return moved;
    }

    /** A metafactory call whose reference may move; a serializable lambda's deserializer checks the original handle. */
    private static boolean expandable(InvokeDynamicInsnNode indy) {
        if (indy.bsmArgs == null || !METAFACTORY.equals(indy.bsm.getOwner())) return false;
        return !("altMetafactory".equals(indy.bsm.getName()) && indy.bsmArgs.length > 3
                && indy.bsmArgs[3] instanceof Integer flags && (flags & LambdaMetafactory.FLAG_SERIALIZABLE) != 0);
    }

    /** {@code private static Owner retromod$new$N(args) { return new Owner(args); }} */
    private static MethodNode addFactory(ClassNode node, List<MethodNode> added, String owner, String ctorDesc) {
        String name = unusedName(node, added);
        String desc = ctorDesc.substring(0, ctorDesc.lastIndexOf(')') + 1) + "L" + owner + ";";
        MethodNode factory = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, desc, null, null);
        factory.instructions.add(new TypeInsnNode(Opcodes.NEW, owner));
        factory.instructions.add(new InsnNode(Opcodes.DUP));
        int slot = 0;
        for (Type argument : Type.getArgumentTypes(ctorDesc)) {
            factory.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
            slot += argument.getSize();
        }
        factory.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, "<init>", ctorDesc, false));
        factory.instructions.add(new InsnNode(Opcodes.ARETURN));
        // Repairs that run on the factory reserve spare locals from maxLocals, so it has to be exact.
        factory.maxLocals = slot;
        factory.maxStack = slot + 2;
        added.add(factory);
        return factory;
    }

    private static String unusedName(ClassNode node, List<MethodNode> added) {
        for (int i = 0; ; i++) {
            String name = FACTORY_PREFIX + i;
            boolean taken = node.methods.stream().anyMatch(m -> m.name.equals(name))
                    || added.stream().anyMatch(m -> m.name.equals(name));
            if (!taken) return name;
        }
    }
}
