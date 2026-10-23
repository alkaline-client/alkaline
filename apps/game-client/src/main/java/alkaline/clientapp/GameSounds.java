package alkaline.clientapp;

import alkaline.audio.AudioEngine;
import alkaline.audio.SoundCategory;
import alkaline.client.settings.ClientSettings;
import alkaline.config.ConfigSnapshot;
import alkaline.renderer.asset.AssetSource;
import alkaline.game.inventory.ItemStack;
import alkaline.game.net.ServerSession;
import alkaline.game.player.Interactions;
import alkaline.game.player.LocalPlayer;
import alkaline.game.world.ClientWorld;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Random;

/**
 * The sounds of a game in progress, split between client and server exactly as 1.8.9 splits them, so nothing plays
 * twice and nothing is missing:
 *
 * <ul>
 *   <li>Sound Effect packets carry nearly everything that happens in the world, the local player's own bow shots,
 *   burps, placed blocks and pickups included: the server's World.playSoundAtEntity reaches every player near, while
 *   the client's own playSoundAtEntity (RenderGlobal.playSound) does nothing.</li>
 *   <li>What the local player's entity sounds itself goes through EntityPlayer.playSound, which the server sends
 *   with playSoundToNearExcept, leaving that player out; EntityPlayerSP.playSound plays it here instead: footsteps
 *   and swimming (Entity.moveEntity), landing (EntityLivingBase.fall), eating and drinking
 *   (EntityPlayer.updateItemUse), and the hurt and death sounds of Entity Status 2 and 3.</li>
 *   <li>Other entities' Entity Status sounds stay silent here: the server sent them already.</li>
 *   <li>World Effect (RenderGlobal.playAuxSFX and broadcastSound), and the arrow-hit ding and guardian curse of
 *   Change Game State 6 and 10 (NetHandlerPlayClient.handleChangeGameState).</li>
 * </ul>
 */
final class GameSounds {
    /**
     * Block.SoundType by block id (Block.registerBlocks): S stone, W wood, G gravel, R grass, M metal, L glass,
     * C cloth, A sand, N snow, D ladder, V anvil, I slime. Ids past the table are stone.
     */
    private static final String SOUND_TYPES =
            "SSRGSWRSSSSSAGSSSWRRLSSSSSWMMSSRRSSCSRRRRMMSSSRWSSWCMWWSSMWRGSSWWD"
            + "MSWWSMWSSWWSNLNCGRSWWSALLWCWWLWSSWWMLWWWRWSSRRSSSSSSSSLSSLLWWWSSSS"
            + "SMWWWSSSSRRWSVWWWWWWMSMSSMSSLRWWWISMSLRCSSLRWWWSSSSWWWWWWWWWWWWWWW";
    /** ItemRecord names by item id from 2256. */
    private static final String[] RECORDS = {"13", "cat", "blocks", "chirp", "far", "mall", "mellohi", "stal", "strad",
            "ward", "11", "wait"};
    private static final int SNOW_LAYER = 78;

    private final AudioEngine audio;
    private final Random random = new Random();
    /** The food or drink last in use, for the last bite Entity Status 9 plays; -1 for none. */
    private int usedItem = -1;
    private long usedTick;
    private int dimension = Integer.MIN_VALUE;

    GameSounds(AudioEngine audio) {
        this.audio = audio;
    }

    /** The Audio settings as category volumes; music and records share one slider. */
    static void volumes(AudioEngine audio, ConfigSnapshot settings) {
        audio.volume(SoundCategory.MASTER, settings.get(ClientSettings.AUDIO_MASTER));
        audio.volume(SoundCategory.PLAYERS, settings.get(ClientSettings.AUDIO_PLAYERS));
        audio.volume(SoundCategory.HOSTILE, settings.get(ClientSettings.AUDIO_HOSTILE));
        audio.volume(SoundCategory.NEUTRAL, settings.get(ClientSettings.AUDIO_NEUTRAL));
        audio.volume(SoundCategory.BLOCKS, settings.get(ClientSettings.AUDIO_BLOCKS));
        audio.volume(SoundCategory.WEATHER, settings.get(ClientSettings.AUDIO_WEATHER));
        audio.volume(SoundCategory.AMBIENT, settings.get(ClientSettings.AUDIO_AMBIENT));
        audio.volume(SoundCategory.MUSIC, settings.get(ClientSettings.AUDIO_MUSIC));
        audio.volume(SoundCategory.RECORDS, settings.get(ClientSettings.AUDIO_MUSIC));
    }

