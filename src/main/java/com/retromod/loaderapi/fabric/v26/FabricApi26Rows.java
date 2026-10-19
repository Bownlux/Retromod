/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26;

import com.retromod.core.RedirectKey;
import com.retromod.core.RetromodVersion;
import com.retromod.loaderapi.LoaderApiRegistration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Loads {@code retromod/loaderapi/fabric-26x/renames.tsv} and registers its rows for one host. */
public final class FabricApi26Rows {

    /** Classpath location of the rename table. */
    public static final String RENAMES_RESOURCE = "retromod/loaderapi/fabric-26x/renames.tsv";

    private static final Pattern INTERMEDIARY = Pattern.compile("(class|method|field)_\\d+");
    private static final List<String> COLUMNS = List.of("kind", "old_owner", "old_name",
            "old_desc", "new_owner", "new_name", "new_desc", "host_from", "host_to",
            "min_fabric_api", "call_site_owners", "batch", "note");

    /** What a row does. RECEIVER_HELPER sends an instance call to a static helper, receiver first. */
    public enum Kind { CLASS, METHOD, RECEIVER_HELPER, FIELD, SUPPRESS }

    /** One row of the table. Dashes in the file are read as null. */
    public record Row(Kind kind, String oldOwner, String oldName, String oldDesc,
            String newOwner, String newName, String newDesc, String hostFrom, String hostTo,
            String minFabricApi, List<String> callSiteOwners, String batch, String note) {

        /** Whether this row applies to {@code host}. A two-part host_to covers its whole line. */
        public boolean appliesTo(String host) {
            if (host == null) return false;
            if (RetromodVersion.compareMcVersions(host, hostFrom) < 0) return false;
            if (hostTo == null) return true;
            String compared = namesLine(hostTo) ? lineOf(host) : host;
            return RetromodVersion.compareMcVersions(compared, hostTo) <= 0;
        }

        /** The key this row claims. */
        public RedirectKey key() {
            return switch (kind) {
                case CLASS -> RedirectKey.type(oldOwner);
                case FIELD -> RedirectKey.field(oldOwner, oldName);
                case METHOD, RECEIVER_HELPER -> RedirectKey.method(oldOwner, oldName, oldDesc);
                case SUPPRESS -> oldName == null ? RedirectKey.type(oldOwner)
                        : oldDesc == null ? RedirectKey.field(oldOwner, oldName)
                        : RedirectKey.method(oldOwner, oldName, oldDesc);
            };
        }
    }

    /** The parsed table and its version. */
    public record Table(String rowsVersion, List<Row> rows) {}

    private static volatile Table bundled;

    private FabricApi26Rows() {}

