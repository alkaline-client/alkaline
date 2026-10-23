package alkaline.client.servers;

import alkaline.config.Json;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Server List Ping for protocol 47: handshake into the status state, read the status JSON, then time one
 * ping/pong round trip. Every ping runs on its own virtual thread with connect and read timeouts, so a dead
 * server never blocks the menu.
 */
public final class ServerPinger {
    private static final int TIMEOUT_MILLIS = 5_000;
    private static final int MAX_STATUS_BYTES = 1 << 20;
    private static final String COLOR_CODES = "0123456789abcdef";
    private static final List<String> COLOR_NAMES = List.of("black", "dark_blue", "dark_green", "dark_aqua",
            "dark_red", "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple",
            "yellow", "white");

    private final Executor executor = command -> Thread.ofVirtual().name("alkaline-server-ping").start(command);

    public CompletableFuture<ServerStatus> ping(String address) {
        return CompletableFuture.supplyAsync(() -> pingNow(address), executor);
    }

    static ServerStatus pingNow(String text) {
        ServerAddress typed;
        try {
            typed = ServerAddress.parse(text);
        } catch (IllegalArgumentException invalid) {
            return ServerStatus.failed(invalid.getMessage());
        }
        ServerAddress target = typed.resolve();
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(target.host(), target.port()), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream body = new DataOutputStream(handshake);
            writeVarInt(body, 0x00);
            writeVarInt(body, ServerStatus.CLIENT_PROTOCOL);
            writeString(body, typed.host());
            body.writeShort(target.port());
            writeVarInt(body, 1); // next state: status
            writePacket(out, handshake.toByteArray());
            writePacket(out, new byte[]{0x00}); // status request
            out.flush();

            readVarInt(in); // packet length
            if (readVarInt(in) != 0x00) {
                return ServerStatus.failed("Unexpected reply");
            }
            String json = readString(in);

            ByteArrayOutputStream ping = new ByteArrayOutputStream();
            DataOutputStream pingBody = new DataOutputStream(ping);
            writeVarInt(pingBody, 0x01);
            long sent = System.nanoTime();
            pingBody.writeLong(sent);
            writePacket(out, ping.toByteArray());
            out.flush();
            long latency = -1;
            try {
                readVarInt(in);
                if (readVarInt(in) == 0x01 && in.readLong() == sent) {
                    latency = (System.nanoTime() - sent) / 1_000_000L;
                }
            } catch (IOException noPong) {
                // Some servers close after the status; the status alone is still useful.
            }
            return parseStatus(json, latency);
        } catch (UnknownHostException unknown) {
            return ServerStatus.failed("Unknown host");
        } catch (ConnectException refused) {
            return ServerStatus.failed("Connection refused");
        } catch (SocketTimeoutException timeout) {
            return ServerStatus.failed("Timed out");
        } catch (IOException | RuntimeException failure) {
            return ServerStatus.failed(failure.getMessage() == null ? "Can't reach server" : failure.getMessage());
        }
    }

    static ServerStatus parseStatus(String json, long latency) {
        if (!(Json.parse(json) instanceof Map<?, ?> root)) {
            return ServerStatus.failed("Malformed status");
        }
        String versionName = "";
        int protocol = -1;
        if (root.get("version") instanceof Map<?, ?> version) {
            versionName = version.get("name") instanceof String name ? name : "";
            protocol = version.get("protocol") instanceof Number number ? number.intValue() : -1;
        }
        int online = 0;
        int max = 0;
        List<String> sample = new ArrayList<>();
        if (root.get("players") instanceof Map<?, ?> players) {
            online = players.get("online") instanceof Number number ? number.intValue() : 0;
            max = players.get("max") instanceof Number number ? number.intValue() : 0;
            if (players.get("sample") instanceof List<?> entries) {
                for (Object entry : entries) {
                    if (entry instanceof Map<?, ?> player && player.get("name") instanceof String name && sample.size() < 12) {
                        sample.add(name);
                    }
                }
            }
        }
        StringBuilder motd = new StringBuilder();
        flatten(root.get("description"), "", motd);
        byte[] favicon = null;
        if (root.get("favicon") instanceof String data && data.startsWith("data:image/png;base64,")) {
            try {
                favicon = Base64.getMimeDecoder().decode(data.substring("data:image/png;base64,".length()));
            } catch (IllegalArgumentException malformed) {
                favicon = null;
            }
        }
        return new ServerStatus(true, null, latency, versionName, protocol, online, max, motd.toString().strip(),
                favicon, List.copyOf(sample));
    }

    /** Appends a chat component as legacy-formatted text, inheriting the parent's style codes. */
    public static void flatten(Object component, String inherited, StringBuilder out) {
        if (component instanceof String text) {
            out.append(inherited).append(text);
        } else if (component instanceof List<?> parts) {
            for (Object part : parts) {
                flatten(part, inherited, out);
            }
        } else if (component instanceof Map<?, ?> map) {
            StringBuilder style = new StringBuilder(inherited);
            if (map.get("color") instanceof String color) {
                int index = COLOR_NAMES.indexOf(color);
                if (index >= 0) {
                    style.setLength(0); // a color resets formatting, as § color codes do
                    style.append('§').append(COLOR_CODES.charAt(index));
                }
            }
            appendFormat(map, "bold", 'l', style);
            appendFormat(map, "italic", 'o', style);
            appendFormat(map, "underlined", 'n', style);
            appendFormat(map, "strikethrough", 'm', style);
            appendFormat(map, "obfuscated", 'k', style);
            String prefix = style.isEmpty() ? "§r" : "§r" + style;
            if (map.get("text") instanceof String text && !text.isEmpty()) {
                out.append(prefix).append(text);
            }
            if (map.get("extra") instanceof List<?> extra) {
                for (Object part : extra) {
                    flatten(part, style.toString(), out);
                }
            }
        }
    }

    private static void appendFormat(Map<?, ?> map, String key, char code, StringBuilder style) {
        if (Boolean.TRUE.equals(map.get(key))) {
            style.append('§').append(code);
        }
    }

    private static void writePacket(DataOutputStream out, byte[] body) throws IOException {
        writeVarInt(out, body.length);
        out.write(body);
    }

    static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    static int readVarInt(DataInputStream in) throws IOException {
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

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > MAX_STATUS_BYTES) {
            throw new IOException("Status too large");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
