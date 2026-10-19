/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi;

import com.retromod.core.RedirectKey;
import com.retromod.core.RetromodTransformer;
import com.retromod.loaderapi.fabric.v26.FabricApi26Provider;
import com.retromod.loaderapi.fabric.v26.FabricApi26Rows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code renames.tsv} loader, including rows whose calls name a subtype of the owner. */
class FabricApi26RowsTest {

    private static final String HEADER = "kind\told_owner\told_name\told_desc\tnew_owner\tnew_name\t"
            + "new_desc\thost_from\thost_to\tmin_fabric_api\tcall_site_owners\tbatch\tnote\n";
    private static final String TRANSFER = "net/fabricmc/fabric/api/transfer/v1/storage/TransferVariant";
    private static final String FLUID = "net/fabricmc/fabric/api/transfer/v1/fluid/FluidVariant";
    private static final String ITEM = "net/fabricmc/fabric/api/transfer/v1/item/ItemVariant";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    @AfterEach
    void clear() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The bundled table parses and names a rows version")
    void bundledTableParses() {
        FabricApi26Rows.Table table = FabricApi26Rows.bundled();
        assertFalse(table.rowsVersion().isBlank());
        assertEquals(table.rowsVersion(), new FabricApi26Provider().rowsVersion());
        for (FabricApi26Rows.Row row : table.rows()) {
            assertTrue(row.appliesTo("26.1") || row.appliesTo("26.3"),
                    "a row that answers no supported host: " + row);
        }
    }

    @Test
    @DisplayName("A row keyed on the declaring owner also rewrites calls through each subtype")
    void subtypeCallSitesAreRedirected() throws IOException {
        String desc = "()Lnet/minecraft/nbt/CompoundTag;";
        FabricApi26Rows.Table table = parse("M\t" + TRANSFER + "\tgetNbt\t" + desc + "\t" + TRANSFER
                + "\tcopyNbtForTest\t" + desc + "\t26.1\t*\t0.145.1\t" + FLUID + "," + ITEM
                + "\tT1\tsubtype call sites\n");
        LoaderApiRegistration registration = new LoaderApiRegistration(transformer,
                new FabricApi26Provider());
        try (var scope = transformer.ownedRegistrations()) {
            assertEquals(1, FabricApi26Rows.register(table, registration, "26.3"));
        }
        assertTrue(registration.declaredKeys().contains(RedirectKey.method(FLUID, "getNbt", desc)));

        byte[] out = transformer.transformClass(caller(FLUID, desc), "fixture/Caller");
        List<String> calls = new ArrayList<>();
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);
        node.methods.forEach(method -> method.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }));
        assertTrue(calls.contains(TRANSFER + ".copyNbtForTest"),
                "an invokeinterface FluidVariant.getNbt must reach the row's target: " + calls);
    }

    @Test
    @DisplayName("Intermediary names, a wrong header and a missing version are refused")
    void badTablesAreRefused() {
        assertThrows(IOException.class, () -> parse("M\t" + FLUID + "\tgetNbt\t()Lnet/minecraft/class_2487;"
                + "\t-\t-\t-\t26.1\t*\t-\t-\tT1\t-\n"));
        assertThrows(IOException.class, () -> FabricApi26Rows.parse(new ByteArrayInputStream(
                "# rows_version\t1\nkind\towner\n".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IOException.class, () -> FabricApi26Rows.parse(new ByteArrayInputStream(
                HEADER.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    @DisplayName("Host ranges are inclusive and * leaves the top open")
    void hostRanges() throws IOException {
        FabricApi26Rows.Row row = parse("S\t" + FLUID + "\tgetNbt\t()V\t-\t-\t-\t26.1\t26.2\t-\t-\tT1\t-\n")
                .rows().get(0);
        assertTrue(row.appliesTo("26.1.2"));
        assertTrue(row.appliesTo("26.2"));
        assertFalse(row.appliesTo("26.3"));
        assertFalse(row.appliesTo("1.21.11"));
    }

    @Test
    @DisplayName("A host_to that names a line covers every patch release of that line")
    void lineBoundCoversPatches() throws IOException {
        FabricApi26Rows.Row line = parse("S\t" + FLUID + "\tgetNbt\t()V\t-\t-\t-\t26.1\t26.1\t-\t-\tC1\t-\n")
                .rows().get(0);
        assertTrue(line.appliesTo("26.1"));
        assertTrue(line.appliesTo("26.1.2"), "host_to 26.1 means the whole 26.1 line");
        assertFalse(line.appliesTo("26.2"));

        FabricApi26Rows.Row patch = parse("S\t" + FLUID + "\tgetNbt\t()V\t-\t-\t-\t26.1\t26.1.1\t-\t-\tC1\t-\n")
                .rows().get(0);
        assertTrue(patch.appliesTo("26.1.1"));
        assertFalse(patch.appliesTo("26.1.2"), "a patch bound stays exact");
    }

    private static FabricApi26Rows.Table parse(String rows) throws IOException {
        return FabricApi26Rows.parse(new ByteArrayInputStream(("# rows_version\ttest\n" + HEADER + rows)
                .getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] caller(String owner, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Caller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read",
                "(L" + owner + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, owner, "getNbt", desc, true);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
