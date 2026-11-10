/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.fabric.Fabric_1_21_10_to_1_21_11;
import com.retromod.shim.fabric.Fabric_1_21_4_to_1_21_5;
import com.retromod.shim.fabric.Fabric_1_21_8_to_1_21_9;
import com.retromod.shim.forge.Forge_1_21_10_to_1_21_11;
import com.retromod.shim.forge.Forge_1_21_4_to_1_21_5;
import com.retromod.shim.forge.Forge_1_21_8_to_1_21_9;
import com.retromod.shim.neoforge.NeoForge_1_21_10_to_1_21_11;
import com.retromod.shim.neoforge.NeoForge_1_21_4_to_1_21_5;
import com.retromod.shim.neoforge.NeoForge_1_21_8_to_1_21_9;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Old JOML-typed calls take the widened descriptor from the exact host version that widened them. */
class JomlParameterWideningTest {

    private static final String V3 = "Lorg/joml/Vector3f;";
    private static final String V3C = "Lorg/joml/Vector3fc;";
    private static final String Q = "Lorg/joml/Quaternionf;";
    private static final String QC = "Lorg/joml/Quaternionfc;";
    private static final String M4 = "Lorg/joml/Matrix4f;";
    private static final String M4C = "Lorg/joml/Matrix4fc;";
    private static final String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    private static final String VERTEX_CONSUMER = "com/mojang/blaze3d/vertex/VertexConsumer";
    private static final String CONSUMER = "Lcom/mojang/blaze3d/vertex/VertexConsumer;";
    private static final String ITEM_TRANSFORM = "net/minecraft/client/renderer/block/model/ItemTransform";
    private static final String ITEM_TRANSFORM_26_1 = "net/minecraft/client/resources/model/cuboid/ItemTransform";
    private static final String INTERP = "Lnet/minecraft/client/animation/AnimationChannel$Interpolation;";

    /** One old call, the host version that removed it, and the descriptor that replaced it. */
    record Call(String since, int opcode, boolean itf, String owner, String name, String oldDesc, String newDesc) {
        String label() { return owner + "." + name + oldDesc; }
    }

    // The change versions come from Mojang's official mappings for each 1.21.x release.
    private static final List<Call> MOJANG = List.of(
            new Call("1.21.5", INVOKEVIRTUAL, false, POSE_STACK, "mulPose", "(" + Q + ")V", "(" + QC + ")V"),
            new Call("1.21.5", INVOKEVIRTUAL, false, POSE_STACK, "mulPose", "(" + M4 + ")V", "(" + M4C + ")V"),
            new Call("1.21.5", INVOKEVIRTUAL, false, POSE_STACK, "rotateAround", "(" + Q + "FFF)V", "(" + QC + "FFF)V"),
            new Call("1.21.5", INVOKEVIRTUAL, false, POSE_STACK + "$Pose", "transformNormal",
                    "(" + V3 + V3 + ")" + V3, "(" + V3C + V3 + ")" + V3),
            new Call("1.21.5", INVOKESPECIAL, false, "com/mojang/math/Transformation", "<init>",
                    "(" + M4 + ")V", "(" + M4C + ")V"),
            new Call("1.21.5", INVOKESPECIAL, false, ITEM_TRANSFORM, "<init>",
                    "(" + V3 + V3 + V3 + ")V", "(" + V3C + V3C + V3C + ")V"),
            new Call("1.21.9", INVOKESPECIAL, false, "net/minecraft/client/animation/Keyframe", "<init>",
                    "(F" + V3 + INTERP + ")V", "(F" + V3C + INTERP + ")V"),
            new Call("1.21.11", INVOKEINTERFACE, true, VERTEX_CONSUMER, "addVertex",
                    "(" + M4 + "FFF)" + CONSUMER, "(" + M4C + "FFF)" + CONSUMER),
            new Call("1.21.11", INVOKEVIRTUAL, false, "com/mojang/blaze3d/vertex/BufferBuilder", "addVertex",
                    "(" + M4 + "FFF)" + CONSUMER, "(" + M4C + "FFF)" + CONSUMER),
            new Call("1.21.11", INVOKEINTERFACE, true, VERTEX_CONSUMER, "addVertex",
                    "(" + V3 + ")" + CONSUMER, "(" + V3C + ")" + CONSUMER),
            new Call("1.21.11", INVOKEVIRTUAL, false, "com/mojang/blaze3d/vertex/BufferBuilder", "addVertex",
                    "(" + V3 + ")" + CONSUMER, "(" + V3C + ")" + CONSUMER),
            new Call("1.21.11", INVOKESPECIAL, false, "com/mojang/math/Transformation", "<init>",
                    "(" + V3 + Q + V3 + Q + ")V", "(" + V3C + QC + V3C + QC + ")V"),
            new Call("1.21.11", INVOKESPECIAL, false, "net/minecraft/world/phys/Vec3", "<init>",
                    "(" + V3 + ")V", "(" + V3C + ")V"));

