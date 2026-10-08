/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.forge.ForgeRegistryPolyfill;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * The 1.12.2 event bus and registry bridge: the annotations the host must not see, the
 * {@code EVENT_BUS} reads, {@code setRegistryName}, the registry event stand-in, the handler
 * table and the builder setters.
 */
class Forge1122EventBridgeTest {

    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String RL = "net/minecraft/resources/ResourceLocation";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.resetForTesting();
        Forge1122EventBridge.resetForTesting();
    }

    private static RetromodTransformer bridged() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.register(t);
        Forge1122EventBridge.register(t);
        return t;
    }

    /** One public class {@code test/Legacy} with methods written by {@code body}. */
    private static byte[] legacyClass(String superName, Consumer<ClassWriter> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Legacy", null, superName, null);
        body.accept(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void method(ClassWriter cw, int access, String name, String desc, String signature,
                               Consumer<MethodVisitor> code) {
        MethodVisitor mv = cw.visitMethod(access, name, desc, signature, null);
        mv.visitCode();
        code.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    private static MethodNode method(ClassNode cn, String name) {
        return cn.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<MethodInsnNode> calls(MethodNode m) {
        List<MethodInsnNode> out = new ArrayList<>();
        for (AbstractInsnNode in : m.instructions.toArray()) {
            if (in instanceof MethodInsnNode mi) out.add(mi);
        }
        return out;
    }

    @Test
    void setRegistryNameOnAModBlockCallsTheBridge() {
        bridged();
        byte[] in = legacyClass("java/lang/Object", cw -> method(cw, ACC_PUBLIC | ACC_STATIC, "make",
                "(Ltest/ModBlock;)Ljava/lang/Object;", null, mv -> {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitLdcInsn("ruby_block");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "test/ModBlock", "setRegistryName",
                            "(Ljava/lang/String;)Ljava/lang/Object;", false);
                    mv.visitInsn(ARETURN);
                }));

        MethodInsnNode call = calls(method(read(Forge1122EventBridge.rewriteLegacyCalls(in)), "make")).get(0);

        assertEquals(INVOKESTATIC, call.getOpcode(), "the name must go to the side table, not the block");
        assertEquals(Forge1122EventBridge.EVENTS, call.owner);
        assertEquals("(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", call.desc);
    }

    @Test
    void getRegistryNameKeepsItsResourceLocationType() {
        bridged();
        byte[] in = legacyClass("java/lang/Object", cw -> method(cw, ACC_PUBLIC | ACC_STATIC, "name",
                "(L" + BLOCK + ";)L" + RL + ";", null, mv -> {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitMethodInsn(INVOKEVIRTUAL, BLOCK, "getRegistryName", "()L" + RL + ";", false);
                    mv.visitInsn(ARETURN);
                }));

        MethodNode name = method(read(Forge1122EventBridge.rewriteLegacyCalls(in)), "name");
        MethodInsnNode call = calls(name).get(0);

        assertEquals(Forge1122EventBridge.EVENTS, call.owner);
        assertTrue(call.getNext() instanceof TypeInsnNode cast && cast.desc.equals(RL),
                "the Object result must be cast back so the verifier accepts the return");
    }

    @Test
    void registryNameOfAForgeRegistryStaysAHostCall() {
        bridged();
        String registry = "net/minecraftforge/registries/IForgeRegistry";
        byte[] in = legacyClass("java/lang/Object", cw -> method(cw, ACC_PUBLIC | ACC_STATIC, "name",
                "(L" + registry + ";)L" + RL + ";", null, mv -> {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitMethodInsn(INVOKEINTERFACE, registry, "getRegistryName", "()L" + RL + ";", true);
                    mv.visitInsn(ARETURN);
                }));

        MethodInsnNode call = calls(method(read(Forge1122EventBridge.rewriteLegacyCalls(in)), "name")).get(0);

        assertEquals(registry, call.owner,
                "Forge 1.20.1 declares IForgeRegistry.getRegistryName, so a newer mod's call must reach it");
        assertEquals(INVOKEINTERFACE, call.getOpcode());
    }

    @Test
    void registryNameDeclaredOnAPackagePrivateModClassIsStillRead() {
        Object entry = new PackagePrivateEntry();

        assertEquals("oldmod:own_name",
                com.retromod.shim.forge.embedded.LegacyForgeEvents.getRegistryName(entry),
                "the mod's own getRegistryName must win even when its class is not public");
    }

    /** A mod entry that names itself, in a class the bridge's package cannot reach directly. */
    static final class PackagePrivateEntry {
        public Object getRegistryName() {
            return "oldmod:own_name";
        }
    }

    @Test
    void legacyEventBusReadsResolveToTheBridgeBus() {
        bridged();
        String bus = "L" + Forge1122EventBridge.BUS + ";";
        byte[] in = legacyClass("java/lang/Object", cw -> method(cw, ACC_PUBLIC | ACC_STATIC, "init",
                "()V", null, mv -> {
                    mv.visitFieldInsn(GETSTATIC, "net/neoforged/neoforge/common/NeoForge", "EVENT_BUS", bus);
                    mv.visitTypeInsn(NEW, "test/Legacy");
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, "test/Legacy", "<init>", "()V", false);
                    mv.visitMethodInsn(INVOKEVIRTUAL, Forge1122EventBridge.BUS, "register",
                            "(Ljava/lang/Object;)V", false);
                    mv.visitInsn(RETURN);
                }));

        MethodNode init = method(read(Forge1122EventBridge.rewriteLegacyCalls(in)), "init");
        FieldInsnNode read = (FieldInsnNode) init.instructions.getFirst();

        assertEquals(Forge1122EventBridge.BUS, read.owner,
                "the host field has a different type, so the read must move to the bridge bus");
        assertEquals("GAME", read.name);
    }

    @Test
    void builderSettersAreDroppedAndKeepTheReceiver() {
        bridged();
        byte[] in = legacyClass("java/lang/Object", cw -> method(cw, ACC_PUBLIC | ACC_STATIC, "build",
                "(L" + BLOCK + ";)L" + BLOCK + ";", null, mv -> {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitLdcInsn(3.0f);
                    mv.visitMethodInsn(INVOKEVIRTUAL, BLOCK, "func_149711_c", "(F)L" + BLOCK + ";", false);
                    mv.visitLdcInsn("ruby");
                    mv.visitMethodInsn(INVOKEVIRTUAL, BLOCK, "func_149663_c",
                            "(Ljava/lang/String;)L" + BLOCK + ";", false);
                    mv.visitInsn(ARETURN);
                }));

        MethodNode build = method(read(Forge1122EventBridge.rewriteLegacyCalls(in)), "build");

        assertTrue(calls(build).isEmpty(), "1.12 setters do not exist on the host: " + calls(build));
        assertEquals(java.util.List.of(ALOAD, ARETURN), java.util.Arrays.stream(build.instructions.toArray())
                        .map(AbstractInsnNode::getOpcode).filter(op -> op >= 0).toList(),
                "a constant argument goes with its dropped setter, leaving the receiver");
    }

    @Test
    void materialSuperCallOnAVanillaBlockBaseGetsDefaultProperties() {
        bridged();
        String base = "net/minecraft/world/level/block/BaseEntityBlock";
        byte[] in = legacyClass(base, cw -> method(cw, ACC_PUBLIC, "<init>", "()V", null, mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKESPECIAL, base, "<init>",
                    "(Lnet/minecraft/block/material/Material;)V", false);
            mv.visitInsn(RETURN);
        }));

        List<MethodInsnNode> calls = calls(method(read(Forge1122EventBridge.rewriteLegacyCalls(in)), "<init>"));

        assertEquals("defaultBlockProperties", calls.get(0).name);
        assertEquals("(Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;)V",
                calls.get(1).desc, "the super call must use the constructor the host still has");
    }

    @Test
    void registryHandlersAreRecordedWithTheirRegistry() {
        bridged();
        String register = Forge1122EventBridge.REGISTER_EVENT;
        byte[] in = legacyClass("java/lang/Object", cw -> {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onItems", "(L" + register + ";)V",
                    "(L" + register + "<L" + ITEM + ";>;)V", null);
            mv.visitAnnotation("L" + Forge1122EventBridge.SUBSCRIBE + ";", true).visitEnd();
            mv.visitCode();
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 1);
            mv.visitEnd();
        });

        AnnotationNode table = read(Forge1122EventBridge.rewriteLegacyCalls(in)).visibleAnnotations.stream()
                .filter(a -> a.desc.equals("L" + Forge1122EventBridge.HANDLERS + ";"))
                .findFirst().orElseThrow(() -> new AssertionError("no handler table recorded"));

        assertEquals(List.of("E|s|onItems|(L" + register + ";)V|item|0"), table.values.get(1));
    }

    @Test
    void sidedProxyFieldsAreRecordedWithBothSides() {
        bridged();
        byte[] in = legacyClass("java/lang/Object", cw -> {
            var field = cw.visitField(ACC_PUBLIC | ACC_STATIC, "proxy", "Ltest/CommonProxy;", null, null);
            AnnotationVisitor proxy = field.visitAnnotation("L" + Forge1122EventBridge.SIDED_PROXY + ";", true);
            proxy.visit("clientSide", "test.ClientProxy");
            proxy.visit("serverSide", "test.CommonProxy");
            proxy.visitEnd();
            field.visitEnd();
        });

        AnnotationNode table = read(Forge1122EventBridge.rewriteLegacyCalls(in)).visibleAnnotations.stream()
                .filter(a -> a.desc.equals("L" + Forge1122EventBridge.HANDLERS + ";"))
                .findFirst().orElseThrow(() -> new AssertionError("no field table recorded"));

        assertEquals(List.of("P|s|proxy|Ltest/CommonProxy;|test.ClientProxy|test.CommonProxy"),
                table.values.get(1));
    }

    @Test
    void legacySubscriberAnnotationIsHiddenFromTheHost() {
        bridged();
        byte[] in = legacyClass("java/lang/Object", cw -> {
            AnnotationVisitor sub = cw.visitAnnotation("Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;", true);
            AnnotationVisitor sides = sub.visitArray("value");
            sides.visitEnum(null, "Lnet/minecraftforge/fml/relauncher/Side;", "CLIENT");
            sides.visitEnd();
            sub.visitEnd();
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onModels", "(Ljava/lang/Object;)V", null, null);
            mv.visitAnnotation("L" + Forge1122EventBridge.SUBSCRIBE + ";", true).visitEnd();
            mv.visitCode();
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 1);
            mv.visitEnd();
        });

        ClassNode out = read(Forge1122EventBridge.rewriteLegacyCalls(in));
        AnnotationNode marker = out.visibleAnnotations.stream()
                .filter(a -> a.desc.equals("L" + Forge1122EventBridge.SUBSCRIBER + ";"))
                .findFirst().orElseThrow(() -> new AssertionError("subscriber marker missing"));

        assertTrue(out.visibleAnnotations.stream().noneMatch(a -> a.desc.contains("Mod$EventBusSubscriber")),
                "Forge would auto-register the class and parse its Side values as Dist names");
        assertEquals(List.of("clientOnly", true), marker.values);
    }

    @Test
    void legacySubscriberRenamedByTheNeoForgeBridgeIsStillHidden() {
        bridged();
        // ForgeNeoForgeApiBridge renames Mod$EventBusSubscriber before this pass runs.
        byte[] in = legacyClass("java/lang/Object", cw -> {
            cw.visitAnnotation("Lnet/neoforged/fml/common/EventBusSubscriber;", true).visitEnd();
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onInput", "(Ljava/lang/Object;)V", null, null);
            mv.visitAnnotation("L" + Forge1122EventBridge.SUBSCRIBE + ";", true).visitEnd();
            mv.visitCode();
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 1);
            mv.visitEnd();
        });

        ClassNode out = read(Forge1122EventBridge.rewriteLegacyCalls(in));
        assertTrue(out.visibleAnnotations.stream().noneMatch(a -> a.desc.contains("EventBusSubscriber;")
                        && !a.desc.equals("L" + Forge1122EventBridge.SUBSCRIBER + ";")),
                "NeoForge would load the 1.12 class itself, and its client-only references fail on a server");
        assertTrue(out.visibleAnnotations.stream()
                        .anyMatch(a -> a.desc.equals("L" + Forge1122EventBridge.SUBSCRIBER + ";")),
                "the 1.12 bridge must still find the class through its own marker");
    }

    @Test
    void subscriberWithAHostHandlerKeepsTheHostAnnotation() {
        bridged();
        byte[] in = legacyClass("java/lang/Object", cw -> {
            cw.visitAnnotation("Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;", true).visitEnd();
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onTick", "(Ljava/lang/Object;)V", null, null);
            mv.visitAnnotation("Lnet/minecraftforge/eventbus/api/SubscribeEvent;", true).visitEnd();
            mv.visitCode();
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 1);
            mv.visitEnd();
        });

        assertSame(in, Forge1122EventBridge.rewriteLegacyCalls(in),
                "a 1.13 to 1.20 Forge subscriber must reach the host unchanged");
    }

    @Test
    void passIsInertUntilTheLegacyChainRegisters() {
        byte[] in = legacyClass("java/lang/Object", cw -> method(cw, ACC_PUBLIC | ACC_STATIC, "make",
                "(Ljava/lang/Object;)V", null, mv -> {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitLdcInsn("x");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "test/ModBlock", "setRegistryName",
                            "(Ljava/lang/String;)Ljava/lang/Object;", false);
                    mv.visitInsn(POP);
                    mv.visitInsn(RETURN);
                }));

        assertSame(in, Forge1122EventBridge.rewriteLegacyCalls(in));
    }

    @Test
    void lifecyclePolyfillNoLongerOverridesTheLifecycleBridge() {
        RetromodTransformer t = bridged();
        new ForgeRegistryPolyfill().registerPolyfills(t);

        assertEquals(Forge1122LifecycleSynthetics.PRE_EVENT,
                t.getClassRedirects().get("net/minecraftforge/fml/common/event/FMLPreInitializationEvent"),
                "an inert stand-in here means @Mod.EventHandler pre-init never runs");
        assertEquals("com/retromod/shim/forge/embedded/LegacySidedProxy",
                t.getClassRedirects().get("net/minecraftforge/fml/common/SidedProxy"));
    }

    /** A host event base for the stand-in to extend in this JVM. */
    public static class FakeHostEvent {
        public FakeHostEvent() {}
    }

    @Test
    void registerEventStandInHandsTheRegistrarToGetRegistry() throws Exception {
        String base = FakeHostEvent.class.getName().replace('.', '/');
        byte[] bytes = Forge1122EventBridge.registerEventBytes(base, false);
        // getRegistry() is typed with the host's IForgeRegistry; give this JVM one to resolve.
        ClassWriter registry = new ClassWriter(0);
        registry.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE,
                "net/minecraftforge/registries/IForgeRegistry", null, "java/lang/Object", null);
        registry.visitEnd();
        byte[] registryBytes = registry.toByteArray();
        Class<?> event = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                defineClass("net.minecraftforge.registries.IForgeRegistry", registryBytes, 0,
                        registryBytes.length);
                return defineClass(Forge1122EventBridge.REGISTER_EVENT.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();

        Consumer<Object> registrar = entry -> {};
        Object instance = event.getConstructor(Object.class).newInstance(registrar);

        assertSame(registrar, event.getMethod("getRegistry").invoke(instance));
        TypeVariable<?>[] params = event.getTypeParameters();
        assertEquals(1, params.length, "Register<Item> handlers need one type parameter to reflect");
    }

    @Test
    void registerEventStandInStaysAModBusEventForOlderModBusSubscribers() {
        String marker = "net/minecraftforge/fml/event/IModBusEvent";
        ClassNode event = read(Forge1122EventBridge.registerEventBytes(
                "net/minecraftforge/eventbus/api/Event", false, marker));
        assertEquals("net/minecraftforge/eventbus/api/Event", event.superName);
        assertTrue(event.interfaces.contains(marker),
                "the bridge's Register replaces the marked stub; a 1.18.2 MOD-bus subscriber is "
                        + "rejected unless the parameter is an IModBusEvent");
        assertTrue(event.signature.startsWith("<T:Ljava/lang/Object;>")
                        && event.signature.endsWith("L" + marker + ";"),
                "the signature must keep the type parameter and list the marker: " + event.signature);
    }

    @Test
    void legacyItemStackFactoriesCoverEveryOldShape() {
        ClassNode factories = read(Forge1122EventBridge.itemStackFactoryBytes());
        Function<String, Boolean> has = desc -> factories.methods.stream().anyMatch(m -> m.desc.equals(desc));
        String like = "Lnet/minecraft/world/level/ItemLike;";
        String stack = "Lnet/minecraft/world/item/ItemStack;";

        assertTrue(has.apply("(" + like + ")" + stack));
        assertTrue(has.apply("(" + like + "I)" + stack));
        assertTrue(has.apply("(" + like + "II)" + stack), "the 1.12 metadata form needs a factory too");
    }
}
