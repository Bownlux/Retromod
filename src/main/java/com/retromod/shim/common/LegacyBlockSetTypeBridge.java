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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps 1.19.2 doors, trapdoors, pressure plates, and fence gates constructing.
 *
 * <p>1.19.4 gave each of them a {@code BlockSetType} or {@code WoodType} for its sounds and
 * behavior, and 1.20.5 moved that argument in front of the properties. A 1.19.2 mod extends the
 * class and calls {@code super(properties)}, which every MCreator door and trapdoor does, and that
 * constructor no longer exists on any supported host.
 *
 * <p>Each class gets a generated subclass with a constructor for every shape it has had: the
 * 1.19.2 one, the 1.19.4 to 1.20.4 one, and the 1.20.5 one. Each forwards to the host's own
 * constructor, read from the host's class when available, matching arguments by type and supplying
 * {@code OAK} for a type the mod did not pass. Shims are registered per host, not per mod, so a
 * mod already on the host's shape is rebased too and passes straight through, and a 1.20.1 mod's
 * constructor on a 1.21 host is reordered rather than failing. A mod's subclasses are rebased onto
 * the generated class, and direct construction goes through its factories. The block keeps
 * working with oak sounds and timing whatever wood it used to imitate.
 *
 * <p>A 1.20.1 fence gate could also pass its own open and close sounds instead of a wood type.
 * That shape forwards to the host's own sound constructor where one exists (NeoForge keeps it),
 * and elsewhere to an oak wood type that carries the two sounds.
 */
public final class LegacyBlockSetTypeBridge {

    private static final String BLOCKS = "net/minecraft/world/level/block/";
    private static final String PROPERTIES = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    private static final String SET_TYPE = "net/minecraft/world/level/block/state/properties/BlockSetType";
    private static final String WOOD_TYPE = "net/minecraft/world/level/block/state/properties/WoodType";
    private static final String SENSITIVITY = BLOCKS + "PressurePlateBlock$Sensitivity";
    private static final String SOUND_EVENT = "net/minecraft/sounds/SoundEvent";
    private static final String SOUND_TYPE = "net/minecraft/world/level/block/SoundType";
    // The record accessors and canonical constructor of WoodType, unchanged from 1.20 to 26.3.
    private static final String WOOD_CTOR = "(Ljava/lang/String;L" + SET_TYPE + ";L" + SOUND_TYPE + ";L"
            + SOUND_TYPE + ";L" + SOUND_EVENT + ";L" + SOUND_EVENT + ";)V";
    private static final String SOUND_WOOD = "retromod$soundWood";

    private LegacyBlockSetTypeBridge() {}

    public static void register(RetromodTransformer transformer) {
        String props = "L" + PROPERTIES + ";";
        String setType = "L" + SET_TYPE + ";";
        String woodType = "L" + WOOD_TYPE + ";";
        String sensitivity = "L" + SENSITIVITY + ";";
        bridge(transformer, "DoorBlock", SET_TYPE,
                "(" + props + ")V", "(" + props + setType + ")V", "(" + setType + props + ")V");
        bridge(transformer, "TrapDoorBlock", SET_TYPE,
                "(" + props + ")V", "(" + props + setType + ")V", "(" + setType + props + ")V");
        String sound = "L" + SOUND_EVENT + ";";
        bridge(transformer, "FenceGateBlock", WOOD_TYPE, "(" + props + ")V", "(" + props + woodType + ")V",
                "(" + woodType + props + ")V", "(" + props + sound + sound + ")V");
        bridge(transformer, "PressurePlateBlock", SET_TYPE, "(" + sensitivity + props + ")V",
                "(" + sensitivity + props + setType + ")V", "(" + setType + props + ")V");

        // 1.20.3 folded the sensitivity into BlockSetType; a read of the old constants on a newer
        // host yields null, which the generated constructor ignores.
        if (!hostHasSensitivity()) transformer.registerStaticFieldNuller(SENSITIVITY);
    }