    // The same members in the intermediary names a Fabric mod carries before 26.1.
    private static final String INT_CONSUMER = "Lnet/minecraft/class_4588;";
    private static final String INT_INTERP = "Lnet/minecraft/class_7179$class_7180;";
    private static final List<Call> INTERMEDIARY = List.of(
            new Call("1.21.5", INVOKEVIRTUAL, false, "net/minecraft/class_4587", "method_22907",
                    "(" + Q + ")V", "(" + QC + ")V"),
            new Call("1.21.5", INVOKEVIRTUAL, false, "net/minecraft/class_4587", "method_34425",
                    "(" + M4 + ")V", "(" + M4C + ")V"),
            new Call("1.21.5", INVOKEVIRTUAL, false, "net/minecraft/class_4587", "method_49278",
                    "(" + Q + "FFF)V", "(" + QC + "FFF)V"),
            new Call("1.21.5", INVOKEVIRTUAL, false, "net/minecraft/class_4587$class_4665", "method_56821",
                    "(" + V3 + V3 + ")" + V3, "(" + V3C + V3 + ")" + V3),
            new Call("1.21.5", INVOKESPECIAL, false, "net/minecraft/class_4590", "<init>",
                    "(" + M4 + ")V", "(" + M4C + ")V"),
            new Call("1.21.5", INVOKESPECIAL, false, "net/minecraft/class_804", "<init>",
                    "(" + V3 + V3 + V3 + ")V", "(" + V3C + V3C + V3C + ")V"),
            new Call("1.21.9", INVOKESPECIAL, false, "net/minecraft/class_7186", "<init>",
                    "(F" + V3 + INT_INTERP + ")V", "(F" + V3C + INT_INTERP + ")V"),
            new Call("1.21.11", INVOKEINTERFACE, true, "net/minecraft/class_4588", "method_22918",
                    "(" + M4 + "FFF)" + INT_CONSUMER, "(" + M4C + "FFF)" + INT_CONSUMER),
            new Call("1.21.11", INVOKEVIRTUAL, false, "net/minecraft/class_287", "method_22918",
                    "(" + M4 + "FFF)" + INT_CONSUMER, "(" + M4C + "FFF)" + INT_CONSUMER),
            new Call("1.21.11", INVOKEINTERFACE, true, "net/minecraft/class_4588", "method_60830",
                    "(" + V3 + ")" + INT_CONSUMER, "(" + V3C + ")" + INT_CONSUMER),
            new Call("1.21.11", INVOKEVIRTUAL, false, "net/minecraft/class_287", "method_60830",
                    "(" + V3 + ")" + INT_CONSUMER, "(" + V3C + ")" + INT_CONSUMER),
            new Call("1.21.11", INVOKESPECIAL, false, "net/minecraft/class_4590", "<init>",
                    "(" + V3 + Q + V3 + Q + ")V", "(" + V3C + QC + V3C + QC + ")V"),
            new Call("1.21.11", INVOKESPECIAL, false, "net/minecraft/class_243", "<init>",
                    "(" + V3 + ")V", "(" + V3C + ")V"));

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private String savedTarget;

