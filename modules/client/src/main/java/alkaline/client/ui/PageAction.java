package alkaline.client.ui;

/** Navigation decisions returned by a page without coupling it to a router implementation. */
public enum PageAction {
    NONE,
    BACK,
    MAIN_MENU,
    SINGLE_PLAYER,
    MULTIPLAYER,
    LANGUAGE,
    PAUSE_MENU,
    QUIT
}
