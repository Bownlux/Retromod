/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/** Rebuilds the music disc constructors that 1.19 replaced with ones that also take the song length. */
public final class LegacyRecordItemBridge {

    static final String HELPER = "com/retromod/generated/LegacyRecordItems";
    /** 1.18 discs had no length. Three minutes is close to the vanilla discs, so the jukebox stops near the song's end. */
    static final int LENGTH_SECONDS = 180;
    static final int LENGTH_TICKS = LENGTH_SECONDS * 20;

    /** The class names one host runs with; {@code forgeSupplier} is false where the Forge supplier constructor never existed. */
    public record Names(String recordItem, String soundEvent, String properties, boolean forgeSupplier) {}

    public static final Names MOJANG = new Names("net/minecraft/world/item/RecordItem", "net/minecraft/sounds/SoundEvent",
            "net/minecraft/world/item/Item$Properties", true);
    /** Fabric hosts before 26.1 run intermediary names. */
    public static final Names INTERMEDIARY = new Names("net/minecraft/class_1813", "net/minecraft/class_3414",
            "net/minecraft/class_1792$class_1793", false);

    private LegacyRecordItemBridge() {}

    /** Registers the bridge for a host from 1.19 up to 1.20.6; 1.21 removed RecordItem, which its own bridge rebuilds. */
    public static void register(RetromodTransformer t, Names names) {
        t.registerSyntheticClass(HELPER, generateHelper(names));
        String sound = "L" + names.soundEvent() + ";";
        // The vanilla constructor counts the length in seconds.
        redirect(t, names, sound, "LENGTH_SECONDS");
        // Forge's own supplier constructor, which mods used for sounds registered later, counts it in ticks.
        if (names.forgeSupplier()) redirect(t, names, "Ljava/util/function/Supplier;", "LENGTH_TICKS");
    }

    private static void redirect(RetromodTransformer t, Names names, String sound, String length) {
        String oldCtor = "(I" + sound + "L" + names.properties() + ";)V";
        String newCtor = "(I" + sound + "L" + names.properties() + ";I)V";
        t.registerConstructorRedirect(names.recordItem(), oldCtor, HELPER, "create",
                "(I" + sound + "L" + names.properties() + ";)L" + names.recordItem() + ";");
        // A mod's own disc class passes the same length to its super call.
        t.registerSuperConstructorRedirect(names.recordItem(), oldCtor, newCtor, HELPER, length, "I");
    }

    /** Static {@code create} factories and the two length constants. */
    static byte[] generateHelper(Names names) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "LENGTH_SECONDS", "I", null, LENGTH_SECONDS).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "LENGTH_TICKS", "I", null, LENGTH_TICKS).visitEnd();
        emitCreate(cw, names, "L" + names.soundEvent() + ";", LENGTH_SECONDS);
        if (names.forgeSupplier()) emitCreate(cw, names, "Ljava/util/function/Supplier;", LENGTH_TICKS);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code create(analog, sound, properties)}: the four-argument constructor with the default length. */
    private static void emitCreate(ClassWriter cw, Names names, String sound, int length) {
        String properties = "L" + names.properties() + ";";
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create",
                "(I" + sound + properties + ")L" + names.recordItem() + ";", null, null);
        m.visitCode();
        m.visitTypeInsn(NEW, names.recordItem());
        m.visitInsn(DUP);
        m.visitVarInsn(ILOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 2);
        m.visitLdcInsn(length);
        m.visitMethodInsn(INVOKESPECIAL, names.recordItem(), "<init>", "(I" + sound + properties + "I)V", false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
