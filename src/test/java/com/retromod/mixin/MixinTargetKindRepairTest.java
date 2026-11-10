/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A mixin of the wrong kind for its target stops the game even when not required, so Retromod drops that target. */
class MixinTargetKindRepairTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String FEATURE = "net/minecraft/world/level/levelgen/feature/Feature";
    private static final String EXPLOSION = "net/minecraft/world/level/Explosion";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String STRIPPED = "retromod/stripped/";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a class mixin on a target that is now an interface is disabled, the way the blocklist disables one")
    void classMixinOnAnInterfaceIsDisabled() {
        ClassNode mixin = node(mixin("mod/FeatureMixin", false, List.of(FEATURE), List.of(), true));
        assertTrue(MixinTargetKindRepair.apply(mixin, MixinTargetKindRepairTest::host263));
        assertEquals(List.of(STRIPPED + "FeatureMixin"), targets(mixin));
    }

    @Test
    @DisplayName("only the targets of the wrong kind go; the others still apply")
    void otherTargetsStay() {
        ClassNode mixin = node(mixin("mod/WorldMixin", false, List.of(LEVEL), List.of(EXPLOSION.replace('/', '.')), true));
        assertTrue(MixinTargetKindRepair.apply(mixin, MixinTargetKindRepairTest::host263));
        assertEquals(List.of(LEVEL), targets(mixin), "the string form target named the interface");
    }

    @Test
    @DisplayName("an interface mixin with its own methods on a target that is now a class is disabled")
    void interfaceMixinOnAClassIsDisabled() {
        ClassNode mixin = node(mixin("mod/LevelDuckMixin", true, List.of(LEVEL), List.of(), true));
        assertTrue(MixinTargetKindRepair.apply(mixin, MixinTargetKindRepairTest::host263));
        assertEquals(List.of(STRIPPED + "LevelDuckMixin"), targets(mixin));
    }

    @Test
    @DisplayName("an accessor interface, a class mixin on a class, and an unreadable target are left alone")
    void validShapesStay() {
        ClassNode accessor = node(mixin("mod/ExplosionAccessor", true, List.of(EXPLOSION), List.of(), false));
        assertFalse(MixinTargetKindRepair.apply(accessor, MixinTargetKindRepairTest::host263), "Mixin accepts an accessor either way");
        ClassNode onClass = node(mixin("mod/LevelMixin", false, List.of(LEVEL), List.of(), true));
        assertFalse(MixinTargetKindRepair.apply(onClass, MixinTargetKindRepairTest::host263));
        ClassNode unknown = node(mixin("mod/OtherMixin", false, List.of("other/Mystery"), List.of(), true));
        assertFalse(MixinTargetKindRepair.apply(unknown, MixinTargetKindRepairTest::host263));
        assertEquals(List.of("other/Mystery"), targets(unknown));
    }

    @Test
    @DisplayName("the post-remap pipeline disables a Feature class mixin on 26.3 and keeps it on 26.2, read from the indexed host")
    void pipelineFollowsTheIndexedHost() throws Exception {
        byte[] featureMixin = mixin("mod/FeatureMixin", false, List.of(FEATURE), List.of(), true);
        MixinCompatibilityTransformer on263 = new MixinCompatibilityTransformer(indexedHost("263.jar", true));
        assertEquals(List.of(STRIPPED + "FeatureMixin"), targets(node(on263.applyPostRemapRepairs(featureMixin))));

        MixinCompatibilityTransformer on262 = new MixinCompatibilityTransformer(indexedHost("262.jar", false));
        assertSame(featureMixin, on262.declineTargetKindChanges(featureMixin), "26.2's Feature is still a class");
        assertEquals(List.of(FEATURE), targets(node(on262.applyPostRemapRepairs(featureMixin))));
    }

    @Test
    @DisplayName("offline with no game jar, the version table decides: 26.3 made Feature an interface, 26.2 had not")
    void offlineTableDecides() {
        byte[] featureMixin = mixin("mod/FeatureMixin", false, List.of(FEATURE), List.of(), true);
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertEquals(List.of(STRIPPED + "FeatureMixin"),
                targets(node(new MixinCompatibilityTransformer(transformer).declineTargetKindChanges(featureMixin))));
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertSame(featureMixin, new MixinCompatibilityTransformer(transformer).declineTargetKindChanges(featureMixin));
    }

    /** A 26.3-like host: Feature and Explosion are interfaces, Level is a class. */
    private static MixinTargetKindRepair.Kind host263(String name) {
        return switch (name) {
            case FEATURE, EXPLOSION -> MixinTargetKindRepair.Kind.INTERFACE;
            case LEVEL -> MixinTargetKindRepair.Kind.CLASS;
            default -> MixinTargetKindRepair.Kind.UNKNOWN;
        };
    }

    /** A fresh transformer indexing a host jar whose Feature is an interface or, as on 26.2, an abstract class. */
    private RetromodTransformer indexedHost(String jar, boolean featureIsInterface) throws Exception {
        ClassWriter cw = new ClassWriter(0);
        int access = featureIsInterface ? ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT : ACC_PUBLIC | ACC_ABSTRACT | ACC_SUPER;
        cw.visit(V21, access, FEATURE, null, "java/lang/Object", null);
        cw.visitEnd();
        Path file = dir.resolve(jar);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new JarEntry(FEATURE + ".class"));
            out.write(cw.toByteArray());
            out.closeEntry();
        }
        Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RetromodTransformer transformer = constructor.newInstance();
        transformer.initFuzzyResolver(file);
        return transformer;
    }

    /** A mixin naming {@code classTargets} and {@code stringTargets}, with an injector or, when it has none, one accessor. */
    private static byte[] mixin(String name, boolean isInterface, List<String> classTargets, List<String> stringTargets,
            boolean injector) {
        ClassWriter cw = new ClassWriter(0);
        int access = isInterface ? ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT : ACC_PUBLIC | ACC_ABSTRACT | ACC_SUPER;
        cw.visit(V21, access, name, null, "java/lang/Object", null);
        AnnotationVisitor annotation = cw.visitAnnotation(MIXIN, false);
        if (!classTargets.isEmpty()) {
            AnnotationVisitor value = annotation.visitArray("value");
            for (String target : classTargets) value.visit(null, Type.getObjectType(target));
            value.visitEnd();
        }
        if (!stringTargets.isEmpty()) {
            AnnotationVisitor targets = annotation.visitArray("targets");
            for (String target : stringTargets) targets.visit(null, target);
            targets.visitEnd();
        }
        annotation.visitEnd();
        int methodAccess = isInterface ? ACC_PUBLIC | ACC_ABSTRACT : ACC_PRIVATE;
        MethodVisitor mv = cw.visitMethod(methodAccess, injector ? "onPlace" : "getRadius", injector ? "()V" : "()F", null, null);
        mv.visitAnnotation(injector ? INJECT : ACCESSOR, false).visitEnd();
        if (!isInterface) {
            mv.visitCode();
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 1);
        }
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** Every target the {@code @Mixin} annotation still names, as internal names. */
    private static List<String> targets(ClassNode mixin) {
        List<String> targets = new ArrayList<>();
        for (AnnotationNode annotation : mixin.invisibleAnnotations) {
            if (!MIXIN.equals(annotation.desc)) continue;
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if (!(annotation.values.get(i + 1) instanceof List<?> list)) continue;
                for (Object target : list) {
                    targets.add(target instanceof Type type ? type.getInternalName() : ((String) target).replace('.', '/'));
                }
            }
        }
        return targets;
    }
}
