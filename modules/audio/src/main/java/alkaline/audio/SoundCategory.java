package alkaline.audio;

/**
 * The volume groups of 1.8.9's SoundCategory, named as sounds.json names them. A sound's category comes from its
 * event, never from the code that plays it. {@link #MASTER} events (the button click) are scaled by the master
 * volume only, as SoundManager.getSoundCategoryVolume answers 1 for them.
 */
public enum SoundCategory {
    MASTER("master"),
    MUSIC("music"),
    RECORDS("record"),
    WEATHER("weather"),
    BLOCKS("block"),
    HOSTILE("hostile"),
    NEUTRAL("neutral"),
    PLAYERS("player"),
    AMBIENT("ambient");

    private static final SoundCategory[] VALUES = values();

    private final String jsonName;

    SoundCategory(String jsonName) {
        this.jsonName = jsonName;
    }

    /** The name sounds.json uses for the category. */
    public String jsonName() {
        return jsonName;
    }

    /** SoundCategory.getCategory: the category of a sounds.json name (case-sensitive), or null when it names none. */
    public static SoundCategory byJsonName(String name) {
        for (SoundCategory category : VALUES) {
            if (category.jsonName.equals(name)) {
                return category;
            }
        }
        return null;
    }
}
