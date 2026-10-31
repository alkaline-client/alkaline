package alkaline.clientapp;

import alkaline.game.entity.Entity;
import alkaline.game.inventory.Inventory;
import alkaline.game.inventory.ItemStack;
import alkaline.game.inventory.Items;
import alkaline.game.net.PacketBuffer;
import alkaline.game.player.BlockTarget;
import alkaline.game.player.LocalPlayer;
import alkaline.game.state.PlayerList;
import alkaline.game.state.Scoreboard;
import alkaline.game.world.ClientWorld;
import alkaline.plugin.Block;
import alkaline.plugin.Dimension;
import alkaline.plugin.Effect;
import alkaline.plugin.EntityKind;
import alkaline.plugin.EntityState;
import alkaline.plugin.Face;
import alkaline.plugin.GameMode;
import alkaline.plugin.InventoryState;
import alkaline.plugin.Item;
import alkaline.plugin.ListedPlayer;
import alkaline.plugin.PlayerState;
import alkaline.plugin.ScreenPoint;
import alkaline.plugin.ServerState;
import alkaline.plugin.Sidebar;
import alkaline.plugin.SidebarLine;
import alkaline.plugin.Target;
import alkaline.plugin.World;
import alkaline.renderer.world.vanilla.Blocks189;
import alkaline.ui.plugins.PluginHost;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The game as plugins see it: snapshots of the session's state in the plugin API's types, and the actions they
 * may take. Reads run on the render thread (the host checks); actions arrive there too.
 */
final class PluginGame implements PluginHost.GameBridge {
    private static final String[] EFFECTS = {null, "speed", "slowness", "haste", "mining_fatigue", "strength",
            "instant_health", "instant_damage", "jump_boost", "nausea", "regeneration", "resistance",
            "fire_resistance", "water_breathing", "invisibility", "blindness", "night_vision", "hunger", "weakness",
            "poison", "wither", "health_boost", "absorption", "saturation"};
    private static final Map<Integer, String> MOBS = Map.ofEntries(Map.entry(50, "creeper"),
            Map.entry(51, "skeleton"), Map.entry(52, "spider"), Map.entry(53, "giant"), Map.entry(54, "zombie"),
            Map.entry(55, "slime"), Map.entry(56, "ghast"), Map.entry(57, "zombie_pigman"), Map.entry(58, "enderman"),
            Map.entry(59, "cave_spider"), Map.entry(60, "silverfish"), Map.entry(61, "blaze"),
            Map.entry(62, "magma_cube"), Map.entry(63, "ender_dragon"), Map.entry(64, "wither"), Map.entry(65, "bat"),
            Map.entry(66, "witch"), Map.entry(67, "endermite"), Map.entry(68, "guardian"), Map.entry(90, "pig"),
            Map.entry(91, "sheep"), Map.entry(92, "cow"), Map.entry(93, "chicken"), Map.entry(94, "squid"),
            Map.entry(95, "wolf"), Map.entry(96, "mooshroom"), Map.entry(97, "snow_golem"), Map.entry(98, "ocelot"),
            Map.entry(99, "iron_golem"), Map.entry(100, "horse"), Map.entry(101, "rabbit"),
            Map.entry(120, "villager"));
    private static final Map<Integer, String> OBJECTS = Map.ofEntries(Map.entry(1, "boat"), Map.entry(2, "item"),
            Map.entry(10, "minecart"), Map.entry(50, "tnt"), Map.entry(51, "ender_crystal"), Map.entry(60, "arrow"),
            Map.entry(61, "snowball"), Map.entry(62, "egg"), Map.entry(63, "fireball"),
            Map.entry(64, "small_fireball"), Map.entry(65, "ender_pearl"), Map.entry(66, "wither_skull"),
            Map.entry(70, "falling_block"), Map.entry(71, "item_frame"), Map.entry(72, "eye_of_ender"),
            Map.entry(73, "potion"), Map.entry(75, "xp_bottle"), Map.entry(76, "firework"),
            Map.entry(77, "leash_knot"), Map.entry(78, "armor_stand"), Map.entry(90, "fishing_hook"),
            Map.entry(-1, "painting"), Map.entry(-2, "xp_orb"), Map.entry(-3, "lightning"));

