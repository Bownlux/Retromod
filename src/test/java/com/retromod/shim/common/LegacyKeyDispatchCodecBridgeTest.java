/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mixin.MixinCompatibilityTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.google.gson.JsonObject;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.retromod.shim.common.LegacyKeyDispatchCodecBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** TerraBlender's, Lithostitched's and Citadel's 1.20.1 KeyDispatchDataCodec calls on 1.20.5 and newer hosts. */
public class LegacyKeyDispatchCodecBridgeTest {

    private static final String KDDC = MOJANG.owner();
    private static final String L_KDDC = "L" + KDDC + ";";
    private static final String L_CODEC = "L" + CODEC + ";";
    private static final String L_MAP_CODEC = "L" + MAP_CODEC + ";";
    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String ID = "net/minecraft/resources/ResourceLocation";
    private static final String BOOTSTRAP = "net/minecraft/core/registries/BuiltInRegistries$RegistryBootstrap";
    public static final String MIXIN_NAME = "terrablender/mixin/MixinBuiltInRegistries";
    public static final String RULE = "terrablender/worldgen/surface/NamespacedSurfaceRuleSource";
    public static final String MARKER = "dev/worldgen/lithostitched/worldgen/densityfunction/WrappedMarkerDensityFunction";
    public static final String WRAPPER = "com/github/alexthe666/citadel/Citadel";
    private static final String HOST_SITES = "com/github/alexthe666/citadel/HostSites";
    public static final String RULE_SOURCE = LEVELGEN + "SurfaceRules$RuleSource";
    private static final String SUPPLIER_DESC = "Ljava/util/function/Supplier;";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private String savedTarget;

