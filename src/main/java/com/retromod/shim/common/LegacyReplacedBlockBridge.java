/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/** Keeps old block classes that a newer host replaced with one taking an extra first argument. */
public final class LegacyReplacedBlockBridge {

    static final String PROPS = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    static final String OLD_CTOR = "(L" + PROPS + ";)V";

    /** One replaced block class: the generated base passes the field the old class behaved like. */
    record Replacement(String old, String replacement, String base, String argOwner, String argName, String argDesc,
            String since) {
    }

    // 26.3: a path names the block it turns back into; the old dirt path turned back into dirt.
    static final Replacement DIRT_PATH = new Replacement("net/minecraft/world/level/block/DirtPathBlock",
            "net/minecraft/world/level/block/PathBlock", "com/retromod/generated/LegacyDirtPathBlock",
            "net/minecraft/world/level/block/Blocks", "DIRT", "Lnet/minecraft/world/level/block/Block;", "26.3");
    // 26.1: nether roots name the blocks they grow on; the warped tag holds the old set (soil, nylium, soul soil).
    static final Replacement NETHER_ROOTS = new Replacement("net/minecraft/world/level/block/RootsBlock",
            "net/minecraft/world/level/block/NetherRootsBlock", "com/retromod/generated/LegacyRootsBlock",
            "net/minecraft/tags/BlockTags", "SUPPORTS_WARPED_ROOTS", "Lnet/minecraft/tags/TagKey;", "26.1");

    private LegacyReplacedBlockBridge() {}

    public static void register(RetromodTransformer t, Replacement r) {
        register(t, r, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Replacement r, Function<String, ClassNode> host) {
        if (!hostReplacedIt(r, host.apply(r.replacement()) != null, host.apply(r.old()) != null)
                || t.getSyntheticClasses().containsKey(r.base())) {
            return;
        }
        t.registerSyntheticClass(r.base(), generate(r));
        t.registerClassRedirect(r.old(), r.replacement());
        // Subclasses and direct construction need the old one-argument constructor.
        t.registerRedirectedSubclassRebase(r.old(), r.replacement(), r.base());
        t.registerConstructorRedirect(r.replacement(), OLD_CTOR, r.base(), "create",
                "(L" + PROPS + ";)L" + r.replacement() + ";");
    }

    /** Whether the host has the replacement and not the old class; offline, whether the target is new enough. */
    static boolean hostReplacedIt(Replacement r, boolean hasReplacement, boolean hasOld) {
        if (!hasReplacement && !hasOld) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, r.since()) >= 0;
        }
        return hasReplacement && !hasOld;
    }

    static byte[] generate(Replacement r) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, r.base(), null, r.replacement(), null);
        // Base(Properties p) { super(<field>, p); }
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", OLD_CTOR, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitFieldInsn(GETSTATIC, r.argOwner(), r.argName(), r.argDesc());
        init.visitVarInsn(ALOAD, 1);
        init.visitMethodInsn(INVOKESPECIAL, r.replacement(), "<init>", "(" + r.argDesc() + "L" + PROPS + ";)V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        // static Replacement create(Properties p) { return new Base(p); }
        MethodVisitor create = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create", "(L" + PROPS + ";)L" + r.replacement() + ";",
                null, null);
        create.visitCode();
        create.visitTypeInsn(NEW, r.base());
        create.visitInsn(DUP);
        create.visitVarInsn(ALOAD, 0);
        create.visitMethodInsn(INVOKESPECIAL, r.base(), "<init>", OLD_CTOR, false);
        create.visitInsn(ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
