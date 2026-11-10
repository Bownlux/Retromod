/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** A Mixin shaped like Carry On's InventoryMixin: a static ItemStack built in its class initializer. */
public final class StaticItemStackMixinFixture {

    public static final String MIXIN = "fixture/carry/InventoryMixin";
    public static final String TARGET = "net/minecraft/world/entity/player/Inventory";
    public static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    public static final String ITEM_LIKE = "net/minecraft/world/level/ItemLike";
    public static final String BLOCK = "net/minecraft/world/level/block/Block";
    public static final String BLOCKS = "net/minecraft/world/level/block/Blocks";
    public static final String STACK_FIELD = "DUMMY_STACK";
    public static final String SEEN_FIELD = "SEEN_DURING_INIT";
    public static final String MIXIN_CONFIG = "carry.mixins.json";

    private StaticItemStackMixinFixture() {}

    /** The Mixin, whose static fields are read by getters, an {@code @Inject} handler, and its constructor. */
    public static byte[] mixinClass(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(TARGET));
        targets.visitEnd();
        mixin.visitEnd();

        String stackDesc = "L" + ITEM_STACK + ";";
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                STACK_FIELD, stackDesc, null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                SEEN_FIELD, "Ljava/lang/Object;", null, null).visitEnd();
        FieldVisitor shadow = cw.visitField(Opcodes.ACC_PUBLIC, "selected", "I", null, null);
        shadow.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", true).visitEnd();
        shadow.visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "initialStack", "Ljava/lang/Object;", null, null).visitEnd();

        // private Object initialStack = DUMMY_STACK;
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitFieldInsn(Opcodes.GETSTATIC, name, STACK_FIELD, stackDesc);
        init.visitFieldInsn(Opcodes.PUTFIELD, name, "initialStack", "Ljava/lang/Object;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        staticGetter(cw, name, "dummy", STACK_FIELD, stackDesc);
        staticGetter(cw, name, "peek", STACK_FIELD, stackDesc);
        staticGetter(cw, name, "seen", SEEN_FIELD, "Ljava/lang/Object;");

        MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE, "onGetFreeSlot",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)Ljava/lang/Object;", null, null);
        AnnotationVisitor inject = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, "getFreeSlot()I");
        methods.visitEnd();
        inject.visitEnd();
        handler.visitCode();
        handler.visitFieldInsn(Opcodes.GETSTATIC, name, STACK_FIELD, stackDesc);
        handler.visitInsn(Opcodes.ARETURN);
        handler.visitMaxs(0, 0);
        handler.visitEnd();

        // static { DUMMY_STACK = new ItemStack(Blocks.COBBLESTONE, 1); SEEN_DURING_INIT = peek(); }
        // peek() reads the stack back, so a deferred initializer re-enters its own guard.
        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, ITEM_STACK);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitFieldInsn(Opcodes.GETSTATIC, BLOCKS, "COBBLESTONE", "L" + BLOCK + ";");
        clinit.visitInsn(Opcodes.ICONST_1);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, ITEM_STACK, "<init>", "(L" + ITEM_LIKE + ";I)V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, name, STACK_FIELD, stackDesc);
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, name, "peek", "()Ljava/lang/Object;", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, name, SEEN_FIELD, "Ljava/lang/Object;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void staticGetter(ClassWriter cw, String owner, String name, String field, String desc) {
        MethodVisitor getter = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name,
                "()Ljava/lang/Object;", null, null);
        getter.visitCode();
        getter.visitFieldInsn(Opcodes.GETSTATIC, owner, field, desc);
        getter.visitInsn(Opcodes.ARETURN);
        getter.visitMaxs(0, 0);
        getter.visitEnd();
    }

    /** Stand-ins for the Minecraft classes the Mixin touches; ItemStack's constructor calls {@code hookOwner.onStackBuilt()}. */
    public static Map<String, byte[]> minecraftStubs(String hookOwner) {
        Map<String, byte[]> stubs = new LinkedHashMap<>();
        ClassWriter itemLike = new ClassWriter(0);
        itemLike.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                ITEM_LIKE, null, "java/lang/Object", null);
        itemLike.visitEnd();
        stubs.put(ITEM_LIKE, itemLike.toByteArray());

        ClassWriter block = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        block.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, BLOCK, null, "java/lang/Object",
                new String[] {ITEM_LIKE});
        emptyConstructor(block, "java/lang/Object", "()V", null);
        block.visitEnd();
        stubs.put(BLOCK, block.toByteArray());

        ClassWriter blocks = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        blocks.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, BLOCKS, null, "java/lang/Object", null);
        blocks.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "COBBLESTONE", "L" + BLOCK + ";", null, null).visitEnd();
        MethodVisitor blocksInit = blocks.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        blocksInit.visitCode();
        blocksInit.visitTypeInsn(Opcodes.NEW, BLOCK);
        blocksInit.visitInsn(Opcodes.DUP);
        blocksInit.visitMethodInsn(Opcodes.INVOKESPECIAL, BLOCK, "<init>", "()V", false);
        blocksInit.visitFieldInsn(Opcodes.PUTSTATIC, BLOCKS, "COBBLESTONE", "L" + BLOCK + ";");
        blocksInit.visitInsn(Opcodes.RETURN);
        blocksInit.visitMaxs(0, 0);
        blocksInit.visitEnd();
        blocks.visitEnd();
        stubs.put(BLOCKS, blocks.toByteArray());

        ClassWriter stack = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        stack.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, ITEM_STACK, null, "java/lang/Object", null);
        emptyConstructor(stack, "java/lang/Object", "(L" + ITEM_LIKE + ";I)V", hookOwner);
        stack.visitEnd();
        stubs.put(ITEM_STACK, stack.toByteArray());
        return stubs;
    }

    private static void emptyConstructor(ClassWriter cw, String superName, String desc, String hookOwner) {
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        if (hookOwner != null) {
            init.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, "onStackBuilt", "()V", false);
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }

    /** A mixin config listing the fixture Mixin, so Fabric's discovery treats it as one. */
    public static String mixinConfig(String mixinName) {
        String dotted = mixinName.replace('/', '.');
        int split = dotted.lastIndexOf('.');
        return "{\"required\":true,\"package\":\"" + dotted.substring(0, split) + "\","
                + "\"mixins\":[\"" + dotted.substring(split + 1) + "\"]}";
    }

    public static String forgeToml(String minecraftRange) {
        return "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"carry_fixture\"\nversion=\"1.0.0\"\n"
                + "[[dependencies.carry_fixture]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"" + minecraftRange + "\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
    }

    public static String fabricJson(String minecraftVersion) {
        return "{\"schemaVersion\":1,\"id\":\"carry_fixture\",\"version\":\"1.0.0\","
                + "\"mixins\":[\"" + MIXIN_CONFIG + "\"],"
                + "\"depends\":{\"fabricloader\":\"*\",\"minecraft\":\"" + minecraftVersion + "\"}}";
    }

    /** Writes a jar holding the given entries, text or bytes, in order. */
    public static Path writeJar(Path path, Map<String, ?> entries) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, ?> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                Object value = entry.getValue();
                out.write(value instanceof String text ? text.getBytes(StandardCharsets.UTF_8) : (byte[]) value);
                out.closeEntry();
            }
        }
        return path;
    }
}
