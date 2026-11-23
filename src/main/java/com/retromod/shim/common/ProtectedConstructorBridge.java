/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Lets an old mod keep writing {@code new X(...)} where the host made that constructor of a game class protected.
 * The object is built as a generated public subclass of X with the same constructor, which may call the protected
 * one. A constructor reference ({@code X::new}) is pointed at that subclass the same way. A mod's own subclass calling {@code super(...)} needs nothing and is left alone. A package-private
 * constructor cannot be reached from a subclass in another package, so it is not bridged.
 */
public final class ProtectedConstructorBridge {

    static final String PREFIX = "com/retromod/generated/ctor/";
    /** The package of the generated subclasses, which the Fabric id stamp also recognizes. */
    public static final String GENERATED_PREFIX = PREFIX;
    private static final byte[] NEW_GAME_TYPE = "net/minecraft/".getBytes(StandardCharsets.ISO_8859_1);

    // Per host reader, class and constructor descriptor: whether a `new` needs the bridge.
    private static final Map<String, Boolean> PROTECTED = new ConcurrentHashMap<>();
    private static final Function<String, ClassNode> HOST = ClassResourceInspector::read;

    private ProtectedConstructorBridge() {}

    /** Rewrites one class of a mod, reading the host the game or the CLI provides. */
    public static byte[] apply(byte[] classBytes, RetromodTransformer t) {
        return apply(classBytes, t, HOST);
    }

