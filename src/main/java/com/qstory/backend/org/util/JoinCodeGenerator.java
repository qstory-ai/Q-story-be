package com.qstory.backend.org.util;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** Generates ClassGroup.joinCode - short enough to print on a flyer, excludes look-alike characters (0/O/1/I/L). */
@Component
public class JoinCodeGenerator {

    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int LENGTH = 8;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
