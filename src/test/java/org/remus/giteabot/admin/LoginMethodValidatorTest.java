package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginMethodValidatorTest {

    @Test
    void acceptsSupportedLoginMethods() {
        assertDoesNotThrow(() -> new LoginMethodValidator("native"));
        assertDoesNotThrow(() -> new LoginMethodValidator("oauth"));
    }

    @Test
    void rejectsUnsupportedLoginMethods() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new LoginMethodValidator("disabled"));

        assertTrue(exception.getMessage().contains("Invalid giteabot.security.login-method"));
    }
}
