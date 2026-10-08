/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps mods that name tool abilities linking on NeoForge 26.3.
 *
 * <p>26.3 made tools data-driven and removed the axe, hoe, pickaxe, shovel, sword, and shield
 * constants from {@code ItemAbilities}, together with the default sets built from them. A mod that
 * read one failed with {@code NoSuchFieldError}, and so did a Forge mod's {@code ToolActions}
 * constant, which Retromod renames to it. {@code ItemAbility.get} still interns an ability by name,
 * so each read now returns the same ability the constant held. Whether a vanilla tool still reports
 * that ability is decided by the host's tool data.
 */
public final class LegacyItemAbilitiesBridge {

    static final String HELPER = "com/retromod/generated/LegacyItemAbilities";
    private static final String ABILITIES = "net/neoforged/neoforge/common/ItemAbilities";
    private static final String FORGE_ACTIONS = "net/minecraftforge/common/ToolActions";
    private static final String ABILITY = "net/neoforged/neoforge/common/ItemAbility";
    private static final String L_ABILITY = "L" + ABILITY + ";";
    private static final String SET = "java/util/Set";

    /** Constant name, then the ability name it held. {@code HOE_TILL} was always "till". */
    private static final String[][] ABILITY_NAMES = {
        {"AXE_DIG", "axe_dig"}, {"PICKAXE_DIG", "pickaxe_dig"}, {"SHOVEL_DIG", "shovel_dig"},
        {"HOE_DIG", "hoe_dig"}, {"SWORD_DIG", "sword_dig"}, {"AXE_STRIP", "axe_strip"},
        {"AXE_SCRAPE", "axe_scrape"}, {"AXE_WAX_OFF", "axe_wax_off"},
        {"SHOVEL_FLATTEN", "shovel_flatten"}, {"HOE_TILL", "till"}, {"SHIELD_BLOCK", "shield_block"},
    };

    /** Set name, then the abilities it held, as NeoForge 21.1 built them. */
    private static final String[][] DEFAULT_SETS = {
        {"DEFAULT_AXE_ACTIONS", "axe_dig", "axe_strip", "axe_scrape", "axe_wax_off"},
        {"DEFAULT_HOE_ACTIONS", "hoe_dig", "till"},
        {"DEFAULT_SHOVEL_ACTIONS", "shovel_dig", "shovel_flatten", "shovel_douse"},
        {"DEFAULT_PICKAXE_ACTIONS", "pickaxe_dig"},
        {"DEFAULT_SWORD_ACTIONS", "sword_dig", "sword_sweep"},
        {"DEFAULT_SHIELD_ACTIONS", "shield_block"},
    };

    private LegacyItemAbilitiesBridge() {}

    public static void register(RetromodTransformer transformer) {
        register(transformer, hostFields());
    }

    /** {@code hostFields} is null when there is no host to inspect. */
    static void register(RetromodTransformer transformer, Set<String> hostFields) {
        List<String[]> abilities = new ArrayList<>();
        for (String[] ability : ABILITY_NAMES) {
            if (removedFromHost(hostFields, ability[0])) abilities.add(ability);
        }
        List<String[]> sets = new ArrayList<>();
        for (String[] set : DEFAULT_SETS) {
            if (removedFromHost(hostFields, set[0])) sets.add(set);
        }
        if (abilities.isEmpty() && sets.isEmpty()) return;

        transformer.registerSyntheticClass(HELPER, generateHelper(abilities, sets));
        for (String[] ability : abilities) {
            redirect(transformer, ability[0], "()" + L_ABILITY);
        }
        for (String[] set : sets) {
            redirect(transformer, set[0], "()L" + SET + ";");
        }
    }

    private static void redirect(RetromodTransformer transformer, String constant, String helperDesc) {
        // Both spellings: a Forge mod may still name ToolActions when this redirect is consulted.
        for (String owner : new String[]{ABILITIES, FORGE_ACTIONS}) {
            transformer.registerFieldRedirect(owner, constant, null, HELPER, constant, helperDesc);
        }
    }

    private static Set<String> hostFields() {
        ClassNode host = ClassResourceInspector.read(ABILITIES);
        return host == null ? null : host.fields.stream().map(f -> f.name).collect(Collectors.toSet());
    }

    /** The host's own ItemAbilities decides; offline, the target version does. */
    private static boolean removedFromHost(Set<String> hostFields, String constant) {
        if (hostFields != null) return !hostFields.contains(constant);
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
    }

    /** One static method per bridged constant, named like the constant it replaces. */
    static byte[] generateHelper(List<String[]> abilities, List<String[]> sets) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        for (String[] ability : abilities) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, ability[0],
                    "()" + L_ABILITY, null, null);
            mv.visitCode();
            pushAbility(mv, ability[1]);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        for (String[] set : sets) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, set[0],
                    "()L" + SET + ";", null, null);
            mv.visitCode();
            int size = set.length - 1;
            mv.visitLdcInsn(size);
            mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
            for (int i = 0; i < size; i++) {
                mv.visitInsn(DUP);
                mv.visitLdcInsn(i);
                pushAbility(mv, set[i + 1]);
                mv.visitInsn(AASTORE);
            }
            mv.visitMethodInsn(INVOKESTATIC, SET, "of", "([Ljava/lang/Object;)L" + SET + ";", true);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void pushAbility(MethodVisitor mv, String name) {
        mv.visitLdcInsn(name);
        mv.visitMethodInsn(INVOKESTATIC, ABILITY, "get", "(Ljava/lang/String;)" + L_ABILITY, false);
    }
}