    @BeforeEach
    void save() {
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("TerraBlender's mixin lambda registers the host's own map codec on 26.2")
    void registrationGetsTheHostMapCodec() {
        registerOffline("26.2");

        MethodNode lambda = method(read(transformer.transformClass(terraBlenderMixin(L_KDDC, REGISTRY, ID), MIXIN_NAME)),
                "lambda$registerSimple$0");

        assertEquals(List.of(KDDC + ".codec()" + L_MAP_CODEC), codecReads(lambda),
                "the registry holds map codecs and finds a value's id by identity, so the value must be the host's own");
        assertFalse(calls(lambda, HELPER), "mixin code merged into BuiltInRegistries calls only the host");
        verify(lambda, MIXIN_NAME);
    }

    @Test
    @DisplayName("The mixin pipeline keeps the repaired call")
    void theMixinPassesKeepTheRepair() {
        registerOffline("26.2");
        byte[] remapped = transformer.transformClass(terraBlenderMixin(L_KDDC, REGISTRY, ID), MIXIN_NAME);

        byte[] repaired = new MixinCompatibilityTransformer(transformer).applyPostRemapRepairs(remapped);

        assertEquals(List.of(KDDC + ".codec()" + L_MAP_CODEC),
                codecReads(method(read(repaired), "lambda$registerSimple$0")));
    }

    @Test
    @DisplayName("A codec used as a Codec, or bound to a method reference, gets the old Codec")
    void codecUsesGoThroughTheHelper() {
        registerOffline("26.2");

        ClassNode rule = read(transformer.transformClass(surfaceRule(L_KDDC), RULE));

        for (String name : new String[]{"read", "bound"}) {
            MethodNode method = method(rule, name);
            assertEquals(List.of(HELPER + ".codec(" + L_KDDC + ")" + L_CODEC), helperCalls(method), name);
            assertTrue(codecReads(method).isEmpty(), name + " must not read the map codec directly");
            verify(method, RULE);
        }
    }

    @Test
    @DisplayName("of(Codec) and new KeyDispatchDataCodec(Codec) build through the helper")
    void oldFactoryAndConstructorUseTheHelper() {
        registerOffline("26.2");

        ClassNode rule = read(transformer.transformClass(surfaceRule(L_KDDC), RULE));

        assertEquals(List.of(HELPER + ".of(" + L_CODEC + ")" + L_KDDC), helperCalls(method(rule, "<clinit>")));
        MethodNode created = method(rule, "create");
        assertEquals(List.of(HELPER + ".of(" + L_CODEC + ")" + L_KDDC), helperCalls(created));
        assertTrue(created.instructions.size() > 0 && noNew(created, KDDC), "the record constructor is not called");
        verify(created, RULE);
    }

    @Test
    @DisplayName("The helper unwraps a record codec and reads any other codec under a value key or inline, as 1.20.4's dispatch did")
    void helperKeepsTheOldDispatchShape() {
        ClassNode helper = read(generateHelper(MOJANG, true));

        List<String> record = List.of("instanceof " + MAP_CODEC_CODEC, "cast " + MAP_CODEC_CODEC,
                MAP_CODEC_CODEC + ".codec", "\"value\"", CODEC + ".fieldOf");
        List<String> both = new ArrayList<>(record);
        both.addAll(List.of(MAP_CODEC + ".assumeMapUnsafe", CODEC + ".mapEither", "Either.unwrap", "Either.left",
                MAP_CODEC + ".xmap"));
        assertEquals(both, steps(method(helper, "mapCodec")));
        assertEquals(List.of(HELPER + ".mapCodec", KDDC + ".of"), callsIn(method(helper, "of")));
        assertEquals(List.of(KDDC + ".codec", MAP_CODEC + ".codec"), callsIn(method(helper, "codec")));
        for (MethodNode method : helper.methods) verify(method, HELPER);

        // DataFixerUpper 6 has no assumeMapUnsafe, so a host with it keeps the value key alone.
        assertEquals(record, steps(method(read(generateHelper(MOJANG, false)), "mapCodec")));
    }

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource({"value, 26.2", "supplier, 26.2", "mapCodec, 26.2", "value, 1.21.1", "value, 26.3", "mapCodec, 26.3"})
    @DisplayName("Lithostitched: of(Codec.unit(x)) and of(mapCodec.codec()) build from the map codec itself")
    void unitCodecsBuildFromTheMapCodec(String made, String host) {
        registerOffline(host);
        LegacyDfuRenames.register(transformer, name -> null, host);
        String holder = host.equals("26.3") ? STAND_IN : KDDC;

        MethodNode init = method(read(transformer.transformClass(unitMarker(made), MARKER)), "<clinit>");

        String unit = MAP_CODEC + ".unit(" + (made.equals("supplier") ? SUPPLIER_DESC : "Ljava/lang/Object;") + ")"
                + L_MAP_CODEC;
        assertEquals(List.of(unit, holder + ".of(" + L_MAP_CODEC + ")L" + holder + ";"), calls(init),
                "DFU 9 made a unit map codec's codec() a plain codec, which the helper would read under a value key");
        verify(init, MARKER);
    }

    @Test
    @DisplayName("of(Codec.unit(x)) behind a jump keeps the helper, since another path joins between the calls")
    void unitCodecBehindAJumpKeepsTheHelper() {
        registerOffline("26.2");
        LegacyDfuRenames.register(transformer, name -> null, "26.2");
        ClassNode node = read(unitMarker("value"));
        MethodNode init = method(node, "<clinit>");
        // if (true) between the unit codec and of(), as a conditional expression would put it.
        AbstractInsnNode unit = init.instructions.get(1);
        LabelNode join = new LabelNode();
        InsnList jump = new InsnList();
        jump.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_1));
        jump.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFNE, join));
        jump.add(join);
        init.instructions.insert(unit, jump);
        // Both paths carry the same Codec, so the frame needs no common superclass.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);

        MethodNode transformed = method(read(transformer.transformClass(writer.toByteArray(), MARKER)), "<clinit>");

        assertEquals(List.of(HELPER + ".of(" + L_CODEC + ")" + L_KDDC), helperCalls(transformed));
    }

    @Test
    @DisplayName("On DataFixerUpper 10, a 1.20.1 marker built with of(Codec.unit(x)) reads data that has no value key")
    void unitMarkerReadsItsDataOnDfu10() throws Exception {
        registerOffline("26.2");
        LegacyDfuRenames.register(transformer, name -> null, "26.2");
        byte[] marker = transformer.transformClass(unitMarker("value"), MARKER);
        Dfu dfu = new Dfu(Map.of(MARKER, marker, HELPER, transformer.getSyntheticClasses().get(HELPER)));

        // Tectonic's remove_islands.json has only {"type": "lithostitched:wrapped_marker"}.
        Object read = dfu.type(MARKER).getMethod("read", Object.class).invoke(null, new JsonObject());

        assertEquals(Optional.of("marker"), dfu.result(read));
    }

    @Test
    @DisplayName("On DataFixerUpper 10, the helper reads a plain codec inline or under a value key, and keeps a record's map codec")
    void helperReadsBothShapesOnDfu10() throws Exception {
        Dfu lenient = new Dfu(Map.of(HELPER, generateHelper(MOJANG, true)));
        Object unit = lenient.call(MAP_CODEC, "unitCodec", new Class<?>[]{Object.class}, "marker");
        JsonObject wrapped = new JsonObject();
        wrapped.add("value", new JsonObject());

        assertEquals(Optional.of("marker"), lenient.parseThroughHelper(unit, new JsonObject()));
        assertEquals(Optional.of("marker"), lenient.parseThroughHelper(unit, wrapped));
        Object mapCodec = lenient.call(MAP_CODEC, "unit", new Class<?>[]{Object.class}, "record");
        Object recordCodec = lenient.type(MAP_CODEC_CODEC).getConstructor(lenient.type(MAP_CODEC)).newInstance(mapCodec);
        assertSame(mapCodec, lenient.helperMapCodec(recordCodec), "a record codec dispatches as its own map codec");

        Dfu valueKeyOnly = new Dfu(Map.of(HELPER, generateHelper(MOJANG, false)));
        Object oldUnit = valueKeyOnly.call(MAP_CODEC, "unitCodec", new Class<?>[]{Object.class}, "marker");
        assertEquals(Optional.empty(), valueKeyOnly.parseThroughHelper(oldUnit, new JsonObject()),
                "without the inline read, the data needs a value key it never had");
    }

    @Test
    @DisplayName("Citadel: a codec() method reference handed to a registration supplies the host's map codec")
    void codecMethodReferenceSuppliesTheMapCodec() throws Exception {
        registerOffline("26.2");

        byte[] wrapper = transformer.transformClass(citadelRegistration(), WRAPPER);

        ClassNode node = read(wrapper);
        InvokeDynamicInsnNode registered = indy(method(node, "registerAll"));
        assertEquals(new Handle(Opcodes.H_INVOKEVIRTUAL, KDDC, "codec", "()" + L_MAP_CODEC, false),
                registered.bsmArgs[1], "the registry holds map codecs and finds a value's id by identity");
        assertEquals(Type.getMethodType("()" + L_MAP_CODEC), registered.bsmArgs[2]);
        Handle kept = (Handle) indy(method(node, "kept")).bsmArgs[1];
        assertEquals(HELPER + ".codec", kept.getOwner() + "." + kept.getName(), "a supplier kept as a value still gives a Codec");
        for (MethodNode method : node.methods) verify(method, WRAPPER);

        // Run on stand-ins: the registration receives the very map codec the holder holds.
        Map<String, byte[]> classes = new HashMap<>(Map.of(WRAPPER, wrapper, KDDC, hostKeyDispatchCodec(),
                MAP_CODEC, emptyClass(MAP_CODEC)));
        ClassLoader loader = new ChildFirstLoader(new URL[0], classes);
        Object mapCodec = loader.loadClass(dotted(MAP_CODEC)).getConstructor().newInstance();
        Class<?> holderType = loader.loadClass(dotted(KDDC));
        Class<?> owner = loader.loadClass(dotted(WRAPPER));
        owner.getField("CODEC").set(null, holderType.getMethod("of", mapCodec.getClass()).invoke(null, mapCodec));
        assertSame(mapCodec, owner.getMethod("registerAll").invoke(null));
    }

    @Test
    @DisplayName("26.2: Minecraft's codec() on a 1.20.1 rule source returns the map codec its old codec() holds")
    void ruleSourceAnswersTheHostAccessor() throws Exception {
        registerOffline("26.2");
        registerAccessorBridges(transformer);
        String legacy = "com/retromod/generated/LegacyRuleSourceCodec";
        String impl = "terrablender/worldgen/surface/Rule";

        byte[] rule = transformer.transformClass(ruleSourceImpl(impl, RULE_SOURCE), impl);

        assertEquals(List.of(legacy), read(rule).interfaces, "the class gets the bridge through its interface");
        Map<String, byte[]> classes = new HashMap<>(Map.of(impl, rule, legacy,
                transformer.getSyntheticClasses().get(legacy), RULE_SOURCE, hostRuleInterface(RULE_SOURCE),
                KDDC, hostKeyDispatchCodec(), MAP_CODEC, emptyClass(MAP_CODEC)));
        ClassLoader loader = new ChildFirstLoader(new URL[0], classes);
        Object mapCodec = loader.loadClass(dotted(MAP_CODEC)).getConstructor().newInstance();
        Class<?> type = loader.loadClass(dotted(impl));
        type.getField("CODEC").set(null,
                loader.loadClass(dotted(KDDC)).getMethod("of", mapCodec.getClass()).invoke(null, mapCodec));
        Object source = type.getConstructor().newInstance();

        Object answered;
        try {
            answered = loader.loadClass(dotted(RULE_SOURCE)).getMethod("codec").invoke(source);
        } catch (InvocationTargetException e) {
            throw new AssertionError("Minecraft's codec() failed on the mod's rule source", e.getCause());
        }
        assertSame(mapCodec, answered);
    }

    @Test
    @DisplayName("A class that lists a rule interface gets its own codec(), so two mods' bridge defaults never compete")
    void directImplementerGetsItsOwnAccessor() {
        registerOffline("26.2");
        registerAccessorBridges(transformer);
        String legacy = "com/retromod/generated/LegacyRuleSourceCodec";
        String impl = "terrablender/worldgen/surface/Rule";

        ClassNode rule = read(transformer.transformClass(ruleSourceImpl(impl, RULE_SOURCE), impl));

        MethodNode own = method(rule, "codec", "()" + L_MAP_CODEC);
        assertNotNull(own, "a class method wins over every interface default it reaches");
        assertEquals(List.of(legacy + ".codec()" + L_KDDC, KDDC + ".codec()" + L_MAP_CODEC), calls(own));
        verify(own, impl);

        // A source built for 26.2 already returns the map codec and keeps its own method.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(ruleSourceImpl(impl, RULE_SOURCE)).accept(cw, 0);
        MethodVisitor current = cw.visitMethod(Opcodes.ACC_PUBLIC, "codec", "()" + L_MAP_CODEC, null, null);
        current.visitCode();
        current.visitInsn(Opcodes.ACONST_NULL);
        current.visitInsn(Opcodes.ARETURN);
        current.visitMaxs(0, 0);
        current.visitEnd();
        ClassNode modern = read(transformer.transformClass(cw.toByteArray(), impl));
        assertEquals(1, modern.methods.stream().filter(m -> m.name.equals("codec") && m.desc.equals("()" + L_MAP_CODEC))
                .count());
        assertTrue(calls(method(modern, "codec", "()" + L_MAP_CODEC)).isEmpty(), "its own codec() is untouched");
    }

    @Test
    @DisplayName("A codec() method reference handed to the game or a loader utility keeps the old Codec")
    void hostSupplierSitesKeepTheCodec() {
        registerOffline("26.2");

        ClassNode node = read(transformer.transformClass(hostSupplierSites(), HOST_SITES));

        // Util.make and Forge's Lazy.of read the supplier back as the Codec it was.
        for (String kept : List.of("util", "lazy")) {
            Handle target = (Handle) indy(method(node, kept)).bsmArgs[1];
            assertEquals(HELPER + ".codec(" + L_KDDC + ")" + L_CODEC,
                    target.getOwner() + "." + target.getName() + target.getDesc(), kept);
        }
        // Forge's and NeoForge's deferred registers hold the dispatch codec.
        for (String registered : List.of("forgeRegister", "neoForgeRegister")) {
            assertEquals(new Handle(Opcodes.H_INVOKEVIRTUAL, KDDC, "codec", "()" + L_MAP_CODEC, false),
                    indy(method(node, registered)).bsmArgs[1], registered);
        }
        for (MethodNode method : node.methods) verify(method, HOST_SITES);
    }

    @Test
    @DisplayName("26.3: material rules, conditions and density functions get the bridge, under their old or new names")
    void the263HostBridgesEveryRuleInterface() {
        registerOffline("26.3");
        registerRemovedClass(transformer);
        registerAccessorBridges(transformer);
        // The 26.3 move Mc26_2To26_3CoreMoves registers with the others.
        transformer.registerClassRedirect(LEVELGEN + "DensityFunction", LEVELGEN + "densityfunction/DensityFunction");
        String legacy = "com/retromod/generated/LegacyDensityFunctionCodec";
        String impl = "dev/worldgen/tectonic/worldgen/densityfunction/ConfigConstant";

        ClassNode function = read(transformer.transformClass(ruleSourceImpl(impl, LEVELGEN + "DensityFunction"), impl));

        assertEquals(List.of(legacy), function.interfaces);
        assertNotNull(method(function, "codec", "()L" + STAND_IN + ";"), "the old accessor now returns the stand-in");
        MethodNode own = method(function, "codec", "()" + L_MAP_CODEC);
        assertNotNull(own, "the class has its own codec() as well");
        assertEquals(List.of(legacy + ".codec()L" + STAND_IN + ";", STAND_IN + ".codec()" + L_MAP_CODEC), calls(own));
        ClassNode bridge = read(transformer.getSyntheticClasses().get(legacy));
        // It also extends the rebuilt old interface, which compiles an old function from its compute.
        assertEquals(List.of(LEVELGEN + "densityfunction/DensityFunction", Legacy263DensityFunctionBridge.TYPE),
                bridge.interfaces);
        assertTrue(transformer.getSyntheticClasses().containsKey(Legacy263DensityFunctionBridge.TYPE),
                "the rebuilt interface travels with the bridge");
        assertEquals(List.of(legacy + ".codec()L" + STAND_IN + ";", STAND_IN + ".codec()" + L_MAP_CODEC),
                calls(method(bridge, "codec", "()" + L_MAP_CODEC)));
        for (MethodNode method : bridge.methods) verify(method, legacy);
        for (String name : List.of("LegacyMaterialRuleCodec", "LegacyMaterialConditionCodec")) {
            assertTrue(transformer.getSyntheticClasses().containsKey("com/retromod/generated/" + name), name);
        }
        assertFalse(transformer.getSyntheticClasses().containsKey("com/retromod/generated/LegacyRuleSourceCodec"),
                "26.3 has no SurfaceRules");
    }

    @Test
    @DisplayName("A host whose rule interfaces still return the holder gets no accessor bridge")
    void olderHostsGetNoAccessorBridge() {
        for (String host : List.of("1.20.4", "1.21.11", "26.1")) {
            registerOffline(host);
            registerAccessorBridges(transformer);
            for (CodecAccessor accessor : MAP_CODEC_ACCESSORS) {
                assertFalse(transformer.getSyntheticClasses().containsKey(accessor.legacy()), accessor.legacy() + " on " + host);
            }
        }
        registerOffline("26.2");
        registerAccessorBridges(transformer);
        assertTrue(transformer.getSyntheticClasses().containsKey("com/retromod/generated/LegacyConditionSourceCodec"));
        assertFalse(transformer.getSyntheticClasses().containsKey("com/retromod/generated/LegacyDensityFunctionCodec"),
                "26.2's DensityFunction.codec() still returns KeyDispatchDataCodec");
    }

    @Test
    @DisplayName("A host before 1.20.5 keeps the old members")
    void oldHostIsLeftAlone() {
        registerOffline("1.20.4");

        assertFalse(transformer.getSyntheticClasses().containsKey(HELPER));
        byte[] mixin = terraBlenderMixin(L_KDDC, REGISTRY, ID);
        assertEquals(List.of(KDDC + ".codec()" + L_CODEC),
                codecReads(method(read(transformer.transformClass(mixin, MIXIN_NAME)), "lambda$registerSimple$0")));
    }

    @Test
    @DisplayName("26.3 removed the class, so a stand-in takes its name and the old members still work")
    void the263HostGetsAStandIn() {
        registerOffline("26.3");

        assertEquals(STAND_IN, transformer.getClassRedirects().get(KDDC));
        MethodNode lambda = method(read(transformer.transformClass(terraBlenderMixin(L_KDDC, REGISTRY, ID), MIXIN_NAME)),
                "lambda$registerSimple$0");
        assertEquals(List.of(STAND_IN + ".codec()" + L_MAP_CODEC), codecReads(lambda));

        // A mod built for 1.20.5 to 26.2 calls the map codec forms, which the stand-in has.
        ClassNode standIn = read(generateStandIn());
        assertNotNull(method(standIn, "of", "(" + L_MAP_CODEC + ")L" + STAND_IN + ";"));
        assertNotNull(method(standIn, "codec", "()" + L_MAP_CODEC));
        assertNotNull(method(standIn, "<init>", "(" + L_MAP_CODEC + ")V"));
        for (MethodNode method : standIn.methods) verify(method, STAND_IN);
    }

    @Test
    @DisplayName("The 26.3 edge adds the stand-in only where the class is gone")
    void theEdgeRegistersTheStandInOnlyOn263() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        transformer.clearRedirectsForTesting();
        registerRemovedClass(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(STAND_IN), "26.2 still has the class");

        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        registerRemovedClass(transformer);
        assertEquals(STAND_IN, transformer.getClassRedirects().get(KDDC),
                "a mod built for 1.21 names the class too, and its chain never passes the 1.20.5 shims");
    }

    @Test
    @DisplayName("Pre-26.1 Fabric: the intermediary names get the same repair")
    void intermediaryNamesOnOldFabricHosts() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        transformer.clearRedirectsForTesting();
        registerIntermediaryForVersion(transformer, "1.21.1");
        String owner = INTERMEDIARY.owner();

        byte[] mixin = terraBlenderMixin("L" + owner + ";", "net/minecraft/class_2378", "net/minecraft/class_2960");
        MethodNode lambda = method(read(transformer.transformClass(mixin, MIXIN_NAME)), "lambda$registerSimple$0");

        assertEquals(List.of(owner + ".comp_640()" + L_MAP_CODEC), codecReads(lambda));
        ClassNode helper = read(transformer.getSyntheticClasses().get(HELPER));
        assertEquals(List.of(HELPER + ".mapCodec", owner + ".method_42116"), callsIn(method(helper, "of")));

        transformer.clearRedirectsForTesting();
        registerIntermediaryForVersion(transformer, "1.20.4");
        assertFalse(transformer.getSyntheticClasses().containsKey(HELPER), "1.20.4 still has the codec shape");
    }

    @Test
    @DisplayName("The nine corpus registrations take the map codec; the JDK, DataFixerUpper, Guava and logging do not")
    void objectSinkRule() {
        String helper = "net/minecraftforge/registries/RegisterEvent$RegisterHelper";
        MethodInsnNode terraBlender = new MethodInsnNode(Opcodes.INVOKESTATIC, REGISTRY, "register",
                "(L" + REGISTRY + ";Lnet/minecraft/resources/Identifier;Ljava/lang/Object;)Ljava/lang/Object;", true);
        MethodInsnNode biConsumer = new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/function/BiConsumer",
                "accept", "(Ljava/lang/Object;Ljava/lang/Object;)V", true);
        MethodInsnNode byKey = new MethodInsnNode(Opcodes.INVOKEINTERFACE, helper, "register",
                "(Lnet/minecraft/resources/ResourceKey;Ljava/lang/Object;)V", true);
        MethodInsnNode byId = new MethodInsnNode(Opcodes.INVOKEINTERFACE, helper, "register",
                "(Lnet/minecraft/resources/Identifier;Ljava/lang/Object;)V", true);
        // TerraBlender's mixin; Lithostitched's three BiConsumer calls and one RegisterHelper call; Tectonic's four.
        for (MethodInsnNode sink : List.of(terraBlender, biConsumer, biConsumer, biConsumer, byKey, byId, byId, byId,
                byId)) {
            assertTrue(feeds(sink), sink.owner + "." + sink.name);
        }

        List<AbstractInsnNode> notRegistrations = List.of(
                new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true),
                new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false),
                new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "com/google/common/collect/ImmutableList$Builder", "add",
                        "(Ljava/lang/Object;)Lcom/google/common/collect/ImmutableList$Builder;", false),
                new MethodInsnNode(Opcodes.INVOKESTATIC, "com/mojang/datafixers/util/Pair", "of",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Lcom/mojang/datafixers/util/Pair;", false),
                new MethodInsnNode(Opcodes.INVOKESTATIC, "com/mojang/serialization/DataResult", "success",
                        "(Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;", true),
                new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/slf4j/Logger", "info",
                        "(Ljava/lang/String;Ljava/lang/Object;)V", true),
                new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/apache/logging/log4j/Logger", "info",
                        "(Ljava/lang/String;Ljava/lang/Object;)V", true),
                new MethodInsnNode(Opcodes.INVOKEINTERFACE, CODEC, "listOf", "()" + L_CODEC, true),
                new InvokeDynamicInsnNode("makeConcatWithConstants", "(Ljava/lang/Object;)Ljava/lang/String;",
                        new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants",
                                "()V", false)));
        for (AbstractInsnNode consumer : notRegistrations) {
            assertFalse(feeds(consumer), consumer instanceof MethodInsnNode call ? call.owner + "." + call.name : "indy");
        }
    }

    /** Whether the accessor's value, followed by a line label and {@code consumer}, counts as registered. */
    private static boolean feeds(AbstractInsnNode consumer) {
        MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "codec", "(" + L_KDDC + ")" + L_CODEC);
        InsnList list = new InsnList();
        list.add(call);
        list.add(new LabelNode());
        list.add(consumer.clone(new HashMap<>()));
        return feedsObjectSink(call);
    }

    // ---- fixtures ----

    private void registerOffline(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        LegacyKeyDispatchCodecBridge.register(transformer);
    }

    /** TerraBlender 3.0.1.11's mixin: the lambda its repaired registerSimple handler passes, in {@code kddc}'s names. */
    static byte[] terraBlenderMixin(String kddc, String registry, String id) {
        return terraBlenderMixin(MIXIN_NAME, kddc, registry, id);
    }

    /** The same mixin under {@code name}. */
    public static byte[] terraBlenderMixin(String name, String kddc, String registry, String id) {
        boolean intermediary = kddc.contains("class_");
        String accessor = intermediary ? INTERMEDIARY.codec() : MOJANG.codec();
        String register = intermediary ? "method_10230" : "register";
        String bootstrap = intermediary ? "net/minecraft/class_7922$class_7923" : BOOTSTRAP;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER, name, null,
                "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType("net/minecraft/core/registries/BuiltInRegistries"));
        targets.visitEnd();
        mixin.visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "CODEC_HOLDER", kddc, null, null).visitEnd();

        // bootstrap.run(registry); return Registry.register(registry, new ResourceLocation(..), RULE.CODEC.codec());
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$registerSimple$0", "(L" + bootstrap + ";L" + registry + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, bootstrap, "run", "(L" + registry + ";)Ljava/lang/Object;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.NEW, id);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("terrablender");
        mv.visitLdcInsn("merged");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, id, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, name, "CODEC_HOLDER", kddc);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getType(kddc).getInternalName(), accessor, "()" + L_CODEC, false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, registry, register,
                "(L" + registry + ";L" + id + ";Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A rule source's codec built with of(Codec) and new KDDC(Codec), and two uses of the codec as a Codec. */
    static byte[] surfaceRule(String kddc) {
        return surfaceRule(RULE, kddc);
    }

    /** The same rule source under {@code name}. */
    public static byte[] surfaceRule(String name, String kddc) {
        String owner = Type.getType(kddc).getInternalName();
        Names names = owner.equals(INTERMEDIARY.owner()) ? INTERMEDIARY : MOJANG;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "CODEC", kddc, null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitInsn(Opcodes.ACONST_NULL);
        init.visitMethodInsn(Opcodes.INVOKESTATIC, owner, names.oldOf(), "(" + L_CODEC + ")" + kddc, false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, name, "CODEC", kddc);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor create = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create",
                "(" + L_CODEC + ")" + kddc, null, null);
        create.visitCode();
        create.visitTypeInsn(Opcodes.NEW, owner);
        create.visitInsn(Opcodes.DUP);
        create.visitVarInsn(Opcodes.ALOAD, 0);
        create.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", "(" + L_CODEC + ")V", false);
        create.visitInsn(Opcodes.ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();

        // return CODEC.codec();
        MethodVisitor read = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "()" + L_CODEC, null, null);
        read.visitCode();
        read.visitFieldInsn(Opcodes.GETSTATIC, name, "CODEC", kddc);
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, names.codec(), "()" + L_CODEC, false);
        read.visitInsn(Opcodes.ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();

        // CODEC.codec()::parse, as javac guards a bound receiver.
        MethodVisitor bound = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bound", "()Ljava/lang/Object;",
                null, null);
        bound.visitCode();
        bound.visitFieldInsn(Opcodes.GETSTATIC, name, "CODEC", kddc);
        bound.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, names.codec(), "()" + L_CODEC, false);
        bound.visitInsn(Opcodes.DUP);
        bound.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        bound.visitInsn(Opcodes.POP);
        bound.visitInsn(Opcodes.ARETURN);
        bound.visitMaxs(0, 0);
        bound.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] unitMarker(String made) {
        return unitMarker(MARKER, L_KDDC, made);
    }

    /**
     * Lithostitched 1.20.1's marker in {@code kddc}'s names: {@code CODEC = KeyDispatchDataCodec.of(<made>)},
     * and {@code read(json)} returning {@code CODEC.codec().parse(JsonOps.INSTANCE, json)}.
     */
    public static byte[] unitMarker(String name, String kddc, String made) {
        String owner = Type.getType(kddc).getInternalName();
        Names names = owner.equals(INTERMEDIARY.owner()) ? INTERMEDIARY : MOJANG;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "CODEC", kddc, null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        switch (made) {
            case "value" -> {
                init.visitLdcInsn("marker");
                init.visitMethodInsn(Opcodes.INVOKESTATIC, CODEC, "unit", "(Ljava/lang/Object;)" + L_CODEC, true);
            }
            case "supplier" -> {
                init.visitInsn(Opcodes.ACONST_NULL);
                init.visitMethodInsn(Opcodes.INVOKESTATIC, CODEC, "unit", "(" + SUPPLIER_DESC + ")" + L_CODEC, true);
            }
            default -> {
                init.visitLdcInsn("marker");
                init.visitMethodInsn(Opcodes.INVOKESTATIC, MAP_CODEC, "unit", "(Ljava/lang/Object;)" + L_MAP_CODEC, false);
                init.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MAP_CODEC, "codec", "()" + L_CODEC, false);
            }
        }
        init.visitMethodInsn(Opcodes.INVOKESTATIC, owner, names.oldOf(), "(" + L_CODEC + ")" + kddc, false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, name, "CODEC", kddc);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor read = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        read.visitCode();
        read.visitFieldInsn(Opcodes.GETSTATIC, name, "CODEC", kddc);
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, names.codec(), "()" + L_CODEC, false);
        read.visitFieldInsn(Opcodes.GETSTATIC, "com/mojang/serialization/JsonOps", "INSTANCE",
                "Lcom/mojang/serialization/JsonOps;");
        read.visitVarInsn(Opcodes.ALOAD, 0);
        read.visitMethodInsn(Opcodes.INVOKEINTERFACE, CODEC, "parse",
                "(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;", true);
        read.visitInsn(Opcodes.ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] citadelRegistration() {
        return citadelRegistration(WRAPPER, L_KDDC);
    }

    /**
     * Citadel 2.6.3's registration in {@code kddc}'s names, {@code register("citadel_wrapper", CODEC::codec)},
     * plus {@code kept()}, which returns such a supplier instead of registering it.
     */
    public static byte[] citadelRegistration(String name, String kddc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "CODEC", kddc, null, null).visitEnd();

        MethodVisitor registerAll = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "registerAll",
                "()Ljava/lang/Object;", null, null);
        registerAll.visitCode();
        registerAll.visitLdcInsn("citadel_wrapper");
        boundCodecSupplier(registerAll, name, kddc);
        registerAll.visitMethodInsn(Opcodes.INVOKESTATIC, name, "register",
                "(Ljava/lang/String;" + SUPPLIER_DESC + ")Ljava/lang/Object;", false);
        registerAll.visitInsn(Opcodes.ARETURN);
        registerAll.visitMaxs(0, 0);
        registerAll.visitEnd();

        MethodVisitor kept = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "kept", "()" + SUPPLIER_DESC,
                null, null);
        kept.visitCode();
        boundCodecSupplier(kept, name, kddc);
        kept.visitInsn(Opcodes.ARETURN);
        kept.visitMaxs(0, 0);
        kept.visitEnd();

        // A registration stand-in that hands back what the supplier gives.
        MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register",
                "(Ljava/lang/String;" + SUPPLIER_DESC + ")Ljava/lang/Object;", null, null);
        register.visitCode();
        register.visitVarInsn(Opcodes.ALOAD, 1);
        register.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;",
                true);
        register.visitInsn(Opcodes.ARETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code CODEC::codec}, as javac compiles a bound method reference: a null check, then the metafactory. */
    private static void boundCodecSupplier(MethodVisitor mv, String owner, String kddc) {
        String holder = Type.getType(kddc).getInternalName();
        String accessor = holder.equals(INTERMEDIARY.owner()) ? INTERMEDIARY.codec() : MOJANG.codec();
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "CODEC", kddc);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInvokeDynamicInsn("get", "(" + kddc + ")" + SUPPLIER_DESC, new Handle(Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                + "Ljava/lang/invoke/CallSite;", false),
                Type.getMethodType("()Ljava/lang/Object;"),
                new Handle(Opcodes.H_INVOKEVIRTUAL, holder, accessor, "()" + L_CODEC, false),
                Type.getMethodType("()" + L_CODEC));
    }

    static byte[] ruleSourceImpl(String name, String iface) {
        return ruleSourceImpl(name, iface, L_KDDC, "codec");
    }

    /**
     * {@code CODEC::codec} handed to {@code Util.make}, Forge's {@code Lazy.of}, and Forge's and
     * NeoForge's {@code DeferredRegister.register}, one method each.
     */
    static byte[] hostSupplierSites() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, HOST_SITES, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "CODEC", L_KDDC, null, null).visitEnd();
        String[][] sites = {
            {"util", "net/minecraft/util/Util", "make", "(" + SUPPLIER_DESC + ")Ljava/lang/Object;"},
            {"lazy", "net/minecraftforge/common/util/Lazy", "of",
                    "(" + SUPPLIER_DESC + ")Lnet/minecraftforge/common/util/Lazy;"},
            {"forgeRegister", "net/minecraftforge/registries/DeferredRegister", "register",
                    "(Ljava/lang/String;" + SUPPLIER_DESC + ")Lnet/minecraftforge/registries/RegistryObject;"},
            {"neoForgeRegister", "net/neoforged/neoforge/registries/DeferredRegister", "register",
                    "(Ljava/lang/String;" + SUPPLIER_DESC + ")Lnet/neoforged/neoforge/registries/DeferredHolder;"},
        };
        for (String[] site : sites) {
            boolean register = site[2].equals("register");
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, site[0], "()Ljava/lang/Object;",
                    null, null);
            mv.visitCode();
            if (register) {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitTypeInsn(Opcodes.CHECKCAST, site[1]);
                mv.visitLdcInsn("citadel_wrapper");
            }
            boundCodecSupplier(mv, HOST_SITES, L_KDDC);
            mv.visitMethodInsn(register ? Opcodes.INVOKEVIRTUAL : Opcodes.INVOKESTATIC, site[1], site[2], site[3], false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A 1.20.1 rule source or density function: {@code accessor()} returns its {@code CODEC}, a KeyDispatchDataCodec. */
    public static byte[] ruleSourceImpl(String name, String iface, String kddc, String accessor) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", new String[]{iface});
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "CODEC", kddc, null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor codec = cw.visitMethod(Opcodes.ACC_PUBLIC, accessor, "()" + kddc, null, null);
        codec.visitCode();
        codec.visitFieldInsn(Opcodes.GETSTATIC, name, "CODEC", kddc);
        codec.visitInsn(Opcodes.ARETURN);
        codec.visitMaxs(0, 0);
        codec.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The host's KeyDispatchDataCodec from 1.20.5 to 26.2, which has the stand-in's shape. */
    public static byte[] hostKeyDispatchCodec() {
        ClassWriter cw = new ClassWriter(0);
        new ClassReader(generateStandIn()).accept(new ClassRemapper(cw, new SimpleRemapper(STAND_IN, KDDC)), 0);
        return cw.toByteArray();
    }

    /** A host rule interface as 26.2 declares it: {@code codec()} returns a MapCodec. */
    public static byte[] hostRuleInterface(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, name, null,
                "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "codec", "()" + L_MAP_CODEC, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A public class with only a no-argument constructor. */
    public static byte[] emptyClass(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }

    /** Loads the given classes, and Minecraft and DataFixerUpper names, before asking the test's own loader. */
    public static final class ChildFirstLoader extends URLClassLoader {
        private final Map<String, byte[]> classes;

        public ChildFirstLoader(URL[] urls, Map<String, byte[]> classes) {
            super(urls, LegacyKeyDispatchCodecBridgeTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && (classes.containsKey(name.replace('.', '/')) || name.startsWith("com.mojang.")
                        || name.startsWith("net.minecraft."))) {
                    loaded = findClass(name);
                }
                return loaded != null ? loaded : super.loadClass(name, resolve);
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes != null) return defineClass(name, bytes, 0, bytes.length);
            return super.findClass(name);
        }
    }

    /** DataFixerUpper 10 and its fastutil from the local Prism Launcher libraries, with the host's KeyDispatchDataCodec and {@code classes}. */
    static final class Dfu {
        private final ClassLoader loader;

        Dfu(Map<String, byte[]> classes) throws Exception {
            Path libraries = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries");
            Path dfu = libraries.resolve("com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar");
            Path fastutil = libraries.resolve("it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar");
            assumeTrue(Files.isRegularFile(dfu) && Files.isRegularFile(fastutil),
                    "no local DataFixerUpper 10 or fastutil jar; they are local test material");
            Map<String, byte[]> all = new HashMap<>(classes);
            all.put(KDDC, hostKeyDispatchCodec());
            loader = new ChildFirstLoader(new URL[]{dfu.toUri().toURL(), fastutil.toUri().toURL()}, all);
        }

        Class<?> type(String internalName) throws ClassNotFoundException {
            return loader.loadClass(dotted(internalName));
        }

        Object call(String owner, String name, Class<?>[] parameters, Object... arguments) throws Exception {
            return type(owner).getMethod(name, parameters).invoke(null, arguments);
        }

        /** The value a DataResult holds, or empty when it is an error. */
        Optional<?> result(Object dataResult) throws Exception {
            return (Optional<?>) type("com/mojang/serialization/DataResult").getMethod("result").invoke(dataResult);
        }

        Object helperMapCodec(Object codec) throws Exception {
            return call(HELPER, "mapCodec", new Class<?>[]{type(CODEC)}, codec);
        }

        /** {@code helper.of(codec).codec().codec().parse(JsonOps.INSTANCE, json)}, as the dispatch reads it. */
        Optional<?> parseThroughHelper(Object codec, JsonObject json) throws Exception {
            Object holder = call(HELPER, "of", new Class<?>[]{type(CODEC)}, codec);
            Object mapCodec = type(KDDC).getMethod("codec").invoke(holder);
            Object asCodec = type(MAP_CODEC).getMethod("codec").invoke(mapCodec);
            Object ops = type("com/mojang/serialization/JsonOps").getField("INSTANCE").get(null);
            Object parsed = type(CODEC).getMethod("parse", type("com/mojang/serialization/DynamicOps"), Object.class)
                    .invoke(asCodec, ops, json);
            return result(parsed);
        }
    }

    // ---- reading ----

    /** Every accessor call on the holder: its owner, name and descriptor. */
    private static List<String> codecReads(MethodNode method) {
        List<String> reads = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && (call.name.equals("codec") || call.name.equals("comp_640"))) {
                reads.add(call.owner + "." + call.name + call.desc);
            }
        }
        return reads;
    }

    /** Every call: owner, name and descriptor. */
    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    /** Type checks, calls, method references and constants, in order. */
    private static List<String> steps(MethodNode method) {
        List<String> steps = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode type) {
                steps.add((type.getOpcode() == Opcodes.INSTANCEOF ? "instanceof " : "cast ") + type.desc);
            }
            if (insn instanceof MethodInsnNode call) steps.add(call.owner + "." + call.name);
            if (insn instanceof LdcInsnNode ldc) steps.add("\"" + ldc.cst + "\"");
            if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs[1] instanceof Handle target) {
                steps.add(target.getOwner().substring(target.getOwner().lastIndexOf('/') + 1) + "." + target.getName());
            }
        }
        return steps;
    }

    private static InvokeDynamicInsnNode indy(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof InvokeDynamicInsnNode indy) return indy;
        }
        throw new AssertionError(method.name + " has no invokedynamic");
    }

    private static List<String> helperCalls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(HELPER)) {
                calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }

    private static List<String> callsIn(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        return calls;
    }

    private static boolean calls(MethodNode method, String owner) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner)) return true;
        }
        return false;
    }

    private static boolean noNew(MethodNode method, String type) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode node && node.getOpcode() == Opcodes.NEW && node.desc.equals(type)) return false;
        }
        return true;
    }

    private static void verify(MethodNode method, String owner) {
        try {
            new Analyzer<>(new BasicVerifier()).analyze(owner, method);
        } catch (Exception e) {
            fail(owner + "." + method.name + " does not verify: " + e.getMessage());
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name));
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
    }
}