    @BeforeEach
    void setUp() {
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"neoforge", "forge"})
    @DisplayName("Each old call takes the widened descriptor on its change version and not one version earlier")
    void widensFromTheChangeVersion(String loader) {
        for (Call call : MOJANG) {
            registerShimsOnHost(shimsFor(loader), call.since());
            assertWidened(call, transformOne(call), call.owner());

            registerShimsOnHost(shimsFor(loader), versionBefore(call.since()));
            assertKept(call, transformOne(call));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"neoforge", "forge"})
    @DisplayName("A 1.21.11 host gets every widening from the whole shim chain")
    void newestHostBefore26TakesEveryWidening(String loader) {
        registerShimsOnHost(shimsFor(loader), "1.21.11");
        for (Call call : MOJANG) {
            assertWidened(call, transformOne(call), call.owner());
        }
    }

    @Test
    @DisplayName("Fabric intermediary calls widen on their change version and not one version earlier")
    void fabricIntermediaryWidensFromTheChangeVersion() {
        for (Call call : INTERMEDIARY) {
            registerShimsOnHost(shimsFor("fabric"), call.since());
            assertWidened(call, transformOne(call), call.owner());

            registerShimsOnHost(shimsFor("fabric"), versionBefore(call.since()));
            assertKept(call, transformOne(call));
        }
    }

    @Test
    @DisplayName("On 26.x the Fabric 1.21.x shims leave intermediary calls to the 26.x remap")
    void fabricIntermediaryFormsStayOff26x() {
        registerShimsOnHost(shimsFor("fabric"), "26.1");
        for (Call call : INTERMEDIARY) {
            assertKept(call, transformOne(call));
        }
    }

    @Test
    @DisplayName("The 26.1 path widens the members the 1.21.x shims added, so 26.x keeps up")
    void the26_1PathCarriesEveryWidening() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        Common_1_21_11_to_26_1_ClassMoves.registerCorpus26xDescriptorAdaptations(transformer);
        for (Call call : MOJANG) {
            if (call.owner().equals(ITEM_TRANSFORM)) continue;
            assertWidened(call, transformOne(call), call.owner());
        }
    }

    @Test
    @DisplayName("On 26.x ItemTransform widens under the class it moved to")
    void itemTransformWidensUnderIts26_1Owner() {
        Call itemTransform = MOJANG.stream().filter(c -> c.owner().equals(ITEM_TRANSFORM)).findFirst().orElseThrow();
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        // The vanilla class moves apply before any member redirect on a 26.x host.
        transformer.registerClassRedirect(ITEM_TRANSFORM, ITEM_TRANSFORM_26_1);
        new NeoForge_1_21_4_to_1_21_5().registerRedirects(transformer);

        assertWidened(itemTransform, transformOne(itemTransform), ITEM_TRANSFORM_26_1);
    }

    @Test
    @DisplayName("On 26.3 mulPose(Quaternionf) still becomes rotate, whatever order the shims register in")
    void mulPoseStillBecomesRotateOn26_3() {
        Call mulPose = MOJANG.get(0);
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        new NeoForge_1_21_4_to_1_21_5().registerRedirects(transformer);
        Common_1_21_11_to_26_1_ClassMoves.registerCorpus26xDescriptorAdaptations(transformer);
        Mc26_2To26_3CoreMoves.register(transformer);
        assertRotate(transformOne(mulPose));

        transformer.clearRedirectsForTesting();
        Mc26_2To26_3CoreMoves.register(transformer);
        Common_1_21_11_to_26_1_ClassMoves.registerCorpus26xDescriptorAdaptations(transformer);
        new NeoForge_1_21_4_to_1_21_5().registerRedirects(transformer);
        assertRotate(transformOne(mulPose));
    }

    @Test
    @DisplayName("Every widened descriptor matches the NeoForge 1.21.11 and the 26.x client jars")
    void tableMatchesTheHostJars() throws Exception {
        String libraries = System.getProperty("user.home", "")
                + "/Library/Application Support/PrismLauncher/libraries/";
        Path neo12111 = existing(libraries
                + "net/neoforged/minecraft-client-patched/21.11.42/minecraft-client-patched-21.11.42.jar");
        List<Path> v26 = new ArrayList<>();
        for (String version : new String[]{"26.1.2", "26.2", "26.3"}) {
            v26.add(existing(libraries + "com/mojang/minecraft/" + version + "/minecraft-" + version + "-client.jar"));
        }
        Assumptions.assumeTrue(neo12111 != null && !v26.contains(null),
                "the NeoForge 1.21.11 and 26.x client jars are present");

        for (Call call : MOJANG) {
            // BufferBuilder inherits addVertex, so VertexConsumer declares the widened form.
            String declaring = call.owner().endsWith("BufferBuilder") ? VERTEX_CONSUMER : call.owner();
            assertGone(neo12111, call.owner(), declaring, call);
            assertTrue(declares(neo12111, declaring, call.name(), call.newDesc()),
                    "NeoForge 1.21.11 has " + call.newDesc());
            for (Path jar : v26) {
                String owner26 = call.owner().equals(ITEM_TRANSFORM) ? ITEM_TRANSFORM_26_1 : call.owner();
                String declaring26 = declaring.equals(ITEM_TRANSFORM) ? ITEM_TRANSFORM_26_1 : declaring;
                // 26.3 renamed mulPose(Quaternionfc) to rotate, which its own shim handles.
                boolean rotate = jar.toString().contains("26.3") && call.newDesc().equals("(" + QC + ")V");
                assertGone(jar, owner26, declaring26, call);
                assertTrue(declares(jar, declaring26, rotate ? "rotate" : call.name(), call.newDesc()),
                        jar.getFileName() + " has the widened " + call.label());
            }
        }
    }

    /** A redirect keyed on a member the host still has would break newer mods, so the old form must be gone. */
    private static void assertGone(Path jar, String owner, String declaring, Call call) throws Exception {
        boolean present = declares(jar, owner, call.name(), call.oldDesc())
                || declares(jar, declaring, call.name(), call.oldDesc());
        assertFalse(present, jar.getFileName() + " must not declare " + call.label());
    }

    private void assertWidened(Call call, MethodInsnNode result, String expectedOwner) {
        assertEquals(call.newDesc(), result.desc, call.label() + " must take the widened descriptor");
        assertEquals(expectedOwner, result.owner, call.label() + " keeps its owner");
        assertEquals(call.name(), result.name, call.label() + " keeps its name");
        assertEquals(call.opcode(), result.getOpcode(), call.label() + " keeps its invoke kind");
        assertEquals(call.itf(), result.itf, call.label() + " keeps its interface flag");
    }

    private void assertKept(Call call, MethodInsnNode result) {
        assertEquals(call.oldDesc(), result.desc,
                call.label() + " still exists on the host before " + call.since() + ", so it must stay as written");
        assertEquals(call.owner(), result.owner);
        assertEquals(call.name(), result.name);
    }

    private static void assertRotate(MethodInsnNode result) {
        assertEquals("rotate", result.name, "26.3 renamed mulPose(Quaternionfc) to rotate");
        assertEquals("(" + QC + ")V", result.desc);
    }

    private static VersionShim[] shimsFor(String loader) {
        return switch (loader) {
            case "neoforge" -> new VersionShim[]{new NeoForge_1_21_4_to_1_21_5(),
                    new NeoForge_1_21_8_to_1_21_9(), new NeoForge_1_21_10_to_1_21_11()};
            case "forge" -> new VersionShim[]{new Forge_1_21_4_to_1_21_5(),
                    new Forge_1_21_8_to_1_21_9(), new Forge_1_21_10_to_1_21_11()};
            default -> new VersionShim[]{new Fabric_1_21_4_to_1_21_5(),
                    new Fabric_1_21_8_to_1_21_9(), new Fabric_1_21_10_to_1_21_11()};
        };
    }

    /** Registers the shims a launch on {@code host} would register, after clearing the last host's. */
    private void registerShimsOnHost(VersionShim[] shims, String host) {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = host;
        for (VersionShim shim : shims) {
            if (ShimRegistry.isAvailableOnHost(shim, host)) shim.registerRedirects(transformer);
        }
    }

    private static String versionBefore(String version) {
        return switch (version) {
            case "1.21.5" -> "1.21.4";
            case "1.21.9" -> "1.21.8";
            case "1.21.11" -> "1.21.10";
            default -> throw new IllegalArgumentException(version);
        };
    }

    /** Transforms a mod method that makes only {@code call} and returns the call as rewritten. */
    private MethodInsnNode transformOne(Call call) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/joml/Caller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "draw", "()V", null, null);
        mv.visitCode();
        if (call.name().equals("<init>")) {
            mv.visitTypeInsn(NEW, call.owner());
            mv.visitInsn(DUP);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        for (Type arg : Type.getArgumentTypes(call.oldDesc())) {
            mv.visitInsn(arg.getSort() == Type.FLOAT ? FCONST_0 : ACONST_NULL);
        }
        mv.visitMethodInsn(call.opcode(), call.owner(), call.name(), call.oldDesc(), call.itf());
        if (call.name().equals("<init>") || Type.getReturnType(call.oldDesc()) != Type.VOID_TYPE) mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/joml/Caller")).accept(node, 0);
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode m) calls.add(m);
            }
        }
        assertEquals(1, calls.size(), "the transform must leave exactly one call for " + call.label());
        return calls.get(0);
    }

    private static Path existing(String path) {
        Path jar = Path.of(path);
        return Files.isRegularFile(jar) ? jar : null;
    }

    private static boolean declares(Path jar, String owner, String name, String desc) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(owner + ".class");
            if (entry == null) return false;
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
            return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
        }
    }
}
