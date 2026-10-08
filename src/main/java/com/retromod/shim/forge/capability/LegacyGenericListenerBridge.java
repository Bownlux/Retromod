/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.capability;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Bridges Forge's {@code IEventBus.addGenericListener}, which NeoForge removed with generic events.
 *
 * <p>In Forge 1.20.1 the only generic event is {@code AttachCapabilitiesEvent}, and a mod listens
 * for one holder type with {@code addGenericListener(Entity.class, this::attach)}. The capability
 * bridge posts its attach event for every holder, so the listener is added for that event with a
 * filter that passes only holders of the requested type. Forge matched the generic type exactly,
 * so a subclass listener sees a few more holders here, which a listener already has to tolerate
 * because Forge fired the {@code Entity} event for every entity.
 */
public final class LegacyGenericListenerBridge {

    static final String FORGE_BUS = "net/minecraftforge/eventbus/api/IEventBus";
    static final String NEO_BUS = "net/neoforged/bus/api/IEventBus";
    static final String HELPER = "com/retromod/generated/LegacyGenericListener";
    private static final String ATTACH = LegacyCapabilitySynthetics.ATTACH_EVENT;
    private static final String CONSUMER = "java/util/function/Consumer";
    private static final String PRIORITY = "net/neoforged/bus/api/EventPriority";

    private LegacyGenericListenerBridge() {}

    /** Needs the attach event stand-in, so it is called after the capability bridge. */
    public static void register(RetromodTransformer transformer) {
        if (!transformer.getSyntheticClasses().containsKey(ATTACH)) return;
        transformer.registerSyntheticClass(HELPER, generate());
        transformer.registerMethodRedirect(FORGE_BUS, "addGenericListener",
                "(Ljava/lang/Class;L" + CONSUMER + ";)V",
                HELPER, "register", "(Ljava/lang/Object;Ljava/lang/Class;L" + CONSUMER + ";)V", true);
        transformer.registerMethodRedirect(FORGE_BUS, "addGenericListener",
                "(Ljava/lang/Class;Lnet/minecraftforge/eventbus/api/EventPriority;L" + CONSUMER + ";)V",
                HELPER, "register", "(Ljava/lang/Object;Ljava/lang/Class;L" + PRIORITY + ";L" + CONSUMER + ";)V", true);
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, HELPER, null,
                "java/lang/Object", new String[] {CONSUMER});
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "holderType", "Ljava/lang/Class;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "listener", "L" + CONSUMER + ";", null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/Class;L" + CONSUMER + ";)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, HELPER, "holderType", "Ljava/lang/Class;");
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 2);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, HELPER, "listener", "L" + CONSUMER + ";");
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor accept = cw.visitMethod(Opcodes.ACC_PUBLIC, "accept", "(Ljava/lang/Object;)V", null, null);
        accept.visitCode();
        Label skip = new Label();
        accept.visitVarInsn(Opcodes.ALOAD, 0);
        accept.visitFieldInsn(Opcodes.GETFIELD, HELPER, "holderType", "Ljava/lang/Class;");
        accept.visitVarInsn(Opcodes.ALOAD, 1);
        accept.visitTypeInsn(Opcodes.CHECKCAST, ATTACH);
        accept.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ATTACH, "getObject", "()Ljava/lang/Object;", false);
        accept.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "isInstance", "(Ljava/lang/Object;)Z", false);
        accept.visitJumpInsn(Opcodes.IFEQ, skip);
        accept.visitVarInsn(Opcodes.ALOAD, 0);
        accept.visitFieldInsn(Opcodes.GETFIELD, HELPER, "listener", "L" + CONSUMER + ";");
        accept.visitVarInsn(Opcodes.ALOAD, 1);
        accept.visitMethodInsn(Opcodes.INVOKEINTERFACE, CONSUMER, "accept", "(Ljava/lang/Object;)V", true);
        accept.visitLabel(skip);
        accept.visitInsn(Opcodes.RETURN);
        accept.visitMaxs(0, 0);
        accept.visitEnd();

        emitRegister(cw, false);
        emitRegister(cw, true);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code bus.addListener([priority,] AttachCapabilitiesEvent.class, new LegacyGenericListener(type, listener))}. */
    private static void emitRegister(ClassWriter cw, boolean withPriority) {
        String desc = withPriority
                ? "(Ljava/lang/Object;Ljava/lang/Class;L" + PRIORITY + ";L" + CONSUMER + ";)V"
                : "(Ljava/lang/Object;Ljava/lang/Class;L" + CONSUMER + ";)V";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register", desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, NEO_BUS);
        if (withPriority) mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitLdcInsn(Type.getObjectType(ATTACH));
        mv.visitTypeInsn(Opcodes.NEW, HELPER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, withPriority ? 3 : 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, HELPER, "<init>", "(Ljava/lang/Class;L" + CONSUMER + ";)V", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, NEO_BUS, "addListener",
                withPriority ? "(L" + PRIORITY + ";Ljava/lang/Class;L" + CONSUMER + ";)V"
                        : "(Ljava/lang/Class;L" + CONSUMER + ";)V", true);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
