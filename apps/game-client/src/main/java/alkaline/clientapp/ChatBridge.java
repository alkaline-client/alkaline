package alkaline.clientapp;

import alkaline.ui.menu.ChatHost;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Takes what the player types in chat from the UI thread to the main thread, where the game's session is, and
 * sends it from there.
 */
final class ChatBridge implements ChatHost {
    /** GuiChat's tab-completion list is one line that the next Tab replaces. */
    private static final int LISTING_ID = 1;

    private final Supplier<PlaySession> play;
    private final Consumer<Runnable> mainThread;

    ChatBridge(Supplier<PlaySession> play, Consumer<Runnable> mainThread) {
        this.play = play;
        this.mainThread = mainThread;
    }

    @Override
    public void send(String message) {
        onPlay(session -> session.sendChat(message));
    }

    @Override
    public void complete(String text) {
        onPlay(session -> session.requestCompletion(text));
    }

    @Override
    public void listing(String text) {
        onPlay(session -> session.localChat(text, LISTING_ID));
    }

    private void onPlay(Consumer<PlaySession> action) {
        mainThread.accept(() -> {
            PlaySession session = play.get();
            if (session != null) {
                action.accept(session);
            }
        });
    }
}
