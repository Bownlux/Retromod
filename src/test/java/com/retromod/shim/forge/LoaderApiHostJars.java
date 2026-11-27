/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.ZipFile;

import static org.objectweb.asm.Opcodes.*;

/** Real loader jars from the local Prism library, and small mod classes, for the loader API bridge tests. */
final class LoaderApiHostJars {

    static final Path PRISM = Path.of(System.getProperty("user.home", ""), "Library/Application Support/PrismLauncher/libraries");
    static final Path FORGE_1201 = PRISM.resolve("net/minecraftforge/forge/1.20.1-47.4.10/forge-1.20.1-47.4.10-universal.jar");
    static final Path FORGE_262 = PRISM.resolve("net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-universal.jar");
    static final Path FORGE_262_CLIENT = PRISM.resolve("net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar");
    static final Path FORGE_262_FMLCORE = PRISM.resolve("net/minecraftforge/fmlcore/26.2-65.0.0/fmlcore-26.2-65.0.0.jar");
    static final Path NEOFORGE_263 = PRISM.resolve("net/neoforged/neoforge/26.3.0.43-beta/neoforge-26.3.0.43-beta-universal.jar");
    static final Path NEOFORGE_263_CLIENT = PRISM.resolve(
            "net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar");
    static final Path FML_12 = PRISM.resolve("net/neoforged/fancymodloader/loader/12.0.8/loader-12.0.8.jar");

    private LoaderApiHostJars() {}

    static boolean present(Path... jars) {
        for (Path jar : jars) {
            if (!Files.isRegularFile(jar)) return false;
        }
        return true;
    }

    /** A host that reads classes from jars, the first jar holding a class winning. */
    static Function<String, ClassNode> host(Path... jars) {
        Map<String, ClassNode> cache = new HashMap<>();
        return name -> cache.computeIfAbsent(name, key -> {
            for (Path jar : jars) {
                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    var entry = zip.getEntry(key + ".class");
                    if (entry == null) continue;
                    ClassNode node = new ClassNode();
                    try (var in = zip.getInputStream(entry)) {
                        new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
                    }
                    return node;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return null;
        });
    }

    /** A mod class {@code name} with one static method {@code run} of {@code desc} whose body {@code body} writes. */
    static byte[] mod(String name, String desc, Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Every call and field access in the class's methods, as {@code owner.name desc}, in order. */
    static List<String> refs(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        List<String> refs = new ArrayList<>();
        node.methods.forEach(method -> {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
                if (insn instanceof FieldInsnNode field) refs.add(field.owner + "." + field.name + ":" + field.desc);
            }
        });
        return refs;
    }

    /** The descriptor of the mod's {@code run} method after a transform. */
    static String runDesc(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node.methods.stream().filter(m -> m.name.equals("run")).findFirst().orElseThrow().desc;
    }
}
