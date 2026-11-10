/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Adds static calls to the constructors of a jar's Forge-family {@code @Mod} classes, for bridges that run while a mod constructs. */
public final class ModConstructorHooks {

    /** Where a call goes: right after the super call, or before every return. */
    public enum Position { HEAD, TAIL }

    /** The mod object under construction, as a call argument. */
    public enum Instance { MOD }

    /** One static call; each argument is {@link Instance#MOD}, a String or a class {@link Type}. */
    public record Call(Position at, String owner, String name, String desc, List<Object> arguments) {
        public Call {
            arguments = List.copyOf(arguments);
        }
    }

    /** A bridge's calls for one jar, decided from its classes before any of them is transformed. */
    public interface JarPlan {
        /** The calls for the {@code @Mod} class {@code modClass}, whose id is {@code modId}; empty for none. */
        List<Call> callsFor(String modClass, String modId);

        /** Changes the plan makes to another class of the jar; none by default. */
        default byte[] adjust(String className, byte[] classBytes) {
            return classBytes;
        }
    }

    /** Plans one jar from its original classes by name and its {@code @Mod} ids by class, or answers null to leave it alone. */
    @FunctionalInterface
    public interface Planner {
        JarPlan plan(Map<String, byte[]> classes, Map<String, String> modIds);
    }

    private static final String FORGE_MOD = "Lnet/minecraftforge/fml/common/Mod;";
    private static final String NEOFORGE_MOD = "Lnet/neoforged/fml/common/Mod;";
    private static final byte[] MOD_NAME = "fml/common/Mod;".getBytes(StandardCharsets.ISO_8859_1);

    // Planners belong to one transformer's registration set and leave with it.
    private static final Map<RetromodTransformer, Map<String, Planner>> PLANNERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ModConstructorHooks() {}

    /** Registers a bridge's planner under {@code key} until the transformer's registrations are cleared. */
    public static void register(RetromodTransformer transformer, String key, Planner planner) {
        Map<String, Planner> planners;
        synchronized (PLANNERS) {
            planners = PLANNERS.computeIfAbsent(transformer, t -> Collections.synchronizedMap(new LinkedHashMap<>()));
        }
        if (planners.put(key, planner) == null && planners.size() == 1) {
            transformer.onRegistrationReset(() -> PLANNERS.remove(transformer));
        }
    }

    /** The hooks for one jar, from its classes as read, or null when no registered bridge changes it. */
    public static JarHooks forJar(RetromodTransformer transformer, Iterable<byte[]> originalClasses) {
        List<Planner> planners;
        synchronized (PLANNERS) {
            Map<String, Planner> registered = PLANNERS.get(transformer);
            if (registered == null) return null;
            synchronized (registered) {
                planners = List.copyOf(registered.values());
            }
        }
        if (planners.isEmpty() || originalClasses == null) return null;

        // Each class by name, and the id of every @Mod class among them.
        Map<String, byte[]> classes = new LinkedHashMap<>();
        Map<String, String> modIds = new LinkedHashMap<>();
        for (byte[] bytes : originalClasses) {
            String name = className(bytes);
            if (name == null) continue;
            classes.put(name, bytes);
            String modId = modId(bytes);
            if (modId != null) modIds.put(name, modId);
        }
        // Without a @Mod class nothing constructs, so no call could run.
        if (modIds.isEmpty()) return null;

        List<JarPlan> plans = new ArrayList<>();
        for (Planner planner : planners) {
            JarPlan plan = planner.plan(Collections.unmodifiableMap(classes), Collections.unmodifiableMap(modIds));
            if (plan != null) plans.add(plan);
        }
        return plans.isEmpty() ? null : new JarHooks(plans, Map.copyOf(modIds));
    }

    /** The planned changes for one jar, applied to each of its classes once they are transformed. */
    public static final class JarHooks {
        private final List<JarPlan> plans;
        private final Map<String, String> modIds;

        private JarHooks(List<JarPlan> plans, Map<String, String> modIds) {
            this.plans = List.copyOf(plans);
            this.modIds = modIds;
        }

        /** The class with every planned change: calls in a {@code @Mod} class's constructors, adjustments elsewhere. */
        public byte[] apply(byte[] classBytes) {
            String name = className(classBytes);
            if (name == null) return classBytes;
            byte[] current = classBytes;
            for (JarPlan plan : plans) {
                current = plan.adjust(name, current);
            }
            String modId = modIds.get(name);
            if (modId == null) return current;
            List<Call> calls = new ArrayList<>();
            for (JarPlan plan : plans) {
                calls.addAll(plan.callsFor(name, modId));
            }
            return calls.isEmpty() ? current : addCalls(current, calls);
        }
    }

    /** The class with each call added to its constructors, or the same array when it cannot be read. */
    static byte[] addCalls(byte[] classBytes, List<Call> calls) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
            for (Call call : calls) {
                addCall(node, call);
            }
            // Every call is straight-line code that leaves the stack as it found it, so the frames stay valid.
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException unreadable) {
            return classBytes;
        }
    }

    /** Adds the call to every constructor that does not hand over to another one with {@code this(...)}; returns how many. */
    public static int addCall(ClassNode node, Call call) {
        int changed = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) continue;
            MethodInsnNode initializer = initializerCall(method);
            // The constructor it hands over to gets the call, so this one would make it twice.
            if (initializer != null && initializer.owner.equals(node.name)) continue;
            if (call.at() == Position.HEAD) {
                // Before the super call the object is not usable yet, and javac puts nothing there either.
                if (initializer == null) continue;
                method.instructions.insert(initializer, instructions(call));
                changed++;
                continue;
            }
            boolean added = false;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() == Opcodes.RETURN) {
                    method.instructions.insertBefore(insn, instructions(call));
                    added = true;
                }
            }
            if (added) changed++;
        }
        return changed;
    }

    /** The {@code super(...)} or {@code this(...)} call that initializes the object, or null when there is none. */
    static MethodInsnNode initializerCall(MethodNode constructor) {
        // Each NEW is finished by its own <init> call, so the first one left over is the object's.
        int pending = 0;
        for (AbstractInsnNode insn : constructor.instructions) {
            if (insn.getOpcode() == Opcodes.NEW) {
                pending++;
            } else if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.name.equals("<init>")) {
                if (pending == 0) return call;
                pending--;
            }
        }
        return null;
    }

    private static InsnList instructions(Call call) {
        InsnList list = new InsnList();
        for (Object argument : call.arguments()) {
            list.add(argument == Instance.MOD ? new VarInsnNode(Opcodes.ALOAD, 0) : new LdcInsnNode(argument));
        }
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, call.owner(), call.name(), call.desc(), false));
        return list;
    }

    /** The class's internal name, or null when the bytes are not a readable class. */
    static String className(byte[] classBytes) {
        if (classBytes == null) return null;
        try {
            return new ClassReader(classBytes).getClassName();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** The id a Forge-family {@code @Mod} class declares in its value, or null. */
    static String modId(byte[] classBytes) {
        if (!contains(classBytes, MOD_NAME)) return null;
        String[] id = new String[1];
        try {
            new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    if (!desc.equals(FORGE_MOD) && !desc.equals(NEOFORGE_MOD)) return null;
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String name, Object value) {
                            if ("value".equals(name) && value instanceof String s && !s.isBlank()) id[0] = s;
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException unreadable) {
            return null;
        }
        return id[0];
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
