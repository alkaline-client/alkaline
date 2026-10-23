package alkaline.client.ui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Owns page transitions and history while keeping page implementations renderer-neutral. */
public final class ClientPageRouter {
    private final Map<PageId, ClientPage> pages = new EnumMap<>(PageId.class);
    private final Deque<PageId> history = new ArrayDeque<>();
    private ClientPage current;
    private int viewportWidth;
    private int viewportHeight;
    private boolean quitRequested;

    public ClientPageRouter(int width, int height) {
        pages.put(PageId.MAIN_MENU, new MainMenuPage());
        pages.put(PageId.SINGLE_PLAYER, new SinglePlayerPage());
        pages.put(PageId.MULTIPLAYER, new MultiplayerPage());
        pages.put(PageId.LANGUAGE, new LanguagePage());
        pages.put(PageId.PAUSE_MENU, new PauseMenuPage());
        viewportWidth = width;
        viewportHeight = height;
        current = pages.get(PageId.MAIN_MENU);
        current.resize(width, height);
        current.enter();
    }

    public PageId currentPage() {
        return current.id();
    }

    public PageLayout layout() {
        return current.layout();
    }

    public boolean quitRequested() {
        return quitRequested;
    }

    public void openPauseMenu() {
        transition(PageAction.PAUSE_MENU);
    }

    public PageAction dispatch(PageInput input) {
        Objects.requireNonNull(input, "input");
        if (input instanceof PageInput.Resized resized) {
            viewportWidth = resized.width();
            viewportHeight = resized.height();
        }
        PageAction action = current.handle(input);
        transition(action);
        return action;
    }

    private void transition(PageAction action) {
        switch (action) {
            case NONE -> {
            }
            case QUIT -> quitRequested = true;
            case BACK -> goBack();
            case MAIN_MENU -> show(PageId.MAIN_MENU, true);
            case SINGLE_PLAYER -> show(PageId.SINGLE_PLAYER, false);
            case MULTIPLAYER -> show(PageId.MULTIPLAYER, false);
            case LANGUAGE -> show(PageId.LANGUAGE, false);
            case PAUSE_MENU -> show(PageId.PAUSE_MENU, false);
        }
    }

    private void show(PageId next, boolean clearHistory) {
        ClientPage destination = pages.get(next);
        if (destination == null) {
            throw new IllegalStateException("No page registered for " + next + ".");
        }
        if (destination == current) {
            return;
        }
        current.exit();
        if (clearHistory) {
            history.clear();
        } else {
            history.push(current.id());
        }
        current = destination;
        current.resize(viewportWidth, viewportHeight);
        current.enter();
    }

    private void goBack() {
        if (history.isEmpty()) {
            if (current.id() == PageId.MAIN_MENU) {
                quitRequested = true;
            }
            return;
        }
        PageId previous = history.pop();
        current.exit();
        current = pages.get(previous);
        current.resize(viewportWidth, viewportHeight);
        current.enter();
    }
}
