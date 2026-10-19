/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Block calls that newer Minecraft made protected. Up to 1.20.1 a mod could call a block's
 * per-state methods on any block, as in {@code state.getBlock().getMenuProvider(state, level, pos)}.
 * Later versions made those {@code BlockBehaviour} methods protected and kept a public delegate on
 * the state, {@code state.getMenuProvider(level, pos)}. The old call then fails verification with
 * "Bad access to protected data", which stops the whole class from loading.
 *
 * <p>Each protected method whose first parameter is the state and whose state delegate takes the
 * remaining parameters is found on the host. A call to it on a {@code Block} or
 * {@code BlockBehaviour} goes to the state's delegate, which runs the same block's method. A
 * {@code super} call in a mod's own override is legal and stays as written. A call whose block is
 * not the state's own block reaches the state's block instead, the only difference.
 */
public final class LegacyProtectedBlockCallBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    /**
     * The classes the bridge names, in one namespace, and how a method name reads across classes.
     * A block method and its state delegate share a Mojang name, while intermediary gives each its
     * own id, so intermediary names are compared through the bundled Mojang map.
     */
    record Names(String behaviour, String stateBase, String state, String block, String helper,
                 Function<String, String> sharedName) {}

    static final Names MOJANG = new Names(
            "net/minecraft/world/level/block/state/BlockBehaviour",
            "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase",
            "net/minecraft/world/level/block/state/BlockState",
            "net/minecraft/world/level/block/Block",
            "com/retromod/generated/LegacyBlockStateCalls",
            Function.identity());

    /** Pre-26.1 Fabric, whose mods and host keep intermediary names. */
    static final Names INTERMEDIARY = new Names(
            "net/minecraft/class_4970",
            "net/minecraft/class_4970$class_4971",
            "net/minecraft/class_2680",
            "net/minecraft/class_2248",
            "com/retromod/generated/LegacyIntermediaryBlockStateCalls",
            name -> com.retromod.mapping.IntermediaryToMojangMapper.getInstance().mapMethod(name));

    /** A protected block method and the name of its public state delegate. */
    record Delegated(MethodNode method, String delegateName) {}

    private LegacyProtectedBlockCallBridge() {}

    public static void register(RetromodTransformer transformer) {
        log(register(transformer, MOJANG, ClassResourceInspector::read));
    }

    public static void registerIntermediary(RetromodTransformer transformer) {
        log(register(transformer, INTERMEDIARY, ClassResourceInspector::read));
    }

    private static void log(int bridged) {
        if (bridged > 0) LOGGER.debug("Routed {} protected block calls to their state delegates", bridged);
    }

    /** Registers a redirect for each protected method with a state delegate on this host. */
    static int register(RetromodTransformer transformer, Names names, Function<String, ClassNode> host) {
        ClassNode behaviour = host.apply(names.behaviour());
        ClassNode stateBase = host.apply(names.stateBase());
        if (behaviour == null || stateBase == null) return 0;
        List<Delegated> delegated = protectedWithStateDelegate(names, behaviour, stateBase);
        if (delegated.isEmpty()) return 0;
        transformer.registerSyntheticClass(names.helper(), generateHelper(names, delegated));
        for (Delegated call : delegated) {
            for (String owner : new String[] {names.block(), names.behaviour()}) {
                transformer.registerWrappingMethodRedirect(owner, call.method().name, call.method().desc,
                        names.helper(), call.method().name, helperDesc(names, call.method().desc));
            }
        }
        return delegated.size();
    }

    static List<Delegated> protectedWithStateDelegate(Names names, ClassNode behaviour, ClassNode stateBase) {
        String statePrefix = "(L" + names.state() + ";";
        List<Delegated> delegated = new ArrayList<>();
        for (MethodNode method : behaviour.methods) {
            if ((method.access & Opcodes.ACC_PROTECTED) == 0 || (method.access & Opcodes.ACC_STATIC) != 0
                    || !method.desc.startsWith(statePrefix)) {
                continue;
            }
            String delegateDesc = delegateDesc(names, method.desc);
            String sharedName = names.sharedName().apply(method.name);
            for (MethodNode candidate : stateBase.methods) {
                if (names.sharedName().apply(candidate.name).equals(sharedName)
                        && candidate.desc.equals(delegateDesc)
                        && (candidate.access & Opcodes.ACC_PUBLIC) != 0
                        && (candidate.access & Opcodes.ACC_STATIC) == 0) {
                    delegated.add(new Delegated(method, candidate.name));
                    break;
                }
            }
        }
        return delegated;
    }

    /** The state delegate's descriptor: the block method's, without the leading state. */
    static String delegateDesc(Names names, String blockDesc) {
        return "(" + blockDesc.substring(("(L" + names.state() + ";").length());
    }

    /** The helper takes the block the call was made on, then the call's own arguments. */
    static String helperDesc(Names names, String blockDesc) {
        return "(L" + names.behaviour() + ";" + blockDesc.substring(1);
    }

    /** {@code static R m(BlockBehaviour block, BlockState state, A... args) { return state.m(args); }} */
    static byte[] generateHelper(Names names, List<Delegated> delegated) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                names.helper(), null, "java/lang/Object", null);
        for (Delegated call : delegated) {
            MethodNode method = call.method();
            String delegateDesc = delegateDesc(names, method.desc);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, method.name,
                    helperDesc(names, method.desc), null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            int slot = 2;
            for (Type argument : Type.getArgumentTypes(delegateDesc)) {
                mv.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
                slot += argument.getSize();
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.stateBase(), call.delegateName(), delegateDesc, false);
            mv.visitInsn(Type.getReturnType(delegateDesc).getOpcode(Opcodes.IRETURN));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
