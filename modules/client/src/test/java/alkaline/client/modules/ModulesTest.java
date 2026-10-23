package alkaline.client.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.config.Setting;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModulesTest {
    @Test
    void everySettingBelongsToExactlyOneModuleAndIsInTheSchema() {
        Set<String> owned = new HashSet<>();
        for (Module module : Modules.all()) {
            assertSame(module, Modules.find(module.id()));
            assertTrue(owned.add(module.enabled().id()));
            for (Setting<?> option : module.options()) {
                assertTrue(option.id().startsWith(module.id() + "."), option.id());
                assertTrue(owned.add(option.id()));
            }
            HudPlacement placement = module.placement();
            if (module.kind() == Module.Kind.HUD) {
                assertNotNull(placement);
                owned.add(placement.anchor().id());
                owned.add(placement.x().id());
                owned.add(placement.y().id());
                owned.add(placement.scale().id());
            } else {
                assertNull(placement);
            }
        }
        assertEquals(Modules.SCHEMA.settings().size(), owned.size());
        for (Setting<?> setting : Modules.SCHEMA.settings()) {
            assertTrue(owned.contains(setting.id()), setting.id());
        }
    }

    @Test
    void optionsAreReachableFromTheirModuleFields() {
        assertTrue(Modules.SCOREBOARD.options().contains(Modules.SCOREBOARD_NUMBERS));
        assertTrue(Modules.ZOOM.options().contains(Modules.ZOOM_KEY));
        assertEquals(HudAnchor.BOTTOM, Modules.HOTBAR.placement().anchor().defaultValue());
    }
}
