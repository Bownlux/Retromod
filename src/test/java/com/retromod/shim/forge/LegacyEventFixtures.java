/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/** Old mod classes as javac wrote them for Forge 1.20.1: events on the old base and listeners with the old annotation. */
final class LegacyEventFixtures {

    static final String EVENT = LegacyEventBaseAdapter.EVENT;
    static final String GENERIC_EVENT = LegacyEventBaseAdapter.GENERIC_EVENT;
    static final String CANCELABLE = "Lnet/minecraftforge/eventbus/api/Cancelable;";
    static final String OLD_SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    static final String OLD_PRIORITY = "Lnet/minecraftforge/eventbus/api/EventPriority;";
    static final String MOD_BUS_EVENT = "net/minecraftforge/fml/event/IModBusEvent";
    /** A Forge event for tests, defined in the test host. */
    static final String HOST_EVENT = "net/minecraftforge/event/HostEvent";

    /** What a handler does after it notes its tag. */
    enum Action { NOTE, CANCEL, UNCANCEL }

    /**
     * One handler of a listener fixture: its tag, its event (with a generic signature or null),
     * its old priority constant or null for none, receiveCanceled, whether it is static, and its action.
     */
    record Handler(String tag, String event, String signature, String priority, boolean receiveCanceled,
                   boolean isStatic, Action action) {

        static Handler of(String tag, String event, String priority) {
            return new Handler(tag, event, null, priority, false, false, Action.NOTE);
        }

        Handler receivingCanceled() {
            return new Handler(tag, event, signature, priority, true, isStatic, action);
        }

        Handler then(Action next) {
            return new Handler(tag, event, signature, priority, receiveCanceled, isStatic, next);
        }

        Handler typed(String genericSignature) {
            return new Handler(tag, event, genericSignature, priority, receiveCanceled, isStatic, action);
        }

        Handler asStatic() {
            return new Handler(tag, event, signature, priority, receiveCanceled, true, action);
        }
    }

    private LegacyEventFixtures() {}

    /** {@code @Cancelable class name extends Event}. */
    static byte[] cancelableEvent(String name) {
        return LegacyEventBaseAdapterTest.annotated(name, CANCELABLE, false);
    }

    /** {@code class name extends superName} with a no-argument constructor. */
    static byte[] subclass(String name, String superName) {
        return LegacyEventBaseAdapterTest.eventClass(name, superName, null);
    }

    /** {@code class name<T> extends GenericEvent<T>} with its Class constructor. */
    static byte[] genericEvent(String name) {
        return LegacyEventBaseAdapterTest.eventClass(name, GENERIC_EVENT, "<T:Ljava/lang/Object;>L" + GENERIC_EVENT + "<TT;>;");
    }

    /** {@code class name extends genericEvent} whose getGenericType() always answers {@code reported}. */
    static byte[] overridingGenericEvent(String name, String genericEvent, Class<?> reported) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, genericEvent, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/Class;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitMethodInsn(INVOKESPECIAL, genericEvent, "<init>", "(Ljava/lang/Class;)V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor type = cw.visitMethod(ACC_PUBLIC, "getGenericType", "()Ljava/lang/reflect/Type;", null, null);
        type.visitCode();
        type.visitLdcInsn(Type.getType(reported));
        type.visitInsn(ARETURN);
        type.visitMaxs(0, 0);
        type.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code class name extends Event implements IModBusEvent}, an event a mod posts on its own bus. */
    static byte[] modBusEvent(String name) {
        ClassNode node = new ClassNode();
        new ClassReader(subclass(name, EVENT)).accept(node, 0);
        node.interfaces = List.of(MOD_BUS_EVENT);
        ClassWriter cw = new ClassWriter(0);
        node.accept(cw);
        return cw.toByteArray();
    }

    /** A Forge 26.2 shaped event: {@code extends MutableEvent implements InheritableEvent}, with its own static BUS. */
    static byte[] hostEvent(String name) {
        String mutable = "net/minecraftforge/eventbus/api/event/MutableEvent";
        String bus = EventBus7Host.EVENT_BUS;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, mutable,
                new String[]{"net/minecraftforge/eventbus/api/event/InheritableEvent"});
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "BUS", "L" + bus + ";", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitFieldInsn(GETSTATIC, EventBus7Host.BUS_GROUP, "DEFAULT", "L" + EventBus7Host.BUS_GROUP + ";");
        clinit.visitLdcInsn(org.objectweb.asm.Type.getObjectType(name));
        clinit.visitMethodInsn(INVOKESTATIC, bus, "create",
                "(L" + EventBus7Host.BUS_GROUP + ";Ljava/lang/Class;)L" + bus + ";", true);
        clinit.visitFieldInsn(PUTSTATIC, name, "BUS", "L" + bus + ";");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, mutable, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A public listener class whose handlers note their tags in {@code public static List<String> NOTES}. */
    static byte[] listener(String name, Handler... handlers) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "NOTES", "Ljava/util/List;", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, "java/util/ArrayList");
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        clinit.visitFieldInsn(PUTSTATIC, name, "NOTES", "Ljava/util/List;");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        for (Handler handler : handlers) {
            String desc = "(L" + handler.event() + ";)V";
            String signature = handler.signature() == null ? null : "(" + handler.signature() + ")V";
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | (handler.isStatic() ? ACC_STATIC : 0),
                    "on" + Character.toUpperCase(handler.tag().charAt(0)) + handler.tag().substring(1).replace("-", ""),
                    desc, signature, null);
            AnnotationVisitor subscribe = mv.visitAnnotation(OLD_SUBSCRIBE, true);
            if (handler.priority() != null) subscribe.visitEnum("priority", OLD_PRIORITY, handler.priority());
            if (handler.receiveCanceled()) subscribe.visit("receiveCanceled", true);
            subscribe.visitEnd();
            mv.visitCode();
            mv.visitFieldInsn(GETSTATIC, name, "NOTES", "Ljava/util/List;");
            mv.visitLdcInsn(handler.tag());
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
            mv.visitInsn(POP);
            if (handler.action() != Action.NOTE) {
                mv.visitVarInsn(ALOAD, handler.isStatic() ? 0 : 1);
                mv.visitInsn(handler.action() == Action.CANCEL ? ICONST_1 : ICONST_0);
                mv.visitMethodInsn(INVOKEVIRTUAL, handler.event(), "setCanceled", "(Z)V", false);
            }
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
