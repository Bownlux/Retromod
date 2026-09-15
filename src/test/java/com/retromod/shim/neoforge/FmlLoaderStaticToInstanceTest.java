/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #248 (TaCZ): NeoForge 1.21.9 turned {@code FMLLoader}'s statics into instance methods.
 *
 * <p>A mod built against 1.21.8 or earlier links fine and then dies on its first call with
 * {@code IncompatibleClassChangeError: Expected static method
 * 'net.neoforged.api.distmarker.Dist net.neoforged.fml.loading.FMLLoader.getDist()'}. The message
 * names a method that plainly still exists, which is why this reads as a missing method rather
 * than a moved one.
 *
 * <p>Bisected against the loader jars themselves: fancymodloader 9.0.18, which NeoForge 21.8 ships,
 * still has {@code public static Dist getDist()} and the {@code FMLEnvironment.dist} field; 10.0.14,
 * which NeoForge 21.9 ships, has neither. So the break belongs to the 1.21.8 to 1.21.9 step.
 */
class FmlLoaderStaticToInstanceTest {

    private static final String LOADER = "net/neoforged/fml/loading/FMLLoader";
    private static final String ENV = "net/neoforged/fml/loading/FMLEnvironment";
    private static final String DIST = "Lnet/neoforged/api/distmarker/Dist;";
    private static final String USER = "test/fml/FmlUser";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new NeoForge_1_21_8_to_1_21_9().registerRedirects(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    /** Every old entry point a 1.21.8 mod could compile against, one per method. */
    private static byte[] modUsingOldFml() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, USER, null, "java/lang/Object", null);

        staticCall(cw, "side", "()" + DIST, LOADER, "getDist", "()" + DIST);
        staticCall(cw, "production", "()Z", LOADER, "isProduction", "()Z");
        staticCall(cw, "gamePath", "()Ljava/nio/file/Path;",
                LOADER, "getGamePath", "()Ljava/nio/file/Path;");
        staticCall(cw, "versions", "()Lnet/neoforged/fml/loading/VersionInfo;",
                LOADER, "versionInfo", "()Lnet/neoforged/fml/loading/VersionInfo;");
        staticCall(cw, "layer", "()Ljava/lang/ModuleLayer;",
                LOADER, "getGameLayer", "()Ljava/lang/ModuleLayer;");
        staticCall(cw, "modList", "()Lnet/neoforged/fml/loading/LoadingModList;",
                LOADER, "getLoadingModList", "()Lnet/neoforged/fml/loading/LoadingModList;");
        staticCall(cw, "bindings", "()Lnet/neoforged/fml/IBindingsProvider;",
                LOADER, "getBindings", "()Lnet/neoforged/fml/IBindingsProvider;");

