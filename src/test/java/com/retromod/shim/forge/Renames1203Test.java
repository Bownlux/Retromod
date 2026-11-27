/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodTransformer.MethodKey;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.Legacy1203RenameBridge;
import com.retromod.shim.neoforge.NeoForgeMovedMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/** A 1.20.1 Forge mod's renamed members under the names 1.20.3 and NeoForge gave them (#406, #415). */
class Renames1203Test {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void clear() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void oldNameCallsTheNewOne() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Legacy1203RenameBridge.register(transformer);
        String properties = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
        var target = transformer.getMethodRedirects().get(new MethodKey(properties, "noParticlesOnBreak", "()L" + properties + ";"));
        assertNotNull(target, "an unreadable host is one from 1.21, which has only the new name");
        assertEquals("noTerrainParticles", target.name());
        assertEquals(properties, target.owner());
        var grass = transformer.getFieldRedirects().get(new RetromodTransformer.FieldKey("net/minecraft/world/level/block/Blocks", "GRASS"));
        assertNotNull(grass, "Blocks.GRASS became SHORT_GRASS");
        assertEquals("SHORT_GRASS", grass.name());
    }

    @Test
    void hostWithTheOldNamesKeepsThem() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.2";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Legacy1203RenameBridge.register(transformer);
        assertTrue(transformer.getFieldRedirects().isEmpty(), "an unreadable 1.20.2 host still has GRASS");
    }

    @Test
    void targetEventGettersTakeNeoForgeNames() {
        // NeoForge 1.21.1 is the oldest host the Forge to NeoForge shim reaches; it registers the moved members there.
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        String forgeEvent = "net/minecraftforge/event/entity/living/LivingChangeTargetEvent";
        String event = "net/neoforged/neoforge/event/entity/living/LivingChangeTargetEvent";
        transformer.registerClassRedirect(forgeEvent, event);
        NeoForgeMovedMembers.register(transformer);

        String entity = "Lnet/minecraft/world/entity/LivingEntity;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "boh/TargetHandler", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onTarget",
                "(L" + forgeEvent + ";)" + entity, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, forgeEvent, "getOriginalTarget", "()" + entity, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "boh/TargetHandler")).accept(node, 0);
        MethodInsnNode call = null;
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode found) call = found;
        }
        assertNotNull(call);
        assertEquals(event + ".getOriginalAboutToBeSetTarget()" + entity, call.owner + "." + call.name + call.desc,
                "a Forge 1.20.1 handler reads the target under NeoForge's name");
    }
}
