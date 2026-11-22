/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
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
import org.objectweb.asm.Opcodes;
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

/** A class mixin that shadows a game member its target no longer has is disabled on that target, so the target still loads. */
class MixinShadowGateTest {

    private static final String NOISE_CHUNK = "net/minecraft/world/level/levelgen/NoiseChunk";
    private static final String BEARDIFIER = "net/minecraft/world/level/levelgen/Beardifier";
    private static final String RULE_LIST = "net/minecraft/world/level/levelgen/RuleList";
    private static final String LOADER_CHILD = "net/minecraft/world/level/levelgen/LoaderChunk";
    private static final String LOADER_BASE = "net/neoforged/neoforge/common/extensions/ExtensionBase";
    private static final String DENSITY = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String STRIPPED = "retromod/stripped/NoiseChunkMixin";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    @DisplayName("A mixin shadowing NoiseChunk.blockX(), gone on 26.3, is pointed at a placeholder target")
    void missingShadowDisablesTheMixin() throws Exception {
        ClassNode mixin = MixinBuilder.on(NOISE_CHUNK).shadowMethod("blockX", "()I").node();
        assertTrue(MixinShadowGate.apply(mixin, host()));
        assertEquals(List.of(STRIPPED), targets(mixin));
    }

    @Test
    @DisplayName("Shadows the host has, by name, without the shadow$ prefix, by alias, or inherited from the JDK, keep the mixin")
    void presentShadowsKeepTheMixin() throws Exception {
        FuzzyMethodResolver host = host();
        assertFalse(MixinShadowGate.apply(MixinBuilder.on(NOISE_CHUNK).shadowMethod("cellWidth", "()I").node(), host));
        assertFalse(MixinShadowGate.apply(MixinBuilder.on(NOISE_CHUNK).shadowMethod("shadow$cellWidth", "()I").node(), host));
        assertFalse(MixinShadowGate.apply(MixinBuilder.on(NOISE_CHUNK).shadowMethod("oldCellWidth", "()I")
                .alias("cellWidth").node(), host));
        assertFalse(MixinShadowGate.apply(MixinBuilder.on(RULE_LIST).shadowMethod("size", "()I").node(), host),
                "RuleList extends java.util.AbstractList, which declares size()");
    }

    @Test
    @DisplayName("Mixins on a mod's own class, and any mixin when no host jar is indexed, are not judged")
    void unknownTargetsAreLeftAlone() throws Exception {
        assertFalse(MixinShadowGate.apply(MixinBuilder.on("mod/OwnChunk").shadowMethod("blockX", "()I").node(), host()));
        assertFalse(MixinShadowGate.apply(MixinBuilder.on(NOISE_CHUNK).shadowMethod("blockX", "()I").node(),
                new FuzzyMethodResolver()));
    }

    @Test
    @DisplayName("A target with a loader or library ancestor the index lacks is not judged, since it may inherit the member")
    void partlyKnownHierarchyIsNotJudged() throws Exception {
        assertFalse(MixinShadowGate.apply(MixinBuilder.on(LOADER_CHILD).shadowMethod("blockX", "()I").node(), host()),
                LOADER_CHILD + " extends " + LOADER_BASE + ", which the game jar does not hold");
    }

    @Test
    @DisplayName("A shadow or mixin marked remap = false keeps its name as written, so it is not judged")
    void remapFalseIsNotJudged() throws Exception {
        FuzzyMethodResolver host = host();
        assertFalse(MixinShadowGate.apply(MixinBuilder.on(NOISE_CHUNK).shadowMethod("blockX", "()I").shadowRemap(false).node(), host));
        assertFalse(MixinShadowGate.apply(MixinBuilder.on(NOISE_CHUNK).shadowMethod("blockX", "()I").mixinRemap(false).node(), host));
    }

    @Test
    @DisplayName("A mixin that adds an interface its target lacks is not judged; one the target already has is")
    void addedInterfaceIsNotJudged() throws Exception {
        FuzzyMethodResolver host = host();
        assertFalse(MixinShadowGate.apply(MixinBuilder.on(NOISE_CHUNK).shadowMethod("blockX", "()I")
                .implementing("mod/duck/ChunkDuck").node(), host));
        assertTrue(MixinShadowGate.apply(MixinBuilder.on(NOISE_CHUNK).shadowMethod("blockX", "()I")
                .implementing(DENSITY).node(), host), "NoiseChunk already implements " + DENSITY);
    }

