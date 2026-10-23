package alkaline.client.servers;

import java.util.Objects;

/**
 * A saved multiplayer server: the player's name for it, the address they typed, and what to do when it offers a
 * resource pack.
 */
public record ServerEntry(String name, String address, Packs packs) {
    /** ServerData.ServerResourceMode: ask each time, use the server's pack, or decline it. */
    public enum Packs { PROMPT, ENABLED, DISABLED }

    public ServerEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(address, "address");
        name = name.strip();
        address = address.strip();
        if (address.isEmpty()) {
            throw new IllegalArgumentException("A server needs an address.");
        }
        if (name.isEmpty()) {
            name = address;
        }
        packs = packs == null ? Packs.PROMPT : packs;
    }

    public ServerEntry(String name, String address) {
        this(name, address, Packs.PROMPT);
    }

    public ServerEntry withPacks(Packs packs) {
        return new ServerEntry(name, address, packs);
    }
}
