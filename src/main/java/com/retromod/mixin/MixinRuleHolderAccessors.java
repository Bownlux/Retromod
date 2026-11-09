/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.util.RetromodLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Moves an old accessor of the generator settings' surface rule onto 26.3's rule holder, together with its callers. */
public final class MixinRuleHolderAccessors {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-mixin");

    private static final String SETTINGS = MixinMaterialRuleRepair.SETTINGS;
    private static final String RULE_DESC = "L" + MixinMaterialRuleRepair.RULE + ";";
    private static final String HOLDER = MixinMaterialRuleRepair.HOLDER;
    private static final String HOLDER_DESC = "L" + HOLDER + ";";
    private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final byte[] RULE_NAME = MixinMaterialRuleRepair.RULE.getBytes(StandardCharsets.UTF_8);
    // The settings and their rule field as an old jar's own bytes name them: Mojang, intermediary, or 1.20.1 SRG.
    private static final Set<String> SETTINGS_NAMES = Set.of(SETTINGS, "net/minecraft/class_5284");
    private static final Set<String> FIELD_NAMES = Set.of(MixinMaterialRuleRepair.OLD_NAME, "comp_478", "f_188871_",
            MixinMaterialRuleRepair.NEW_NAME);

    private MixinRuleHolderAccessors() {}

    /** Points each surface rule accessor of a settings mixin at the rule holder. */
    static boolean retarget(ClassNode mixin) {
        // An accessor interface is not merged into the settings (pitfall 20), so it stays an accessor for the new field.
        if (!targetsSettings(mixin, Set.of(SETTINGS))) return false;
        List<MethodNode> accessors = new ArrayList<>();
        for (MethodNode method : mixin.methods) {
            AnnotationNode accessor = accessor(method);
            if (accessor != null && MixinMaterialRuleRepair.OLD_NAME.equals(MixinAccessorFieldMigration.accessedField(method, accessor))
                    && holderDesc(method.desc) != null) {
                accessors.add(method);
            }
        }
        if (accessors.isEmpty() || !MixinMaterialRuleRepair.hostKeepsRuleInHolder()) return false;
        for (MethodNode method : accessors) {
            // The new name is written out, so a refmap entry for the old one is no longer consulted.
            setValue(accessor(method), MixinMaterialRuleRepair.NEW_NAME);
            method.desc = holderDesc(method.desc);
            method.signature = null;
            LOGGER.debug(RetromodLog.DETAIL, "Moved accessor {}.{} to the generator settings' rule holder", mixin.name,
                    method.name);
        }
        return true;
    }

    /** Wraps the rule a caller passes to a retargeted setter in a holder, and unwraps the one a getter returns. */
    public static byte[] rewriteCalls(byte[] classBytes, Function<String, byte[]> jarClasses) {
        // The accessor is recognized from its own class in the jar, so this needs no state from retarget.
        if (classBytes == null || jarClasses == null || !contains(classBytes, RULE_NAME)) return classBytes;
        if (!MixinMaterialRuleRepair.hostKeepsRuleInHolder()) return classBytes;
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            Map<String, Boolean> known = new HashMap<>();
            boolean changed = false;
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode call && holderDesc(call.desc) != null && callsJarAccessor(call)
                            && known.computeIfAbsent(call.owner + "." + call.name + call.desc,
                                    key -> isRuleAccessor(call, jarClasses))) {
                        useHolder(method, call);
                        changed = true;
                    }
                }
            }
            if (!changed) return classBytes;
            // Each change keeps the stack shape at every branch, so the frames stay valid.
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException e) {
            LOGGER.debug("Rule accessor call rewrite skipped ({}); class left unchanged", e.toString());
            return classBytes;
        }
    }

    /** The accessor descriptor with the holder in place of the rule, or null when it is no rule getter or setter. */
    static String holderDesc(String desc) {
        if (desc.equals("()" + RULE_DESC)) return "()" + HOLDER_DESC;
        if (desc.equals("(" + RULE_DESC + ")V")) return "(" + HOLDER_DESC + ")V";
        return null;
    }

    private static boolean callsJarAccessor(MethodInsnNode call) {
        int opcode = call.getOpcode();
        return (opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL)
                && !call.owner.startsWith("java/") && !call.owner.startsWith("net/minecraft/")
                && !call.owner.startsWith("com/mojang/");
    }

    /** Whether the call's owner in the jar is a settings mixin whose same-named method accesses the rule field. */
    private static boolean isRuleAccessor(MethodInsnNode call, Function<String, byte[]> jarClasses) {
        byte[] bytes = jarClasses.apply(call.owner);
        if (bytes == null) return false;
        ClassNode owner = new ClassNode();
        new ClassReader(bytes).accept(owner, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!targetsSettings(owner, SETTINGS_NAMES)) return false;
        boolean getter = call.desc.startsWith("()");
        for (MethodNode method : owner.methods) {
            AnnotationNode accessor = accessor(method);
            if (accessor == null || !method.name.equals(call.name)) continue;
            Type[] arguments = Type.getArgumentTypes(method.desc);
            boolean voidReturn = Type.getReturnType(method.desc).getSort() == Type.VOID;
            boolean shape = getter ? arguments.length == 0 && !voidReturn : arguments.length == 1 && voidReturn;
            if (shape && FIELD_NAMES.contains(MixinAccessorFieldMigration.accessedField(method, accessor))) return true;
        }
        return false;
    }

    private static void useHolder(MethodNode method, MethodInsnNode call) {
        boolean getter = call.desc.startsWith("()");
        call.desc = holderDesc(call.desc);
        if (getter) {
            InsnList value = new InsnList();
            value.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, HOLDER, "value", "()Ljava/lang/Object;", true));
            value.add(new TypeInsnNode(Opcodes.CHECKCAST, MixinMaterialRuleRepair.RULE));
            method.instructions.insert(call, value);
        } else {
            method.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOLDER, "direct",
                    "(Ljava/lang/Object;)" + HOLDER_DESC, true));
        }
    }

    private static boolean targetsSettings(ClassNode mixin, Set<String> names) {
        for (List<AnnotationNode> annotations : List.of(listOrEmpty(mixin.visibleAnnotations),
                listOrEmpty(mixin.invisibleAnnotations))) {
            for (AnnotationNode annotation : annotations) {
                if (!MIXIN_DESC.equals(annotation.desc) || annotation.values == null) continue;
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    if (!(annotation.values.get(i + 1) instanceof List<?> values)) continue;
                    for (Object target : values) {
                        String name = target instanceof Type type ? type.getInternalName() : target.toString().replace('.', '/');
                        if (names.contains(name)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static AnnotationNode accessor(MethodNode method) {
        for (List<AnnotationNode> annotations : List.of(listOrEmpty(method.visibleAnnotations),
                listOrEmpty(method.invisibleAnnotations))) {
            for (AnnotationNode annotation : annotations) {
                if (ACCESSOR_DESC.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }

    private static void setValue(AnnotationNode annotation, String value) {
        if (annotation.values == null) annotation.values = new ArrayList<>();
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if ("value".equals(annotation.values.get(i))) {
                annotation.values.set(i + 1, value);
                return;
            }
        }
        annotation.values.add("value");
        annotation.values.add(value);
    }

    private static <T> List<T> listOrEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
