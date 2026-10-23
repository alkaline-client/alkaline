package alkaline.client.servers;

import java.util.Hashtable;
import java.util.Locale;
import java.util.Objects;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;

/**
 * A parsed {@code host[:port]} server address. IPv6 literals use brackets ({@code [::1]:25565}). Without an
 * explicit port, {@link #resolve()} follows the {@code _minecraft._tcp} SRV record the way vanilla does.
 */
public record ServerAddress(String host, int port, boolean explicitPort) {
    public static final int DEFAULT_PORT = 25565;

    public ServerAddress {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("Enter a server address.");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Ports run from 1 to 65535.");
        }
    }

    public static ServerAddress parse(String text) {
        String value = Objects.requireNonNull(text, "text").strip();
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("Unclosed IPv6 bracket.");
            }
            String host = value.substring(1, close);
            String rest = value.substring(close + 1);
            if (rest.isEmpty()) {
                return new ServerAddress(host, DEFAULT_PORT, false);
            }
            if (!rest.startsWith(":")) {
                throw new IllegalArgumentException("Unexpected text after the IPv6 address.");
            }
            return new ServerAddress(host, port(rest.substring(1)), true);
        }
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && value.indexOf(':') == colon) {
            return new ServerAddress(value.substring(0, colon), port(value.substring(colon + 1)), true);
        }
        // Several colons without brackets: a bare IPv6 literal.
        return new ServerAddress(value, DEFAULT_PORT, false);
    }

    /** Returns true when {@code text} parses as an address; for live validation while typing. */
    public static boolean isValid(String text) {
        try {
            ServerAddress address = parse(text);
            return !address.host().contains(" ");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static int port(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("The port must be a number.");
        }
    }

    /**
     * The address to connect to: the SRV target when the player gave no port and one is published, otherwise
     * this address. Blocking DNS; call off the UI thread.
     */
    public ServerAddress resolve() {
        if (explicitPort || host.indexOf(':') >= 0) {
            return this;
        }
        try {
            Hashtable<String, String> environment = new Hashtable<>();
            environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            environment.put("com.sun.jndi.dns.timeout.initial", "1500");
            environment.put("com.sun.jndi.dns.timeout.retries", "1");
            InitialDirContext context = new InitialDirContext(environment);
            try {
                Attribute records = context.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"}).get("srv");
                if (records != null && records.size() > 0) {
                    // "priority weight port target."
                    String[] fields = records.get(0).toString().split(" ");
                    if (fields.length == 4) {
                        String target = fields[3].endsWith(".") ? fields[3].substring(0, fields[3].length() - 1) : fields[3];
                        return new ServerAddress(target, Integer.parseInt(fields[2]), true);
                    }
                }
            } finally {
                context.close();
            }
        } catch (NamingException | RuntimeException noRecord) {
            // No SRV record (the common case) or no DNS: connect to the host directly.
        }
        return this;
    }

    @Override
    public String toString() {
        String shown = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return explicitPort ? shown + ":" + port : shown.toLowerCase(Locale.ROOT);
    }
}
