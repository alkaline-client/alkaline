package alkaline.client.modules;

import alkaline.config.Setting;

/**
 * Where a HUD module draws: an anchor, an offset from it in dp (positive is right and down), and a scale. The
 * HUD editor writes these; the anchor follows the screen third the element is dropped in.
 */
public record HudPlacement(Setting.EnumSetting<HudAnchor> anchor, Setting.FloatSetting x, Setting.FloatSetting y,
                           Setting.FloatSetting scale) {
    public static final float MAX_OFFSET = 4096f;
    public static final float MIN_SCALE = 0.5f;
    public static final float MAX_SCALE = 2f;
}
