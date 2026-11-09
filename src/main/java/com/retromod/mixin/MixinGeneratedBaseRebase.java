/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.UnaryOperator;

/** Moves a class mixin off a Retromod-generated legacy base onto the class that base extends. */
final class MixinGeneratedBaseRebase {

    private MixinGeneratedBaseRebase() {}

    /** Rebases the mixin when {@code parentOf} names a parent for its superclass, and says whether it did. */
    static boolean apply(ClassNode classNode, UnaryOperator<String> parentOf) {
        // An accessor interface has no superclass to fix.
        if ((classNode.access & Opcodes.ACC_INTERFACE) != 0) return false;
        String generated = classNode.superName;
        String parent = parentOf.apply(generated);
        if (parent == null) return false;

        // Mixin requires a mixin's superclass to sit in its target's hierarchy. A vanilla target never
        // has the generated base, which lives in the mod jar; a mod class that extends the base has
        // the parent too, so the rebase holds there as well. A generated base adds constructors, and
        // sometimes interfaces, but no other members, so every member the mixin inherits resolves on
        // the parent the same way. Mixin never applies a mixin's inherited interfaces to the target.
        classNode.superName = parent;
        if (classNode.signature != null) {
            classNode.signature = classNode.signature
                    .replace("L" + generated + "<", "L" + parent + "<")
                    .replace("L" + generated + ";", "L" + parent + ";");
        }
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                // Mixin finds the super(...) call it drops by its owner, so the owner moves too.
                if (instruction instanceof MethodInsnNode call && generated.equals(call.owner)) {
                    call.owner = parent;
                } else if (instruction instanceof FieldInsnNode access && generated.equals(access.owner)) {
                    access.owner = parent;
                }
            }
        }
        return true;
    }
}
