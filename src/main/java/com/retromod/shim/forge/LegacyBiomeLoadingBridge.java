/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.objectweb.asm.Opcodes.*;

/**
 * Posts a pre-1.19 mod's {@code BiomeLoadingEvent} again, through a Forge biome modifier.
 *
 * <p>Forge 1.19 replaced the event with data-driven biome modifiers, so the spawns and features a
 * 1.16 to 1.18 mod added from its handler were silently dropped. The mod's handler stays
 * subscribed to a stand-in event. This bridge gives each such mod a biome modifier that builds the
 * stand-in from the biome's name and Forge's spawn and generation builders, and posts it during
 * the add phase, so the handler edits the biome the way the old event let it.
 *
 * <p>A biome modifier needs a registered serializer and a data entry that names it. The serializer
 * is registered from the mod's own {@code @Mod} constructor, the one moment the mod's event bus
 * and namespace are both known, and the entry is written into the mod's data under its own id.
 * Both are added only to a mod that subscribes to the old event. The old {@code getCategory} and
 * climate accessors are not provided, because 1.19 removed biome categories.
 */
public final class LegacyBiomeLoadingBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-BiomeLoading");

    static final String EVENT = LegacyForge119Bridge.BIOME_LOADING_STAND_IN;
    static final String MODIFIER = "com/retromod/generated/forge118/LegacyBiomeLoadingModifier";
    static final String SERIALIZER_NAME = "retromod_biome_loading";

    private static final String MOD_ANNOTATION = "Lnet/minecraftforge/fml/common/Mod;";
    private static final String FORGE_EVENT = "net/minecraftforge/eventbus/api/Event";
    private static final String WORLD = "net/minecraftforge/common/world/";
    private static final String GENERATION = WORLD + "BiomeGenerationSettingsBuilder";
    private static final String SPAWNS = WORLD + "MobSpawnSettingsBuilder";
    private static final String BIOME_MODIFIER = WORLD + "BiomeModifier";
    private static final String PHASE = WORLD + "BiomeModifier$Phase";
    private static final String BIOME_INFO_BUILDER = WORLD + "ModifiableBiomeInfo$BiomeInfo$Builder";
    private static final String LOCATION = "net/minecraft/resources/ResourceLocation";
    private static final String CODEC = "com/mojang/serialization/Codec";
    private static final String DEFERRED = "net/minecraftforge/registries/DeferredRegister";
    private static final String EVENT_BUS = "net/minecraftforge/eventbus/api/IEventBus";

    private LegacyBiomeLoadingBridge() {
    }

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(MODIFIER, generateModifier());
    }

    /** The stand-in event, carrying what a 1.18 handler reads and edits. */
    static byte[] generateEvent() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, EVENT, null, FORGE_EVENT, null);
        String[][] fields = {{"name", LOCATION}, {"generation", GENERATION}, {"spawns", SPAWNS}};
        for (String[] field : fields) {
            cw.visitField(ACC_PRIVATE | ACC_FINAL, field[0], "L" + field[1] + ";", null, null).visitEnd();
        }

        // Forge's listener setup may build an event with no arguments.
        MethodVisitor empty = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        empty.visitCode();
        empty.visitVarInsn(ALOAD, 0);
        empty.visitInsn(ACONST_NULL);
        empty.visitInsn(ACONST_NULL);
        empty.visitInsn(ACONST_NULL);
        empty.visitMethodInsn(INVOKESPECIAL, EVENT, "<init>", eventConstructor(), false);
        empty.visitInsn(RETURN);
        empty.visitMaxs(0, 0);
        empty.visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", eventConstructor(), null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, FORGE_EVENT, "<init>", "()V", false);
        for (int i = 0; i < fields.length; i++) {
            init.visitVarInsn(ALOAD, 0);
            init.visitVarInsn(ALOAD, i + 1);
            init.visitFieldInsn(PUTFIELD, EVENT, fields[i][0], "L" + fields[i][1] + ";");
        }
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        String[] getters = {"getName", "getGeneration", "getSpawns"};
        for (int i = 0; i < fields.length; i++) {
            MethodVisitor get = cw.visitMethod(ACC_PUBLIC, getters[i], "()L" + fields[i][1] + ";", null, null);
            get.visitCode();
            get.visitVarInsn(ALOAD, 0);
            get.visitFieldInsn(GETFIELD, EVENT, fields[i][0], "L" + fields[i][1] + ";");
            get.visitInsn(ARETURN);
            get.visitMaxs(0, 0);
            get.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static String eventConstructor() {
        return "(L" + LOCATION + ";L" + GENERATION + ";L" + SPAWNS + ";)V";
    }

    /** A biome modifier that is also the supplier its serializer registers. */
    static byte[] generateModifier() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, MODIFIER, null, "java/lang/Object",
                new String[]{BIOME_MODIFIER, "java/util/function/Supplier"});
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "CODEC", "L" + CODEC + ";", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "REGISTERED", "Ljava/util/Set;", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "warned", "Z", null, null).visitEnd();

        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, MODIFIER);
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, MODIFIER, "<init>", "()V", false);
        clinit.visitMethodInsn(INVOKESTATIC, CODEC, "unit", "(Ljava/lang/Object;)L" + CODEC + ";", true);
        clinit.visitFieldInsn(PUTSTATIC, MODIFIER, "CODEC", "L" + CODEC + ";");
        clinit.visitTypeInsn(NEW, "java/util/HashSet");
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, "java/util/HashSet", "<init>", "()V", false);
        clinit.visitFieldInsn(PUTSTATIC, MODIFIER, "REGISTERED", "Ljava/util/Set;");
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

        writeRegisterSerializer(cw);
        writeModify(cw);

        for (String name : new String[]{"codec", "get"}) {
            String desc = name.equals("codec") ? "()L" + CODEC + ";" : "()Ljava/lang/Object;";
            MethodVisitor get = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
            get.visitCode();
            get.visitFieldInsn(GETSTATIC, MODIFIER, "CODEC", "L" + CODEC + ";");
            get.visitInsn(ARETURN);
            get.visitMaxs(0, 0);
            get.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Registers the serializer once per mod namespace. A failure is reported, not thrown, so it
     * cannot stop the mod's own constructor.
     */
    private static void writeRegisterSerializer(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerSerializer", "()V", null, null);
        mv.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        Label done = new Label();
        mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        mv.visitLabel(start);
        mv.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/fml/ModLoadingContext", "get",
                "()Lnet/minecraftforge/fml/ModLoadingContext;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraftforge/fml/ModLoadingContext", "getActiveNamespace",
                "()Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 0);
        mv.visitFieldInsn(GETSTATIC, MODIFIER, "REGISTERED", "Ljava/util/Set;");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitJumpInsn(IFEQ, end);
        mv.visitFieldInsn(GETSTATIC, "net/minecraftforge/registries/ForgeRegistries$Keys",
                "BIOME_MODIFIER_SERIALIZERS", "Lnet/minecraft/resources/ResourceKey;");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, DEFERRED, "create",
                "(Lnet/minecraft/resources/ResourceKey;Ljava/lang/String;)L" + DEFERRED + ";", false);
        mv.visitVarInsn(ASTORE, 1);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitLdcInsn(SERIALIZER_NAME);
        mv.visitTypeInsn(NEW, MODIFIER);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, MODIFIER, "<init>", "()V", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, DEFERRED, "register",
                "(Ljava/lang/String;Ljava/util/function/Supplier;)Lnet/minecraftforge/registries/RegistryObject;",
                false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext", "get",
                "()Lnet/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext",
                "getModEventBus", "()L" + EVENT_BUS + ";", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, DEFERRED, "register", "(L" + EVENT_BUS + ";)V", false);
        mv.visitLabel(end);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(handler);
        mv.visitVarInsn(ASTORE, 0);
        report(mv, "[Retromod] Could not register the biome modifier for an old BiomeLoadingEvent handler: ");
        mv.visitLabel(done);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** In the add phase, posts the stand-in event with this biome's name and builders. */
    private static void writeModify(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "modify", "(Lnet/minecraft/core/Holder;L" + PHASE
                + ";L" + BIOME_INFO_BUILDER + ";)V", null, null);
        mv.visitCode();
        Label skip = new Label();
        mv.visitVarInsn(ALOAD, 2);
        mv.visitFieldInsn(GETSTATIC, PHASE, "ADD", "L" + PHASE + ";");
        mv.visitJumpInsn(IF_ACMPNE, skip);

        Label unnamed = new Label();
        Label named = new Label();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/core/Holder", "unwrapKey", "()Ljava/util/Optional;", true);
        mv.visitVarInsn(ASTORE, 4);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false);
        mv.visitJumpInsn(IFEQ, unnamed);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "get", "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(CHECKCAST, "net/minecraft/resources/ResourceKey");
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/resources/ResourceKey", "location", "()L" + LOCATION + ";", false);
        mv.visitJumpInsn(GOTO, named);
        mv.visitLabel(unnamed);
        mv.visitInsn(ACONST_NULL);
        mv.visitLabel(named);
        mv.visitVarInsn(ASTORE, 5);

        mv.visitTypeInsn(NEW, EVENT);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, BIOME_INFO_BUILDER, "getGenerationSettings", "()L" + GENERATION + ";", false);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, BIOME_INFO_BUILDER, "getMobSpawnSettings", "()L" + SPAWNS + ";", false);
        mv.visitMethodInsn(INVOKESPECIAL, EVENT, "<init>", eventConstructor(), false);
        mv.visitVarInsn(ASTORE, 6);

        // One failing handler, such as one that reads a feature removed since 1.18, must not stop
        // the server from building its biomes. Report it once and keep what was already added.
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        mv.visitLabel(start);
        mv.visitFieldInsn(GETSTATIC, "net/minecraftforge/common/MinecraftForge", "EVENT_BUS", "L" + EVENT_BUS + ";");
        mv.visitVarInsn(ALOAD, 6);
        mv.visitMethodInsn(INVOKEINTERFACE, EVENT_BUS, "post", "(L" + FORGE_EVENT + ";)Z", true);
        mv.visitInsn(POP);
        mv.visitLabel(end);
        mv.visitJumpInsn(GOTO, skip);
        mv.visitLabel(handler);
        mv.visitVarInsn(ASTORE, 7);
        mv.visitFieldInsn(GETSTATIC, MODIFIER, "warned", "Z");
        mv.visitJumpInsn(IFNE, skip);
        mv.visitInsn(ICONST_1);
        mv.visitFieldInsn(PUTSTATIC, MODIFIER, "warned", "Z");
        mv.visitVarInsn(ALOAD, 7);
        mv.visitVarInsn(ASTORE, 0);
        report(mv, "[Retromod] An old BiomeLoadingEvent handler failed, so some of its spawns or features "
                + "may be missing: ");
        mv.visitLabel(skip);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Prints {@code message} and the throwable in local 0 to standard error, which Forge logs. */
    private static void report(MethodVisitor mv, String message) {
        mv.visitFieldInsn(GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
        mv.visitLdcInsn(message);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
    }

    /**
     * Wires the modifier when this host registered it. On a host without it, such as NeoForge, the
     * injected call would name a class the embed step cannot supply.
     */
    public static int wire(Path modRoot, RetromodTransformer transformer) throws IOException {
        if (!transformer.getSyntheticClasses().containsKey(MODIFIER)) return 0;
        return wire(modRoot);
    }

    /**
     * Wires the modifier into an extracted, already transformed mod that subscribes to the old
     * event: every {@code @Mod} constructor registers the serializer first, and each mod id gets a
     * data entry that names it. Returns the number of mod ids wired.
     */
    public static int wire(Path modRoot) throws IOException {
        byte[] eventName = EVENT.getBytes(StandardCharsets.ISO_8859_1);
        boolean subscribes = false;
        Map<Path, String> modClasses = new LinkedHashMap<>();
        List<Path> classes;
        try (Stream<Path> walk = Files.walk(modRoot)) {
            classes = walk.filter(p -> p.toString().endsWith(".class")).toList();
        }
        for (Path file : classes) {
            byte[] bytes = Files.readAllBytes(file);
            if (!subscribes && contains(bytes, eventName)) subscribes = true;
            String modId = modId(bytes);
            if (modId != null) modClasses.put(file, modId);
        }
        if (!subscribes || modClasses.isEmpty()) return 0;

        List<String> wired = new ArrayList<>();
        for (Map.Entry<Path, String> modClass : modClasses.entrySet()) {
            Files.write(modClass.getKey(), registerInConstructors(Files.readAllBytes(modClass.getKey())));
            String modId = modClass.getValue();
            if (wired.contains(modId)) continue;
            Path entry = modRoot.resolve("data").resolve(modId).resolve("forge").resolve("biome_modifier")
                    .resolve(SERIALIZER_NAME + ".json");
            Files.createDirectories(entry.getParent());
            Files.writeString(entry, "{\n  \"type\": \"" + modId + ":" + SERIALIZER_NAME + "\"\n}\n");
            wired.add(modId);
        }
        LOGGER.info("Posting the old BiomeLoadingEvent through a biome modifier for {}", wired);
        return wired.size();
    }

    /** The {@code @Mod} id a class declares, or null. */
    static String modId(byte[] classBytes) {
        if (!contains(classBytes, MOD_ANNOTATION.getBytes(StandardCharsets.ISO_8859_1))) return null;
        String[] id = new String[1];
        new ClassReader(classBytes).accept(new ClassVisitor(ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (!MOD_ANNOTATION.equals(desc)) return null;
                return new AnnotationVisitor(ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        if ("value".equals(name) && value instanceof String s && !s.isBlank()) id[0] = s;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return id[0];
    }

    /** Calls the serializer registration first in every constructor. A static call may precede super(). */
    static byte[] registerInConstructors(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"<init>".equals(name)) return mv;
                return new MethodVisitor(ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, MODIFIER, "registerSerializer", "()V", false);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
