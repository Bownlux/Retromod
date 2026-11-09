/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Moves a Mixin's static initializer that builds an ItemStack out of its target's class initializer. */
public final class MixinLazyStaticInitializer {

    static final String HOLDER_REFERENCE = "net/minecraft/core/Holder$Reference";
    public static final String FIRST_LATE_COMPONENTS_HOST = "26.1";
    private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    private static final String RETURNS_ITEM_STACK = ")L" + ITEM_STACK + ";";
    private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String UNIQUE_DESC = "Lorg/spongepowered/asm/mixin/Unique;";

    static final String BODY = "retromod$staticInit$";
    static final String INIT = "retromod$initStatics$";
    static final String ENSURE = "retromod$ensureStatics$";
    static final String READY = "retromod$staticsReady$";
    static final String STARTED = "retromod$staticsStarted$";

    private MixinLazyStaticInitializer() {}

    /** Whether a mod built for {@code source} predates late component binding. An unknown version never does. */
    static boolean sourceBindsComponentsEarly(String source) {
        return source != null
                && RetromodVersion.compareMcVersions(source, FIRST_LATE_COMPONENTS_HOST) < 0;
    }

    /** Whether the host binds item components after its classes load, so an early ItemStack fails. */
    public static boolean hostBindsComponentsLate(String host, FuzzyMethodResolver index) {
        // A running host answers from its own Holder.Reference, read without loading it.
        ClassNode reference = ClassResourceInspector.read(HOLDER_REFERENCE);
        if (reference != null) {
            return reference.methods.stream().anyMatch(m -> isBindCheck(m.name, m.desc));
        }
        // The CLI and AOT ask the indexed target jar the same question.
        if (index != null && index.isIndexed() && index.hasClass(HOLDER_REFERENCE)) {
            return index.hasMethod(HOLDER_REFERENCE, "areComponentsBound", "()Z");
        }
        // With neither, 26.1 is the first version whose stacks need bound components.
        return host != null && RetromodVersion.compareMcVersions(host, FIRST_LATE_COMPONENTS_HOST) >= 0;
    }

    private static boolean isBindCheck(String name, String desc) {
        return name.equals("areComponentsBound") && desc.equals("()Z");
    }

    /** Whether the class has a static initializer that builds an ItemStack. */
    static boolean handles(ClassNode node) {
        if ((node.access & Opcodes.ACC_INTERFACE) != 0) return false;
        MethodNode clinit = findMethod(node, "<clinit>");
        return clinit != null && buildsItemStack(clinit);
    }

