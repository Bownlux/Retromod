/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.shim.common.LegacyMemberRenames;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Applies {@link LegacyMemberRenames} to a mixin's {@code @Shadow} members.
 *
 * <p>A shadow is declared on the mixin class and reached through the mixin's own name, so the
 * call-site redirects for the target never touch it, and Mixin then rejects the class because the
 * old name "was not located in the target class". Only a shadow whose name, descriptor, and
 * mixin target match a rename the host confirms is renamed, together with the mixin's own
 * accesses to it.
 */
final class MixinShadowRenames {

    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";

    private MixinShadowRenames() {}

    static boolean apply(ClassNode classNode, Predicate<LegacyMemberRenames.Rename> hostRenamed) {
        List<String> targets = mixinTargets(classNode);
        if (targets.isEmpty()) return false;
        boolean modified = false;
        for (LegacyMemberRenames.Rename rename : LegacyMemberRenames.RENAMES) {
            if (!targets.contains(rename.owner())) continue;
            FieldNode field = rename.field() ? findShadowField(classNode, rename) : null;
            MethodNode method = rename.field() ? null : findShadowMethod(classNode, rename);
            if (field == null && method == null) continue;
            // Checked only once a matching shadow exists, because the check reads the host class.
            if (!hostRenamed.test(rename)) continue;
            if (field != null) field.name = rename.newName();
            if (method != null) method.name = rename.newName();
            renameOwnAccesses(classNode, rename);
            modified = true;
        }
        return modified;
    }

    private static FieldNode findShadowField(ClassNode classNode, LegacyMemberRenames.Rename rename) {
        for (FieldNode field : classNode.fields) {
            if (field.name.equals(rename.oldName()) && field.desc.equals(rename.desc())
                    && hasShadow(field.visibleAnnotations, field.invisibleAnnotations)) {
                return field;
            }
        }
        return null;
    }

    private static MethodNode findShadowMethod(ClassNode classNode,
            LegacyMemberRenames.Rename rename) {
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(rename.oldName()) && method.desc.equals(rename.desc())
                    && hasShadow(method.visibleAnnotations, method.invisibleAnnotations)) {
                return method;
            }
        }
        return null;
    }

    private static void renameOwnAccesses(ClassNode classNode, LegacyMemberRenames.Rename rename) {
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (rename.field() && insn instanceof FieldInsnNode access
                        && access.owner.equals(classNode.name)
                        && access.name.equals(rename.oldName())
                        && access.desc.equals(rename.desc())) {
                    access.name = rename.newName();
                } else if (!rename.field() && insn instanceof MethodInsnNode call
                        && call.owner.equals(classNode.name)
                        && call.name.equals(rename.oldName())
                        && call.desc.equals(rename.desc())) {
                    call.name = rename.newName();
                }
            }
        }
    }

    private static boolean hasShadow(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        for (List<AnnotationNode> annotations : annotationLists(visible, invisible)) {
            for (AnnotationNode annotation : annotations) {
                if (SHADOW_DESC.equals(annotation.desc)) return true;
            }
        }
        return false;
    }

    private static List<String> mixinTargets(ClassNode classNode) {
        List<String> result = new ArrayList<>();
        for (List<AnnotationNode> annotations : annotationLists(classNode.visibleAnnotations,
                classNode.invisibleAnnotations)) {
            for (AnnotationNode annotation : annotations) {
                if (!MIXIN_DESC.equals(annotation.desc) || annotation.values == null) continue;
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    Object key = annotation.values.get(i);
                    if (!(annotation.values.get(i + 1) instanceof List<?> values)) continue;
                    for (Object target : values) {
                        if ("value".equals(key) && target instanceof Type type) {
                            result.add(type.getInternalName());
                        } else if ("targets".equals(key)) {
                            result.add(target.toString().replace('.', '/'));
                        }
                    }
                }
            }
        }
        return result;
    }

    private static List<List<AnnotationNode>> annotationLists(List<AnnotationNode> visible,
            List<AnnotationNode> invisible) {
        List<List<AnnotationNode>> lists = new ArrayList<>(2);
        if (visible != null) lists.add(visible);
        if (invisible != null) lists.add(invisible);
        return lists;
    }
}
