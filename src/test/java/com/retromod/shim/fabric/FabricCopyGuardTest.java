/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyBlockPropertiesBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A Fabric block copied from the barrier keeps a map color that reads a property it lacks. */
class FabricCopyGuardTest {

    private static final String PROPERTIES = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void blockOfFullCopyRunsTheCopyThroughTheGuardBeforeStamping() throws Exception {
        ClassNode bridge = new ClassNode();
        new ClassReader(FabricRegistryIdSynthetic.generate()).accept(bridge, 0);
        MethodNode copy = bridge.methods.stream()
                .filter(m -> m.name.equals("blockOfFullCopy")).findFirst().orElseThrow();

        List<String> steps = new ArrayList<>();
        for (AbstractInsnNode insn : copy.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) steps.add(call.name);
            if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.CHECKCAST) {
                steps.add("checkcast " + type.desc);
            }
        }
        assertEquals(List.of("ofFullCopy", "guardMapColor", "checkcast " + PROPERTIES, "stampBlock"), steps);
        new Analyzer<>(new BasicVerifier()).analyze(bridge.name, copy);
    }

    @Test
    void aCopiedColorThatReadsAMissingPropertyAnswersNone() throws Exception {
        FabricRegistryIdSharedChannelTest.Fixture fixture = new FabricRegistryIdSharedChannelTest.Fixture();
        Object barrier = fixture.loadClass("net.minecraft.world.level.block.state.BlockBehaviour")
                .getMethod("waterloggedColored").invoke(null);

        Object copied = fixture.call(FabricRegistryIdSharedChannelTest.COPY_A, "blockOfFullCopy", barrier);

        Class<?> mapColor = fixture.loadClass("net.minecraft.world.level.material.MapColor");
        Method mapColorOf = copied.getClass().getMethod("mapColorOf", Object.class);
        // The stub reads the property from a null state, which stands for the mod's own block.
        assertSame(mapColor.getField("NONE").get(null), mapColorOf.invoke(copied, (Object) null),
                "a color that reads a property the block lacks must answer NONE, not throw");
        assertSame(mapColor.getField("STONE").get(null), mapColorOf.invoke(copied, new Object()),
                "a state that has the property keeps the copied color");
    }

    @Test
    void fabric26xEmbedsTheGuardWithTheBridge() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_11_to_26_1().registerRedirects(transformer);

        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyBlockPropertiesBridge.SUPPORT),
                "the bridge calls the guard, so the embedder must ship the guard with it");
    }
}
