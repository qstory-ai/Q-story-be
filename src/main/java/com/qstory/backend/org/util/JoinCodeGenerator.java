package com.qstory.backend.org.util;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** ClassGroup.joinCode를 생성한다 - 전단지에 인쇄할 수 있을 만큼 짧고, 헷갈리기 쉬운 문자(0/O/1/I/L)는 제외한다. */
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