        // The field form of the same two, which is the more common idiom in mods of that era.
        fieldRead(cw, "sideField", "()" + DIST, ENV, "dist", DIST, Opcodes.ARETURN);
        fieldRead(cw, "productionField", "()Z", ENV, "production", "Z", Opcodes.IRETURN);

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void staticCall(ClassWriter cw, String name, String desc,
                                   String owner, String target, String targetDesc) {
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, target, targetDesc, false);
        mv.visitInsn(desc.endsWith("Z") ? Opcodes.IRETURN : Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void fieldRead(ClassWriter cw, String name, String desc,
                                  String owner, String field, String fieldDesc, int ret) {
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, field, fieldDesc);
        mv.visitInsn(ret);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** One call or field access the transformed class still makes. */
    private record Ref(int opcode, String owner, String name, String desc) {}

    private static List<Ref> referencesIn(byte[] bytes) {
        List<Ref> refs = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String o, String nm, String de, boolean i) {
                        refs.add(new Ref(op, o, nm, de));
                    }

                    @Override
                    public void visitFieldInsn(int op, String o, String nm, String de) {
                        refs.add(new Ref(op, o, nm, de));
                    }
                };
            }
        }, 0);
        return refs;
    }

    @Test
    @DisplayName("no call or field read is left pointing at the old static shape")
    void oldStaticShapeIsGone() {
        List<Ref> refs = referencesIn(transformer.transformClass(modUsingOldFml(), USER));

        List<Ref> stale = refs.stream()
                .filter(r -> r.owner().equals(LOADER) || r.owner().equals(ENV))
                .filter(r -> !(r.opcode() == Opcodes.INVOKESTATIC && r.name().equals("getCurrent"))
                        && !(r.opcode() == Opcodes.INVOKEVIRTUAL && r.owner().equals(LOADER))
                        && !(r.opcode() == Opcodes.INVOKESTATIC && r.owner().equals(ENV)))
                .toList();

        assertTrue(stale.isEmpty(),
                "these still use the shape 1.21.9 removed, so the mod would hit "
                + "IncompatibleClassChangeError or NoSuchFieldError on its first call: " + stale);
    }

    @Test
    @DisplayName("each instance accessor is reached through the loader that owns it")
    void instanceCallsPickUpTheLoaderFirst() {
        List<Ref> refs = referencesIn(transformer.transformClass(modUsingOldFml(), USER));

        long picked = refs.stream()
                .filter(r -> r.opcode() == Opcodes.INVOKESTATIC
                        && r.owner().equals(LOADER) && r.name().equals("getCurrent"))
                .count();
        long used = refs.stream()
                .filter(r -> r.opcode() == Opcodes.INVOKEVIRTUAL && r.owner().equals(LOADER))
                .count();

        assertEquals(5, used, "getLoadingModList, getGameDir, getVersionInfo, getGameLayer and "
                + "getBindings have no static form, so all five go through the instance");
        assertEquals(used, picked,
                "every instance call needs its own getCurrent(); a shared receiver would leave "
                + "the stack short by one");
    }

    @Test
    @DisplayName("the two with a static form stay a direct call, not a loader round trip")
    void distAndProductionStayDirect() {
        List<Ref> refs = referencesIn(transformer.transformClass(modUsingOldFml(), USER));

        assertEquals(2, refs.stream().filter(r -> r.opcode() == Opcodes.INVOKESTATIC
                && r.owner().equals(ENV) && r.name().equals("getDist")).count(),
                "both the old FMLLoader.getDist() call and the FMLEnvironment.dist field read "
                + "become FMLEnvironment.getDist()");
        assertEquals(2, refs.stream().filter(r -> r.opcode() == Opcodes.INVOKESTATIC
                && r.owner().equals(ENV) && r.name().equals("isProduction")).count(),
                "same for the production flag in both of its forms");
    }

    @Test
    @DisplayName("every rewritten reference resolves against a real NeoForge loader jar")
    void resolvesAgainstRealNeoForge() throws Exception {
        Path jar = loaderJar();
        Assumptions.assumeTrue(jar != null,
                "needs an installed fancymodloader 10.x or 11.x jar; the whole question is what "
                + "shape the members have on a 1.21.9-or-newer loader");

        Map<String, Integer> members = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String owner : List.of(LOADER, ENV)) {
                ZipEntry entry = zip.getEntry(owner + ".class");
                assertNotNull(entry, owner + " is missing from " + jar.getFileName());
                try (InputStream in = zip.getInputStream(entry)) {
                    new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(
                                int access, String n, String d, String sig, String[] ex) {
                            members.put(owner + "." + n + d, access);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE);
                }
            }
        }

        for (Ref ref : referencesIn(transformer.transformClass(modUsingOldFml(), USER))) {
            if (!ref.owner().equals(LOADER) && !ref.owner().equals(ENV)) continue;
            String key = ref.owner() + "." + ref.name() + ref.desc();
            Integer access = members.get(key);
            assertNotNull(access, key + " does not exist on " + jar.getFileName()
                    + ", so the rewritten call would not link");

            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            if (ref.opcode() == Opcodes.INVOKESTATIC) {
                assertTrue(isStatic, ref.name() + " is called statically but is an instance member "
                        + "here, which is the exact IncompatibleClassChangeError in #248");
            } else if (ref.opcode() == Opcodes.INVOKEVIRTUAL) {
                assertFalse(isStatic, ref.name() + " is called on a receiver but is static here");
            } else {
                fail("unexpected " + ref + "; a field access should not survive the rewrite");
            }
        }
    }

    /** The newest installed loader jar from 1.21.9 onwards, or null if none is present. */
    private static Path loaderJar() {
        Path root = Path.of(System.getProperty("user.home", ""),
                "Library/Application Support/minecraft/libraries/net/neoforged/fancymodloader/loader");
        if (!Files.isDirectory(root)) return null;
        try (var dirs = Files.list(root)) {
            return dirs.filter(d -> {
                        String v = d.getFileName().toString();
                        int dot = v.indexOf('.');
                        try {
                            return dot > 0 && Integer.parseInt(v.substring(0, dot)) >= 10;
                        } catch (NumberFormatException e) {
                            return false;
                        }
                    })
                    .map(d -> d.resolve("loader-" + d.getFileName() + ".jar"))
                    .filter(Files::isRegularFile)
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
