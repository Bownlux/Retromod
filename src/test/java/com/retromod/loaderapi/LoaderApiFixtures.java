/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Old Fabric mod jars that call the most used gap shapes, with the same bytes on every run. */
public final class LoaderApiFixtures {

    static final String API = "net/fabricmc/fabric/api/";
    static final String SERVER_PLAY = API + "networking/v1/ServerPlayNetworking";
    static final String SERVER_HANDLER = SERVER_PLAY + "$PlayChannelHandler";
    static final String CLIENT_HANDLER = API + "client/networking/v1/ClientPlayNetworking$PlayChannelHandler";
    static final String PACKET_SENDER = API + "networking/v1/PacketSender";
    static final String LAYER_MAP = API + "blockrenderlayer/v1/BlockRenderLayerMap";
    static final String HUD = API + "client/rendering/v1/HudRenderCallback";
    static final String COLORS = API + "client/rendering/v1/ColorProviderRegistry";
    static final String TOOLTIP = API + "client/item/v1/ItemTooltipCallback";
    static final String FLUID_VARIANT = API + "transfer/v1/fluid/FluidVariant";
    static final String ENTITY_BUILDER = API + "object/builder/v1/entity/FabricEntityTypeBuilder";
    static final String EVENT = API + "event/Event";
    static final String FABRIC_LOADER = "net/fabricmc/loader/api/FabricLoader";

    // Fixed entry times keep the golden hashes from the 1.4.0-snapshot.2 CLI comparable.
    private static final long FIXED_TIME = 1_600_000_000_000L;

    private LoaderApiFixtures() {}

    /** Writes every fixture jar into {@code args[0]}, for a golden refresh. */
    public static void main(String[] args) throws IOException {
        // java -cp target/test-classes:target/classes:<asm> com.retromod.loaderapi.LoaderApiFixtures <dir>
        Path dir = Path.of(args[0]);
        Files.createDirectories(dir);
        Files.write(dir.resolve("loaderapi-fixture-fabric.jar"), fabricJar());
        Files.write(dir.resolve("loaderapi-fixture-quilt.jar"), quiltJar());
    }

    /** The Fabric fixture: every shape plus a nested library jar. */
    public static byte[] fabricJar() throws IOException {
        Map<String, byte[]> entries = commonEntries();
        entries.put("fabric.mod.json", fabricModJson("loaderapi_fixture", true));
        entries.put("META-INF/jars/loaderapi-fixture-lib.jar", libraryJar());
        return jar(entries);
    }

    /** The same classes declared through {@code quilt.mod.json}. */
    public static byte[] quiltJar() throws IOException {
        Map<String, byte[]> entries = commonEntries();
        entries.put("quilt.mod.json", ("{\"schema_version\":1,\"quilt_loader\":{\"group\":\"fixture\","
                + "\"id\":\"loaderapi_fixture\",\"version\":\"1.0.0\",\"depends\":["
                + "{\"id\":\"minecraft\",\"versions\":\"1.20.1\"}]},"
                + "\"mixin\":\"loaderapi_fixture.mixins.json\"}").getBytes(StandardCharsets.UTF_8));
        return jar(entries);
    }

