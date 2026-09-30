package com.qstory.backend.org.util;

import java.security.SecureRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** ClassGroup.joinCode를 생성한다 - 전단지에 인쇄할 수 있을 만큼 짧고, 헷갈리기 쉬운 문자(0/O/1/I/L)는 제외한다. */
@Component
public class JoinCodeGenerator {

    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int LENGTH = 8;
    private static final int MAX_ATTEMPTS = 10;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** 아직 쓰이지 않은 코드를 만든다 - 충돌은 드물지만 방어적으로 10회까지만 시도하고 실패하면 onExhausted를 던진다. */
    public String generateUnique(Predicate<String> taken, Supplier<? extends RuntimeException> onExhausted) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = generate();
            if (!taken.test(code)) {
                return code;
            }
        }
        throw onExhausted.get();
    }
}
