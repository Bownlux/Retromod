/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** What a repaired mixin's code would load: a mod class it initializes, through supertypes the host moved. */
class MixinLinkCheckTest {

    private static final String OLD_RULE = "net/minecraft/world/level/levelgen/SurfaceRules$RuleSource";
    private static final String NEW_RULE = "net/minecraft/world/level/levelgen/material/rule/MaterialRule";

    @TempDir
    Path dir;

    @Test
    @DisplayName("a mod class whose interface the host moved loads, as TerraBlender's rule source does on 26.3")
    void movedSupertypeLoads() throws Exception {
        FuzzyMethodResolver host = host(Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT);
        ClassNode mixin = readsRuleCodec();
        Map<String, byte[]> jar = Map.of("mod/Rule", modRule(null, OLD_RULE));

        assertNull(new MixinLinkCheck(host, Map.of(OLD_RULE, NEW_RULE), jar::get).firstMissing(mixin, null),
                "the old rule interface moved to one the host has");
        String missing = new MixinLinkCheck(host, Map.of(), jar::get).firstMissing(mixin, null);
        assertNotNull(missing, "with no move, the old interface is still missing");
        assertTrue(missing.startsWith(OLD_RULE), missing);
    }

    @Test
    @DisplayName("a supertype the host has as the other kind, or as a final class, does not load, moved or not")
    void wrongKindOfSupertype() throws Exception {
        // The old interface moved to a host class, which a class cannot implement.
        FuzzyMethodResolver classHost = host(0);
        String missing = new MixinLinkCheck(classHost, Map.of(OLD_RULE, NEW_RULE), Map.of("mod/Rule", modRule(null, OLD_RULE))::get)
                .firstMissing(readsRuleCodec(), null);
        assertNotNull(missing);
        assertTrue(missing.startsWith(NEW_RULE + " is a class on this host"), missing);
        // A final host class named as the superclass, with no move involved.
        FuzzyMethodResolver finalHost = host(Opcodes.ACC_FINAL);
        missing = new MixinLinkCheck(finalHost, Map.of(), Map.of("mod/Rule", modRule(NEW_RULE, null))::get)
                .firstMissing(readsRuleCodec(), null);
        assertNotNull(missing);
        assertTrue(missing.startsWith(NEW_RULE + " is final on this host"), missing);
    }

    /** TerraBlender's registry mixin reads its rule source's codec while the registry bootstraps. */
    private static ClassNode readsRuleCodec() {
        ClassNode mixin = new ClassNode();
        mixin.name = "mod/RegistryMixin";
        MethodNode lambda = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "lambda$register$0", "()V", null, null);
        lambda.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "mod/Rule", "CODEC", "Ljava/lang/Object;"));
        lambda.instructions.add(new InsnNode(Opcodes.POP));
        lambda.instructions.add(new InsnNode(Opcodes.RETURN));
        mixin.methods.add(lambda);
        return mixin;
    }

    /** A host whose only class is the new rule type, with {@code access}. */
    private FuzzyMethodResolver host(int access) throws Exception {
        Path jar = Files.createTempFile(dir, "host-", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | access, NEW_RULE, null, "java/lang/Object", null);
            writer.visitEnd();
            out.putNextEntry(new JarEntry(NEW_RULE + ".class"));
            out.write(writer.toByteArray());
            out.closeEntry();
        }
        FuzzyMethodResolver host = new FuzzyMethodResolver();
        host.indexJar(jar);
        assertTrue(host.isIndexed());
        return host;
    }

    /** The mod's rule source as its jar holds it, extending {@code superName} or implementing {@code itf}. */
    private static byte[] modRule(String superName, String itf) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Rule", null, superName == null ? "java/lang/Object" : superName,
                itf == null ? null : new String[]{itf});
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "CODEC", "Ljava/lang/Object;", null, null).visitEnd();
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        assertEquals("mod/Rule", new ClassReader(bytes).getClassName());
        return bytes;
    }
}
