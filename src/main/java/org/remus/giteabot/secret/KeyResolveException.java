package org.remus.giteabot.secret;

public class KeyResolveException extends RuntimeException {
    public KeyResolveException(String message) {
        super(message);
    }

    public KeyResolveException(String message, Throwable cause) {
        super(message, cause);
    }
}
