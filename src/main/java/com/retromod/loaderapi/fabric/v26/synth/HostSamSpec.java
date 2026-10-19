/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.synth;

import com.retromod.core.RedirectKey;
import com.retromod.core.RetromodTransformer;
import com.retromod.loaderapi.LoaderApiRegistration;
import com.retromod.loaderapi.fabric.v26.synth.SamBridgeSpec.Arg;
import com.retromod.loaderapi.fabric.v26.synth.SamBridgeSpec.HostArg;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An old callback shape the host still serves through its own interface, renamed or reshaped.
 *
 * @param oldNames    names a mod may use for the type; any that differ from {@code hostName} are
 *                    redirected to it
 * @param hostName    the host interface
 * @param oldSam      the old method name, which mods implement and call
 * @param oldDesc     the old method descriptor, in 26.x names
 * @param hostSam     the host method name
 * @param hostDesc    the host method descriptor
 * @param args        for each old parameter, the host parameter it comes from
 * @param bridgeName  internal name of the per-mod bridge interface
 * @param typedCall   the static helper a call to the old method goes to, receiver first, or null
 *                    when the host method takes the old arguments in order
 */
public record HostSamSpec(List<String> oldNames, String hostName, String oldSam, String oldDesc,
        String hostSam, String hostDesc, List<Arg> args, String bridgeName, Target typedCall) {

    /** A static method, as {@code owner.name(desc)}. */
    public record Target(String owner, String name, String desc) {}

    public HostSamSpec {
        oldNames = List.copyOf(oldNames);
        args = List.copyOf(args);
        Objects.requireNonNull(hostName, "hostName");
        if (args.stream().anyMatch(arg -> !(arg instanceof HostArg))) {
            throw new IllegalArgumentException(bridgeName + ": every old argument must come from a "
                    + "host argument, so a class can call its old method directly");
        }
        // The compact constructor reads its parameters; the fields are not assigned yet.
        if (!oldDesc.equals(hostDesc) && typedCall == null) {
            throw new IllegalArgumentException(bridgeName + ": the host method takes other arguments, "
                    + "so a typed call needs a helper");
        }
    }

    /** Whether the host method takes the old arguments unchanged. */
    public boolean sameShape() {
        return oldDesc.equals(hostDesc);
    }

    /** The per-mod wrap class for lambdas of a reshaped callback. */
    public String wrapName() {
        return bridgeName + "Wrap";
    }

    /** The per-mod bridge interface: the old method stays abstract and the host method calls it. */
    public byte[] generateBridge() {
        return new SamBridgeSpec(bridgeName, List.of(hostName), oldSam, oldDesc, hostSam, hostDesc,
                args, SamBridgeSpec.Result.SAME).generate();
    }

    /**
     * A class whose {@code wrap} returns its bridge argument typed as the host interface, so a
     * lambda built as the bridge stays the host type wherever the mod stores it.
     */
    public byte[] generateWrap() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, wrapName(), null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "wrap", wrapDesc(), null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private String wrapDesc() {
        return "(L" + bridgeName + ";)L" + hostName + ";";
    }

    /** Registers the spec. */
    public void register(LoaderApiRegistration registration) {
        RetromodTransformer transformer = registration.transformer();
        Set<String> names = new LinkedHashSet<>(oldNames);
        names.add(hostName);

        // Every claim comes first: a type claim clears all registrations keyed on the type, and a
        // method claim clears the lambda adapter on the same key.
        for (String name : names) registration.claim(RedirectKey.type(name));
        for (String name : names) registration.claim(RedirectKey.method(name, oldSam, oldDesc));
        registration.registerPerModSynthetic(bridgeName, generateBridge());
        if (!sameShape()) registration.registerPerModSynthetic(wrapName(), generateWrap());

        // Every mod names the host type, so a library and its caller agree on it.
        for (String name : oldNames) {
            if (!name.equals(hostName)) transformer.registerClassRedirect(name, hostName);
        }
        // Classes and mod interfaces that implement the old shape implement the bridge instead.
        for (String name : names) transformer.registerInterfaceReplacement(name, bridgeName);

        for (String name : names) {
            // Typed calls go to the host method, or to a helper that supplies what the host adds.
            if (typedCall == null) {
                transformer.registerMethodRedirect(name, oldSam, oldDesc, hostName, hostSam, hostDesc);
            } else {
                transformer.registerMethodRedirect(name, oldSam, oldDesc, typedCall.owner(),
                        typedCall.name(), typedCall.desc(), true);
            }
            // A rename keeps a lambda on the host type; a reshape builds it as the bridge.
            if (sameShape()) {
                transformer.registerLambdaSamRetype(name, oldSam, oldDesc, hostSam, hostDesc);
            } else {
                transformer.registerLambdaAdapter(name, oldSam, oldDesc, new RetromodTransformer.LambdaAdapter(
                        bridgeName, oldSam, oldDesc, wrapName(), "wrap", wrapDesc()));
            }
        }

        // Each class gets its own host method, which wins over two jars' bridge defaults.
        transformer.registerImplementedMethodBridge(bridgeName, hostSam, hostDesc, oldSam, oldDesc,
                sameShape() ? null : sources());
    }

    /** For each old parameter, the index of the host parameter passed to it. */
    private List<Integer> sources() {
        List<Integer> sources = new ArrayList<>();
        for (Arg arg : args) sources.add(((HostArg) arg).index());
        if (sources.size() != Type.getArgumentTypes(oldDesc).length) {
            throw new IllegalStateException(bridgeName + ": one source per old parameter expected");
        }
        return sources;
    }
}
