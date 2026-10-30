package alkaline.game.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.game.net.PacketBuffer;
import alkaline.game.net.ServerSession;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntityTest {
    @Test
    void livingEntitiesReachAMoveOverThreeTicksAndObjectsOnTheNext() {
        Entity zombie = new Entity(1, Entity.Category.MOB, EntityTypes.ZOMBIE, null, 0, 64, 0, 0, 0);
        zombie.move(0.75, 0, 0, false, 0, 0, true);
        zombie.tick();
        assertEquals(0.25, zombie.x, 1e-9);
        zombie.tick();
        zombie.tick();
        assertEquals(0.75, zombie.x, 1e-9);
        assertTrue(zombie.limbSwingAmount > 0);
        Entity arrow = new Entity(2, Entity.Category.OBJECT, EntityTypes.OBJECT_ARROW, null, 0, 64, 0, 0, 0);
        arrow.move(1, 0, 0, false, 0, 0, false);
        assertEquals(0, arrow.x, 1e-9);
        arrow.tick();
        assertEquals(1, arrow.x, 1e-9);
        assertEquals(0.5, arrow.renderX(0.5f), 1e-9);
    }

    @Test
    void relativeMovesAccumulateInFixedPointLikeTheServer() {
        Entity entity = new Entity(1, Entity.Category.MOB, EntityTypes.PIG, null, 10.5, 64, 10.5, 0, 0);
        for (int step = 0; step < 32; step++) {
            entity.move(1 / 32.0, 0, 0, false, 0, 0, true);
        }
        for (int tick = 0; tick < 3; tick++) {
            entity.tick();
        }
        assertEquals(11.5, entity.x, 1e-9);
    }

    @Test
    void hurtFlashesForTenTicksAndSwingsTakeSix() {
        Entity player = new Entity(3, Entity.Category.PLAYER, 0, UUID.randomUUID(), 0, 64, 0, 0, 0);
        player.hurt();
        assertEquals(Entity.HURT_TICKS, player.hurtTime);
        player.swingArm();
        for (int tick = 0; tick < 3; tick++) {
            player.tick();
        }
        assertEquals(2 / 6f, player.swingProgress, 1e-6);
        assertEquals(Entity.HURT_TICKS - 3, player.hurtTime);
        for (int tick = 0; tick < 4; tick++) {
            player.tick();
        }
        assertEquals(0f, player.swingProgress);
    }

    @Test
    void bodyYawStaysWithin75DegreesOfTheHead() {
        Entity player = new Entity(3, Entity.Category.PLAYER, 0, UUID.randomUUID(), 0, 64, 0, 0, 0);
        player.move(0, 0, 0, true, 120, 0, true);
        for (int tick = 0; tick < 10; tick++) {
            player.tick();
        }
        assertTrue(Math.abs(Entity.wrap(player.yaw - player.bodyYaw)) <= 75f + 1e-3, String.valueOf(player.bodyYaw));
    }

    @Test
    void trackerAppliesSpawnsMetadataAndDestroys() throws IOException {
        EntityTracker tracker = new EntityTracker();
        tracker.selfId(7);
        PacketBuffer metadata = new PacketBuffer().writeByte(0x00).writeByte(0x02).writeByte(0x66)
                .writeFloat(12f).writeByte(0x7F);
        Metadata values = Metadata.read(PacketBuffer.wrap(metadata.toByteArray()));
        assertTrue(tracker.apply(new ServerSession.SpawnMob(5, EntityTypes.CREEPER, 1, 64, 1, 0, 0, 0, 0, 0, 0, values)));
        Entity creeper = tracker.get(5);
        assertNotNull(creeper);
        assertTrue(creeper.sneaking());
        assertEquals(12f, creeper.health());
        tracker.apply(new ServerSession.EntityMetadata(7, values));
        assertEquals(12f, tracker.self().getFloat(6, 0));
        tracker.apply(new ServerSession.DestroyEntities(new int[]{5}));
        assertNull(tracker.get(5));
    }

    @Test
    void pickerHitsTheNearestEntityInReach() {
        EntityTracker tracker = new EntityTracker();
        tracker.apply(new ServerSession.SpawnMob(1, EntityTypes.ZOMBIE, 0, 64, 2.5, 0, 0, 0, 0, 0, 0, new Metadata()));
        tracker.apply(new ServerSession.SpawnMob(2, EntityTypes.ZOMBIE, 0, 64, 5.0, 0, 0, 0, 0, 0, 0, new Metadata()));
        EntityPicker picker = new EntityPicker();
        // Yaw 0 looks toward +z.
        Entity hit = picker.pick(tracker, (x, y, z) -> 0, 0, 65.62, 0, 0f, 0f, 3.0);
        assertSame(tracker.get(1), hit);
        assertNull(picker.pick(tracker, (x, y, z) -> 0, 0, 65.62, 0, 180f, 0f, 3.0));
        // A stone wall at z = 1 blocks the swing.
        assertNull(picker.pick(tracker, (x, y, z) -> z == 1 ? 1 << 4 : 0, 0, 65.62, 0, 0f, 0f, 3.0));
    }

    @Test
    void enderCrystalsArePickableTwoBlockCubes() {
        EntityTracker tracker = new EntityTracker();
        tracker.apply(new ServerSession.SpawnObject(3, EntityTypes.OBJECT_ENDER_CRYSTAL, 0.5, 64, 2.5, 0f, 0f, 0, 0,
                0, 0));
        Entity crystal = tracker.get(3);
        assertEquals(2f, EntityTypes.width(crystal), 0f);
        assertEquals(2f, EntityTypes.height(crystal), 0f);
        // The look ray from (0.5, 65.62, 0) toward +z meets the crystal's near face at z = 1.5 - 0.1.
        EntityPicker picker = new EntityPicker();
        assertSame(crystal, picker.pick(tracker, (x, y, z) -> 0, 0.5, 65.62, 0, 0f, 0f, 3.0));
        assertEquals(1.4, picker.distance(), 1e-9);
        // Other objects stay out of reach of the crosshair.
        tracker.apply(new ServerSession.DestroyEntities(new int[]{3}));
        tracker.apply(new ServerSession.SpawnObject(4, EntityTypes.OBJECT_BOAT, 0.5, 64, 2.5, 0f, 0f, 0, 0, 0, 0));
        assertNull(picker.pick(tracker, (x, y, z) -> 0, 0.5, 65.62, 0, 0f, 0f, 3.0));
    }
}
