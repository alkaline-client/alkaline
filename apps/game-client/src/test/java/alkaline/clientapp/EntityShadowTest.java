package alkaline.clientapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.game.entity.Entity;
import alkaline.game.entity.EntityTypes;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntityShadowTest {
    private static Entity entity(Entity.Category category, int type) {
        return new Entity(1, category, type, UUID.randomUUID(), 0, 64, 0, 0, 0);
    }

    @Test
    void sizesFollowEachRenderer() {
        assertEquals(0.5f, EntityView.shadowSize(entity(Entity.Category.PLAYER, 0)));
        assertEquals(0.15f, EntityView.shadowSize(entity(Entity.Category.OBJECT, EntityTypes.OBJECT_ITEM)));
        assertEquals(0.5f, EntityView.shadowSize(entity(Entity.Category.OBJECT, EntityTypes.OBJECT_MINECART)));
        assertEquals(0f, EntityView.shadowSize(entity(Entity.Category.OBJECT, EntityTypes.OBJECT_ARMOR_STAND)));
        assertEquals(0f, EntityView.shadowSize(entity(Entity.Category.OBJECT, EntityTypes.OBJECT_ARROW)));
        assertEquals(1f, EntityView.shadowSize(entity(Entity.Category.MOB, EntityTypes.SPIDER)));
        assertEquals(0.7f, EntityView.shadowSize(entity(Entity.Category.MOB, EntityTypes.COW)));
        assertEquals(3f, EntityView.shadowSize(entity(Entity.Category.MOB, EntityTypes.GIANT)));
        Entity slime = entity(Entity.Category.MOB, EntityTypes.SLIME);
        slime.metadata.setByte(16, 4);
        assertEquals(1f, EntityView.shadowSize(slime));
    }

    @Test
    void childrenCastHalfShadowsAndChildVillagersAQuarterOfThat() {
        Entity calf = entity(Entity.Category.MOB, EntityTypes.COW);
        calf.metadata.setByte(12, -1);
        assertEquals(0.35f, EntityView.shadowSize(calf));
        Entity child = entity(Entity.Category.MOB, EntityTypes.VILLAGER);
        child.metadata.setByte(12, -1);
        assertEquals(0.125f, EntityView.shadowSize(child));
    }

    @Test
    void shadowsLieOnlyOnFullCubesThatRender() {
        assertTrue(EntityView.castsOn(1 << 4));       // stone
        assertTrue(EntityView.castsOn(2 << 4));       // grass
        assertFalse(EntityView.castsOn(20 << 4));     // glass: BlockGlass is not a full cube
        assertFalse(EntityView.castsOn(0));           // air
        assertFalse(EntityView.castsOn(44 << 4));     // half slab
        assertFalse(EntityView.castsOn(166 << 4));    // barrier: a full cube that does not render
        assertFalse(EntityView.castsOn(4095 << 4));   // unknown id
    }

    @Test
    void lightLosesTheTimesDarkeningAndSlabsTakeTheirBrightestNeighbor() {
        EntityView.Lighting world = new EntityView.Lighting() {
            @Override
            public int light(int x, int y, int z) {
                return x == 1 ? 0xF0 | 9 : 0xF0;
            }

            @Override
            public int state(int x, int y, int z) {
                return x == 0 && y == 5 ? 44 << 4 : 0;
            }
        };
        assertEquals(15, EntityView.shadowLight(world, 0, 3, 5, 0));
        assertEquals(4, EntityView.shadowLight(world, 11, 3, 5, 0));
        // The slab at x 0 reads its east neighbor's block light of 9 at midnight.
        assertEquals(9, EntityView.shadowLight(world, 11, 0, 5, 0));
        assertEquals(0, EntityView.shadowLight(world, 0, 3, -1, 0));
    }
}