    private static void bridge(RetromodTransformer transformer, String block, String typeClass,
            String... shapes) {
        String base = BLOCKS + block;
        String generated = "com/retromod/generated/Legacy" + block;
        String hostDesc = hostConstructor(base, typeClass);
        Set<String> hostShapes = hostConstructors(base);
        transformer.registerSyntheticClass(generated,
                generate(generated, base, hostDesc, typeClass, hostShapes, shapes));
        transformer.registerSuperclassRebase(base, generated);
        for (String shape : shapes) {
            if (shape.equals(hostDesc) || hostShapes.contains(shape)) continue;
            transformer.registerConstructorRedirect(base, shape, generated, "create",
                    shape.replace(")V", ")L" + base + ";"));
        }
    }

    /**
     * The host constructor that takes the properties and the new type. Read from the host's class
     * when it is on the classpath, otherwise the shape for the host version.
     */
    static String hostConstructor(String base, String typeClass) {
        ClassNode host = ClassResourceInspector.read(base);
        if (host != null) {
            // The shortest match: Forge 26.2 adds a gate constructor that also takes two sounds.
            String best = null;
            for (MethodNode method : host.methods) {
                if (method.name.equals("<init>") && method.desc.contains("L" + PROPERTIES + ";")
                        && method.desc.contains("L" + typeClass + ";")
                        && (best == null || Type.getArgumentTypes(method.desc).length
                                < Type.getArgumentTypes(best).length)) {
                    best = method.desc;
                }
            }
            if (best != null) return best;
        }
        String props = "L" + PROPERTIES + ";";
        String type = "L" + typeClass + ";";
        boolean typeFirst = RetromodVersion.compareMcVersions(
                RetromodVersion.TARGET_MC_VERSION, "1.20.5") >= 0;
        if (typeFirst) return "(" + type + props + ")V";
        if (base.endsWith("PressurePlateBlock")) return "(L" + SENSITIVITY + ";" + props + type + ")V";
        return "(" + props + type + ")V";
    }

    /** Every constructor descriptor the host's class declares, or none when it cannot be read. */
    static Set<String> hostConstructors(String base) {
        ClassNode host = ClassResourceInspector.read(base);
        Set<String> shapes = new HashSet<>();
        if (host != null) {
            for (MethodNode method : host.methods) {
                if (method.name.equals("<init>")) shapes.add(method.desc);
            }
        }
        return shapes;
    }

    private static boolean hostHasSensitivity() {
        ClassNode host = ClassResourceInspector.read(BLOCKS + "PressurePlateBlock");
        if (host != null) {
            return host.methods.stream().anyMatch(m -> m.name.equals("<init>")
                    && m.desc.contains("L" + SENSITIVITY + ";"));
        }
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20.3") < 0;
    }

    /**
     * {@code class Legacy<Block> extends <Block>} with a constructor and a static factory for each
     * shape. Each host parameter is filled from the shape's arguments by type: the properties, the
     * type, and the sensitivity pass through, and a type the shape lacks is {@code OAK}.
     */
    static byte[] generate(String name, String base, String hostDesc, String typeClass,
            String... shapes) {
        return generate(name, base, hostDesc, typeClass, Set.of(), shapes);
    }

