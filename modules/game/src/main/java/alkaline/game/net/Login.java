package alkaline.game.net;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Objects;
import javax.crypto.Cipher;

/**
 * Protocol 47's handshake and login: Handshake (next state login), Login Start, then the server's Encryption
 * Request (online mode), Set Compression and Login Success, or a Disconnect.
 *
 * <p>Online-mode servers send an RSA public key; the client picks a 16-byte shared secret, has the session
 * service record the join (Mojang's {@code session/minecraft/join}, through {@link SessionJoin}), answers with
 * the secret and the server's verify token encrypted with the key, and both sides switch to AES/CFB8.
 */
public final class Login {
    public static final int PROTOCOL = 47;

    /** Records a server join with the session service for online-mode servers. */
    @FunctionalInterface
    public interface SessionJoin {
        /** @param serverHash Minecraft's signed SHA-1 hex of server id, shared secret and public key */
        void join(String serverHash) throws IOException;
    }

    /** The profile the server assigned. */
    public record Result(String uuid, String username) {
    }

    /** A login the server refused, with its reason (a chat component as JSON). */
    public static final class RefusedException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String reasonJson;

        public RefusedException(String reasonJson) {
            super("Disconnected: " + reasonJson);
            this.reasonJson = reasonJson;
        }

        public String reasonJson() {
            return reasonJson;
        }
    }

    private Login() {
    }

    /**
     * Logs in on a fresh connection. {@code session} may be null to log in to offline-mode servers only.
     *
     * @param hostAsTyped the host the player typed (servers behind proxies route on it)
     */
    public static Result login(Connection connection, String hostAsTyped, int port, String username,
                               SessionJoin session) throws IOException {
        Objects.requireNonNull(username, "username");
        connection.send(0x00, new PacketBuffer().writeVarInt(PROTOCOL).writeString(hostAsTyped)
                .writeShort(port).writeVarInt(2));
        connection.send(0x00, new PacketBuffer().writeString(username));
        while (true) {
            PacketBuffer packet = connection.read();
            int id = packet.readVarInt();
            switch (id) {
                case 0x00 -> throw new RefusedException(packet.readString());
                case 0x01 -> encrypt(connection, packet, session);
                case 0x02 -> {
                    return new Result(packet.readString(), packet.readString());
                }
                case 0x03 -> connection.compression(packet.readVarInt());
                default -> throw new IOException("Unexpected login packet 0x" + Integer.toHexString(id));
            }
        }
    }

    private static void encrypt(Connection connection, PacketBuffer request, SessionJoin session) throws IOException {
        String serverId = request.readString();
        byte[] keyBytes = request.readByteArray(1024);
        byte[] verifyToken = request.readByteArray(64);
        if (session == null) {
            throw new IOException("This server is in online mode; sign in with a Microsoft account to join.");
        }
        try {
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
            byte[] secret = new byte[16];
            new SecureRandom().nextBytes(secret);
            session.join(serverHash(serverId, secret, keyBytes));
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, key);
            byte[] encryptedSecret = rsa.doFinal(secret);
            rsa.init(Cipher.ENCRYPT_MODE, key);
            byte[] encryptedToken = rsa.doFinal(verifyToken);
            connection.send(0x01, new PacketBuffer().writeByteArray(encryptedSecret).writeByteArray(encryptedToken));
            connection.encrypt(secret);
        } catch (GeneralSecurityException failure) {
            throw new IOException("Could not set up encryption: " + failure.getMessage(), failure);
        }
    }

    /** Minecraft's server hash: SHA-1 of id, secret and key, as a signed two's-complement hex number. */
    public static String serverHash(String serverId, byte[] secret, byte[] publicKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(serverId.getBytes(StandardCharsets.ISO_8859_1));
            sha1.update(secret);
            sha1.update(publicKey);
            return new BigInteger(sha1.digest()).toString(16);
        } catch (GeneralSecurityException missing) {
            throw new IllegalStateException("SHA-1 is unavailable.", missing);
        }
    }
}
