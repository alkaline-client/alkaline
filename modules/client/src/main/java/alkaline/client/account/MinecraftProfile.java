package alkaline.client.account;

import java.util.Objects;

/** A Minecraft Java profile: the player's UUID (undashed hex), name, and active skin texture URL (may be null). */
public record MinecraftProfile(String uuid, String name, String skinUrl) {
    public MinecraftProfile {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
    }
}
