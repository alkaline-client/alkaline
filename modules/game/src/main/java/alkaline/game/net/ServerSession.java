package alkaline.game.net;

import alkaline.game.data.nbt.NBTTagCompound;
import alkaline.game.entity.Metadata;
import alkaline.game.inventory.ItemStack;
import alkaline.game.inventory.Trade;
import alkaline.game.world.ClientWorld;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * A logged-in play session with a 1.8.9 server. A reader thread decodes every protocol 47 play packet: world data
 * (chunks, block changes, explosions) goes straight into the {@link #world() world}, keep-alives are answered at
 * once, and everything else becomes an {@link Event} the game thread drains in arrival order. {@link #decode} is
 * the pure packet-to-event half, so each layout is testable without a connection.
 *
 * <p>Units are converted here: entity positions from 1/32 blocks, angles from 1/256 turns to degrees,
 * velocities from 1/8000 blocks per tick, sound positions from 1/8 blocks and sound pitch from 1/63.
 */
public final class ServerSession implements AutoCloseable {
    /** What the game thread learns from the server, in arrival order. */
    public sealed interface Event {
    }

    public record JoinGame(int entityId, int gameMode, int dimension, int difficulty, String levelType)
            implements Event {
    }

    /** Player Position And Look; {@code relative} bits: x 1, y 2, z 4, yaw 8, pitch 16. */
    public record Teleport(double x, double y, double z, float yaw, float pitch, int relative) implements Event {
    }

    public record Time(long worldAge, long timeOfDay) implements Event {
    }

    public record Health(float health, int food, float saturation) implements Event {
    }

    /** {@code position}: 0 chat, 1 system, 2 above the hotbar. */
    public record Chat(String json, int position) implements Event {
    }

    public record Respawn(int dimension, int difficulty, int gameMode, String levelType) implements Event {
    }

    /** Ability flags: invulnerable 1, flying 2, allow flying 4, creative 8. */
    public record Abilities(int flags, float flySpeed, float walkSpeed) implements Event {
    }

    public record GameState(int reason, float value) implements Event {
    }

    public record Disconnected(String reason) implements Event {
    }

    /** The world spawn the compass points to. */
    public record SpawnPosition(int x, int y, int z) implements Event {
    }

    /** A player went to bed at the given block. */
    public record UseBed(int entityId, int x, int y, int z) implements Event {
    }

    public record SpawnPlayer(int entityId, UUID uuid, double x, double y, double z, float yaw, float pitch,
                              int heldItem, Metadata metadata) implements Event {
    }

    /** Spawn Mob: {@code type} is the 1.8.9 entity type id (50 creeper ... 120 villager). */
    public record SpawnMob(int entityId, int type, double x, double y, double z, float yaw, float pitch,
                           float headYaw, double motionX, double motionY, double motionZ,
                           Metadata metadata) implements Event {
    }

    /** Spawn Object: {@code type} is the object id (2 item, 60 arrow ...), {@code data} its extra integer. */
    public record SpawnObject(int entityId, int type, double x, double y, double z, float pitch, float yaw, int data,
                              double motionX, double motionY, double motionZ) implements Event {
    }

    /** A painting: its EnumArt title, the block it hangs on and the horizontal facing (0 south ... 3 east). */
    public record SpawnPainting(int entityId, String title, int x, int y, int z, int facing) implements Event {
    }

    public record SpawnExperienceOrb(int entityId, double x, double y, double z, int count) implements Event {
    }

    /** Spawn Global Entity: type 1 is a lightning bolt, the only one. */
    public record SpawnGlobalEntity(int entityId, int type, double x, double y, double z) implements Event {
    }

    /** Collect Item: {@code collector} picked up {@code collected} (an item, arrow or orb), which leaves the world. */
    public record CollectItem(int collected, int collector) implements Event {
    }

    /** Entity Relative Move, Look, or both: deltas in blocks, angles in degrees (when {@code look}). */
    public record EntityMove(int entityId, double dx, double dy, double dz, boolean look, float yaw, float pitch,
                             boolean onGround) implements Event {
    }

    public record EntityTeleport(int entityId, double x, double y, double z, float yaw, float pitch, boolean onGround)
            implements Event {
    }

    public record EntityHeadLook(int entityId, float headYaw) implements Event {
    }

    /** Entity Velocity in blocks per tick (also the local player's knockback). */
    public record EntityVelocity(int entityId, double motionX, double motionY, double motionZ) implements Event {
    }

    public record DestroyEntities(int[] entityIds) implements Event {
    }

    /** Entity Status: 2 hurt, 3 dead, 9 finished using an item... */
    public record EntityStatus(int entityId, int status) implements Event {
    }

    /** Animation: 0 swing arm, 1 hurt, 2 leave bed, 3 eat, 4 critical hit, 5 magic critical hit. */
    public record EntityAnimation(int entityId, int animation) implements Event {
    }

    public record EntityMetadata(int entityId, Metadata metadata) implements Event {
    }

    /** Equipment slot 0 held item, 1 boots, 2 leggings, 3 chestplate, 4 helmet; null item clears it. */
    public record EntityEquipment(int entityId, int slot, ItemStack item) implements Event {
    }

    public record EntityEffect(int entityId, int effect, int amplifier, int durationTicks, boolean hideParticles)
            implements Event {
    }

    public record RemoveEntityEffect(int entityId, int effect) implements Event {
    }

    /** Attach Entity: {@code entity} rides {@code vehicle} (-1 dismounts), or is leashed to it. */
    public record EntityAttach(int entityId, int vehicleId, boolean leash) implements Event {
    }

    /** An attribute modifier: operation 0 adds, 1 adds a multiple of the base, 2 multiplies the total. */
    public record AttributeModifier(UUID uuid, double amount, int operation) {
    }

    /** One attribute as the server has it, for example generic.movementSpeed. */
    public record Attribute(String key, double base, List<AttributeModifier> modifiers) {
    }

    public record EntityProperties(int entityId, List<Attribute> attributes) implements Event {
    }

    /** Update Entity NBT (vanilla's client only reads it for the camera entity; nothing sends it in practice). */
    public record EntityNbt(int entityId, NBTTagCompound nbt) implements Event {
    }

    public record Experience(float progress, int level, int total) implements Event {
    }

    /** Set Slot; window 0 is the player's inventory, -1 the cursor. */
    public record SetSlot(int window, int slot, ItemStack item) implements Event {
    }

    public record WindowItems(int window, ItemStack[] items) implements Event {
    }

    /** The server's verdict on a click; a rejected one must be acknowledged before the window takes more clicks. */
    public record ConfirmTransaction(int window, short action, boolean accepted) implements Event {
    }

    /**
     * The server opened a window: its type is the GUI id (minecraft:chest, minecraft:furnace... or EntityHorse),
     * its title a chat component (JSON), {@code slots} the size of its own part (0 for crafting tables, enchanting
     * tables and anvils), and {@code entityId} the horse for EntityHorse windows (else -1).
     */
    public record OpenWindow(int window, String type, String titleJson, int slots, int entityId) implements Event {
    }

    /** The server closed the open window. */
    public record CloseWindow(int window) implements Event {
    }

    /** Window Property: a furnace's burn and cook times, an enchantment's levels, a beacon's powers... */
    public record WindowProperty(int window, int property, int value) implements Event {
    }

    /** MC|TrList: the trades of the villager window {@code window}. */
    public record Trades(int window, List<Trade> trades) implements Event {
    }

    /** The hotbar slot (0..8) the server selected. */
    public record HeldItem(int slot) implements Event {
    }

    /**
     * One Player List Item entry. Fields the action does not carry are null or -1: {@code ADD} sets every field
     * ({@code textures} is the base64 "textures" property value, or null), the others only theirs.
     */
    public record PlayerListEntry(UUID uuid, String name, String textures, int gameMode, int ping,
                                  String displayNameJson) {
    }

    /** Player List Item: action 0 add, 1 game mode, 2 latency, 3 display name, 4 remove. */
    public record PlayerList(int action, List<PlayerListEntry> entries) implements Event {
    }

    /** The player list's header and footer (chat components; "" when cleared). */
    public record PlayerListHeaderFooter(String headerJson, String footerJson) implements Event {
    }

    /** Scoreboard Objective: mode 0 create, 1 remove, 2 update its display name. */
    public record ScoreboardObjective(String name, int mode, String displayName) implements Event {
    }

    /** Update Score: action 0 sets {@code value}, 1 removes the entry. */
    public record UpdateScore(String entry, int action, String objective, int value) implements Event {
    }

    /** Display Scoreboard: position 0 list, 1 sidebar, 2 below name, 3..18 team-colored sidebars. */
    public record DisplayScoreboard(int position, String objective) implements Event {
    }

    /**
     * Teams: mode 0 create, 1 remove, 2 update info, 3 add players, 4 remove players. {@code flags} bit 0 is friendly
     * fire and bit 1 seeing invisible teammates; {@code nameTagVisibility} is always, never, hideForOtherTeams or
     * hideForOwnTeam. Both are absent (0 and null) in modes that carry no team info.
     */
    public record Team(String name, int mode, String displayName, String prefix, String suffix, int color,
                       List<String> players, int flags, String nameTagVisibility) implements Event {
    }

    /** Particle: the EnumParticleTypes id and its arguments (item or block state ids). */
    public record Particle(int type, boolean longDistance, double x, double y, double z, float offsetX,
                           float offsetY, float offsetZ, float speed, int count, int[] arguments) implements Event {
    }

    /** Effect (sounds and particles of world events): 2001 block broken (data = state id), 2000 smoke... */
    public record WorldEffect(int type, int x, int y, int z, int data, boolean global) implements Event {
    }

    /** A named sound (sounds.json event, for example random.click) at a position. */
    public record Sound(String name, double x, double y, double z, float volume, float pitch) implements Event {
    }

    /** Explosion: the blast center and strength, and the push the player receives. */
    public record Explosion(double x, double y, double z, float strength, double pushX, double pushY, double pushZ)
            implements Event {
    }

    /** Block Action: a note block's note, a chest's open count, a piston's push... ({@code block} is the block id). */
    public record BlockAction(int x, int y, int z, int data1, int data2, int block) implements Event {
    }

    /** Block Break Animation: crack stage 0..9 that {@code breakerId} has dug; anything else clears it. */
    public record BlockBreak(int breakerId, int x, int y, int z, int stage) implements Event {
    }

    /** A tile entity's data: action 1 spawner, 2 command block, 3 beacon, 4 skull, 5 flower pot, 6 banner. */
    public record BlockEntity(int x, int y, int z, int action, NBTTagCompound nbt) implements Event {
    }

    /** A sign's four lines (chat components). */
    public record UpdateSign(int x, int y, int z, String[] lines) implements Event {
    }

    /** The server asks the player to write the sign at this block. */
    public record OpenSignEditor(int x, int y, int z) implements Event {
    }

    /**
     * Map data: decorations as {@code type << 24 | x << 16 | z << 8 | rotation} (unsigned bytes), and when
     * {@code columns > 0} a {@code columns} by {@code rows} patch of colors at ({@code x}, {@code z}).
     */
    public record MapData(int mapId, int scale, int[] icons, int columns, int rows, int x, int z, byte[] colors)
            implements Event {
    }

    /** Title: action 0 title, 1 subtitle, 2 times, 3 clear, 4 reset; text is a chat component (JSON). */
    public record Title(int action, String json, int fadeIn, int stay, int fadeOut) implements Event {
    }

    /** Statistics the server sent, by statistic name. */
    public record Statistics(Map<String, Integer> values) implements Event {
    }

    /** Tab-Complete: the server's completions of the text the client sent. */
    public record TabComplete(List<String> matches) implements Event {
    }

    /** A plugin channel message the client has no decoder for. */
    public record PluginMessage(String channel, byte[] data) implements Event {
    }

    /** MC|Brand: the server software's name. */
    public record ServerBrand(String brand) implements Event {
    }

    public record ServerDifficulty(int difficulty) implements Event {
    }

    /**
     * Combat Event: 0 enter combat, 1 end combat ({@code duration} ticks, {@code entityId}), 2 entity died
     * ({@code playerId} died, killed by {@code entityId}, with the death message as JSON).
     */
    public record Combat(int event, int duration, int playerId, int entityId, String messageJson) implements Event {
    }

    /** Camera: the entity the view follows (a spectator inside a mob); the player's own id returns to it. */
    public record Camera(int entityId) implements Event {
    }

    /**
     * World Border: action 0 size, 1 lerp size, 2 center, 3 initialize, 4 warning time, 5 warning blocks. Fields
     * the action does not carry are NaN or -1.
     */
    public record WorldBorder(int action, double centerX, double centerZ, double oldDiameter, double newDiameter,
                              long speedMillis, int portalBoundary, int warningTime, int warningBlocks)
            implements Event {
    }

    /** The server offers a resource pack; the client must answer with {@link #sendResourcePackStatus}. */
    public record ResourcePack(String url, String hash) implements Event {
    }

    /** Resource Pack Status results. */
    public static final int PACK_LOADED = 0, PACK_DECLINED = 1, PACK_FAILED = 2, PACK_ACCEPTED = 3;

    /** Player Digging statuses. */
    public static final int DIG_START = 0, DIG_ABORT = 1, DIG_FINISH = 2, DROP_STACK = 3, DROP_ITEM = 4,
            RELEASE_USE_ITEM = 5;

    private final Connection connection;
    private final Login.Result profile;
    private final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
    private final Thread reader;
    private volatile ClientWorld world = new ClientWorld(true);
    private volatile boolean closed;
    private int entityId;
    private volatile String locale = "en_US";
    private volatile int viewDistance = 8;

    private ServerSession(Connection connection, Login.Result profile) {
        this.connection = connection;
        this.profile = profile;
        reader = Thread.ofPlatform().daemon().name("alkaline-network").unstarted(this::readLoop);
    }

    /**
     * Connects and logs in (blocking; call off the render thread), then starts reading play packets.
     *
     * @param typedHost the address as the player typed it (sent in the handshake)
     * @param session   joins online-mode servers through the session service; null for offline mode only
     */
    public static ServerSession connect(String host, int port, String typedHost, String username,
                                        Login.SessionJoin session) throws IOException {
        Connection connection = Connection.open(host, port, 10_000);
        try {
            connection.readTimeout(30_000);
            Login.Result profile = Login.login(connection, typedHost, port, username, session);
            ServerSession result = new ServerSession(connection, profile);
            connection.readTimeout(0);
            result.reader.start();
            return result;
        } catch (IOException | RuntimeException failure) {
            connection.close();
            throw failure;
        }
    }

    public Login.Result profile() {
        return profile;
    }

    /** The current dimension's blocks; replaced on dimension changes. */
    public ClientWorld world() {
        return world;
    }

    public int entityId() {
        return entityId;
    }

    public boolean isClosed() {
        return closed;
    }

    /** Hands queued events to {@code consumer} on the calling (game) thread. */
    public void drainEvents(Consumer<Event> consumer) {
        Event event;
        while ((event = events.poll()) != null) {
            consumer.accept(event);
        }
    }

    // ---- sending -------------------------------------------------------------------------------------------

    public void send(int packetId, PacketBuffer body) {
        if (closed) {
            return;
        }
        try {
            connection.send(packetId, body);
        } catch (IOException failure) {
            fail("Connection lost: " + failure.getMessage());
        }
    }

    public void sendChat(String message) {
        send(0x01, new PacketBuffer().writeString(message.length() > 100 ? message.substring(0, 100) : message));
    }

    /** Client Settings; sent automatically on joining, and again whenever this is called afterwards. */
    public void sendClientSettings(String locale, int viewDistance) {
        this.locale = locale;
        this.viewDistance = viewDistance;
        if (entityId != 0) {
            try {
                writeClientSettings();
            } catch (IOException failure) {
                fail("Connection lost: " + failure.getMessage());
            }
        }
    }

    private void writeClientSettings() throws IOException {
        connection.send(0x15, new PacketBuffer().writeString(locale).writeByte(viewDistance).writeByte(0)
                .writeBoolean(true).writeByte(0x7F));
    }

    /** Client Status: 0 respawn, 1 request statistics, 2 open inventory achievement. */
    public void sendClientStatus(int action) {
        send(0x16, new PacketBuffer().writeVarInt(action));
    }

    /**
     * Entity Action: 0/1 start/stop sneaking, 2 leave bed, 3/4 start/stop sprinting, 5 horse jump ({@code boost}
     * 0..100), 6 open the ridden horse's inventory.
     */
    public void sendEntityAction(int action, int boost) {
        send(0x0B, new PacketBuffer().writeVarInt(entityId).writeVarInt(action).writeVarInt(boost));
    }

    public void sendEntityAction(int action) {
        sendEntityAction(action, 0);
    }

    public void sendGround(boolean onGround) {
        send(0x03, new PacketBuffer().writeBoolean(onGround));
    }

    public void sendPosition(double x, double feetY, double z, boolean onGround) {
        send(0x04, new PacketBuffer().writeDouble(x).writeDouble(feetY).writeDouble(z).writeBoolean(onGround));
    }

    public void sendLook(float yaw, float pitch, boolean onGround) {
        send(0x05, new PacketBuffer().writeFloat(yaw).writeFloat(pitch).writeBoolean(onGround));
    }

    public void sendPositionLook(double x, double feetY, double z, float yaw, float pitch, boolean onGround) {
        send(0x06, new PacketBuffer().writeDouble(x).writeDouble(feetY).writeDouble(z).writeFloat(yaw)
                .writeFloat(pitch).writeBoolean(onGround));
    }

    /** Animation (the arm swing); vanilla sends it before the attack it belongs to. */
    public void sendSwing() {
        send(0x0A, new PacketBuffer());
    }

    /** Use Entity with type 1: attack {@code target}. */
    public void sendAttack(int target) {
        send(0x02, new PacketBuffer().writeVarInt(target).writeVarInt(1));
    }

    /**
     * Right-clicking an entity, as PlayerControllerMP does: Use Entity type 2 (interact at the hit point, relative
     * to the entity's position) and then type 0 (interact).
     */
    public void sendInteract(int target, float hitX, float hitY, float hitZ) {
        send(0x02, new PacketBuffer().writeVarInt(target).writeVarInt(2).writeFloat(hitX).writeFloat(hitY)
                .writeFloat(hitZ));
        send(0x02, new PacketBuffer().writeVarInt(target).writeVarInt(0));
    }

    /** Player Digging: see {@link #DIG_START} ... {@link #RELEASE_USE_ITEM}; face 0 down ... 5 east. */
    public void sendDigging(int status, int x, int y, int z, int face) {
        send(0x07, new PacketBuffer().writeByte(status).writePosition(x, y, z).writeByte(face));
    }

    /**
     * Player Block Placement: a right click on the {@code face} of a block, with the held stack and the hit point
     * inside the block in sixteenths (0..16).
     */
    public void sendBlockPlacement(int x, int y, int z, int face, ItemStack held, float hitX, float hitY, float hitZ) {
        send(0x08, new PacketBuffer().writePosition(x, y, z).writeByte(face).writeSlot(held)
                .writeByte((int) (hitX * 16.0F)).writeByte((int) (hitY * 16.0F)).writeByte((int) (hitZ * 16.0F)));
    }

    /** Player Block Placement at position -1 and face 255: use the held item in the air. */
    public void sendUseItem(ItemStack held) {
        send(0x08, new PacketBuffer().writeLong(-1L).writeByte(255).writeSlot(held).writeByte(0).writeByte(0)
                .writeByte(0));
    }

    /** Steer Vehicle: strafe and forward input (-1..1 scaled by 0.98), flags jump 1, unmount 2. */
    public void sendSteerVehicle(float strafe, float forward, boolean jump, boolean unmount) {
        send(0x0C, new PacketBuffer().writeFloat(strafe).writeFloat(forward)
                .writeByte((jump ? 1 : 0) | (unmount ? 2 : 0)));
    }

    /** Click Window: a click the client already applied, with the stack it saw in the slot and the action number. */
    public void sendClickWindow(int window, int slot, int button, short action, int mode, ItemStack clicked) {
        send(0x0E, new PacketBuffer().writeByte(window).writeShort(slot).writeByte(button).writeShort(action)
                .writeByte(mode).writeSlot(clicked));
    }

    /** Close Window. */
    public void sendCloseWindow(int window) {
        send(0x0D, new PacketBuffer().writeByte(window));
    }

    /** Confirm Transaction: acknowledges a rejected click so the server accepts clicks again. */
    public void sendConfirmTransaction(int window, short action) {
        send(0x0F, new PacketBuffer().writeByte(window).writeShort(action).writeBoolean(true));
    }

    /** Creative Inventory Action: puts {@code item} into window 0's {@code slot} (-1 drops it). */
    public void sendCreativeSlot(int slot, ItemStack item) {
        send(0x10, new PacketBuffer().writeShort(slot).writeSlot(item));
    }

    /** Enchant Item: the enchantment table's option 0..2 (also a window's button for other containers). */
    public void sendEnchantItem(int window, int option) {
        send(0x11, new PacketBuffer().writeByte(window).writeByte(option));
    }

    /** Update Sign: the four lines as chat components (JSON). */
    public void sendUpdateSign(int x, int y, int z, String[] linesJson) {
        PacketBuffer body = new PacketBuffer().writePosition(x, y, z);
        for (int line = 0; line < 4; line++) {
            body.writeString(line < linesJson.length ? linesJson[line] : "\"\"");
        }
        send(0x12, body);
    }

    /** Held Item Change: the hotbar slot (0..8) now in hand. */
    public void sendHeldItem(int slot) {
        send(0x09, new PacketBuffer().writeShort(slot));
    }

    /** Player Abilities (the flying flag, 2, is what the client reports). */
    public void sendAbilities(int flags, float flySpeed, float walkSpeed) {
        send(0x13, new PacketBuffer().writeByte(flags).writeFloat(flySpeed).writeFloat(walkSpeed));
    }

    /** Tab-Complete: the chat text so far, and the looked-at block when there is one. */
    public void sendTabComplete(String text, boolean hasBlock, int x, int y, int z) {
        PacketBuffer body = new PacketBuffer().writeString(text.length() > 32767 ? text.substring(0, 32767) : text)
                .writeBoolean(hasBlock);
        if (hasBlock) {
            body.writePosition(x, y, z);
        }
        send(0x14, body);
    }

    /** Plugin Message on {@code channel} (at most 32767 bytes, as vanilla's C17 allows). */
    public void sendPluginMessage(String channel, PacketBuffer data) {
        byte[] bytes = data.toByteArray();
        if (bytes.length > 32767) {
            throw new IllegalArgumentException("Plugin message of " + bytes.length + " bytes is too large.");
        }
        send(0x17, new PacketBuffer().writeString(channel).writeBytes(bytes));
    }

    /** MC|TrSel: the villager trade the player turned to. */
    public void sendTradeSelect(int index) {
        sendPluginMessage("MC|TrSel", new PacketBuffer().writeInt(index));
    }

    /** MC|ItemName: the anvil's rename text. */
    public void sendItemName(String name) {
        sendPluginMessage("MC|ItemName", new PacketBuffer().writeString(name));
    }

    /** MC|Beacon: the beacon's chosen primary and secondary effect ids (0 for none). */
    public void sendBeacon(int primary, int secondary) {
        sendPluginMessage("MC|Beacon", new PacketBuffer().writeInt(primary).writeInt(secondary));
    }

    /** Spectate: teleport (as a spectator) to the entity with this UUID. */
    public void sendSpectate(UUID target) {
        send(0x18, new PacketBuffer().writeUuid(target));
    }

    /** Resource Pack Status for the pack with {@code hash}: one of {@link #PACK_LOADED} ... {@link #PACK_ACCEPTED}. */
    public void sendResourcePackStatus(String hash, int result) {
        send(0x19, new PacketBuffer().writeString(hash).writeVarInt(result));
    }

    // ---- receiving -----------------------------------------------------------------------------------------

    private void readLoop() {
        try {
            while (!closed) {
                PacketBuffer packet = connection.read();
                handle(packet.readVarInt(), packet);
            }
        } catch (IOException | RuntimeException failure) {
            if (!closed) {
                fail(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            }
        }
    }

    /** Packets that change the connection or the world directly; everything else is {@link #decode decoded}. */
    private void handle(int id, PacketBuffer in) throws IOException {
        switch (id) {
            case 0x00 -> connection.send(0x00, new PacketBuffer().writeVarInt(in.readVarInt()));
            case 0x01 -> {
                entityId = in.readInt();
                int gameMode = in.readUnsignedByte();
                int dimension = in.readByte();
                int difficulty = in.readUnsignedByte();
                in.readUnsignedByte(); // max players
                String levelType = in.readString();
                in.readBoolean(); // reduced debug info
                world = new ClientWorld(dimension == 0);
                // As vanilla's handleJoinGame does: the server decodes play packets only after sending this one.
                writeClientSettings();
                connection.send(0x17, new PacketBuffer().writeString("MC|Brand").writeString("vanilla"));
                events.add(new JoinGame(entityId, gameMode, dimension, difficulty, levelType));
            }
            case 0x07 -> {
                int dimension = in.readInt();
                int difficulty = in.readUnsignedByte();
                int gameMode = in.readUnsignedByte();
                String levelType = in.readString();
                world = new ClientWorld(dimension == 0);
                events.add(new Respawn(dimension, difficulty, gameMode, levelType));
            }
            case 0x21 -> {
                int chunkX = in.readInt();
                int chunkZ = in.readInt();
                boolean groundUp = in.readBoolean();
                int mask = in.readUnsignedShort();
                int size = in.readVarInt();
                if (size < 0 || size > in.remaining()) {
                    throw new IOException("Chunk data size " + size + " exceeds the packet.");
                }
                world.applyChunk(chunkX, chunkZ, groundUp, mask, in);
            }
            case 0x22 -> {
                int chunkX = in.readInt();
                int chunkZ = in.readInt();
                int count = count(in.readVarInt(), 65536, "multi block change");
                int[] positions = new int[count];
                int[] states = new int[count];
                for (int index = 0; index < count; index++) {
                    int horizontal = in.readUnsignedByte();
                    int y = in.readUnsignedByte();
                    // Horizontal byte: x << 4 | z.
                    positions[index] = (horizontal >> 4 & 15) | (horizontal & 15) << 4 | y << 8;
                    states[index] = in.readVarInt();
                }
                world.setBlocks(chunkX, chunkZ, positions, states, count);
            }
            case 0x23 -> {
                long position = in.readPosition();
                world.setBlock(PacketBuffer.positionX(position), PacketBuffer.positionY(position),
                        PacketBuffer.positionZ(position), in.readVarInt());
            }
            case 0x26 -> {
                boolean sky = in.readBoolean();
                int count = count(in.readVarInt(), 1024, "chunk bulk");
                int[] xs = new int[count];
                int[] zs = new int[count];
                int[] masks = new int[count];
                for (int index = 0; index < count; index++) {
                    xs[index] = in.readInt();
                    zs[index] = in.readInt();
                    masks[index] = in.readUnsignedShort();
                }
                if (sky != world.hasSkyLight()) {
                    world = new ClientWorld(sky);
                }
                for (int index = 0; index < count; index++) {
                    world.applyChunk(xs[index], zs[index], true, masks[index], in);
                }
            }
            case 0x27 -> {
                float x = in.readFloat();
                float y = in.readFloat();
                float z = in.readFloat();
                float strength = in.readFloat();
                int count = in.readInt();
                if (count < 0 || count > 65536) {
                    throw new IOException("Bad explosion record count " + count);
                }
                int[] positions = new int[count * 3];
                for (int index = 0; index < count * 3; index += 3) {
                    positions[index] = in.readByte() + (int) x;
                    positions[index + 1] = in.readByte() + (int) y;
                    positions[index + 2] = in.readByte() + (int) z;
                }
                // NetHandlerPlayClient.handleExplosion: Explosion.doExplosionB clears the blocks client-side.
                for (int index = 0; index < positions.length; index += 3) {
                    world.setBlock(positions[index], positions[index + 1], positions[index + 2], 0);
                }
                events.add(new Explosion(x, y, z, strength, in.readFloat(), in.readFloat(), in.readFloat()));
            }
            case 0x40 -> fail(in.readString());
            case 0x46 -> connection.compression(in.readVarInt());
            default -> {
                Event event = decode(id, in);
                if (event != null) {
                    events.add(event);
                }
            }
        }
    }

    /**
     * Decodes one play packet that only informs the game (every clientbound packet but keep-alive, join, respawn,
     * the chunk and block packets, explosions, disconnect and compression, which {@link ServerSession} applies
     * itself). Returns null for packets that carry nothing (Entity, 0x14) or ids protocol 47 does not define.
     */
    static Event decode(int id, PacketBuffer in) throws IOException {
        return switch (id) {
            case 0x02 -> new Chat(in.readString(), in.readByte());
            case 0x03 -> new Time(in.readLong(), in.readLong());
            case 0x04 -> new EntityEquipment(in.readVarInt(), in.readShort(), in.readSlot());
            case 0x05 -> {
                long position = in.readPosition();
                yield new SpawnPosition(PacketBuffer.positionX(position), PacketBuffer.positionY(position),
                        PacketBuffer.positionZ(position));
            }
            case 0x06 -> new Health(in.readFloat(), in.readVarInt(), in.readFloat());
            case 0x08 -> new Teleport(in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(),
                    in.readFloat(), in.readByte());
            case 0x09 -> new HeldItem(in.readByte());
            case 0x0A -> {
                int entity = in.readVarInt();
                long position = in.readPosition();
                yield new UseBed(entity, PacketBuffer.positionX(position), PacketBuffer.positionY(position),
                        PacketBuffer.positionZ(position));
            }
            case 0x0B -> new EntityAnimation(in.readVarInt(), in.readUnsignedByte());
            case 0x0C -> new SpawnPlayer(in.readVarInt(), in.readUuid(), fixed(in.readInt()),
                    fixed(in.readInt()), fixed(in.readInt()), angle(in.readByte()), angle(in.readByte()),
                    in.readShort(), Metadata.read(in));
            case 0x0D -> new CollectItem(in.readVarInt(), in.readVarInt());
            case 0x0E -> {
                int entity = in.readVarInt();
                int type = in.readByte();
                double x = fixed(in.readInt());
                double y = fixed(in.readInt());
                double z = fixed(in.readInt());
                float pitch = angle(in.readByte());
                float yaw = angle(in.readByte());
                int data = in.readInt();
                double vx = 0;
                double vy = 0;
                double vz = 0;
                if (data > 0) {
                    vx = in.readShort() / 8000.0;
                    vy = in.readShort() / 8000.0;
                    vz = in.readShort() / 8000.0;
                }
                yield new SpawnObject(entity, type, x, y, z, pitch, yaw, data, vx, vy, vz);
            }
            case 0x0F -> new SpawnMob(in.readVarInt(), in.readUnsignedByte(), fixed(in.readInt()),
                    fixed(in.readInt()), fixed(in.readInt()), angle(in.readByte()), angle(in.readByte()),
                    angle(in.readByte()), in.readShort() / 8000.0, in.readShort() / 8000.0, in.readShort() / 8000.0,
                    Metadata.read(in));
            case 0x10 -> {
                int entity = in.readVarInt();
                String title = in.readString();
                long position = in.readPosition();
                yield new SpawnPainting(entity, title, PacketBuffer.positionX(position),
                        PacketBuffer.positionY(position), PacketBuffer.positionZ(position), in.readUnsignedByte() & 3);
            }
            case 0x11 -> new SpawnExperienceOrb(in.readVarInt(), fixed(in.readInt()), fixed(in.readInt()),
                    fixed(in.readInt()), in.readShort());
            case 0x12 -> new EntityVelocity(in.readVarInt(), in.readShort() / 8000.0, in.readShort() / 8000.0,
                    in.readShort() / 8000.0);
            case 0x13 -> {
                int count = count(in.readVarInt(), 32768, "destroy entities");
                int[] ids = new int[count];
                for (int index = 0; index < count; index++) {
                    ids[index] = in.readVarInt();
                }
                yield new DestroyEntities(ids);
            }
            case 0x14 -> {
                in.readVarInt(); // Entity: a keep-alive for one entity; nothing to apply
                yield null;
            }
            case 0x15 -> new EntityMove(in.readVarInt(), in.readByte() / 32.0, in.readByte() / 32.0,
                    in.readByte() / 32.0, false, 0f, 0f, in.readBoolean());
            case 0x16 -> new EntityMove(in.readVarInt(), 0, 0, 0, true, angle(in.readByte()),
                    angle(in.readByte()), in.readBoolean());
            case 0x17 -> new EntityMove(in.readVarInt(), in.readByte() / 32.0, in.readByte() / 32.0,
                    in.readByte() / 32.0, true, angle(in.readByte()), angle(in.readByte()), in.readBoolean());
            case 0x18 -> new EntityTeleport(in.readVarInt(), fixed(in.readInt()), fixed(in.readInt()),
                    fixed(in.readInt()), angle(in.readByte()), angle(in.readByte()), in.readBoolean());
            case 0x19 -> new EntityHeadLook(in.readVarInt(), angle(in.readByte()));
            case 0x1A -> new EntityStatus(in.readInt(), in.readByte());
            case 0x1B -> new EntityAttach(in.readInt(), in.readInt(), in.readUnsignedByte() == 1);
            case 0x1C -> new EntityMetadata(in.readVarInt(), Metadata.read(in));
            case 0x1D -> new EntityEffect(in.readVarInt(), in.readByte() & 0xFF, in.readByte(),
                    in.readVarInt(), in.readByte() != 0);
            case 0x1E -> new RemoveEntityEffect(in.readVarInt(), in.readByte() & 0xFF);
            case 0x1F -> new Experience(in.readFloat(), in.readVarInt(), in.readVarInt());
            case 0x20 -> readProperties(in);
            case 0x24 -> {
                long position = in.readPosition();
                yield new BlockAction(PacketBuffer.positionX(position), PacketBuffer.positionY(position),
                        PacketBuffer.positionZ(position), in.readUnsignedByte(), in.readUnsignedByte(),
                        in.readVarInt() & 4095);
            }
            case 0x25 -> {
                int breaker = in.readVarInt();
                long position = in.readPosition();
                yield new BlockBreak(breaker, PacketBuffer.positionX(position), PacketBuffer.positionY(position),
                        PacketBuffer.positionZ(position), in.readByte());
            }
            case 0x28 -> {
                int type = in.readInt();
                long position = in.readPosition();
                yield new WorldEffect(type, PacketBuffer.positionX(position), PacketBuffer.positionY(position),
                        PacketBuffer.positionZ(position), in.readInt(), in.readBoolean());
            }
            case 0x29 -> new Sound(in.readString(), in.readInt() / 8.0, in.readInt() / 8.0, in.readInt() / 8.0,
                    in.readFloat(), in.readUnsignedByte() / 63.0F);
            case 0x2A -> {
                int type = in.readInt();
                boolean longDistance = in.readBoolean();
                double x = in.readFloat();
                double y = in.readFloat();
                double z = in.readFloat();
                float offsetX = in.readFloat();
                float offsetY = in.readFloat();
                float offsetZ = in.readFloat();
                float speed = in.readFloat();
                int count = in.readInt();
                // EnumParticleTypes.getArgumentCount: item crack 2, block crack and dust 1, others none.
                int[] arguments = new int[type == 36 ? 2 : type == 37 || type == 38 ? 1 : 0];
                for (int index = 0; index < arguments.length; index++) {
                    arguments[index] = in.readVarInt();
                }
                yield new Particle(type, longDistance, x, y, z, offsetX, offsetY, offsetZ, speed, count, arguments);
            }
            case 0x2B -> new GameState(in.readUnsignedByte(), in.readFloat());
            case 0x2C -> new SpawnGlobalEntity(in.readVarInt(), in.readByte(), fixed(in.readInt()),
                    fixed(in.readInt()), fixed(in.readInt()));
            case 0x2D -> {
                int window = in.readUnsignedByte();
                String type = in.readString();
                String title = in.readString();
                int slots = in.readUnsignedByte();
                yield new OpenWindow(window, type, title, slots, type.equals("EntityHorse") ? in.readInt() : -1);
            }
            case 0x2E -> new CloseWindow(in.readUnsignedByte());
            case 0x2F -> new SetSlot(in.readByte(), in.readShort(), in.readSlot());
            case 0x30 -> {
                int window = in.readUnsignedByte();
                int count = count(in.readShort(), 512, "window item");
                ItemStack[] items = new ItemStack[count];
                for (int index = 0; index < count; index++) {
                    items[index] = in.readSlot();
                }
                yield new WindowItems(window, items);
            }
            case 0x31 -> new WindowProperty(in.readUnsignedByte(), in.readShort(), in.readShort());
            case 0x32 -> new ConfirmTransaction(in.readByte(), in.readShort(), in.readBoolean());
            case 0x33 -> {
                long position = in.readPosition();
                String[] lines = new String[4];
                for (int line = 0; line < 4; line++) {
                    lines[line] = in.readString();
                }
                yield new UpdateSign(PacketBuffer.positionX(position), PacketBuffer.positionY(position),
                        PacketBuffer.positionZ(position), lines);
            }
            case 0x34 -> readMap(in);
            case 0x35 -> {
                long position = in.readPosition();
                yield new BlockEntity(PacketBuffer.positionX(position), PacketBuffer.positionY(position),
                        PacketBuffer.positionZ(position), in.readUnsignedByte(), in.readNbt());
            }
            case 0x36 -> {
                long position = in.readPosition();
                yield new OpenSignEditor(PacketBuffer.positionX(position), PacketBuffer.positionY(position),
                        PacketBuffer.positionZ(position));
            }
            case 0x37 -> {
                int count = count(in.readVarInt(), 65536, "statistics");
                Map<String, Integer> values = new LinkedHashMap<>();
                for (int index = 0; index < count; index++) {
                    values.put(in.readString(), in.readVarInt());
                }
                yield new Statistics(values);
            }
            case 0x38 -> readPlayerList(in);
            case 0x39 -> new Abilities(in.readByte(), in.readFloat(), in.readFloat());
            case 0x3A -> {
                int count = count(in.readVarInt(), 65536, "tab completion");
                List<String> matches = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    matches.add(in.readString());
                }
                yield new TabComplete(matches);
            }
            case 0x3B -> {
                String name = in.readString();
                int mode = in.readByte();
                String display = null;
                if (mode == 0 || mode == 2) {
                    display = in.readString();
                    in.readString(); // render type: "integer" or "hearts"
                }
                yield new ScoreboardObjective(name, mode, display);
            }
            case 0x3C -> {
                String entry = in.readString();
                int action = in.readVarInt();
                String objective = in.readString();
                yield new UpdateScore(entry, action, objective, action != 1 ? in.readVarInt() : 0);
            }
            case 0x3D -> new DisplayScoreboard(in.readByte(), in.readString());
            case 0x3E -> readTeam(in);
            case 0x3F -> readPluginMessage(in);
            case 0x41 -> new ServerDifficulty(in.readUnsignedByte());
            case 0x42 -> {
                int event = in.readVarInt();
                yield switch (event) {
                    case 1 -> {
                        int duration = in.readVarInt();
                        yield new Combat(1, duration, -1, in.readInt(), null);
                    }
                    case 2 -> {
                        int player = in.readVarInt();
                        int killer = in.readInt();
                        yield new Combat(2, 0, player, killer, in.readString());
                    }
                    default -> new Combat(event, 0, -1, -1, null);
                };
            }
            case 0x43 -> new Camera(in.readVarInt());
            case 0x44 -> readWorldBorder(in);
            case 0x45 -> {
                int action = in.readVarInt();
                String json = action == 0 || action == 1 ? in.readString() : null;
                int fadeIn = 0;
                int stay = 0;
                int fadeOut = 0;
                if (action == 2) {
                    fadeIn = in.readInt();
                    stay = in.readInt();
                    fadeOut = in.readInt();
                }
                yield new Title(action, json, fadeIn, stay, fadeOut);
            }
            case 0x47 -> new PlayerListHeaderFooter(in.readString(), in.readString());
            case 0x48 -> new ResourcePack(in.readString(), in.readString());
            case 0x49 -> new EntityNbt(in.readVarInt(), in.readNbt());
            default -> null;
        };
    }

    private static EntityProperties readProperties(PacketBuffer in) throws IOException {
        int entity = in.readVarInt();
        int count = in.readInt();
        if (count < 0 || count > 256) {
            throw new IOException("Bad entity property count " + count);
        }
        List<Attribute> attributes = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String key = in.readString();
            double base = in.readDouble();
            int modifiers = count(in.readVarInt(), 1024, "attribute modifier");
            List<AttributeModifier> list = new ArrayList<>(modifiers);
            for (int modifier = 0; modifier < modifiers; modifier++) {
                list.add(new AttributeModifier(in.readUuid(), in.readDouble(), in.readByte()));
            }
            attributes.add(new Attribute(key, base, list));
        }
        return new EntityProperties(entity, attributes);
    }

    private static MapData readMap(PacketBuffer in) throws IOException {
        int mapId = in.readVarInt();
        int scale = in.readByte();
        int[] icons = new int[count(in.readVarInt(), 1024, "map decoration")];
        for (int index = 0; index < icons.length; index++) {
            int typeAndRotation = in.readUnsignedByte();
            int x = in.readUnsignedByte();
            int z = in.readUnsignedByte();
            icons[index] = (typeAndRotation >> 4 & 15) << 24 | x << 16 | z << 8 | typeAndRotation & 15;
        }
        int columns = in.readUnsignedByte();
        if (columns == 0) {
            return new MapData(mapId, scale, icons, 0, 0, 0, 0, null);
        }
        int rows = in.readUnsignedByte();
        int x = in.readUnsignedByte();
        int z = in.readUnsignedByte();
        return new MapData(mapId, scale, icons, columns, rows, x, z, in.readByteArray(128 * 128));
    }

    private static Team readTeam(PacketBuffer in) throws IOException {
        String name = in.readString();
        int mode = in.readByte();
        String display = null;
        String prefix = null;
        String suffix = null;
        int color = -1;
        int flags = 0;
        String visibility = null;
        if (mode == 0 || mode == 2) {
            display = in.readString();
            prefix = in.readString();
            suffix = in.readString();
            flags = in.readByte();
            visibility = in.readString();
            color = in.readByte();
        }
        List<String> players = List.of();
        if (mode == 0 || mode == 3 || mode == 4) {
            int count = count(in.readVarInt(), 65536, "team player");
            List<String> names = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                names.add(in.readString());
            }
            players = names;
        }
        return new Team(name, mode, display, prefix, suffix, color, players, flags, visibility);
    }

    /** Plugin messages: MC|TrList and MC|Brand are decoded, any other channel is passed on with its bytes. */
    private static Event readPluginMessage(PacketBuffer in) throws IOException {
        String channel = in.readString();
        if (in.remaining() > 1_048_576) {
            throw new IOException("Payload may not be larger than 1048576 bytes");
        }
        switch (channel) {
            case "MC|TrList" -> {
                int window = in.readInt();
                int count = in.readUnsignedByte();
                List<Trade> trades = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    ItemStack first = in.readSlot();
                    ItemStack result = in.readSlot();
                    ItemStack second = in.readBoolean() ? in.readSlot() : null;
                    boolean disabled = in.readBoolean();
                    trades.add(new Trade(first, second, result, disabled, in.readInt(), in.readInt()));
                }
                return new Trades(window, trades);
            }
            case "MC|Brand" -> {
                return new ServerBrand(in.readString());
            }
            default -> {
                return new PluginMessage(channel, in.readBytes(in.remaining()));
            }
        }
    }

    private static WorldBorder readWorldBorder(PacketBuffer in) throws IOException {
        int action = in.readVarInt();
        double nan = Double.NaN;
        return switch (action) {
            case 0 -> new WorldBorder(0, nan, nan, nan, in.readDouble(), 0, -1, -1, -1);
            case 1 -> new WorldBorder(1, nan, nan, in.readDouble(), in.readDouble(), in.readVarLong(), -1, -1, -1);
            case 2 -> new WorldBorder(2, in.readDouble(), in.readDouble(), nan, nan, 0, -1, -1, -1);
            case 3 -> new WorldBorder(3, in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(),
                    in.readVarLong(), in.readVarInt(), in.readVarInt(), in.readVarInt());
            case 4 -> new WorldBorder(4, nan, nan, nan, nan, 0, -1, in.readVarInt(), -1);
            case 5 -> new WorldBorder(5, nan, nan, nan, nan, 0, -1, -1, in.readVarInt());
            default -> throw new IOException("Bad world border action " + action);
        };
    }

    private static PlayerList readPlayerList(PacketBuffer in) throws IOException {
        int action = in.readVarInt();
        int count = count(in.readVarInt(), 4096, "player list");
        List<PlayerListEntry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            UUID uuid = in.readUuid();
            switch (action) {
                case 0 -> {
                    String name = in.readString();
                    int properties = count(in.readVarInt(), 64, "profile property");
                    String textures = null;
                    for (int property = 0; property < properties; property++) {
                        String key = in.readString();
                        String value = in.readString();
                        if (in.readBoolean()) {
                            in.readString(); // signature
                        }
                        if (key.equals("textures")) {
                            textures = value;
                        }
                    }
                    int gameMode = in.readVarInt();
                    int ping = in.readVarInt();
                    String display = in.readBoolean() ? in.readString() : null;
                    entries.add(new PlayerListEntry(uuid, name, textures, gameMode, ping, display));
                }
                case 1 -> entries.add(new PlayerListEntry(uuid, null, null, in.readVarInt(), -1, null));
                case 2 -> entries.add(new PlayerListEntry(uuid, null, null, -1, in.readVarInt(), null));
                case 3 -> entries.add(new PlayerListEntry(uuid, null, null, -1, -1,
                        in.readBoolean() ? in.readString() : null));
                default -> entries.add(new PlayerListEntry(uuid, null, null, -1, -1, null));
            }
        }
        return new PlayerList(action, entries);
    }

    /** A count field checked before anything is allocated for it. */
    private static int count(int value, int max, String what) throws IOException {
        if (value < 0 || value > max) {
            throw new IOException("Bad " + what + " count " + value);
        }
        return value;
    }

    /** A fixed-point coordinate (1/32 block). */
    private static double fixed(int value) {
        return value / 32.0;
    }

    /** A byte angle (1/256 turn) in degrees. */
    private static float angle(byte value) {
        return value * 360.0F / 256.0F;
    }

    private void fail(String reason) {
        if (!closed) {
            closed = true;
            events.add(new Disconnected(reason));
            connection.close();
        }
    }

    @Override
    public void close() {
        closed = true;
        connection.close();
    }
}