    /** As above; a shape the host declares itself ({@code hostShapes}) passes straight through. */
    static byte[] generate(String name, String base, String hostDesc, String typeClass,
            Set<String> hostShapes, String... shapes) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, base, null);
        boolean soundWood = false;
        for (String shape : new LinkedHashSet<>(Arrays.asList(shapes))) {
            String target = hostShapes.contains(shape) ? shape : hostDesc;
            soundWood |= emitShape(cw, name, base, shape, target, typeClass);
        }
        if (soundWood) emitSoundWood(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Emits one shape's constructor and factory; true when it needs {@link #emitSoundWood}. */
    private static boolean emitShape(ClassWriter cw, String name, String base, String oldDesc,
            String hostDesc, String typeClass) {
        Type[] oldArgs = Type.getArgumentTypes(oldDesc);
        int[] sounds = soundSlots(oldArgs);
        boolean soundWood = false;
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", oldDesc, null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        Type[] hostArgs = Type.getArgumentTypes(hostDesc);
        for (int i = 0; i < hostArgs.length; i++) {
            Type param = hostArgs[i];
            // The n-th argument of a type takes the n-th old argument of that type, so the open
            // sound stays ahead of the close sound.
            int slot = slotOf(oldArgs, param, occurrence(hostArgs, i));
            if (slot > 0) {
                ctor.visitVarInsn(ALOAD, slot);
            } else if (param.getInternalName().equals(typeClass) && typeClass.equals(WOOD_TYPE)
                    && sounds != null) {
                // A gate built from its own sounds gets an oak wood type that plays them.
                ctor.visitVarInsn(ALOAD, sounds[0]);
                ctor.visitVarInsn(ALOAD, sounds[1]);
                ctor.visitMethodInsn(INVOKESTATIC, name, SOUND_WOOD,
                        "(L" + SOUND_EVENT + ";L" + SOUND_EVENT + ";)L" + WOOD_TYPE + ";", false);
                soundWood = true;
            } else if (param.getInternalName().equals(typeClass)) {
                ctor.visitFieldInsn(GETSTATIC, typeClass, "OAK", param.getDescriptor());
            } else {
                ctor.visitInsn(ACONST_NULL);
            }
        }
        ctor.visitMethodInsn(INVOKESPECIAL, base, "<init>", hostDesc, false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor create = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create",
                oldDesc.replace(")V", ")L" + base + ";"), null, null);
        create.visitCode();
        create.visitTypeInsn(NEW, name);
        create.visitInsn(DUP);
        for (int i = 0; i < oldArgs.length; i++) create.visitVarInsn(ALOAD, i);
        create.visitMethodInsn(INVOKESPECIAL, name, "<init>", oldDesc, false);
        create.visitInsn(ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();
        return soundWood;
    }

    /**
     * {@code private static WoodType retromod$soundWood(SoundEvent open, SoundEvent close)}: oak in
     * everything but its fence gate sounds. The type is not registered, so it shares oak's name.
     */
    private static void emitSoundWood(ClassWriter cw) {
        String wood = "L" + WOOD_TYPE + ";";
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, SOUND_WOOD,
                "(L" + SOUND_EVENT + ";L" + SOUND_EVENT + ";)" + wood, null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, WOOD_TYPE);
        mv.visitInsn(DUP);
        String[][] copied = {{"name", "Ljava/lang/String;"}, {"setType", "L" + SET_TYPE + ";"},
                {"soundType", "L" + SOUND_TYPE + ";"}, {"hangingSignSoundType", "L" + SOUND_TYPE + ";"}};
        for (String[] accessor : copied) {
            mv.visitFieldInsn(GETSTATIC, WOOD_TYPE, "OAK", wood);
            mv.visitMethodInsn(INVOKEVIRTUAL, WOOD_TYPE, accessor[0], "()" + accessor[1], false);
        }
        // The record lists the close sound before the open one.
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, WOOD_TYPE, "<init>", WOOD_CTOR, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** The local slots of an old shape's two sounds, or null when it does not pass both. */
    private static int[] soundSlots(Type[] oldArgs) {
        Type sound = Type.getObjectType(SOUND_EVENT);
        int first = slotOf(oldArgs, sound, 0);
        int second = slotOf(oldArgs, sound, 1);
        return first > 0 && second > 0 ? new int[] {first, second} : null;
    }

    /** How many arguments before {@code index} have the same type as it. */
    private static int occurrence(Type[] args, int index) {
        int seen = 0;
        for (int i = 0; i < index; i++) {
            if (args[i].equals(args[index])) seen++;
        }
        return seen;
    }

    /** The local slot of the {@code nth} old argument with this type, or 0 when there is none. */
    private static int slotOf(Type[] oldArgs, Type wanted, int nth) {
        for (int i = 0; i < oldArgs.length; i++) {
            if (oldArgs[i].equals(wanted) && nth-- == 0) return i + 1;
        }
        return 0;
    }
}