    /** The layered assets (resource packs over the vanilla jar and asset index) as the audio engine reads them. */
    static AudioEngine.Assets assets(AssetSource source) {
        return new AudioEngine.Assets() {
            @Override
            public InputStream open(String path) throws IOException {
                return source.open(path);
            }

            @Override
            public List<byte[]> readAll(String path) throws IOException {
                return source.readAll(path);
            }
        };
    }

    /** Every server event, before the session applies it (so the item in use is still known). */
    void event(ServerSession.Event event, PlaySession play) {
        switch (event) {
            // Minecraft.loadWorld stops every sound: on joining, and on respawning into another dimension.
            case ServerSession.JoinGame join -> {
                dimension = join.dimension();
                audio.stopAll();
            }
            case ServerSession.Respawn respawn when respawn.dimension() != dimension -> {
                dimension = respawn.dimension();
                audio.stopAll();
            }
            case ServerSession.Sound sound -> audio.play(sound.name(), sound.x(), sound.y(), sound.z(), sound.volume(),
                    sound.pitch());
            case ServerSession.EntityStatus status when status.entityId() == play.entities().selfId() -> {
                LocalPlayer player = play.player();
                float pitch = (random.nextFloat() - random.nextFloat()) * 0.2F + 1.0F;
                switch (status.status()) {
                    case 2 -> self(player, "game.player.hurt", 1.0F, pitch);
                    case 3 -> self(player, "game.player.die", 1.0F, pitch);
                    case 9 -> {
                        // EntityPlayer.onItemUseFinish: one last bite or sip (updateItemUse with 16 particles).
                        if (usedItem >= 0 && play.ticks() - usedTick <= 40) {
                            useSound(player, usedItem);
                        }
                        usedItem = -1;
                    }
                    default -> {
                    }
                }
            }
            case ServerSession.GameState state when state.reason() == 6 -> {
                LocalPlayer player = play.player();
                audio.play("random.successful_hit", player.x, player.y + LocalPlayer.EYE_HEIGHT, player.z, 0.18F, 0.45F);
            }
            case ServerSession.GameState state when state.reason() == 10 -> {
                LocalPlayer player = play.player();
                audio.play("mob.guardian.curse", player.x, player.y, player.z, 1.0F, 1.0F);
            }
            case ServerSession.WorldEffect effect -> {
                if (effect.global()) {
                    broadcast(effect, play.player());
                } else {
                    worldEffect(effect.type(), effect.x(), effect.y(), effect.z(), effect.data());
                }
            }
            default -> {
            }
        }
    }

    /** After the local player's tick: the sounds its movement and item use made. */
    void tick(PlaySession play) {
        LocalPlayer player = play.player();
        ClientWorld world = play.session().world();
        if (player.stepped) {
            player.stepped = false;
            step(player, world);
        }
        if (player.landedFall > 0) {
            float distance = player.landedFall;
            player.landedFall = 0;
            fall(player, world, distance, play.amplifier(8));
        }
        ItemStack held = play.held();
        if (player.usingItem && held != null && Interactions.useDuration(held, 0, false, true) == 32) {
            usedItem = held.id();
            usedTick = play.ticks();
            // EntityPlayer.onUpdate: a bite every fourth tick once 25 or fewer of the 32 remain.
            int remaining = 32 - (player.itemUseTicks - 1);
            if (player.itemUseTicks > 0 && remaining <= 25 && remaining % 4 == 0) {
                useSound(player, held.id());
            }
        }
    }

