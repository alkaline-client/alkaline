package alkaline.clientapp;

import alkaline.client.settings.ClientSettings;
import alkaline.config.ConfigSnapshot;
import alkaline.game.entity.Entity;
import alkaline.game.entity.EntityPicker;
import alkaline.game.entity.EntityTracker;
import alkaline.game.entity.EntityTypes;
import alkaline.game.inventory.Container;
import alkaline.game.inventory.Inventory;
import alkaline.game.inventory.ItemStack;
import alkaline.game.inventory.Window;
import alkaline.game.inventory.WindowInput;
import alkaline.game.net.ServerSession;
import alkaline.game.player.BlockTarget;
import alkaline.game.player.Interactions;
import alkaline.game.player.LocalPlayer;
import alkaline.game.state.PlayerList;
import alkaline.game.state.Scoreboard;
import alkaline.game.world.ClientWorld;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLMouse;

/**
 * One multiplayer game in progress, driven by the client's 20 Hz tick: applies server events to the game state
 * (entities, inventory and the windows the server opens, player list, scoreboard, effects, chat and titles),
 * moves the local player from the Controls bindings, attacks the entity under the crosshair, uses items and
 * blocks with the use key (Minecraft.rightClickMouse), and reports movement as vanilla's
 * EntityPlayerSP.onUpdateWalkingPlayer does (position when moved more than 0.03 blocks or every 20 ticks, look
 * when turned, entity actions when sprinting or sneaking changes), or steering while riding.
 */
public final class PlaySession implements AutoCloseable {
    /** Lines vanilla's GuiNewChat keeps. */
    private static final int CHAT_HISTORY = 100;

    /** An active potion effect on the local player. */
    record Effect(int id, int amplifier, int ticksLeft) {
    }

    /** A received chat line (§ formatted) and the tick it arrived. */
    record ChatLine(String text, long tick) {
    }

    private final ServerSession session;
    private final LocalPlayer player = new LocalPlayer();
    private final Consumer<String> disconnected;
    private final ChatText chat;
    private final EntityTracker entities = new EntityTracker();
    private final MobAnimator animation = new MobAnimator();

    {
        animation.attach(entities);
    }
    private final EntityPicker picker = new EntityPicker();
    private final Inventory inventory = new Inventory();
    /** GuiContainer's input for the open screen's container; its clicks apply here and go to the server. */
    private WindowInput windowInput = new WindowInput(inventory, this::click);
    private boolean inventoryOpen;
    /** The window the server opened (a chest, a furnace...), or null. */
    private Window window;
    /** Called when the server closes the window the player has open. */
    private Runnable windowClosedByServer = () -> { };
    /** Called when the server opens a window, to show its screen. */
    private Runnable windowOpenedByServer = () -> { };
    /** Called when the server asks for a sign to be written, with the sign's current lines (plain text). */
    private Consumer<String[]> signEditorOpened = lines -> { };
    /** Sign text by packed block position, from Update Sign, for the editor to start from. */
    private final Map<Long, String[]> signs = new java.util.HashMap<>();
    private long editingSign = Long.MIN_VALUE;
    /** Minecraft.rightClickDelayTimer: ticks before a held use key acts again. */
    private int useDelay;
    private int useTicks;
    /** The entity the player rides, or -1. */
    private int vehicle = -1;
    private float saturation = 5f;
    private float maxHealth = 20f;
    private String playerListHeader = "";
    private String playerListFooter = "";
    private String serverBrand = "";
    private int difficulty;
    private int spawnX;
    private int spawnY;
    private int spawnZ;
    private final PlayerList players = new PlayerList();
    private final Scoreboard scoreboard = new Scoreboard();
    private final Map<Integer, Effect> effects = new LinkedHashMap<>();
    private final Deque<ChatLine> chatLines = new ArrayDeque<>();
    private final UUID self;
    private GameView view;
    private boolean spawned;
    private double reportedX;
    private double reportedY;
    private double reportedZ;
    private float reportedYaw;
    private float reportedPitch;
    private int ticksSinceReport;
    private boolean reportedSprint;
    private boolean reportedSneak;
    private float rain;
    private float thunder;
    private int gameMode;
    private float experience;
    private int level;
    private long ticks;
    private String title = "";
    private String subtitle = "";
    private String actionBar = "";
    private int actionBarTicks;
    private int titleFadeIn = 10;
    private int titleStay = 70;
    private int titleFadeOut = 20;
    private int titleTicks;
    private int chatVersion;
    private boolean flat;

    PlaySession(ServerSession session, ChatText chat, Consumer<String> disconnected) {
        this.session = session;
        this.chat = chat;
        this.disconnected = disconnected;
        UUID parsed;
        try {
            parsed = UUID.fromString(session.profile().uuid());
        } catch (IllegalArgumentException unparsed) {
            parsed = null;
        }
        self = parsed;
    }

    ServerSession session() {
        return session;
    }

    LocalPlayer player() {
        return player;
    }

    MobAnimator animation() {
        return animation;
    }

    EntityTracker entities() {
        return entities;
    }

