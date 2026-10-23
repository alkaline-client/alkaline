package alkaline.client.servers;

import java.util.Objects;

/** A saved multiplayer server: the player's name for it and the address they typed. */
public record ServerEntry(String name, String address) {
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
    }
}
