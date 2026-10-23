package alkaline.client.account;

import java.io.Serial;

/** A sign-in step failed; the message is written for the player. */
public final class AuthException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    public AuthException(String message) {
        super(message);
    }

    public AuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