    Inventory inventory() {
        return inventory;
    }

    PlayerList players() {
        return players;
    }

    Scoreboard scoreboard() {
        return scoreboard;
    }

    Map<Integer, Effect> effects() {
        return effects;
    }

    Deque<ChatLine> chat() {
        return chatLines;
    }

    /** Bumped whenever a chat line arrives. */
    int chatVersion() {
        return chatVersion;
    }

    UUID self() {
        return self;
    }

    int gameMode() {
        return gameMode;
    }

    float experience() {
        return experience;
    }

    int level() {
        return level;
    }

    long ticks() {
        return ticks;
    }

    String title() {
        return title;
    }

    String subtitle() {
        return subtitle;
    }

    String actionBar() {
        return actionBar;
    }

    /** Title opacity 0..1 now (fade in, stay, fade out, as GuiIngame's titlesTimer). */
    float titleAlpha(float partialTick) {
        if (titleTicks <= 0) {
            return 0f;
        }
        float time = titleTicks - partialTick;
        if (time > titleFadeOut + titleStay) {
            return Math.min(1f, (titleFadeIn + titleStay + titleFadeOut - time) / Math.max(1f, titleFadeIn));
        }
        if (time < titleFadeOut) {
            return Math.max(0f, time / Math.max(1f, titleFadeOut));
        }
        return 1f;
    }

    /** Action bar opacity: GuiIngame's recordPlayingUpFor fades over the last 20 of 60 ticks. */
    float actionBarAlpha(float partialTick) {
        float time = actionBarTicks - partialTick;
        return time <= 0f ? 0f : Math.min(1f, time / 20f);
    }

    /** Air from the player's own metadata (300 = full), as vanilla's air bubbles show it. */
    int air() {
        return entities.self().getShort(1, 300);
    }

    void view(GameView view) {
        this.view = view;
        view.weather(rain, thunder);
    }

    /**
     * Mouse motion in relative mode (SDL: +y down), turned the way vanilla's EntityRenderer turns it: sensitivity
     * s gives f = (0.6 s + 0.2)^3 * 8 degrees-per-count factors of 0.15; zooming slows the turn by its magnification.
     */
    void look(float dx, float dy, ConfigSnapshot settings, float magnification) {
        float sensitivity = settings.get(ClientSettings.MOUSE_SENSITIVITY);
        float f = sensitivity * 0.6F + 0.2F;
        float factor = f * f * f * 8.0F / magnification;
        int invert = settings.get(ClientSettings.INVERT_MOUSE) ? -1 : 1;
        player.yaw += dx * factor * 0.15F;
        player.pitch = Math.max(-90.0F, Math.min(90.0F, player.pitch + dy * factor * 0.15F * invert));
    }

    /**
     * Minecraft.clickMouse: swing the arm (vanilla always does), then attack the entity under the crosshair
     * within reach. Sprinting into a player slows the attacker and ends the sprint, as vanilla's client does.
     */
    void attack() {
        if (!spawned) {
            return;
        }
        // EntityPlayerSP.swingItem sends the animation even when the swing is already under way.
        player.swing();
        session.sendSwing();
        double reach = gameMode == 1 ? 6.0 : 3.0;
        ClientWorld world = session.world();
        Entity target = picker.pick(entities, world::state, player.x, player.eyeY(1f), player.z, player.yaw,
                player.pitch, reach);
        if (target == null || gameMode == 3) {
            return;
        }
        session.sendAttack(target.id);
        // EntityPlayer.attackTargetEntityWithCurrentItem on the client: only another player "takes" the hit there
        // (EntityOtherPlayerMP.attackEntityFrom answers true; other entities false), so only hits on players slow
        // the attacker and show the critical and enchantment particles.
        if (target.category == Entity.Category.PLAYER) {
            ItemStack weapon = inventory.held();
            int knockback = (weapon == null ? 0 : weapon.enchantment(19)) + (player.sprinting ? 1 : 0);
            if (knockback > 0) {
                player.knockbackDealt();
            }
            if (view != null) {
                if (player.critical(world::state)) {
                    view.critical(target, false);
                }
                // EnchantmentHelper.func_152377_a: Sharpness counts against any creature.
                if (weapon != null && weapon.enchantment(16) > 0) {
                    view.critical(target, true);
                }
            }
        }
    }

    /** Toggle sprint & sneak: whether each toggle is in use, and its state. */
    private boolean sprintToggles;
    private boolean sneakToggles;
    private boolean sprintToggled;
    private boolean sneakToggled;

    /** The toggle sprint & sneak module's options, read each tick by the client. */
    void movementToggles(boolean sprint, boolean sneak) {
        if (!sprint) {
            sprintToggled = false;
        }
        if (!sneak) {
            sneakToggled = false;
        }
        sprintToggles = sprint;
        sneakToggles = sneak;
    }

    /** The sprint key was pressed: flips the toggle when toggling is on. */
    void sprintPressed() {
        if (sprintToggles) {
            sprintToggled = !sprintToggled;
        }
    }

