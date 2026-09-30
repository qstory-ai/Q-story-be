package com.qstory.backend.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 공용 해싱 헬퍼. */
public final class DigestUtil {

    private DigestUtil() {}

    public static String sha256Hex(String value) {
        return hex("SHA-256", value);
    }

    public static String md5Hex(String value) {
        return hex("MD5", value);
    }

    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(String algorithm, String value) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