    /** A library jar whose class registers a raw play channel receiver. */
    public static byte[] libraryJar() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        entries.put("fabric.mod.json", fabricModJson("loaderapi_fixture_lib", false));
        entries.put("fixturelib/Lib.class", libraryClass());
        return jar(entries);
    }

    private static Map<String, byte[]> commonEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        entries.put("fixture/Calls.class", callsClass());
        entries.put("fixture/Handler.class", handlerClass());
        entries.put("fixture/mixin/ExampleMixin.class", mixinClass());
        entries.put("loaderapi_fixture.mixins.json", ("{\"required\":true,\"package\":\"fixture.mixin\","
                + "\"compatibilityLevel\":\"JAVA_17\",\"client\":[\"ExampleMixin\"],"
                + "\"refmap\":\"loaderapi_fixture-refmap.json\"}").getBytes(StandardCharsets.UTF_8));
        entries.put("loaderapi_fixture-refmap.json", ("{\"mappings\":{\"fixture/mixin/ExampleMixin\":"
                + "{\"method_1574\":\"Lnet/minecraft/class_310;method_1574()V\"}},\"data\":{"
                + "\"named:intermediary\":{\"fixture/mixin/ExampleMixin\":{\"method_1574\":"
                + "\"Lnet/minecraft/class_310;method_1574()V\"}}}}").getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    private static byte[] fabricModJson(String id, boolean mixins) {
        return ("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\","
                + "\"environment\":\"*\",\"depends\":{\"minecraft\":\"1.20.1\","
                + "\"fabricloader\":\">=0.14.21\",\"fabric-api\":\"*\"}"
                + (mixins ? ",\"mixins\":[\"loaderapi_fixture.mixins.json\"]" : "")
                + "}").getBytes(StandardCharsets.UTF_8);
    }

    /** Writes a jar with fixed entry times, so the same entries always give the same bytes. */
    public static byte[] jar(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(FIXED_TIME);
                out.putNextEntry(jarEntry);
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    // ---- Classes -----------------------------------------------------------------------------

    public static byte[] callsClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "fixture/Calls", null,
                "java/lang/Object", null);

        // ServerPlayNetworking.send(ServerPlayer, Identifier, FriendlyByteBuf), 34 mods.
        MethodVisitor mv = method(cw, "serverSend",
                "(Lnet/minecraft/class_3222;Lnet/minecraft/class_2960;Lnet/minecraft/class_2540;)V");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SERVER_PLAY, "send",
                "(Lnet/minecraft/class_3222;Lnet/minecraft/class_2960;Lnet/minecraft/class_2540;)V", false);
        end(mv, Opcodes.RETURN);

        // registerGlobalReceiver with a lambda handler, 30 mods.
        mv = method(cw, "registerServer", "(Lnet/minecraft/class_2960;)Z");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        lambda(mv, SERVER_HANDLER, "receive", "(Lnet/minecraft/server/MinecraftServer;"
                + "Lnet/minecraft/class_3222;Lnet/minecraft/class_3244;Lnet/minecraft/class_2540;L"
                + PACKET_SENDER + ";)V", "fixture/Calls", "lambda$server$0");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SERVER_PLAY, "registerGlobalReceiver",
                "(Lnet/minecraft/class_2960;L" + SERVER_HANDLER + ";)Z", false);
        end(mv, Opcodes.IRETURN);
        lambdaBody(cw, "lambda$server$0", "(Lnet/minecraft/server/MinecraftServer;"
                + "Lnet/minecraft/class_3222;Lnet/minecraft/class_3244;Lnet/minecraft/class_2540;L"
                + PACKET_SENDER + ";)V");

        // BlockRenderLayerMap.INSTANCE.putBlock(Block, RenderType), a field read and a call.
        mv = method(cw, "layers", "(Lnet/minecraft/class_2248;Lnet/minecraft/class_1921;)V");
        mv.visitFieldInsn(Opcodes.GETSTATIC, LAYER_MAP, "INSTANCE", "L" + LAYER_MAP + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LAYER_MAP, "putBlock",
                "(Lnet/minecraft/class_2248;Lnet/minecraft/class_1921;)V", true);
        end(mv, Opcodes.RETURN);

        // HudRenderCallback.EVENT.register(lambda).
        mv = method(cw, "hud", "()V");
        mv.visitFieldInsn(Opcodes.GETSTATIC, HUD, "EVENT", "L" + EVENT + ";");
        lambda(mv, HUD, "onHudRender", "(Lnet/minecraft/class_332;F)V", "fixture/Calls", "lambda$hud$1");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "register", "(Ljava/lang/Object;)V", false);
        end(mv, Opcodes.RETURN);
        lambdaBody(cw, "lambda$hud$1", "(Lnet/minecraft/class_332;F)V");

        // ColorProviderRegistry.BLOCK.register(provider, blocks...).
        mv = method(cw, "colors", "(Ljava/lang/Object;[Ljava/lang/Object;)V");
        mv.visitFieldInsn(Opcodes.GETSTATIC, COLORS, "BLOCK", "L" + COLORS + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, COLORS, "register",
                "(Ljava/lang/Object;[Ljava/lang/Object;)V", true);
        end(mv, Opcodes.RETURN);

        // FabricLoader.getModContainer, which the version spoofer answers on every path.
        mv = method(cw, "modContainer", "()Ljava/util/Optional;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, FABRIC_LOADER, "getInstance",
                "()L" + FABRIC_LOADER + ";", true);
        mv.visitLdcInsn("minecraft");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FABRIC_LOADER, "getModContainer",
                "(Ljava/lang/String;)Ljava/util/Optional;", true);
        end(mv, Opcodes.ARETURN);

        // FluidVariant.getNbt, a member declared on TransferVariant called through a subtype.
        mv = method(cw, "fluidNbt", "(L" + FLUID_VARIANT + ";)Lnet/minecraft/class_2487;");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FLUID_VARIANT, "getNbt",
                "()Lnet/minecraft/class_2487;", true);
        end(mv, Opcodes.ARETURN);

        // ItemTooltipCallback.EVENT, a field read only.
        mv = method(cw, "tooltipEvent", "()L" + EVENT + ";");
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOOLTIP, "EVENT", "L" + EVENT + ";");
        end(mv, Opcodes.ARETURN);

        // FabricEntityTypeBuilder.create().build().
        mv = method(cw, "entityType", "()Lnet/minecraft/class_1299;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ENTITY_BUILDER, "create",
                "()L" + ENTITY_BUILDER + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY_BUILDER, "build",
                "()Lnet/minecraft/class_1299;", false);
        end(mv, Opcodes.ARETURN);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class that overrides the old client handler instead of using a lambda. */
    static byte[] handlerClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "fixture/Handler", null,
                "java/lang/Object", new String[]{CLIENT_HANDLER});
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        end(init, Opcodes.RETURN);
        MethodVisitor receive = cw.visitMethod(Opcodes.ACC_PUBLIC, "receive",
                "(Lnet/minecraft/class_310;Lnet/minecraft/class_634;Lnet/minecraft/class_2540;L"
                        + PACKET_SENDER + ";)V", null, null);
        receive.visitCode();
        end(receive, Opcodes.RETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A client mixin into MinecraftClient's tick, by intermediary selector. */
    static byte[] mixinClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER,
                "fixture/mixin/ExampleMixin", null, "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType("net/minecraft/class_310"));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "onTick",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        AnnotationVisitor inject = mv.visitAnnotation(
                "Lorg/spongepowered/asm/mixin/injection/Inject;", false);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, "method_1574");
        methods.visitEnd();
        AnnotationVisitor at = inject.visitArray("at");
        AnnotationVisitor head = at.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        head.visit("value", "HEAD");
        head.visitEnd();
        at.visitEnd();
        inject.visitEnd();
        mv.visitCode();
        end(mv, Opcodes.RETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The nested library's class: a raw channel receiver registered through a lambda. */
    public static byte[] libraryClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "fixturelib/Lib", null,
                "java/lang/Object", null);
        MethodVisitor mv = method(cw, "register", "(Lnet/minecraft/class_2960;)Z");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        lambda(mv, SERVER_HANDLER, "receive", "(Lnet/minecraft/server/MinecraftServer;"
                + "Lnet/minecraft/class_3222;Lnet/minecraft/class_3244;Lnet/minecraft/class_2540;L"
                + PACKET_SENDER + ";)V", "fixturelib/Lib", "lambda$register$0");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SERVER_PLAY, "registerGlobalReceiver",
                "(Lnet/minecraft/class_2960;L" + SERVER_HANDLER + ";)Z", false);
        end(mv, Opcodes.IRETURN);
        lambdaBody(cw, "lambda$register$0", "(Lnet/minecraft/server/MinecraftServer;"
                + "Lnet/minecraft/class_3222;Lnet/minecraft/class_3244;Lnet/minecraft/class_2540;L"
                + PACKET_SENDER + ";)V");
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ---- Bytecode helpers --------------------------------------------------------------------

    public static MethodVisitor method(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc,
                null, null);
        mv.visitCode();
        return mv;
    }

    public static void end(MethodVisitor mv, int returnOpcode) {
        mv.visitInsn(returnOpcode);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Pushes a lambda implementing {@code iface.sam(samDesc)} by a static method of {@code owner}. */
    public static void lambda(MethodVisitor mv, String iface, String sam, String samDesc, String owner,
            String body) {
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        mv.visitInvokeDynamicInsn(sam, "()L" + iface + ";", metafactory, Type.getType(samDesc),
                new Handle(Opcodes.H_INVOKESTATIC, owner, body, samDesc, false),
                Type.getType(samDesc));
    }

    public static void lambdaBody(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC
                | Opcodes.ACC_SYNTHETIC, name, desc, null, null);
        mv.visitCode();
        end(mv, Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
    }
}