    void sneakPressed() {
        if (sneakToggles) {
            sneakToggled = !sneakToggled;
        }
    }

    /** How the player moves, for the module's readout: "Sprinting (Toggled)", "Sneaking (Key held)"... or "". */
    String movementStatus(ConfigSnapshot settings, boolean[] keys, boolean[] buttons) {
        if (player.flying) {
            return player.sprinting ? "Flying (Boost)" : "Flying";
        }
        if (player.sneaking) {
            return sneakToggled ? "Sneaking (Toggled)" : "Sneaking (Key held)";
        }
        if (sneakToggled) {
            return "Sneaking (Toggled)";
        }
        boolean sprintKey = held(settings.get(ClientSettings.KEY_SPRINT), keys, buttons);
        if (player.sprinting) {
            return sprintToggled ? "Sprinting (Toggled)" : sprintKey ? "Sprinting (Key held)" : "Sprinting";
        }
        return sprintToggled ? "Sprinting (Toggled)" : "";
    }

    WindowInput windowInput() {
        return windowInput;
    }

    /** Whether a container screen is open: the player's inventory or a window the server opened. */
    boolean containerOpen() {
        return inventoryOpen || window != null;
    }

    /** The open screen's container, or null. */
    Container container() {
        return window != null ? window : inventoryOpen ? inventory : null;
    }

    /** The window the server opened, or null (the player's own inventory is not one). */
    Window window() {
        return window;
    }

    void onWindowClosedByServer(Runnable action) {
        windowClosedByServer = action;
    }

    void onWindowOpenedByServer(Runnable action) {
        windowOpenedByServer = action;
    }

    void onSignEditorOpened(Consumer<String[]> action) {
        signEditorOpened = action;
    }

    /** The inventory screen opened (vanilla reports it with Client Status 2, the open-inventory achievement). */
    void openInventory() {
        if (!containerOpen()) {
            inventoryOpen = true;
            windowInput = new WindowInput(inventory, this::click);
            session.sendClientStatus(2);
        }
    }

    /**
     * The player closed the screen (EntityPlayerSP.closeScreen): Close Window for the open container; the server
     * throws the cursor stack (and the inventory's crafting grid), so they leave the client's copy too.
     */
    void closeContainer() {
        if (window != null) {
            session.sendCloseWindow(window.id());
            inventory.setCursor(null);
            window = null;
            windowInput = new WindowInput(inventory, this::click);
        } else if (inventoryOpen) {
            inventoryOpen = false;
            session.sendCloseWindow(0);
            inventory.closed();
            windowInput.reset();
        }
    }

    /** The server closed the screen, or opened another over it: forget it without reporting a close. */
    private void dropContainer() {
        if (window != null) {
            inventory.setCursor(null);
            window = null;
        } else if (inventoryOpen) {
            inventoryOpen = false;
            inventory.closed();
        }
        windowInput = new WindowInput(inventory, this::click);
    }

    /** PlayerControllerMP.windowClick: applies a click to the local copy and sends it with what it saw. */
    private void click(int slot, int button, int mode) {
        Container open = window != null ? window : inventory;
        if (window != null) {
            window.player(level, gameMode == 1);
        }
        short action = open.nextTransaction();
        ItemStack clicked = open.click(slot, button, mode, gameMode == 1);
        session.sendClickWindow(window != null ? window.id() : 0, slot, button, action, mode, clicked);
    }

    /** GuiEnchantment: the player picked option 0..2 (sent as Enchant Item when the table allows it). */
    void enchant(int option) {
        if (window != null && window.type() == Window.Type.ENCHANTING_TABLE) {
            window.player(level, gameMode == 1);
            if (window.canEnchant(option)) {
                session.sendEnchantItem(window.id(), option);
            }
        }
    }

    /** GuiMerchant's arrows: the trade the villager window shows (MC|TrSel). */
    void selectTrade(int index) {
        if (window != null && window.type() == Window.Type.VILLAGER && index >= 0
                && index < Math.max(1, window.trades().size())) {
            window.selectTrade(index);
            session.sendTradeSelect(index);
        }
    }

    /** GuiRepair's name field (MC|ItemName); the server answers with the renamed result. */
    void rename(String name) {
        if (window != null && window.type() == Window.Type.ANVIL) {
            String text = name.length() > 30 ? name.substring(0, 30) : name;
            window.rename(text);
            session.sendItemName(text);
        }
    }

    /** GuiBeacon's confirm: the chosen powers (MC|Beacon), then the screen closes. */
    void beacon(int primary, int secondary) {
        if (window != null && window.type() == Window.Type.BEACON) {
            session.sendBeacon(primary, secondary);
            closeContainer();
        }
    }

    /** GuiEditSign closed: the sign's four lines, as plain text, go to the server. */
    void signDone(String[] lines) {
        if (editingSign == Long.MIN_VALUE) {
            return;
        }
        String[] json = new String[4];
        for (int line = 0; line < 4; line++) {
            String text = line < lines.length && lines[line] != null ? lines[line] : "";
            json[line] = jsonString(text.length() > 15 ? text.substring(0, 15) : text);
        }
        session.sendUpdateSign(alkaline.game.net.PacketBuffer.positionX(editingSign),
                alkaline.game.net.PacketBuffer.positionY(editingSign),
                alkaline.game.net.PacketBuffer.positionZ(editingSign), json);
        editingSign = Long.MIN_VALUE;
    }

