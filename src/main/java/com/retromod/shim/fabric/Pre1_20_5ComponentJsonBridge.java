/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.util.HostLibraryNames;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Bridges the JSON text component helpers that 1.20.5 made registry-aware, for
 * intermediary-namespace Fabric mods on pre-26.1 hosts.
 *
 * <p>{@code Component.Serializer.fromJson}, {@code fromJsonLenient} and {@code toJson} gained a
 * {@code HolderLookup.Provider} parameter. A 1.20.1 mod that reads text from its data files, such
 * as Palladium's power definitions, died with {@code NoSuchMethodError} inside a reload listener,
 * and that failure stopped the server from loading its data packs at all.
 *
 * <p>The old calls pass {@code RegistryAccess.EMPTY}. Plain, translated and styled text parses the
 * same as before. Text that names a registry entry, such as an item shown on hover, cannot be
 * resolved without the live registries and fails as the host would for unknown content.
 * {@code toJsonTree} has no public replacement and is not bridged.
 */
public final class Pre1_20_5ComponentJsonBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String SERIALIZER = "net/minecraft/class_2561$class_2562";
    static final String HELPER = "com/retromod/generated/LegacyComponentJson";
    private static final String PROVIDER = "Lnet/minecraft/class_7225$class_7874;";
    private static final String REGISTRY_ACCESS = "net/minecraft/class_5455";
    private static final String EMPTY_ACCESS = "field_40585";
    private static final String EMPTY_ACCESS_DESC = "Lnet/minecraft/class_5455$class_6890;";

    /** One old static helper whose modern form takes a trailing lookup provider. */
    record Call(String name, String oldDesc) {
        String modernDesc() {
            int close = oldDesc.indexOf(')');
            return oldDesc.substring(0, close) + PROVIDER + oldDesc.substring(close);
        }
    }

    static final List<Call> CALLS = List.of(
            // Built through HostLibraryNames because the release jar shades Gson literals.
            new Call("method_10872", "(L" + HostLibraryNames.JSON_ELEMENT + ";)Lnet/minecraft/class_5250;"),
            new Call("method_10877", "(Ljava/lang/String;)Lnet/minecraft/class_5250;"),
            new Call("method_10873", "(Ljava/lang/String;)Lnet/minecraft/class_5250;"),
            new Call("method_10867", "(Lnet/minecraft/class_2561;)Ljava/lang/String;"));

    private Pre1_20_5ComponentJsonBridge() {}

    /** Registers each call whose old form is gone and whose provider form is public on the host. */
    public static void register(RetromodTransformer transformer) {
        ClassNode serializer = ClassResourceInspector.read(SERIALIZER);
        if (serializer == null) return;
        List<Call> replaced = CALLS.stream()
                .filter(call -> findPublic(serializer, call.name(), call.modernDesc())
                        && !hasMethod(serializer, call.name(), call.oldDesc()))
                .toList();
        if (replaced.isEmpty()) {
            LOGGER.debug("The host still has the JSON text helpers without a lookup provider");
            return;
        }
        registerRedirects(transformer, replaced);
        LOGGER.debug("Added support for {} JSON text helpers without a lookup provider", replaced.size());
    }

    /** Unconditional registration for offline transforms and transform-shape tests. */
    public static void registerRedirects(RetromodTransformer transformer, List<Call> calls) {
        transformer.registerSyntheticClass(HELPER, generateHelper(calls));
        for (Call call : calls) {
            transformer.registerMethodRedirect(SERIALIZER, call.name(), call.oldDesc(),
                    HELPER, call.name(), call.oldDesc());
        }
    }

    private static boolean hasMethod(ClassNode owner, String name, String descriptor) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return true;
        }
        return false;
    }

    private static boolean findPublic(ClassNode owner, String name, String descriptor) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return (method.access & Opcodes.ACC_PUBLIC) != 0;
            }
        }
        return false;
    }

    static byte[] generateHelper(List<Call> calls) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);
        for (Call call : calls) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    call.name(), call.oldDesc(), null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETSTATIC, REGISTRY_ACCESS, EMPTY_ACCESS, EMPTY_ACCESS_DESC);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SERIALIZER, call.name(), call.modernDesc(), false);
            mv.visitInsn(Type.getReturnType(call.oldDesc()).getOpcode(Opcodes.IRETURN));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
