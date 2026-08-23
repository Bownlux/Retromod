/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Bounds untrusted JSON before a parser or recursive tree walk receives it. */
public final class JsonSecurity {

    public static final long DEFAULT_MAX_BYTES = 16L * 1024 * 1024;
    public static final int DEFAULT_MAX_DEPTH = 256;

    private JsonSecurity() {}

    /** Validates a JSON string against the default byte and nesting limits. */
    public static void validate(String json, String description) throws IOException {
        validate(json, DEFAULT_MAX_BYTES, DEFAULT_MAX_DEPTH, description);
    }

    /** Validates a JSON string before Gson or a recursive visitor sees it. */
    public static void validate(String json, long maxBytes, int maxDepth,
                                String description) throws IOException {
        requireLimits(maxBytes, maxDepth);
        if (json == null) {
            throw new IOException(description + " is missing");
        }
        if (utf8LengthExceeds(json, maxBytes)) {
            throw new IOException(description + " exceeds " + maxBytes + " bytes");
        }
        validateDepth(json, maxDepth, description);
    }

    /** Validates already-read UTF-8 JSON bytes before they are decoded and parsed. */
    public static void validate(byte[] json, long maxBytes, int maxDepth,
                                String description) throws IOException {
        requireLimits(maxBytes, maxDepth);
        if (json == null) {
            throw new IOException(description + " is missing");
        }
        if (json.length > maxBytes) {
            throw new IOException(description + " exceeds " + maxBytes + " bytes");
        }
        validateDepth(decodeUtf8(json, description), maxDepth, description);
    }

    /** Reads and validates one UTF-8 JSON stream. */
    public static String readUtf8(InputStream input, long maxBytes, int maxDepth,
                                  String description) throws IOException {
        if (input == null) {
            throw new IOException(description + " is missing");
        }
        byte[] bytes = ZipSecurity.safeReadAllBytes(input, maxBytes);
        validate(bytes, maxBytes, maxDepth, description);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Reads and validates one UTF-8 JSON file. */
    public static String readUtf8(Path path, long maxBytes, int maxDepth,
                                  String description) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return readUtf8(input, maxBytes, maxDepth, description);
        }
    }

    private static void validateDepth(String json, int maxDepth, String description)
            throws IOException {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int index = 0; index < json.length(); index++) {
            char value = json.charAt(index);
            char next = index + 1 < json.length() ? json.charAt(index + 1) : '\0';

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    inString = false;
                }
                continue;
            }
            if (inLineComment) {
                if (value == '\n' || value == '\r') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (value == '*' && next == '/') {
                    inBlockComment = false;
                    index++;
                }
                continue;
            }
            if (value == '"') {
                inString = true;
            } else if (value == '/' && next == '/') {
                inLineComment = true;
                index++;
            } else if (value == '/' && next == '*') {
                inBlockComment = true;
                index++;
            } else if (value == '{' || value == '[') {
                if (++depth > maxDepth) {
                    throw new IOException(description + " nesting exceeds "
                            + maxDepth + " levels");
                }
            } else if ((value == '}' || value == ']') && depth > 0) {
                depth--;
            }
        }
    }

    private static boolean utf8LengthExceeds(String value, long maxBytes) {
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x7f) {
                bytes++;
            } else if (character <= 0x7ff) {
                bytes += 2;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
            if (bytes > maxBytes) return true;
        }
        return false;
    }

    private static String decodeUtf8(byte[] value, String description) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw new IOException(description + " is not valid UTF-8", invalidUtf8);
        }
    }

    private static void requireLimits(long maxBytes, int maxDepth) {
        if (maxBytes <= 0 || maxDepth <= 0) {
            throw new IllegalArgumentException("JSON limits must be positive");
        }
    }
}