    /** A plain chat component, as IChatComponent.Serializer writes an unstyled ChatComponentText. */
    private static String jsonString(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Selects a hotbar slot (number keys, the wheel) and tells the server. */
    void select(int slot) {
        int next = Math.floorMod(slot, 9);
        if (next != inventory.selected()) {
            inventory.select(next);
            session.sendHeldItem(next);
            // EntityPlayer.onUpdate: the item in use is no longer held.
            player.usingItem = false;
        }
    }

    /**
     * Minecraft.rightClickMouse: interact with the entity under the crosshair, or use the targeted block (open a
     * chest, place a block...), and otherwise use the held item (eat, drink, draw the bow, block with the sword).
     */
    void use() {
        if (!spawned || player.usingItem) {
            return;
        }
        useDelay = 4;
        ItemStack held = inventory.held();
        ClientWorld world = session.world();
        double eyeY = player.eyeY(1f);
        BlockTarget block = BlockTarget.pick(world::state, player.x, eyeY, player.z, player.yaw, player.pitch,
                gameMode == 1 ? 5.0 : 4.5);
        Entity target = picker.pick(entities, world::state, player.x, eyeY, player.z, player.yaw, player.pitch,
                gameMode == 1 ? 6.0 : 3.0);
        boolean useItem = true;
        if (target != null && (block == null || picker.distance() < block.distance())) {
            double yawRadians = Math.toRadians(player.yaw);
            double pitchRadians = Math.toRadians(player.pitch);
            double distance = picker.distance();
            float hitX = (float) (player.x - Math.sin(yawRadians) * Math.cos(pitchRadians) * distance - target.x);
            float hitY = (float) (eyeY - Math.sin(pitchRadians) * distance - target.y);
            float hitZ = (float) (player.z + Math.cos(yawRadians) * Math.cos(pitchRadians) * distance - target.z);
            session.sendInteract(target.id, hitX, hitY, hitZ);
            // Entity.interactFirst: villagers trade and horses open or mount; players and most mobs do nothing,
            // so the held item is still used (a sword blocks while aiming at an opponent).
            useItem = !(target.category == Entity.Category.MOB
                    && (target.type == EntityTypes.VILLAGER || target.type == EntityTypes.HORSE));
        } else if (block != null) {
            session.sendBlockPlacement(block.x(), block.y(), block.z(), block.face(), held,
                    (float) (block.hitX() - block.x()), (float) (block.hitY() - block.y()),
                    (float) (block.hitZ() - block.z()));
            int id = world.state(block.x(), block.y(), block.z()) >> 4;
            boolean activated = Interactions.activatable(id) && !(player.sneaking && held != null);
            boolean placed = held != null && Interactions.placeable(held.id());
            if (activated || placed) {
                // PlayerControllerMP.onPlayerRightClick succeeded: the arm swings, nothing else is used.
                player.swing();
                session.sendSwing();
                useItem = false;
            }
        }
        if (useItem && held != null && gameMode != 3) {
            session.sendUseItem(held);
            int duration = Interactions.useDuration(held, player.food, gameMode == 1, hasArrows());
            if (duration > 0) {
                player.usingItem = true;
                player.itemInUse = held.id();
                player.itemUseTicks = 0;
                useTicks = duration;
            }
        }
    }

    /** PlayerControllerMP.onStoppedUsingItem: the use key was let go (a bow shoots, a sword stops blocking). */
    private void releaseUse() {
        if (player.usingItem) {
            session.sendDigging(alkaline.game.net.ServerSession.RELEASE_USE_ITEM, 0, 0, 0, 0);
            player.usingItem = false;
        }
    }

    private boolean hasArrows() {
        for (int slot = Inventory.FIRST_MAIN; slot < Inventory.SLOTS; slot++) {
            ItemStack stack = inventory.slot(slot);
            if (stack != null && stack.id() == 262) {
                return true;
            }
        }
        return false;
    }

    /** Whether the player is using an item (for the hand's eating, drinking and blocking poses). */
    boolean usingItem() {
        return player.usingItem;
    }

    float saturation() {
        return saturation;
    }

    /** generic.maxHealth (Health Boost raises it). */
    float maxHealth() {
        return maxHealth;
    }

    /** Absorption from the player's own metadata (17), golden hearts. */
    float absorption() {
        return entities.self().getFloat(17, 0f);
    }

    boolean riding() {
        return vehicle >= 0;
    }

    /** The player list's header and footer (§ formatted, "" when the server sets none). */
    String playerListHeader() {
        return playerListHeader;
    }

    String playerListFooter() {
        return playerListFooter;
    }

    /** The server software's name from MC|Brand. */
    String serverBrand() {
        return serverBrand;
    }

    int difficulty() {
        return difficulty;
    }

    /** The world spawn (Spawn Position), which the compass points to. */
    int[] spawn() {
        return new int[]{spawnX, spawnY, spawnZ};
    }

    /** One game tick; {@code controls} is false while the pause card or a menu has the input. */
    void tick(boolean controls, ConfigSnapshot settings, boolean[] keys, boolean[] buttons) {
        ticks++;
        session.drainEvents(this::handle);
        entities.tick(session.world()::state);
        animation.tick(entities.all(), session.world()::state);
        effects.replaceAll((id, effect) -> new Effect(id, effect.amplifier(), Math.max(0, effect.ticksLeft() - 1)));
        if (titleTicks > 0) {
            titleTicks--;
        }
        if (actionBarTicks > 0) {
            actionBarTicks--;
        }
        if (!spawned) {
            return;
        }
        if (useDelay > 0) {
            useDelay--;
        }
        boolean useHeld = controls && held(settings.get(ClientSettings.KEY_USE), keys, buttons);
        if (player.usingItem) {
            // Minecraft.runTick: letting go of the use key stops using; a finished use ends by itself.
            player.itemUseTicks++;
            if (!useHeld) {
                releaseUse();
            } else if (--useTicks <= 0) {
                player.usingItem = false;
            }
        } else if (useHeld && useDelay == 0) {
            use();
        }
        if (!player.usingItem) {
            player.itemInUse = -1;
            player.itemUseTicks = 0;
        }
        player.jumpDelay = settings.get(ClientSettings.JUMP_DELAY);
        applyEffects();
        LocalPlayer.Input input = controls ? input(settings, keys, buttons) : LocalPlayer.Input.NONE;
        Entity ridden = vehicle >= 0 ? entities.get(vehicle) : null;
        if (ridden != null) {
            // Entity.updateRidden: seated on the vehicle; EntityPlayerSP.onUpdate steers instead of walking.
            player.ride(ridden.x, ridden.y + mountedOffset(ridden) - 0.35, ridden.z);
            session.sendLook(player.yaw, player.pitch, false);
            session.sendSteerVehicle(input.strafe() * 0.98F, input.forward() * 0.98F, input.jump(), input.sneak());
            return;
        }
        ClientWorld world = session.world();
        boolean loaded = world.isLoaded(Math.floorDiv((int) Math.floor(player.x), 16),
                Math.floorDiv((int) Math.floor(player.z), 16));
        player.tick(input, world::state, loaded);
        report();
    }

    /** Entity.getMountedYOffset: three quarters up a mob; boats and minecarts seat lower. */
    private static double mountedOffset(Entity vehicle) {
        if (vehicle.category == Entity.Category.OBJECT
                && (vehicle.type == EntityTypes.OBJECT_BOAT || vehicle.type == EntityTypes.OBJECT_MINECART)) {
            return -0.3;
        }
        return EntityTypes.height(vehicle) * 0.75;
    }

    private void handle(ServerSession.Event event) {
        if (event instanceof ServerSession.EntityVelocity velocity && velocity.entityId() == entities.selfId()) {
            player.velocity(velocity.motionX(), velocity.motionY(), velocity.motionZ());
            return;
        }
        if (event instanceof ServerSession.BlockAction action) {
            if (view != null) {
                view.blockAction(action.x(), action.y(), action.z(), action.data1(), action.data2(), action.block());
            }
            return;
        }
        if (event instanceof ServerSession.EntityStatus status && status.entityId() == entities.selfId()
                && status.status() == 9) {
            // EntityPlayer.handleStatusUpdate: the server finished the item use.
            player.usingItem = false;
            return;
        }
        if (event instanceof ServerSession.EntityAttach attach && attach.entityId() == entities.selfId()
                && !attach.leash()) {
            vehicle = attach.vehicleId();
            return;
        }
        if (event instanceof ServerSession.EntityAnimation animation && animation.animation() >= 4) {
            Entity target = entities.get(animation.entityId());
            if (target != null && view != null) {
                view.critical(target, animation.animation() == 5);
            }
            return;
        }
        if (entities.apply(event) || players.apply(event) || scoreboard.apply(event)) {
            return;
        }
        switch (event) {
            case ServerSession.JoinGame join -> {
                flat = "flat".equalsIgnoreCase(join.levelType());
                entities.selfId(join.entityId());
                gameMode = join.gameMode() & 7;
                player.allowFlying = gameMode == 1;
                newWorld();
            }
            case ServerSession.Teleport teleport -> {
                int relative = teleport.relative();
                double x = (relative & 1) != 0 ? player.x + teleport.x() : teleport.x();
                double y = (relative & 2) != 0 ? player.y + teleport.y() : teleport.y();
                double z = (relative & 4) != 0 ? player.z + teleport.z() : teleport.z();
                float yaw = (relative & 8) != 0 ? player.yaw + teleport.yaw() : teleport.yaw();
                float pitch = (relative & 16) != 0 ? player.pitch + teleport.pitch() : teleport.pitch();
                player.teleport(x, y, z);
                player.yaw = yaw;
                player.pitch = pitch;
                // NetHandlerPlayClient.handlePlayerPosLook confirms with a full position and look, not on ground.
                session.sendPositionLook(x, y, z, yaw, pitch, false);
                reportedX = x;
                reportedY = y;
                reportedZ = z;
                reportedYaw = yaw;
                reportedPitch = pitch;
                spawned = true;
            }
            case ServerSession.Abilities abilities -> {
                player.allowFlying = (abilities.flags() & 4) != 0;
                player.flying = (abilities.flags() & 2) != 0;
                player.flySpeed = abilities.flySpeed();
                player.walkSpeed = abilities.walkSpeed();
            }
            case ServerSession.Time time -> {
                if (view != null) {
                    view.time(time.timeOfDay());
                }
            }
            case ServerSession.Health health -> {
                saturation = health.saturation();
                player.food = health.food();
                player.health(health.health());
                if (health.health() <= 0) {
                    session.sendClientStatus(0); // respawn at once; the death screen is not built yet
                }
            }
            case ServerSession.Respawn respawn -> {
                flat = "flat".equalsIgnoreCase(respawn.levelType());
                gameMode = respawn.gameMode() & 7;
                player.allowFlying = gameMode == 1;
                newWorld();
            }
            case ServerSession.GameState state -> {
                if (state.reason() == 3) {
                    gameMode = (int) state.value() & 7;
                    player.allowFlying = gameMode == 1;
                } else {
                    weather(state);
                }
            }
            case ServerSession.Chat message -> {
                String text = chat.legacy(message.json());
                if (message.position() == 2) {
                    actionBar = text;
                    actionBarTicks = 60;
                } else {
                    chatLines.addFirst(new ChatLine(text, ticks));
                    while (chatLines.size() > CHAT_HISTORY) {
                        chatLines.removeLast();
                    }
                    chatVersion++;
                    System.out.println("[chat] " + text.replaceAll("§.", ""));
                }
            }
            case ServerSession.Title titleEvent -> title(titleEvent);
            case ServerSession.SetSlot slot -> {
                if (slot.window() == -1 && slot.slot() == -1) {
                    inventory.setCursor(slot.item());
                } else if (slot.window() == 0) {
                    inventory.set(slot.slot(), slot.item());
                } else if (window != null && slot.window() == window.id()) {
                    window.set(slot.slot(), slot.item());
                }
            }
            case ServerSession.ConfirmTransaction confirm -> {
                boolean known = confirm.window() == 0 || window != null && confirm.window() == window.id();
                if (known && !confirm.accepted()) {
                    // The server resends the window after a rejected click; it waits for this acknowledgement.
                    session.sendConfirmTransaction(confirm.window(), confirm.action());
                }
            }
            case ServerSession.OpenWindow open -> {
                boolean armor = false;
                if (open.type().equals("EntityHorse")) {
                    // EntityHorse.canWearArmor: plain horses only (type 0, metadata 19).
                    Entity horse = entities.get(open.entityId());
                    armor = horse != null && horse.metadata.getByte(19, 0) == 0;
                }
                Window opened = Window.open(open.window(), open.type(), open.titleJson(), open.slots(), inventory,
                        armor);
                if (opened == null) {
                    // A type vanilla opens no screen for: close it, so the server's open window is the
                    // inventory again and its clicks keep working.
                    System.out.println("[window] closed " + open.type() + " (no screen for this type)");
                    session.sendCloseWindow(open.window());
                } else {
                    // GuiScreen replaced: the previous container is dropped without a Close Window.
                    dropContainer();
                    opened.player(level, gameMode == 1);
                    window = opened;
                    windowInput = new WindowInput(opened, this::click);
                    windowOpenedByServer.run();
                }
            }
            case ServerSession.CloseWindow ignored -> {
                // NetHandlerPlayClient.handleCloseWindow closes whatever is open, whatever its id.
                if (containerOpen()) {
                    dropContainer();
                    windowClosedByServer.run();
                }
            }
            case ServerSession.WindowItems items -> {
                if (items.window() == 0) {
                    inventory.setAll(items.items());
                } else if (window != null && items.window() == window.id()) {
                    window.setAll(items.items());
                }
            }
            case ServerSession.WindowProperty property -> {
                if (window != null && property.window() == window.id()) {
                    window.property(property.property(), property.value());
                }
            }
            case ServerSession.Trades trades -> {
                if (window != null && trades.window() == window.id() && window.type() == Window.Type.VILLAGER) {
                    window.trades(trades.trades());
                }
            }
            case ServerSession.UpdateSign sign -> {
                String[] lines = new String[4];
                for (int line = 0; line < 4; line++) {
                    lines[line] = chat.plain(sign.lines()[line]);
                }
                signs.put(pack(sign.x(), sign.y(), sign.z()), lines);
            }
            case ServerSession.OpenSignEditor editor -> {
                editingSign = pack(editor.x(), editor.y(), editor.z());
                String[] lines = signs.getOrDefault(editingSign, new String[]{"", "", "", ""});
                signEditorOpened.accept(lines.clone());
            }
            case ServerSession.EntityProperties properties -> {
                if (properties.entityId() == entities.selfId()) {
                    for (ServerSession.Attribute attribute : properties.attributes()) {
                        if (attribute.key().equals("generic.maxHealth")) {
                            maxHealth = (float) LocalPlayer.attribute(attribute.base(), attribute.modifiers(), null);
                        }
                        if (attribute.key().equals("generic.movementSpeed")) {
                            player.movementSpeed = LocalPlayer.attribute(attribute.base(), attribute.modifiers(),
                                    LocalPlayer.SPRINT_MODIFIER);
                        }
                    }
                }
            }
            case ServerSession.ResourcePack pack -> {
                // Server resource packs are not supported yet; vanilla answers a refusal the same way.
                System.out.println("[resource pack] declined " + pack.url());
                session.sendResourcePackStatus(pack.hash(), ServerSession.PACK_DECLINED);
            }
            case ServerSession.PlayerListHeaderFooter tab -> {
                playerListHeader = chat.legacy(tab.headerJson());
                playerListFooter = chat.legacy(tab.footerJson());
            }
            case ServerSession.ServerBrand brand -> serverBrand = brand.brand();
            case ServerSession.ServerDifficulty serverDifficulty -> difficulty = serverDifficulty.difficulty();
            case ServerSession.SpawnPosition position -> {
                spawnX = position.x();
                spawnY = position.y();
                spawnZ = position.z();
            }
            case ServerSession.HeldItem held -> inventory.select(held.slot());
            case ServerSession.EntityEffect effect -> {
                if (effect.entityId() == entities.selfId()) {
                    effects.put(effect.effect(), new Effect(effect.effect(), effect.amplifier(), effect.durationTicks()));
                }
            }
            case ServerSession.RemoveEntityEffect effect -> {
                if (effect.entityId() == entities.selfId()) {
                    effects.remove(effect.effect());
                }
            }
            case ServerSession.Experience xp -> {
                experience = xp.progress();
                level = xp.level();
                if (window != null) {
                    window.player(level, gameMode == 1);
                }
            }
            case ServerSession.Particle particle -> {
                if (view != null) {
                    view.particles().packet(particle.type(), particle.longDistance(), particle.x(), particle.y(),
                            particle.z(), particle.offsetX(), particle.offsetY(), particle.offsetZ(), particle.speed(),
                            particle.count(), particle.arguments());
                }
            }
            case ServerSession.WorldEffect effect -> {
                if (view != null) {
                    view.particles().worldEffect(effect.type(), effect.x(), effect.y(), effect.z(), effect.data());
                }
            }
            case ServerSession.Explosion explosion -> {
                player.push(explosion.pushX(), explosion.pushY(), explosion.pushZ());
                if (view != null) {
                    // Explosion.doExplosionB: one huge or large explosion particle at the center.
                    view.particles().spawn(explosion.strength() >= 2f ? 2 : 1, true, explosion.x(), explosion.y(),
                            explosion.z(), 1, 0, 0);
                }
            }
            case ServerSession.Disconnected gone -> disconnected.accept(gone.reason());
            default -> {
            }
        }
    }

    /** A new world (join or respawn): the client's WorldClient starts clear; the server re-sends weather. */
    private void newWorld() {
        rain = 0.0F;
        thunder = 0.0F;
        entities.clear();
        signs.clear();
        vehicle = -1;
        player.usingItem = false;
        if (view != null) {
            view.weather(rain, thunder);
        }
        spawned = false;
    }

    /** GuiIngame.displayTitle. */
    private void title(ServerSession.Title event) {
        switch (event.action()) {
            case 0 -> {
                title = chat.legacy(event.json());
                titleTicks = titleFadeIn + titleStay + titleFadeOut;
            }
            case 1 -> subtitle = chat.legacy(event.json());
            case 2 -> {
                titleFadeIn = Math.max(0, event.fadeIn());
                titleStay = Math.max(0, event.stay());
                titleFadeOut = Math.max(0, event.fadeOut());
                if (titleTicks > 0) {
                    titleTicks = titleFadeIn + titleStay + titleFadeOut;
                }
            }
            case 3 -> {
                title = "";
                subtitle = "";
                titleTicks = 0;
            }
            default -> {
                title = "";
                subtitle = "";
                titleTicks = 0;
                titleFadeIn = 10;
                titleStay = 70;
                titleFadeOut = 20;
            }
        }
    }

    /**
     * NetHandlerPlayClient.handleChangeGameState's weather reasons: 1 begins rain (from strength 0), 2 ends it
     * (from strength 1), 7 and 8 set the rain and thunder strength the server fades each tick.
     */
    private void weather(ServerSession.GameState state) {
        switch (state.reason()) {
            case 1 -> rain = 0.0F;
            case 2 -> rain = 1.0F;
            case 7 -> rain = state.value();
            case 8 -> thunder = state.value();
            default -> {
                return;
            }
        }
        if (view != null) {
            view.weather(rain, thunder);
        }
    }

    /** Whether the world is superflat (vanilla's horizon and void fog differ there). */
    boolean flat() {
        return flat;
    }

    float rain() {
        return rain;
    }

    /** The Controls bindings, read from the current keyboard and mouse state; Auto sprint sprints walking forward. */
    private LocalPlayer.Input input(ConfigSnapshot settings, boolean[] keys, boolean[] buttons) {
        float forward = (held(settings.get(ClientSettings.KEY_FORWARD), keys, buttons) ? 1 : 0)
                - (held(settings.get(ClientSettings.KEY_BACK), keys, buttons) ? 1 : 0);
        float strafe = (held(settings.get(ClientSettings.KEY_LEFT), keys, buttons) ? 1 : 0)
                - (held(settings.get(ClientSettings.KEY_RIGHT), keys, buttons) ? 1 : 0);
        // A toggle stands in for the held key, so vanilla's sprint rules (forward >= 0.8, food, no item use) apply.
        boolean sprint = held(settings.get(ClientSettings.KEY_SPRINT), keys, buttons) || sprintToggled
                || settings.get(ClientSettings.AUTO_SPRINT) && forward > 0;
        boolean sneak = held(settings.get(ClientSettings.KEY_SNEAK), keys, buttons) || sneakToggled;
        return new LocalPlayer.Input(forward, strafe, held(settings.get(ClientSettings.KEY_JUMP), keys, buttons),
                sneak, sprint);
    }

    /** Whether a binding's input is held: a key by its SDL scancode name, or a mouse button. */
    static boolean held(String binding, boolean[] keys, boolean[] buttons) {
        if (binding == null || binding.equals("none")) {
            return false;
        }
        if (binding.startsWith("key:")) {
            int scancode = SDLKeyboard.SDL_GetScancodeFromName(binding.substring(4));
            return scancode > 0 && scancode < keys.length && keys[scancode];
        }
        if (binding.startsWith("mouse:")) {
            int button = mouseButton(binding);
            return button < buttons.length && buttons[button];
        }
        return false;
    }

    /** The SDL button of a {@code mouse:} binding. */
    static int mouseButton(String binding) {
        return switch (binding.substring(6)) {
            case "left" -> SDLMouse.SDL_BUTTON_LEFT;
            case "right" -> SDLMouse.SDL_BUTTON_RIGHT;
            case "middle" -> SDLMouse.SDL_BUTTON_MIDDLE;
            case "x1" -> SDLMouse.SDL_BUTTON_X1;
            default -> SDLMouse.SDL_BUTTON_X2;
        };
    }

    private void report() {
        if (player.sprinting != reportedSprint) {
            session.sendEntityAction(player.sprinting ? 3 : 4);
            reportedSprint = player.sprinting;
        }
        if (player.sneaking != reportedSneak) {
            session.sendEntityAction(player.sneaking ? 0 : 1);
            reportedSneak = player.sneaking;
        }
        double dx = player.x - reportedX;
        double dy = player.y - reportedY;
        double dz = player.z - reportedZ;
        boolean moved = dx * dx + dy * dy + dz * dz > 9.0E-4D || ticksSinceReport >= 20;
        boolean turned = player.yaw != reportedYaw || player.pitch != reportedPitch;
        if (moved && turned) {
            session.sendPositionLook(player.x, player.y, player.z, player.yaw, player.pitch, player.onGround);
        } else if (moved) {
            session.sendPosition(player.x, player.y, player.z, player.onGround);
        } else if (turned) {
            session.sendLook(player.yaw, player.pitch, player.onGround);
        } else {
            session.sendGround(player.onGround);
        }
        ticksSinceReport++;
        if (moved) {
            reportedX = player.x;
            reportedY = player.y;
            reportedZ = player.z;
            ticksSinceReport = 0;
        }
        if (turned) {
            reportedYaw = player.yaw;
            reportedPitch = player.pitch;
        }
    }

    /** The player's own potion effects and gear as the movement code reads them (vanilla's isPotionActive). */
    private void applyEffects() {
        player.jumpBoost = amplifier(8);
        player.blind = effects.containsKey(15);
        player.haste = amplifier(3);
        player.miningFatigue = amplifier(4);
        ItemStack boots = inventory.armor(0);
        player.depthStrider = boots == null ? 0 : boots.enchantment(8);
        player.riding = vehicle >= 0;
    }

    /** An active effect's amplifier, or -1. The vanilla client keeps an effect until the server removes it. */
    int amplifier(int effect) {
        Effect active = effects.get(effect);
        return active == null ? -1 : active.amplifier();
    }

    /** The held hotbar stack (for the hand and the hotbar). */
    ItemStack held() {
        return inventory.held();
    }

    /** Hitbox of an entity for the critical hit emitter. */
    static float[] size(Entity entity) {
        return new float[]{EntityTypes.width(entity), EntityTypes.height(entity)};
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | (long) z & 0x3FFFFFF;
    }

    @Override
    public void close() {
        session.close();
    }
}