    /** Turns the static initializer into a guarded method run on first use, or returns false and changes nothing. */
    static boolean apply(ClassNode node) {
        if (!handles(node)) return false;
        // A nested class reads the outer class's private fields directly, past any guard.
        if (node.nestMembers != null && !node.nestMembers.isEmpty()) return false;
        MethodNode clinit = findMethod(node, "<clinit>");
        Set<String> lazyFields = ownFieldsAssigned(node, clinit);
        // Without a field to guard, the moved code would never run.
        if (lazyFields == null || lazyFields.isEmpty()) return false;

        String suffix = node.name.replace('/', '_');
        String body = BODY + suffix;
        String init = INIT + suffix;
        String ensure = ENSURE + suffix;
        String ready = READY + suffix;
        String started = STARTED + suffix;
        if (findMethod(node, body) != null || findMethod(node, init) != null
                || findMethod(node, ensure) != null) {
            return false;
        }

        // Guard every access outside the initializer, which itself runs inside the guard.
        for (MethodNode method : node.methods) {
            if (method == clinit) continue;
            guardAccesses(node.name, method, lazyFields, ensure);
        }

        // The old initializer becomes an ordinary method, so its fields can no longer be final.
        clinit.name = body;
        clinit.access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC;
        markUnique(clinit);
        for (FieldNode field : node.fields) {
            if (lazyFields.contains(field.name + field.desc)) field.access &= ~Opcodes.ACC_FINAL;
        }

        // Names carry the Mixin class, so two Mixins of one target never share a flag or method.
        node.fields.add(uniqueField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, ready));
        node.fields.add(uniqueField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, started));
        node.methods.add(initMethod(node.name, init, body, ready, started));
        node.methods.add(ensureMethod(node.name, ensure, init, ready));
        return true;
    }

    private static boolean buildsItemStack(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode type && insn.getOpcode() == Opcodes.NEW
                    && ITEM_STACK.equals(type.desc)) {
                return true;
            }
            // Static factories, Item.getDefaultInstance and Retromod's own stack bridges all return one.
            if (insn instanceof MethodInsnNode call && call.desc.endsWith(RETURNS_ITEM_STACK)) {
                return true;
            }
        }
        return false;
    }

    /** The Mixin's own fields the initializer assigns, as name plus descriptor, or null when it assigns any other. */
    private static Set<String> ownFieldsAssigned(ClassNode node, MethodNode clinit) {
        Set<String> assigned = new HashSet<>();
        for (AbstractInsnNode insn : clinit.instructions) {
            if (!(insn instanceof FieldInsnNode field) || insn.getOpcode() != Opcodes.PUTSTATIC) continue;
            // A shadowed target field or another class's field must keep its timing.
            FieldNode declared = field.owner.equals(node.name) ? findField(node, field.name, field.desc) : null;
            if (declared == null || hasAnnotation(declared.visibleAnnotations, SHADOW_DESC)
                    || hasAnnotation(declared.invisibleAnnotations, SHADOW_DESC)) {
                return null;
            }
            assigned.add(field.name + field.desc);
        }
        return assigned;
    }

    /** Runs the guard before each read or write of a lazy field. */
    private static void guardAccesses(String owner, MethodNode method, Set<String> lazyFields, String ensure) {
        // Before each access rather than at the method start: Mixin copies a constructor's field
        // initializers only from after its super() call.
        List<FieldInsnNode> accesses = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field && field.owner.equals(owner)
                    && (insn.getOpcode() == Opcodes.GETSTATIC || insn.getOpcode() == Opcodes.PUTSTATIC)
                    && lazyFields.contains(field.name + field.desc)) {
                accesses.add(field);
            }
        }
        // The call takes and leaves nothing on the stack, so existing frames and limits stay valid.
        for (FieldInsnNode access : accesses) {
            method.instructions.insertBefore(access,
                    new MethodInsnNode(Opcodes.INVOKESTATIC, owner, ensure, "()V", false));
        }
    }

    /** {@code if (started) return; started = true; body(); ready = true;}, holding the target's class lock. */
    private static MethodNode initMethod(String owner, String name, String body, String ready, String started) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED, name, "()V", null, null);
        LabelNode run = new LabelNode();
        // Setting the flag before the body lets a re-entrant read return, as a class initializer does.
        method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, started, "Z"));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, run));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(run);
        method.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, started, "Z"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, body, "()V", false));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, ready, "Z"));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 1;
        method.maxLocals = 0;
        markUnique(method);
        return method;
    }

    /** {@code if (!ready) init();}: one volatile read once the fields are set. */
    private static MethodNode ensureMethod(String owner, String name, String init, String ready) {
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name, "()V", null, null);
        LabelNode done = new LabelNode();
        method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, ready, "Z"));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNE, done));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, init, "()V", false));
        method.instructions.add(done);
        method.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 1;
        method.maxLocals = 0;
        markUnique(method);
        return method;
    }

    private static FieldNode uniqueField(int access, String name) {
        FieldNode field = new FieldNode(access, name, "Z", null, null);
        field.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(UNIQUE_DESC)));
        return field;
    }

    private static void markUnique(MethodNode method) {
        if (hasAnnotation(method.visibleAnnotations, UNIQUE_DESC)) return;
        if (method.visibleAnnotations == null) method.visibleAnnotations = new ArrayList<>();
        method.visibleAnnotations.add(new AnnotationNode(UNIQUE_DESC));
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations, String desc) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (desc.equals(annotation.desc)) return true;
        }
        return false;
    }

    private static MethodNode findMethod(ClassNode node, String name) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals("()V")) return method;
        }
        return null;
    }

    private static FieldNode findField(ClassNode node, String name, String desc) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(desc)) return field;
        }
        return null;
    }
}