    /** The bundled table, read once. */
    public static Table bundled() {
        Table table = bundled;
        if (table != null) return table;
        try (InputStream in = FabricApi26Rows.class.getClassLoader()
                .getResourceAsStream(RENAMES_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RENAMES_RESOURCE + " is missing from the Retromod jar");
            }
            table = parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + RENAMES_RESOURCE, e);
        }
        bundled = table;
        return table;
    }

    /** Parses a rename table. Rejects intermediary names, which never match after the remap. */
    public static Table parse(InputStream in) throws IOException {
        String rowsVersion = null;
        List<Row> rows = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        int lineNumber = 0;
        boolean headerSeen = false;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            // The version lives in a comment so the table stays one header plus rows.
            if (line.startsWith("# rows_version\t")) {
                rowsVersion = line.substring("# rows_version\t".length()).trim();
                continue;
            }
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] cells = line.split("\t", -1);
            if (!headerSeen) {
                if (!Arrays.asList(cells).equals(COLUMNS)) {
                    throw new IOException(RENAMES_RESOURCE + " line " + lineNumber
                            + ": expected the header " + String.join(",", COLUMNS));
                }
                headerSeen = true;
                continue;
            }
            rows.add(parseRow(cells, lineNumber));
        }
        if (rowsVersion == null || rowsVersion.isBlank()) {
            throw new IOException(RENAMES_RESOURCE + " has no '# rows_version' line");
        }
        return new Table(rowsVersion, List.copyOf(rows));
    }

    private static Row parseRow(String[] cells, int lineNumber) throws IOException {
        if (cells.length != COLUMNS.size()) {
            throw new IOException(RENAMES_RESOURCE + " line " + lineNumber + ": expected "
                    + COLUMNS.size() + " columns, found " + cells.length);
        }
        Kind kind = switch (cells[0]) {
            case "C" -> Kind.CLASS;
            case "M" -> Kind.METHOD;
            case "D" -> Kind.RECEIVER_HELPER;
            case "F" -> Kind.FIELD;
            case "S" -> Kind.SUPPRESS;
            default -> throw new IOException(RENAMES_RESOURCE + " line " + lineNumber
                    + ": unknown kind " + cells[0]);
        };
        for (String cell : cells) {
            if (INTERMEDIARY.matcher(cell).find()) {
                throw new IOException(RENAMES_RESOURCE + " line " + lineNumber
                        + ": uses an intermediary name. Use the 26.x Mojang name.");
            }
        }
        String hostFrom = cell(cells[7]);
        if (hostFrom == null) {
            throw new IOException(RENAMES_RESOURCE + " line " + lineNumber + ": host_from is empty");
        }
        String owners = cell(cells[10]);
        List<String> callSiteOwners = owners == null ? List.of() : List.of(owners.split(","));
        String hostTo = "*".equals(cells[8]) ? null : cell(cells[8]);
        return new Row(kind, cell(cells[1]), cell(cells[2]), cell(cells[3]), cell(cells[4]),
                cell(cells[5]), cell(cells[6]), hostFrom, hostTo, cell(cells[9]), callSiteOwners,
                cell(cells[11]), cell(cells[12]));
    }

    /** Whether {@code version} has two parts, such as 26.1, and so names a whole host line. */
    private static boolean namesLine(String version) {
        return version.split("\\.").length == 2;
    }

    /** The line of {@code host}, such as 26.1 for 26.1.2 or 26.3-rc-1. */
    private static String lineOf(String host) {
        String[] parts = host.trim().split("[.-]");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : host;
    }

    private static String cell(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() || "-".equals(trimmed) ? null : trimmed;
    }

    /** Registers every row of {@code table} that applies to {@code host}. */
    public static int register(Table table, LoaderApiRegistration registration, String host) {
        int registered = 0;
        for (Row row : table.rows()) {
            if (!row.appliesTo(host)) continue;
            switch (row.kind()) {
                case SUPPRESS -> registration.suppress(row.key());
                case CLASS -> registration.redirectClass(row.oldOwner(), row.newOwner());
                case FIELD -> registerField(row, registration);
                case METHOD -> registration.redirectInherited(row.oldOwner(), row.oldName(),
                        row.oldDesc(), row.callSiteOwners(), row.newOwner(), row.newName(),
                        row.newDesc());
                case RECEIVER_HELPER -> registerReceiverHelper(row, registration);
            }
            registered++;
        }
        return registered;
    }

    /**
     * Redirects a field read. A batch that redirected the old owner's class first gets the same
     * redirect under the new owner, because a field read reaches the redirects after the class remap.
     */
    private static void registerField(Row row, LoaderApiRegistration registration) {
        registration.redirectField(row.oldOwner(), row.oldName(), row.oldDesc(), row.newOwner(),
                row.newName(), row.newDesc());
        String remapped = registration.transformer().getClassRedirects().get(row.oldOwner());
        if (remapped != null && !remapped.equals(row.oldOwner())) {
            registration.redirectField(remapped, row.oldName(), row.oldDesc(), row.newOwner(),
                    row.newName(), row.newDesc());
        }
    }

    /** One exact-owner redirect per owner a call site may name, each passing the receiver first. */
    private static void registerReceiverHelper(Row row, LoaderApiRegistration registration) {
        List<String> owners = new ArrayList<>(List.of(row.oldOwner()));
        for (String owner : row.callSiteOwners()) {
            if (!owners.contains(owner)) owners.add(owner);
        }
        for (String owner : owners) {
            registration.redirectMethod(owner, row.oldName(), row.oldDesc(), row.newOwner(),
                    row.newName(), row.newDesc(), true);
        }
    }
}
