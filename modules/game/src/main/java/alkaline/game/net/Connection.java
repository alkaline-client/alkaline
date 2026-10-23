package alkaline.game.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * One protocol-47 TCP connection: VarInt length framing, zlib compression above the server's threshold, and
 * AES/CFB8 encryption with the shared secret once login enables it. {@link #read} is called from one reader
 * thread and {@link #send} from any thread (sends are serialized).
 */
public final class Connection implements AutoCloseable {
    /** Vanilla rejects packets longer than 2 MiB (2^21) before decompression framing; so does this. */
    private static final int MAX_FRAME = 2 * 1024 * 1024;
    private static final int MAX_UNCOMPRESSED = 8 * 1024 * 1024;

    private final Socket socket;
    private InputStream in;
    private OutputStream out;
    private int compressionThreshold = -1;
    private final Inflater inflater = new Inflater();
    private final Deflater deflater = new Deflater();
    private final Object sendLock = new Object();
    private volatile boolean closed;

    private Connection(Socket socket) throws IOException {
        this.socket = socket;
        in = new BufferedInputStream(socket.getInputStream(), 1 << 16);
        out = new BufferedOutputStream(socket.getOutputStream(), 1 << 16);
    }

    public static Connection open(String host, int port, int timeoutMillis) throws IOException {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            return new Connection(socket);
        } catch (IOException failure) {
            socket.close();
            throw failure;
        }
    }

    /** Sets how long a read may block before failing (0: forever). */
    public void readTimeout(int millis) throws IOException {
        socket.setSoTimeout(millis);
    }

    /** Packets of at least {@code threshold} bytes are compressed from now on; negative disables it. */
    public void compression(int threshold) {
        compressionThreshold = threshold;
    }

    /** Encrypts both directions with AES/CFB8, the shared secret serving as key and IV, from now on. */
    public void encrypt(byte[] sharedSecret) throws IOException {
        try {
            SecretKeySpec key = new SecretKeySpec(sharedSecret, "AES");
            Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
            decrypt.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(sharedSecret));
            Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
            encrypt.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(sharedSecret));
            synchronized (sendLock) {
                out.flush();
                in = new BufferedInputStream(new CipherInputStream(socket.getInputStream(), decrypt), 1 << 16);
                out = new BufferedOutputStream(new CipherOutputStream(socket.getOutputStream(), encrypt), 1 << 16);
            }
        } catch (GeneralSecurityException failure) {
            throw new IOException("AES/CFB8 is unavailable: " + failure.getMessage(), failure);
        }
    }

    /** Sends one packet: its id followed by the body. */
    public void send(int packetId, PacketBuffer body) throws IOException {
        PacketBuffer packet = new PacketBuffer().writeVarInt(packetId);
        byte[] payload = body == null ? new byte[0] : body.toByteArray();
        packet.writeBytes(payload);
        byte[] data = packet.toByteArray();
        synchronized (sendLock) {
            if (compressionThreshold >= 0) {
                if (data.length >= compressionThreshold) {
                    deflater.reset();
                    deflater.setInput(data);
                    deflater.finish();
                    byte[] buffer = new byte[Math.max(64, data.length + 64)];
                    int compressed = 0;
                    while (!deflater.finished()) {
                        if (compressed == buffer.length) {
                            buffer = java.util.Arrays.copyOf(buffer, buffer.length * 2);
                        }
                        compressed += deflater.deflate(buffer, compressed, buffer.length - compressed);
                    }
                    writeVarInt(out, PacketBuffer.varIntSize(data.length) + compressed);
                    writeVarInt(out, data.length);
                    out.write(buffer, 0, compressed);
                } else {
                    writeVarInt(out, data.length + 1);
                    writeVarInt(out, 0);
                    out.write(data);
                }
            } else {
                writeVarInt(out, data.length);
                out.write(data);
            }
            out.flush();
        }
    }

    /** Reads the next packet; its first field is the packet id (read it with {@link PacketBuffer#readVarInt}). */
    public PacketBuffer read() throws IOException {
        int length = readVarInt(in);
        if (length <= 0 || length > MAX_FRAME) {
            throw new IOException("Bad packet length " + length);
        }
        byte[] frame = new byte[length];
        readFully(in, frame);
        if (compressionThreshold < 0) {
            return PacketBuffer.wrap(frame);
        }
        PacketBuffer header = PacketBuffer.wrap(frame);
        int dataLength = header.readVarInt();
        int offset = length - header.remaining();
        if (dataLength == 0) {
            return new PacketBuffer(frame, offset, length - offset);
        }
        if (dataLength < 0 || dataLength > MAX_UNCOMPRESSED) {
            throw new IOException("Bad uncompressed length " + dataLength);
        }
        byte[] data = new byte[dataLength];
        inflater.reset();
        inflater.setInput(frame, offset, length - offset);
        try {
            int produced = 0;
            while (produced < dataLength) {
                int count = inflater.inflate(data, produced, dataLength - produced);
                if (count == 0 && (inflater.finished() || inflater.needsInput())) {
                    break;
                }
                produced += count;
            }
            if (produced != dataLength) {
                throw new IOException("Packet inflated to " + produced + " of " + dataLength + " bytes.");
            }
        } catch (DataFormatException malformed) {
            throw new IOException("Corrupt compressed packet: " + malformed.getMessage(), malformed);
        }
        return PacketBuffer.wrap(data);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing is best effort.
        }
        inflater.end();
        deflater.end();
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write(value & 0x7F | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int read = in.read();
            if (read < 0) {
                throw new EOFException("Connection closed");
            }
            value |= (read & 0x7F) << shift;
            if ((read & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("VarInt too long");
    }

    private static void readFully(InputStream in, byte[] target) throws IOException {
        int read = 0;
        while (read < target.length) {
            int count = in.read(target, read, target.length - read);
            if (count < 0) {
                throw new EOFException("Connection closed");
            }
            read += count;
        }
    }
}