    private final Supplier<PlaySession> play;
    private final Supplier<GameView> view;
    private final Supplier<String> address;
    private final Consumer<Runnable> renderThreadTasks;
    private final alkaline.audio.AudioEngine audio;
    private volatile Thread renderThread;

    PluginGame(Supplier<PlaySession> play, Supplier<GameView> view, Supplier<String> address,
               Consumer<Runnable> renderThreadTasks, alkaline.audio.AudioEngine audio) {
        this.play = play;
        this.view = view;
        this.address = address;
        this.renderThreadTasks = renderThreadTasks;
        this.audio = audio;
    }

    /** The calling thread is the render thread from now on. */
    void renderThread(Thread thread) {
        renderThread = thread;
    }

    @Override
    public boolean getInGame() {
        return play.get() != null;
    }

    @Override
    public boolean onRenderThread() {
        return Thread.currentThread() == renderThread;
    }

    @Override
    public void runOnRenderThread(Runnable task) {
        renderThreadTasks.accept(task);
    }

    // ---- state ------------------------------------------------------------------------------------------

    @Override
    public PlayerState player() {
        PlaySession session = play.get();
        if (session == null) {
            return null;
        }
        LocalPlayer self = session.player();
        List<Effect> effects = new ArrayList<>();
        for (PlaySession.Effect effect : session.effects().values()) {
            String name = effect.id() > 0 && effect.id() < EFFECTS.length ? EFFECTS[effect.id()] : "unknown";
            effects.add(new Effect(effect.id(), name, effect.amplifier(), effect.ticksLeft()));
        }
        return new PlayerState(session.name(), session.self(), session.session().entityId(), self.x, self.y, self.z,
                self.motionX, self.motionY, self.motionZ, self.yaw, self.pitch, self.onGround, self.sneaking,
                self.sprinting, self.health, session.maxHealth(), session.absorption(), self.food,
                session.saturation(), session.air(), session.level(), session.experience(),
                gameMode(session.gameMode()), dimension(session), session.inventory().selected(), effects,
                session.riding(), session.usingItem());
    }

    @Override
    public InventoryState inventory() {
        PlaySession session = play.get();
        if (session == null) {
            return null;
        }
        Inventory inventory = session.inventory();
        List<Item> hotbar = new ArrayList<>(9);
        for (int slot = 0; slot < 9; slot++) {
            hotbar.add(item(inventory.hotbar(slot)));
        }
        List<Item> main = new ArrayList<>(27);
        for (int slot = Inventory.FIRST_MAIN; slot < Inventory.FIRST_HOTBAR; slot++) {
            main.add(item(inventory.slot(slot)));
        }
        List<Item> armor = new ArrayList<>(4);
        for (int piece = 0; piece < 4; piece++) {
            armor.add(item(inventory.armor(piece)));
        }
        return new InventoryState(inventory.selected(), hotbar, main, armor, item(inventory.cursor()));
    }

    @Override
    public World world() {
        PlaySession session = play.get();
        return session == null ? null : new SessionWorld(session);
    }

    @Override
    public List<EntityState> entities() {
        PlaySession session = play.get();
        if (session == null) {
            return List.of();
        }
        int self = session.entities().selfId();
        List<EntityState> entities = new ArrayList<>(session.entities().size());
        for (Entity entity : session.entities().all()) {
            if (entity.id != self) {
                entities.add(entity(session, entity));
            }
        }
        return Collections.unmodifiableList(entities);
    }

    @Override
    public EntityState entity(int id) {
        PlaySession session = play.get();
        Entity entity = session == null ? null : session.entities().get(id);
        return entity == null ? null : entity(session, entity);
    }

    @Override
    public Target target() {
        PlaySession session = play.get();
        if (session == null) {
            return null;
        }
        Entity pointed = session.pointed();
        if (pointed != null) {
            return new Target.EntityHit(entity(session, pointed));
        }
        BlockTarget block = session.crosshair();
        if (block == null) {
            return null;
        }
        return new Target.BlockHit(block.x(), block.y(), block.z(), Face.values()[block.face()],
                block(session.session().world().state(block.x(), block.y(), block.z())));
    }

