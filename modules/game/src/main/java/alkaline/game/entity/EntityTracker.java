package alkaline.game.entity;

import alkaline.game.net.ServerSession;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The entities around the player, by id, applied from {@link ServerSession} events and ticked at 20 Hz on the
 * game thread. The local player is not in here (the client simulates it itself), but its own metadata (air,
 * flags) is kept as {@link #self()}.
 */
public final class EntityTracker {
    private final Map<Integer, Entity> entities = new HashMap<>();
    private final Collection<Entity> view = Collections.unmodifiableCollection(entities.values());
    private final List<Integer> removed = new ArrayList<>();
    private final Metadata self = new Metadata();
    private int selfId = Integer.MIN_VALUE;
    /** Told of every Entity Status for a tracked entity (client-side animations such as the rabbit's hop). */
    public java.util.function.BiConsumer<Entity, Integer> statusListener;

    /** The local player's entity id (from Join Game); its events update {@link #self()} instead. */
    public void selfId(int id) {
        selfId = id;
    }

    public int selfId() {
        return selfId;
    }

    /** The local player's watched values: flags (0), air (1), health (6)... */
    public Metadata self() {
        return self;
    }

    public Entity get(int id) {
        return entities.get(id);
    }

    public Collection<Entity> all() {
        return view;
    }

    public int size() {
        return entities.size();
    }

    public void clear() {
        entities.clear();
    }

    /**
     * Applies one event if it concerns entities; returns false for other events. Events about the local
     * player's id that the game must act on (velocity) are left to the caller.
     */
    public boolean apply(ServerSession.Event event) {
        switch (event) {
            case ServerSession.SpawnPlayer spawn -> {
                Entity entity = new Entity(spawn.entityId(), Entity.Category.PLAYER, 0, spawn.uuid(), spawn.x(),
                        spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
                entity.metadata.merge(spawn.metadata());
                entities.put(entity.id, entity);
            }
            case ServerSession.SpawnMob spawn -> {
                Entity entity = new Entity(spawn.entityId(), Entity.Category.MOB, spawn.type(), null, spawn.x(),
                        spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
                entity.headYaw = entity.previousHeadYaw = spawn.headYaw();
                entity.motionX = spawn.motionX();
                entity.motionY = spawn.motionY();
                entity.motionZ = spawn.motionZ();
                entity.metadata.merge(spawn.metadata());
                entities.put(entity.id, entity);
            }
            case ServerSession.SpawnObject spawn -> {
                Entity entity = new Entity(spawn.entityId(), Entity.Category.OBJECT, spawn.type(), null, spawn.x(),
                        spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
                entity.objectData = spawn.data();
                entity.motionX = spawn.motionX();
                entity.motionY = spawn.motionY();
                entity.motionZ = spawn.motionZ();
                if (spawn.type() == EntityTypes.OBJECT_ITEM_FRAME) {
                    // The packet carries the frame's block and facing (the data); the entity stands in the wall.
                    int facing = spawn.data() & 3;
                    entity = hanging(spawn.entityId(), EntityTypes.OBJECT_ITEM_FRAME, (int) Math.floor(spawn.x()),
                            (int) Math.floor(spawn.y()), (int) Math.floor(spawn.z()), facing, Hanging.FRAME_PIXELS,
                            Hanging.FRAME_PIXELS);
                    entity.objectData = facing;
                }
                entities.put(entity.id, entity);
            }
            case ServerSession.SpawnPainting spawn -> {
                Hanging.Art art = Hanging.art(Hanging.artIndex(spawn.title()));
                Entity entity = hanging(spawn.entityId(), EntityTypes.OBJECT_PAINTING, spawn.x(), spawn.y(),
                        spawn.z(), spawn.facing(), art.width(), art.height());
                entity.objectData = Hanging.artIndex(spawn.title());
                entities.put(entity.id, entity);
            }
            case ServerSession.SpawnExperienceOrb spawn -> {
                Entity entity = new Entity(spawn.entityId(), Entity.Category.OBJECT, EntityTypes.OBJECT_XP_ORB, null,
                        spawn.x(), spawn.y(), spawn.z(), 0f, 0f);
                entity.objectData = spawn.count();
                entities.put(entity.id, entity);
            }
            case ServerSession.SpawnGlobalEntity spawn -> {
                if (spawn.type() == 1) {
                    entities.put(spawn.entityId(), new Entity(spawn.entityId(), Entity.Category.OBJECT,
                            EntityTypes.OBJECT_LIGHTNING, null, spawn.x(), spawn.y(), spawn.z(), 0f, 0f));
                }
            }
            case ServerSession.CollectItem collect -> entities.remove(collect.collected());
            case ServerSession.EntityAttach attach -> {
                Entity entity = entities.get(attach.entityId());
                if (entity != null && !attach.leash()) {
                    entity.vehicle = attach.vehicleId();
                }
                // The local player's own riding is the game's to act on.
                return attach.entityId() != selfId;
            }
            case ServerSession.EntityMove move -> {
                Entity entity = entities.get(move.entityId());
                if (entity != null) {
                    entity.move(move.dx(), move.dy(), move.dz(), move.look(), move.yaw(), move.pitch(),
                            move.onGround());
                }
            }
            case ServerSession.EntityTeleport teleport -> {
                Entity entity = entities.get(teleport.entityId());
                if (entity != null) {
                    entity.teleport(teleport.x(), teleport.y(), teleport.z(), teleport.yaw(), teleport.pitch(),
                            teleport.onGround());
                }
            }
            case ServerSession.EntityHeadLook look -> {
                Entity entity = entities.get(look.entityId());
                if (entity != null) {
                    entity.headYaw = look.headYaw();
                }
            }
            case ServerSession.EntityVelocity velocity -> {
                Entity entity = entities.get(velocity.entityId());
                if (entity == null) {
                    return velocity.entityId() != selfId;
                }
                entity.motionX = velocity.motionX();
                entity.motionY = velocity.motionY();
                entity.motionZ = velocity.motionZ();
            }
            case ServerSession.DestroyEntities destroy -> {
                for (int id : destroy.entityIds()) {
                    entities.remove(id);
                }
            }
            case ServerSession.EntityStatus status -> {
                Entity entity = entities.get(status.entityId());
                if (entity != null) {
                    if (status.status() == 2) {
                        entity.hurt();
                    } else if (status.status() == 3) {
                        entity.die();
                    }
                    if (statusListener != null) {
                        statusListener.accept(entity, status.status());
                    }
                }
                return status.entityId() != selfId || entity != null;
            }
            case ServerSession.EntityAnimation animation -> {
                Entity entity = entities.get(animation.entityId());
                if (entity != null) {
                    if (animation.animation() == 0) {
                        entity.swingArm();
                    } else if (animation.animation() == 1) {
                        entity.hurt();
                    }
                }
                // Critical hits also spawn particles, which the caller handles.
                return animation.animation() < 4;
            }
            case ServerSession.EntityMetadata metadata -> {
                if (metadata.entityId() == selfId) {
                    self.merge(metadata.metadata());
                } else {
                    Entity entity = entities.get(metadata.entityId());
                    if (entity != null) {
                        entity.metadata.merge(metadata.metadata());
                    }
                }
            }
            case ServerSession.EntityEffect effect -> {
                Entity entity = entities.get(effect.entityId());
                if (entity != null) {
                    entity.effects.put(effect.effect(), effect.amplifier());
                }
                // The local player's own effects are the game's.
                return effect.entityId() != selfId;
            }
            case ServerSession.RemoveEntityEffect effect -> {
                Entity entity = entities.get(effect.entityId());
                if (entity != null) {
                    entity.effects.remove(effect.effect());
                }
                return effect.entityId() != selfId;
            }
            case ServerSession.EntityEquipment equipment -> {
                Entity entity = entities.get(equipment.entityId());
                if (entity != null && equipment.slot() >= 0 && equipment.slot() < entity.equipment.length) {
                    entity.equipment[equipment.slot()] = equipment.item();
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** A painting or item frame at its wall block, standing where EntityHanging puts it, turned to its facing. */
    private static Entity hanging(int id, int type, int x, int y, int z, int facing, int width, int height) {
        double[] at = Hanging.position(x, y, z, facing, width, height);
        return new Entity(id, Entity.Category.OBJECT, type, null, at[0], at[1], at[2], (facing & 3) * 90f, 0f);
    }

    /** One 20 Hz tick for every entity; entities that finished dying are dropped as the client does. */
    public void tick() {
        tick(null);
    }

    /** One tick with the world, so objects fly (and arrows stick) between the server's updates. */
    public void tick(alkaline.game.player.CollisionShapes.States world) {
        removed.clear();
        for (Entity entity : entities.values()) {
            entity.tick(world);
            // EntityLightningBolt strikes a few times and is gone; the server never destroys it.
            if (entity.dead && (entity.deathTime >= 20
                    || entity.category == Entity.Category.OBJECT && entity.type == EntityTypes.OBJECT_LIGHTNING)) {
                removed.add(entity.id);
            }
        }
        for (int id : removed) {
            entities.remove(id);
        }
    }
}
