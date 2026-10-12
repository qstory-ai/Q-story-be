package com.qstory.backend.identity.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthValidatorTest {

    private final AuthValidator validator = new AuthValidator();

    @Test
    void passwordUpTo72BytesIsAcceptedAndEncodable() {
        String ascii72 = "a".repeat(72);
        String korean24 = "가".repeat(24);
        assertDoesNotThrow(() -> validator.validatePassword(ascii72));
        assertDoesNotThrow(() -> validator.validatePassword(korean24));
        assertDoesNotThrow(() -> new BCryptPasswordEncoder().encode(korean24));
    }

    @Test
    void passwordOver72BytesIsRejectedAs400InsteadOfReachingBcrypt() {
        // Spring Security 6.5의 BCryptPasswordEncoder.encode는 72바이트를 넘으면 IllegalArgumentException(=500)을 던진다.
        assertThrows(IllegalArgumentException.class, () -> new BCryptPasswordEncoder().encode("가".repeat(25)));

        ApiException ascii = assertThrows(ApiException.class, () -> validator.validatePassword("a".repeat(73)));
        ApiException korean = assertThrows(ApiException.class, () -> validator.validatePassword("가".repeat(25)));
        assertEquals(ErrorCode.VALIDATION_FAILED, ascii.code());
        assertEquals(ErrorCode.VALIDATION_FAILED, korean.code());
    }
}