    static byte[] apply(byte[] classBytes, RetromodTransformer t, Function<String, ClassNode> host) {
        if (classBytes == null || !StaticFieldIdStamper.containsBytes(classBytes, NEW_GAME_TYPE)
                || !callsProtectedConstructor(classBytes, host)) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, 0);
        } catch (RuntimeException unreadable) {
            return classBytes;
        }
        // A mixin's code is merged into a game class, where the constructor is reachable as written.
        if (node.name.startsWith("net/minecraft/") || com.retromod.core.SyntheticEmbedder.isRetromodClass(node.name)
                || StaticFieldIdStamper.isMixin(node)) {
            return classBytes;
        }
        int bridged = 0;
        for (MethodNode method : node.methods) bridged += bridgeMethod(node, method, t, host);
        if (bridged == 0) return classBytes;
        // Only owners of a new and its constructor call changed; the frames still hold, as the new type is a subtype.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** A light first pass: whether any constructor call in the class targets a protected host constructor. */
    private static boolean callsProtectedConstructor(byte[] classBytes, Function<String, ClassNode> host) {
        boolean[] found = new boolean[1];
        try {
            new ClassReader(classBytes).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method, String mdesc, boolean itf) {
                            if (!found[0] && opcode == Opcodes.INVOKESPECIAL && method.equals("<init>")
                                    && owner.startsWith("net/minecraft/") && needsBridge(owner, mdesc, host)) {
                                found[0] = true;
                            }
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String name, String idesc, Handle bootstrap, Object... args) {
                            for (Object arg : args) {
                                if (!found[0] && !serializable(bootstrap, args) && bridgedReference(bootstrap, arg, host)) found[0] = true;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException unreadable) {
            return false;
        }
        return found[0];
    }

    private static int bridgeMethod(ClassNode owner, MethodNode method, RetromodTransformer t, Function<String, ClassNode> host) {
        if (method.instructions == null || method.instructions.size() == 0) return 0;
        int references = bridgeReferences(method, t, host);
        List<MethodInsnNode> candidates = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>")
                    && call.owner.startsWith("net/minecraft/") && needsBridge(call.owner, call.desc, host)) {
                candidates.add(call);
            }
        }
        if (candidates.isEmpty()) return references;
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(owner.name, method);
        } catch (Throwable unanalyzable) {
            return references;
        }
        int bridged = references;
        for (MethodInsnNode call : candidates) {
            TypeInsnNode creation = creationOf(method, frames, call);
            // A super(...) or this(...) call has no `new` behind it and stays as written.
            if (creation == null) continue;
            String open = openClass(t, call.owner, call.desc);
            creation.desc = open;
            call.owner = open;
            bridged++;
        }
        return bridged;
    }

    /** Points each constructor reference (X::new) at the open class; the lambda still returns an X. */
    private static int bridgeReferences(MethodNode method, RetromodTransformer t, Function<String, ClassNode> host) {
        int bridged = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof InvokeDynamicInsnNode indy) || indy.bsmArgs == null || serializable(indy.bsm, indy.bsmArgs)) continue;
            for (int i = 0; i < indy.bsmArgs.length; i++) {
                if (!bridgedReference(indy.bsm, indy.bsmArgs[i], host)) continue;
                Handle reference = (Handle) indy.bsmArgs[i];
                String open = openClass(t, reference.getOwner(), reference.getDesc());
                indy.bsmArgs[i] = new Handle(Opcodes.H_NEWINVOKESPECIAL, open, "<init>", reference.getDesc(), false);
                bridged++;
            }
        }
        return bridged;
    }

    /** Whether a lambda is serializable: its deserializer checks the original constructor, so it stays as written. */
    private static boolean serializable(Handle bootstrap, Object[] args) {
        return bootstrap.getName().equals("altMetafactory") && args.length > 3 && args[3] instanceof Integer flags
                && (flags & java.lang.invoke.LambdaMetafactory.FLAG_SERIALIZABLE) != 0;
    }

    /** Whether a lambda bootstrap argument is a reference to a protected host constructor. */
    private static boolean bridgedReference(Handle bootstrap, Object argument, Function<String, ClassNode> host) {
        // Only LambdaMetafactory is known to accept a subclass constructor in place of the original.
        return bootstrap.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                && argument instanceof Handle reference && reference.getTag() == Opcodes.H_NEWINVOKESPECIAL
                && reference.getOwner().startsWith("net/minecraft/") && needsBridge(reference.getOwner(), reference.getDesc(), host);
    }

    /** The `new` whose object this constructor call initializes, or null for a super or this call. */
    private static TypeInsnNode creationOf(MethodNode method, Frame<SourceValue>[] frames, MethodInsnNode call) {
        Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
        if (frame == null) return null;
        int receiver = frame.getStackSize() - Type.getArgumentTypes(call.desc).length - 1;
        if (receiver < 0) return null;
        SourceValue value = frame.getStack(receiver);
        if (value.insns.size() != 1) return null;
        AbstractInsnNode source = value.insns.iterator().next();
        // javac duplicates the new object, so the receiver's source is the dup right after it.
        if (source.getOpcode() == Opcodes.DUP) {
            source = source.getPrevious();
            while (source != null && source.getOpcode() < 0) source = source.getPrevious();
        }
        return source instanceof TypeInsnNode creation && creation.getOpcode() == Opcodes.NEW
                && creation.desc.equals(call.owner) ? creation : null;
    }

    /** Whether the host has this constructor, protected, on a class that can be subclassed. */
    static boolean needsBridge(String owner, String desc, Function<String, ClassNode> host) {
        // Keyed on the reader and on the offline host too, so a CLI run with another --host-jar asks again.
        String key = System.identityHashCode(host) + ":" + System.identityHashCode(ClassResourceInspector.offlineHost())
                + ":" + owner + desc;
        Boolean known = PROTECTED.get(key);
        if (known != null) return known;
        ClassNode type = host.apply(owner);
        // An unreadable class is not remembered, since the host may be readable on a later call.
        if (type == null) return false;
        boolean bridge = subclassable(type) && type.methods.stream()
                .filter(m -> m.name.equals("<init>") && m.desc.equals(desc)).findFirst()
                .map(m -> (m.access & Opcodes.ACC_PROTECTED) != 0).orElse(false);
        PROTECTED.put(key, bridge);
        return bridge;
    }

    /** Whether a generated class may extend the type: not final, abstract, an interface, or sealed. */
    private static boolean subclassable(ClassNode type) {
        return (type.access & (Opcodes.ACC_FINAL | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0
                && (type.permittedSubclasses == null || type.permittedSubclasses.isEmpty());
    }

    /** The generated subclass for one constructor, registered once so every mod that uses it embeds it. */
    static String openClass(RetromodTransformer t, String owner, String desc) {
        String name = className(owner, desc);
        if (!t.getSyntheticClasses().containsKey(name)) t.registerSyntheticClass(name, generate(name, owner, desc));
        return name;
    }

    /** The owner's simple name and a hash of owner and descriptor: unique, and short enough for a file name. */
    static String className(String owner, String desc) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((owner + desc).getBytes(StandardCharsets.UTF_8));
            String hash = java.util.HexFormat.of().formatHex(digest).substring(0, 24);
            return PREFIX + owner.substring(owner.lastIndexOf('/') + 1) + "$" + hash;
        } catch (java.security.NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", missing);
        }
    }

    static byte[] generate(String name, String owner, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, owner, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        int slot = 1;
        for (Type argument : Type.getArgumentTypes(desc)) {
            init.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
            slot += argument.getSize();
        }
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", desc, false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
