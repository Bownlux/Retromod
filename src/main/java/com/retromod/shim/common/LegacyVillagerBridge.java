/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps villager professions and workstations from mods built before 1.19 constructible.
 *
 * <p>1.19 turned {@code PoiType} into a record without a name, removed
 * {@code PoiType.getBlockStates(Block)}, and made {@code VillagerProfession} match its workstation
 * through holder predicates instead of a {@code PoiType}. A mod that registered its own trader
 * stopped at registration with {@code NoSuchMethodError}, which also left every later registry
 * object of that mod unbound. The name was only used for the registry id, which the registry
 * still supplies. The profession now matches a workstation whose registered value is the mod's
 * point of interest type, for both the job site and the acquirable job site, as vanilla does.
 *
 * <p>1.19 also registers professions before points of interest, so a profession supplier that
 * reads the mod's own {@code RegistryObject<PoiType>} finds it empty. On Forge that read gets a
 * placeholder, and the profession resolves the real type the first time it matches a workstation.
 */
public final class LegacyVillagerBridge {

    static final String SYNTH = "com/retromod/generated/LegacyVillagers";
    static final String MATCH = "com/retromod/generated/LegacyPoiMatch";
    private static final String POI = "net/minecraft/world/entity/ai/village/poi/PoiType";
    private static final String PROFESSION = "net/minecraft/world/entity/npc/VillagerProfession";
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final String IMMUTABLE_SET = "com/google/common/collect/ImmutableSet";
    private static final String SOUND = "Lnet/minecraft/sounds/SoundEvent;";
    private static final String PREDICATE = "java/util/function/Predicate";
    private static final String REGISTRY_OBJECT = "net/minecraftforge/registries/RegistryObject";
    private static final String PENDING_DESC = "Ljava/util/Map;";

    static final String OLD_POI = "(Ljava/lang/String;Ljava/util/Set;II)V";
    static final String OLD_PROFESSION = "(Ljava/lang/String;L" + POI + ";L" + IMMUTABLE_SET + ";L"
            + IMMUTABLE_SET + ";" + SOUND + ")V";