    @Test
    @DisplayName("With several targets only the ones lacking the shadow are removed; none left means the placeholder")
    void multipleTargetsLoseOnlyTheFailingOnes() throws Exception {
        FuzzyMethodResolver host = host();
        ClassNode partly = MixinBuilder.on(NOISE_CHUNK, BEARDIFIER).shadowMethod("blockX", "()I").node();
        assertTrue(MixinShadowGate.apply(partly, host));
        assertEquals(List.of(BEARDIFIER), targets(partly), "Beardifier still has blockX()");

        ClassNode none = MixinBuilder.on(NOISE_CHUNK, BEARDIFIER).shadowMethod("blockY", "()I").node();
        assertTrue(MixinShadowGate.apply(none, host));
        assertEquals(List.of(STRIPPED), targets(none));
    }

    @Test
    @DisplayName("A shadow typed with a Retromod stand-in whose name the target lacks entirely disables the mixin")
    void retromodTypedShadowWithAbsentNameIsMissing() throws Exception {
        ClassNode mixin = MixinBuilder.on(NOISE_CHUNK)
                .shadowField("bufferSource", "Lcom/retromod/embedded/mod_key/com/retromod/shim/Example;").node();
        assertTrue(MixinShadowGate.apply(mixin, host()));
        assertEquals(List.of(STRIPPED), targets(mixin));
    }

    @Test
    @DisplayName("A shadow typed with a Retromod stand-in whose name the target still has is left for a bug report")
    void retromodTypedShadowWithPresentNameIsNotJudged() throws Exception {
        assertFalse(MixinShadowGate.apply(MixinBuilder.on(NOISE_CHUNK)
                .shadowMethod("cellWidth", "()Lcom/retromod/embedded/mod_key/com/retromod/shim/Example;").node(), host()),
                "NoiseChunk has a cellWidth() of another type, so only the type changed");
    }

    @Test
    @DisplayName("After drift repair drops an unread shadow field the host lacks, the gate keeps the mixin")
    void gateRunsAfterDriftRepair() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        MixinCompatibilityTransformer repairs = new MixinCompatibilityTransformer(indexedTransformer());
        byte[] unread = MixinBuilder.on(NOISE_CHUNK).shadowField("oldCount", "I").bytes();
        ClassNode kept = node(repairs.applyPostRemapRepairs(unread));
        assertEquals(List.of(NOISE_CHUNK), targets(kept));
        assertTrue(kept.fields.stream().noneMatch(field -> field.name.equals("oldCount")), "drift repair drops the field");