    /** Entity.moveEntity's step: the swim sound in water, then playStepSound for the block walked on. */
    private void step(LocalPlayer player, ClientWorld world) {
        int x = (int) Math.floor(player.x);
        int y = (int) Math.floor(player.y - 0.20000000298023224D);
        int z = (int) Math.floor(player.z);
        int block = world.state(x, y, z) >> 4;
        if (block == 0 && fenceLike(world.state(x, y - 1, z) >> 4)) {
            y--;
            block = world.state(x, y, z) >> 4;
        }
        if (player.inWater) {
            float volume = Math.min(1.0F, (float) Math.sqrt(player.motionX * player.motionX * 0.2F
                    + player.motionY * player.motionY + player.motionZ * player.motionZ * 0.2F) * 0.35F);
            self(player, "game.player.swim", volume, 1.0F + (random.nextFloat() - random.nextFloat()) * 0.4F);
        }
        if (world.state(x, y + 1, z) >> 4 == SNOW_LAYER) {
            self(player, "step.snow", 0.15F, 1.0F);
        } else if (block < 8 || block > 11) {
            char type = type(block);
            self(player, stepSound(type), volume(type) * 0.15F, frequency(type));
        }
    }

    /**
     * EntityLivingBase.fall for the local player (EntityPlayer.fall skips it while flying is allowed): the damage
     * sound and the landing block's step, once the fall hurts. Hay bales take four fifths off the fall, slime all
     * of it unless sneaking (Block.onFallenUpon).
     */
    private void fall(LocalPlayer player, ClientWorld world, float distance, int jumpBoost) {
        if (player.allowFlying) {
            return;
        }
        int x = (int) Math.floor(player.x);
        int y = (int) Math.floor(player.y - 0.20000000298023224D);
        int z = (int) Math.floor(player.z);
        int block = world.state(x, y, z) >> 4;
        int landedOn = block == 0 && fenceLike(world.state(x, y - 1, z) >> 4) ? world.state(x, y - 1, z) >> 4 : block;
        float multiplier = landedOn == 170 ? 0.2F : landedOn == 165 && !player.sneaking ? 0.0F : 1.0F;
        int damage = (int) Math.ceil((distance - 3.0F - (jumpBoost + 1)) * multiplier);
        if (damage <= 0) {
            return;
        }
        self(player, damage > 4 ? "game.player.hurt.fall.big" : "game.player.hurt.fall.small", 1.0F, 1.0F);
        if (block != 0) {
            char type = type(block);
            self(player, stepSound(type), volume(type) * 0.5F, frequency(type) * 0.75F);
        }
    }

    /** EntityPlayer.updateItemUse's sound: a sip for potions and milk, a bite for food. */
    private void useSound(LocalPlayer player, int item) {
        if (item == 373 || item == 335) {
            self(player, "random.drink", 0.5F, random.nextFloat() * 0.1F + 0.9F);
        } else {
            self(player, "random.eat", 0.5F + 0.5F * random.nextInt(2),
                    (random.nextFloat() - random.nextFloat()) * 0.2F + 1.0F);
        }
    }

    /** EntityPlayerSP.playSound: at the player's feet. */
    private void self(LocalPlayer player, String event, float volume, float pitch) {
        audio.play(event, player.x, player.y, player.z, volume, pitch);
    }

    /** RenderGlobal.playAuxSFX's sounds, centered on the block (World.playSoundAtPos) unless noted. */
    private void worldEffect(int type, int x, int y, int z, int data) {
        float slight = random.nextFloat() * 0.1F + 0.9F;
        float spread = (random.nextFloat() - random.nextFloat()) * 0.2F + 1.0F;
        switch (type) {
            case 1000 -> at(x, y, z, "random.click", 1.0F, 1.0F);
            case 1001 -> at(x, y, z, "random.click", 1.0F, 1.2F);
            case 1002 -> at(x, y, z, "random.bow", 1.0F, 1.2F);
            case 1003 -> at(x, y, z, "random.door_open", 1.0F, slight);
            case 1004 -> at(x, y, z, "random.fizz", 0.5F, 2.6F + (random.nextFloat() - random.nextFloat()) * 0.8F);
            case 1005 -> record(x, y, z, data);
            case 1006 -> at(x, y, z, "random.door_close", 1.0F, slight);
            case 1007 -> at(x, y, z, "mob.ghast.charge", 10.0F, spread);
            case 1008 -> at(x, y, z, "mob.ghast.fireball", 10.0F, spread);
            case 1009 -> at(x, y, z, "mob.ghast.fireball", 2.0F, spread);
            case 1010 -> at(x, y, z, "mob.zombie.wood", 2.0F, spread);
            case 1011 -> at(x, y, z, "mob.zombie.metal", 2.0F, spread);
            case 1012 -> at(x, y, z, "mob.zombie.woodbreak", 2.0F, spread);
            case 1014 -> at(x, y, z, "mob.wither.shoot", 2.0F, spread);
            case 1015 -> at(x, y, z, "mob.bat.takeoff", 0.05F, spread);
            case 1016 -> at(x, y, z, "mob.zombie.infect", 2.0F, spread);
            case 1017 -> at(x, y, z, "mob.zombie.unfect", 2.0F, spread);
            case 1020 -> at(x, y, z, "random.anvil_break", 1.0F, slight);
            case 1021 -> at(x, y, z, "random.anvil_use", 1.0F, slight);
            case 1022 -> at(x, y, z, "random.anvil_land", 0.3F, slight);
            case 2001 -> {
                // A block broken (data: block id, and metadata from bit 12): its sound type's break sound.
                int block = data & 4095;
                if (block != 0) {
                    char sound = type(block);
                    audio.play(breakSound(sound), x + 0.5, y + 0.5, z + 0.5, (volume(sound) + 1.0F) / 2.0F,
                            frequency(sound) * 0.8F);
                }
            }
            case 2002 -> at(x, y, z, "game.potion.smash", 1.0F, slight);
            default -> {
            }
        }
    }