    private LegacyVillagerBridge() {
    }

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(MATCH, generateMatch());
        transformer.registerSyntheticClass(SYNTH, generate());
        transformer.registerConstructorRedirect(POI, OLD_POI, SYNTH, "poiType",
                "(Ljava/lang/String;Ljava/util/Set;II)L" + POI + ";");
        transformer.registerConstructorRedirect(PROFESSION, OLD_PROFESSION, SYNTH, "profession",
                OLD_PROFESSION.replace(")V", ")L" + PROFESSION + ";"));
        transformer.registerMethodRedirect(POI, "getBlockStates", "(L" + BLOCK + ";)Ljava/util/Set;",
                SYNTH, "blockStates", "(L" + BLOCK + ";)Ljava/util/Set;");
    }

    /** True once the bridge is registered, so the call-site pass runs only where it can link. */
    public static boolean isRegistered(RetromodTransformer transformer) {
        return transformer.getSyntheticClasses().containsKey(SYNTH);
    }

    /**
     * Routes {@code (PoiType) registryObject.get()} through {@code poiOrPending}, which returns the
     * same object once it is registered.
     *
     * @return the repaired class, or the same array when nothing matched
     */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null) return null;
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable unreadable) {
            return classBytes;
        }
        if (node.name.startsWith("com/retromod/") || node.name.startsWith("net/minecraft/")) {
            return classBytes;
        }
        int changes = 0;
        for (MethodNode method : node.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKEVIRTUAL
                        && call.owner.equals(REGISTRY_OBJECT) && call.name.equals("get")
                        && call.desc.equals("()Ljava/lang/Object;")
                        && nextReal(call) instanceof TypeInsnNode cast && cast.getOpcode() == CHECKCAST
                        && cast.desc.equals(POI)) {
                    method.instructions.set(call, new MethodInsnNode(INVOKESTATIC, SYNTH, "poiOrPending",
                            "(L" + REGISTRY_OBJECT + ";)L" + POI + ";", false));
                    changes++;
                }
            }
        }
        if (changes == 0) return classBytes;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SYNTH, null, "java/lang/Object", null);
        emitPending(cw);

        MethodVisitor poi = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "poiType",
                "(Ljava/lang/String;Ljava/util/Set;II)L" + POI + ";", null, null);
        poi.visitCode();
        poi.visitTypeInsn(NEW, POI);
        poi.visitInsn(DUP);
        poi.visitVarInsn(ALOAD, 1);
        poi.visitVarInsn(ILOAD, 2);
        poi.visitVarInsn(ILOAD, 3);
        poi.visitMethodInsn(INVOKESPECIAL, POI, "<init>", "(Ljava/util/Set;II)V", false);
        poi.visitInsn(ARETURN);
        poi.visitMaxs(0, 0);
        poi.visitEnd();

        MethodVisitor states = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "blockStates",
                "(L" + BLOCK + ";)Ljava/util/Set;", null, null);
        states.visitCode();
        states.visitVarInsn(ALOAD, 0);
        states.visitMethodInsn(INVOKEVIRTUAL, BLOCK, "getStateDefinition",
                "()Lnet/minecraft/world/level/block/state/StateDefinition;", false);
        states.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/block/state/StateDefinition",
                "getPossibleStates", "()Lcom/google/common/collect/ImmutableList;", false);
        states.visitMethodInsn(INVOKESTATIC, IMMUTABLE_SET, "copyOf",
                "(Ljava/util/Collection;)L" + IMMUTABLE_SET + ";", false);
        states.visitInsn(ARETURN);
        states.visitMaxs(0, 0);
        states.visitEnd();

        MethodVisitor profession = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "profession",
                OLD_PROFESSION.replace(")V", ")L" + PROFESSION + ";"), null, null);
        profession.visitCode();
        profession.visitTypeInsn(NEW, PROFESSION);
        profession.visitInsn(DUP);
        profession.visitVarInsn(ALOAD, 0);
        for (int i = 0; i < 2; i++) {
            profession.visitTypeInsn(NEW, MATCH);
            profession.visitInsn(DUP);
            profession.visitVarInsn(ALOAD, 1);
            profession.visitMethodInsn(INVOKESPECIAL, MATCH, "<init>", "(Ljava/lang/Object;)V", false);
        }
        profession.visitVarInsn(ALOAD, 2);
        profession.visitVarInsn(ALOAD, 3);
        profession.visitVarInsn(ALOAD, 4);
        profession.visitMethodInsn(INVOKESPECIAL, PROFESSION, "<init>", "(Ljava/lang/String;L" + PREDICATE
                + ";L" + PREDICATE + ";L" + IMMUTABLE_SET + ";L" + IMMUTABLE_SET + ";" + SOUND + ")V", false);
        profession.visitInsn(ARETURN);
        profession.visitMaxs(0, 0);
        profession.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The placeholder map, {@code poiOrPending}, and {@code resolve}. */
    private static void emitPending(ClassWriter cw) {
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "PENDING", PENDING_DESC, null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, "java/util/IdentityHashMap");
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, "java/util/IdentityHashMap", "<init>", "()V", false);
        clinit.visitMethodInsn(INVOKESTATIC, "java/util/Collections", "synchronizedMap",
                "(Ljava/util/Map;)Ljava/util/Map;", false);
        clinit.visitFieldInsn(PUTSTATIC, SYNTH, "PENDING", PENDING_DESC);
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        // poiOrPending(ro): ro.get() once registered, otherwise a placeholder remembered for ro.
        MethodVisitor pending = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "poiOrPending",
                "(L" + REGISTRY_OBJECT + ";)L" + POI + ";", null, null);
        pending.visitCode();
        Label absent = new Label();
        pending.visitVarInsn(ALOAD, 0);
        pending.visitMethodInsn(INVOKEVIRTUAL, REGISTRY_OBJECT, "isPresent", "()Z", false);
        pending.visitJumpInsn(IFEQ, absent);
        pending.visitVarInsn(ALOAD, 0);
        pending.visitMethodInsn(INVOKEVIRTUAL, REGISTRY_OBJECT, "get", "()Ljava/lang/Object;", false);
        pending.visitTypeInsn(CHECKCAST, POI);
        pending.visitInsn(ARETURN);
        pending.visitLabel(absent);
        pending.visitTypeInsn(NEW, POI);
        pending.visitInsn(DUP);
        pending.visitMethodInsn(INVOKESTATIC, IMMUTABLE_SET, "of", "()L" + IMMUTABLE_SET + ";", false);
        pending.visitInsn(ICONST_0);
        pending.visitInsn(ICONST_0);
        pending.visitMethodInsn(INVOKESPECIAL, POI, "<init>", "(Ljava/util/Set;II)V", false);
        pending.visitVarInsn(ASTORE, 1);
        pending.visitFieldInsn(GETSTATIC, SYNTH, "PENDING", PENDING_DESC);
        pending.visitVarInsn(ALOAD, 1);
        pending.visitVarInsn(ALOAD, 0);
        pending.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        pending.visitInsn(POP);
        pending.visitVarInsn(ALOAD, 1);
        pending.visitInsn(ARETURN);
        pending.visitMaxs(0, 0);
        pending.visitEnd();

        // resolve(poi): the registered type behind a placeholder, or poi itself.
        MethodVisitor resolve = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "resolve",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        resolve.visitCode();
        Label direct = new Label();
        resolve.visitFieldInsn(GETSTATIC, SYNTH, "PENDING", PENDING_DESC);
        resolve.visitVarInsn(ALOAD, 0);
        resolve.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        resolve.visitInsn(DUP);
        resolve.visitJumpInsn(IFNULL, direct);
        resolve.visitTypeInsn(CHECKCAST, "java/util/function/Supplier");
        resolve.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
        resolve.visitInsn(ARETURN);
        resolve.visitLabel(direct);
        resolve.visitInsn(POP);
        resolve.visitVarInsn(ALOAD, 0);
        resolve.visitInsn(ARETURN);
        resolve.visitMaxs(0, 0);
        resolve.visitEnd();
    }

    /** {@code holder -> holder.value() == LegacyVillagers.resolve(poi)}. */
    static byte[] generateMatch() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, MATCH, null, "java/lang/Object",
                new String[]{PREDICATE});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "poi", "Ljava/lang/Object;", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/Object;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, MATCH, "poi", "Ljava/lang/Object;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor test = cw.visitMethod(ACC_PUBLIC, "test", "(Ljava/lang/Object;)Z", null, null);
        test.visitCode();
        Label other = new Label();
        test.visitVarInsn(ALOAD, 1);
        test.visitTypeInsn(CHECKCAST, HOLDER);
        test.visitMethodInsn(INVOKEINTERFACE, HOLDER, "value", "()Ljava/lang/Object;", true);
        test.visitVarInsn(ALOAD, 0);
        test.visitFieldInsn(GETFIELD, MATCH, "poi", "Ljava/lang/Object;");
        test.visitMethodInsn(INVOKESTATIC, SYNTH, "resolve", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        test.visitJumpInsn(IF_ACMPNE, other);
        test.visitInsn(ICONST_1);
        test.visitInsn(IRETURN);
        test.visitLabel(other);
        test.visitInsn(ICONST_0);
        test.visitInsn(IRETURN);
        test.visitMaxs(0, 0);
        test.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