        byte[] read = MixinBuilder.on(NOISE_CHUNK).shadowMethod("blockX", "()I").callingShadow().bytes();
        assertEquals(List.of(STRIPPED), targets(node(repairs.applyPostRemapRepairs(read))),
                "a shadow the mixin calls survives drift repair, so the gate disables the mixin");
    }

    /**
     * A 26.3-shaped host: NoiseChunk keeps cellWidth() and implements DensityFunction.FunctionContext, Beardifier
     * keeps blockX(), RuleList extends AbstractList, and LoaderChunk extends a loader class the jar lacks.
     */
    private Path hostJar() throws Exception {
        Path jar = dir.resolve("host.jar");
        if (Files.exists(jar)) return jar;
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            write(out, hostClass(NOISE_CHUNK, "java/lang/Object", new String[] {DENSITY}, "cellWidth"));
            write(out, hostClass(BEARDIFIER, "java/lang/Object", null, "blockX"));
            write(out, hostClass(RULE_LIST, "java/util/AbstractList", null, null));
            write(out, hostClass(LOADER_CHILD, LOADER_BASE, null, null));
            ClassWriter density = new ClassWriter(0);
            density.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, DENSITY, null,
                    "java/lang/Object", null);
            density.visitEnd();
            write(out, density);
        }
        return jar;
    }

    private FuzzyMethodResolver host() throws Exception {
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(hostJar());
        return resolver;
    }

    private RetromodTransformer indexedTransformer() throws Exception {
        Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RetromodTransformer transformer = constructor.newInstance();
        transformer.initFuzzyResolver(hostJar());
        return transformer;
    }

    private static ClassWriter hostClass(String name, String superName, String[] interfaces, String intMethod) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, null, superName, interfaces);
        if (intMethod != null) {
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, intMethod, "()I", null, null).visitEnd();
        }
        cw.visitEnd();
        return cw;
    }

    private static void write(JarOutputStream out, ClassWriter cw) throws Exception {
        byte[] bytes = cw.toByteArray();
        out.putNextEntry(new JarEntry(node(bytes).name + ".class"));
        out.write(bytes);
        out.closeEntry();
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
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                Object key = annotation.values.get(i);
                if (!("value".equals(key) || "targets".equals(key))
                        || !(annotation.values.get(i + 1) instanceof List<?> list)) continue;
                for (Object target : list) {
                    targets.add(target instanceof Type type ? type.getInternalName() : ((String) target).replace('.', '/'));
                }
            }
        }
        return targets;
    }

    /** Builds {@code @Mixin(targets) abstract class NoiseChunkMixin} with one shadow and the options a case needs. */
    private static final class MixinBuilder {
        private final String[] targets;
        private String memberName;
        private String memberDesc;
        private boolean field;
        private String alias;
        private Boolean shadowRemap;
        private Boolean mixinRemap;
        private String addedInterface;
        private boolean callsShadow;

        private MixinBuilder(String[] targets) {
            this.targets = targets;
        }

        static MixinBuilder on(String... targets) {
            return new MixinBuilder(targets);
        }

        MixinBuilder shadowMethod(String name, String desc) {
            memberName = name;
            memberDesc = desc;
            return this;
        }

        MixinBuilder shadowField(String name, String desc) {
            memberName = name;
            memberDesc = desc;
            field = true;
            return this;
        }

        MixinBuilder alias(String name) {
            alias = name;
            return this;
        }

        MixinBuilder shadowRemap(boolean remap) {
            shadowRemap = remap;
            return this;
        }

        MixinBuilder mixinRemap(boolean remap) {
            mixinRemap = remap;
            return this;
        }

        MixinBuilder implementing(String type) {
            addedInterface = type;
            return this;
        }

        MixinBuilder callingShadow() {
            callsShadow = true;
            return this;
        }

        ClassNode node() {
            return MixinShadowGateTest.node(bytes());
        }

        byte[] bytes() {
            String name = "mod/mixin/NoiseChunkMixin";
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER, name, null,
                    "java/lang/Object", addedInterface == null ? null : new String[] {addedInterface});
            AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
            AnnotationVisitor value = mixin.visitArray("value");
            for (String target : targets) value.visit(null, Type.getObjectType(target));
            value.visitEnd();
            if (mixinRemap != null) mixin.visit("remap", mixinRemap);
            mixin.visitEnd();
            if (field) {
                var fv = cw.visitField(Opcodes.ACC_PRIVATE, memberName, memberDesc, null, null);
                annotate(fv.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", false));
                fv.visitEnd();
            } else {
                MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, memberName, memberDesc, null, null);
                annotate(mv.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", false));
                mv.visitEnd();
            }
            if (callsShadow) {
                MethodVisitor caller = cw.visitMethod(Opcodes.ACC_PRIVATE, "useShadow", "()I", null, null);
                caller.visitCode();
                caller.visitVarInsn(Opcodes.ALOAD, 0);
                caller.visitMethodInsn(Opcodes.INVOKEVIRTUAL, name, memberName, memberDesc, false);
                caller.visitInsn(Opcodes.IRETURN);
                caller.visitMaxs(0, 0);
                caller.visitEnd();
            }
            cw.visitEnd();
            return cw.toByteArray();
        }

        private void annotate(AnnotationVisitor shadow) {
            if (alias != null) {
                AnnotationVisitor aliases = shadow.visitArray("aliases");
                aliases.visit(null, alias);
                aliases.visitEnd();
            }
            if (shadowRemap != null) shadow.visit("remap", shadowRemap);
            shadow.visitEnd();
        }
    }
}
