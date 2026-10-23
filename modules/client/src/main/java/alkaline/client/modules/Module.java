package alkaline.client.modules;

import alkaline.config.Setting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One switchable client feature. Its state lives in the active profile: an enabled switch, its own options, and,
 * for HUD modules, a {@link HudPlacement}. Modules are declared in {@link Modules}; behavior lives with the code
 * that reads them (the HUD draws HUD modules, the game applies gameplay ones).
 */
public final class Module {
    /** HUD modules draw an element the HUD editor can move; gameplay modules change how the game behaves. */
    public enum Kind { HUD, GAMEPLAY }

    private final String id;
    private final String title;
    private final String description;
    private final Kind kind;
    private final Setting.BooleanSetting enabled;
    private final List<Setting<?>> options = new ArrayList<>();
    private final List<Setting<?>> optionsView = Collections.unmodifiableList(options);
    private final HudPlacement placement;

    Module(String id, String title, String description, Kind kind, Setting.BooleanSetting enabled,
           HudPlacement placement) {
        this.id = Objects.requireNonNull(id, "id");
        this.title = Objects.requireNonNull(title, "title");
        this.description = Objects.requireNonNull(description, "description");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.placement = placement;
        if ((kind == Kind.HUD) != (placement != null)) {
            throw new IllegalArgumentException("HUD modules, and only they, have a placement: " + id);
        }
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public Kind kind() {
        return kind;
    }

    public Setting.BooleanSetting enabled() {
        return enabled;
    }

    /** The module's own options, in the order the module page shows them. */
    public List<Setting<?>> options() {
        return optionsView;
    }

    /** Appends an option while {@link Modules} declares the module; never called afterwards. */
    void addOption(Setting<?> option) {
        options.add(Objects.requireNonNull(option, "option"));
    }

    /** Where a HUD module draws; null for gameplay modules. */
    public HudPlacement placement() {
        return placement;
    }

    @Override
    public String toString() {
        return id;
    }
}