    @Override
    public ServerState server() {
        PlaySession session = play.get();
        if (session == null) {
            return null;
        }
        Scoreboard scoreboard = session.scoreboard();
        List<ListedPlayer> players = new ArrayList<>();
        for (PlayerList.Player listed : session.players().sorted(scoreboard)) {
            String shown = listed.displayNameJson() != null ? session.formatted(listed.displayNameJson())
                    : scoreboard.format(listed.name());
            players.add(new ListedPlayer(listed.uuid(), listed.name(), shown, listed.ping(),
                    gameMode(listed.gameMode())));
        }
        Scoreboard.Sidebar sidebar = scoreboard.sidebar();
        Sidebar shownSidebar = null;
        if (sidebar != null) {
            List<SidebarLine> lines = new ArrayList<>();
            for (Scoreboard.Line line : sidebar.lines()) {
                lines.add(new SidebarLine(line.text(), line.score()));
            }
            shownSidebar = new Sidebar(sidebar.title(), lines);
        }
        return new ServerState(address.get(), session.serverBrand(), players, session.playerListHeader(),
                session.playerListFooter(), shownSidebar, session.title(), session.subtitle(), session.actionBar());
    }

    @Override
    public ScreenPoint project(double x, double y, double z) {
        GameView current = view.get();
        GameView.Projection camera = current == null ? null : current.projection;
        if (camera == null) {
            return null;
        }
        float dx = (float) (x - camera.eyeX());
        float dy = (float) (y - camera.eyeY());
        float dz = (float) (z - camera.eyeZ());
        float[] m = camera.viewProjection();
        float clipX = m[0] * dx + m[4] * dy + m[8] * dz + m[12];
        float clipY = m[1] * dx + m[5] * dy + m[9] * dz + m[13];
        float clipW = m[3] * dx + m[7] * dy + m[11] * dz + m[15];
        if (clipW <= 1e-4f) {
            return null;
        }
        // Clip y points up (the projection is GL-style; the scene's viewport flips it), screen y points down.
        return new ScreenPoint((clipX / clipW + 1f) * 0.5f, (1f - clipY / clipW) * 0.5f,
                (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    // ---- actions (render thread) ------------------------------------------------------------------------

    @Override
    public void sendChat(String message) {
        PlaySession session = play.get();
        if (session != null) {
            session.sendChat(message);
        }
    }

    @Override
    public void showChat(String text) {
        PlaySession session = play.get();
        if (session != null) {
            session.showChat(text);
        }
    }

    @Override
    public void attack() {
        PlaySession session = play.get();
        if (session != null) {
            session.attack();
        }
    }

    @Override
    public void use() {
        PlaySession session = play.get();
        if (session != null) {
            session.use();
        }
    }

    @Override
    public void select(int slot) {
        PlaySession session = play.get();
        if (session != null) {
            session.select(slot);
        }
    }

    @Override
    public void look(float yaw, float pitch) {
        PlaySession session = play.get();
        if (session != null) {
            session.turnTo(yaw, pitch);
        }
    }

    @Override
    public void playSound(String sound, float volume, float pitch) {
        audio.playUi(sound, volume, pitch);
    }

    @Override
    public void playSound(String sound, double x, double y, double z, float volume, float pitch) {
        audio.play(sound, x, y, z, volume, pitch);
    }

    @Override
    public void sendMessage(String channel, byte[] data) {
        PlaySession session = play.get();
        if (session != null) {
            session.session().sendPluginMessage(channel, new PacketBuffer().writeBytes(data));
        }
    }

    @Override
    public void sendPacket(int id, byte[] body) {
        PlaySession session = play.get();
        if (session != null) {
            session.session().send(id, new PacketBuffer().writeBytes(body));
        }
    }

    // ---- conversions --------------------------------------------------------------------------------------

    private static GameMode gameMode(int mode) {
        return switch (mode & 3) {
            case 1 -> GameMode.CREATIVE;
            case 2 -> GameMode.ADVENTURE;
            case 3 -> GameMode.SPECTATOR;
            default -> GameMode.SURVIVAL;
        };
    }

    private static Dimension dimension(PlaySession session) {
        return switch (session.dimension()) {
            case NETHER -> Dimension.NETHER;
            case END -> Dimension.END;
            case OVERWORLD -> Dimension.OVERWORLD;
        };
    }

    private static Block block(int state) {
        int id = state >> 4;
        String name = Blocks189.name(id);
        return new Block(id, state & 15, name != null ? name : "unknown");
    }

    private static Item item(ItemStack stack) {
        if (stack == null || stack.id() <= 0) {
            return null;
        }
        String name = stack.id() < 256 ? Blocks189.name(stack.id()) : Items.name(stack.id());
        Map<Integer, Integer> enchantments = new LinkedHashMap<>();
        if (stack.tag() != null) {
            alkaline.game.data.nbt.NBTTagList list = stack.tag().getTagList("ench", 10);
            for (int index = 0; index < list.tagCount(); index++) {
                alkaline.game.data.nbt.NBTTagCompound entry = list.getCompoundTagAt(index);
                enchantments.put((int) entry.getShort("id"), (int) entry.getShort("lvl"));
            }
        }
        String custom = stack.customName();
        return new Item(stack.id(), name != null ? name : "unknown", stack.count(), stack.damage(),
                custom == null ? null : custom.replaceAll("§.", ""), enchantments);
    }

    private static EntityState entity(PlaySession session, Entity entity) {
        String type;
        EntityKind kind;
        String name = null;
        switch (entity.category) {
            case PLAYER -> {
                kind = EntityKind.PLAYER;
                type = "player";
                PlayerList.Player listed = entity.uuid == null ? null : session.players().get(entity.uuid);
                name = listed != null ? listed.name() : null;
            }
            case MOB -> {
                kind = EntityKind.MOB;
                type = MOBS.getOrDefault(entity.type, "mob");
            }
            default -> {
                kind = EntityKind.OBJECT;
                type = OBJECTS.getOrDefault(entity.type, "object");
            }
        }
        if (name == null) {
            String tag = entity.metadata.getString(2, "");
            name = tag.isEmpty() ? null : tag.replaceAll("§.", "");
        }
        List<Item> equipment = new ArrayList<>(5);
        for (ItemStack stack : entity.equipment) {
            equipment.add(item(stack));
        }
        boolean invisible = (entity.metadata.getByte(0, 0) & 0x20) != 0;
        Float health = entity.living() ? entity.metadata.getFloat(6, entity.maxHealth) : null;
        return new EntityState(entity.id, kind, type, name, entity.uuid, entity.x, entity.y, entity.z,
                entity.motionX, entity.motionY, entity.motionZ, entity.yaw, entity.pitch, entity.headYaw,
                entity.onGround, entity.sneaking(), entity.sprinting(), invisible, health, equipment,
                entity.hurtTime);
    }

    /** The session's world, read where it is (render thread); block changes are queued like other actions. */
    private final class SessionWorld implements World {
        private final PlaySession session;
        private final ClientWorld world;

        SessionWorld(PlaySession session) {
            this.session = session;
            this.world = session.session().world();
        }

        @Override
        public Dimension getDimension() {
            return dimension(session);
        }

        @Override
        public long getWorldAge() {
            return session.worldAge();
        }

        @Override
        public long getTimeOfDay() {
            return Math.floorMod(Math.abs(session.timeOfDay()), 24000L);
        }

        @Override
        public float getRain() {
            return session.rain();
        }

        @Override
        public float getThunder() {
            return session.thunder();
        }

        @Override
        public Block block(int x, int y, int z) {
            return PluginGame.block(world.state(x, y, z));
        }

        @Override
        public void setBlock(int x, int y, int z, int id, int meta) {
            Runnable change = () -> {
                if (play.get() == session) {
                    world.setBlock(x, y, z, id << 4 | meta & 15);
                }
            };
            if (onRenderThread()) {
                change.run();
            } else {
                runOnRenderThread(change);
            }
        }

        @Override
        public boolean loaded(int x, int z) {
            return world.isLoaded(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        }

        @Override
        public int biome(int x, int z) {
            return world.biome(x, z);
        }

        @Override
        public int skyLight(int x, int y, int z) {
            return world.light(x, y, z) >> 4 & 15;
        }

        @Override
        public int blockLight(int x, int y, int z) {
            return world.light(x, y, z) & 15;
        }
    }
}