    /** RenderGlobal.broadcastSound: heard everywhere, two blocks from the player toward the source. */
    private void broadcast(ServerSession.WorldEffect effect, LocalPlayer player) {
        if (effect.type() != 1013 && effect.type() != 1018) {
            return;
        }
        double dx = effect.x() - player.x;
        double dy = effect.y() - player.y;
        double dz = effect.z() - player.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double x = player.x + (distance > 0 ? dx / distance * 2.0 : 0);
        double y = player.y + (distance > 0 ? dy / distance * 2.0 : 0);
        double z = player.z + (distance > 0 ? dz / distance * 2.0 : 0);
        if (effect.type() == 1013) {
            audio.play("mob.wither.spawn", x, y, z, 1.0F, 1.0F);
        } else {
            audio.play("mob.enderdragon.end", x, y, z, 5.0F, 1.0F);
        }
    }

    /** RenderGlobal.playRecord: one record per jukebox, at its corner (PositionedSoundRecord.create, volume 4). */
    private void record(int x, int y, int z, int item) {
        long key = ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | (long) z & 0x3FFFFFF;
        if (item >= 2256 && item < 2256 + RECORDS.length) {
            audio.playKeyed(key, "records." + RECORDS[item - 2256], x, y, z, 4.0F, 1.0F);
        } else {
            audio.stopKeyed(key);
        }
    }

    private void at(int x, int y, int z, String event, float volume, float pitch) {
        audio.play(event, x + 0.5, y + 0.5, z + 0.5, volume, pitch);
    }

    /** Fences, walls and gates: Entity.moveEntity steps on them from the air block above. */
    private static boolean fenceLike(int id) {
        return id == 85 || id == 113 || id >= 188 && id <= 192 || id == 139 || id == 107 || id >= 183 && id <= 187;
    }

    private static char type(int block) {
        return block >= 0 && block < SOUND_TYPES.length() ? SOUND_TYPES.charAt(block) : 'S';
    }

    /** Block.SoundType.getStepSound. */
    private static String stepSound(char type) {
        return switch (type) {
            case 'I' -> "mob.slime.small";
            default -> "step." + name(type);
        };
    }

    /** Block.SoundType.getBreakSound, with the glass, ladder, anvil and slime overrides. */
    private static String breakSound(char type) {
        return switch (type) {
            case 'L' -> "dig.glass";
            case 'D' -> "dig.wood";
            case 'V' -> "dig.stone";
            case 'I' -> "mob.slime.big";
            default -> "dig." + name(type);
        };
    }

    private static String name(char type) {
        return switch (type) {
            case 'W' -> "wood";
            case 'G' -> "gravel";
            case 'R' -> "grass";
            case 'C' -> "cloth";
            case 'A' -> "sand";
            case 'N' -> "snow";
            case 'D' -> "ladder";
            case 'V' -> "anvil";
            case 'I' -> "slime";
            default -> "stone"; // stone, piston, metal and glass
        };
    }

    private static float volume(char type) {
        return type == 'V' ? 0.3F : 1.0F;
    }

    private static float frequency(char type) {
        return type == 'M' ? 1.5F : 1.0F;
    }
}
