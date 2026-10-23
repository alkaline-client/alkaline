package alkaline.clientapp;

import alkaline.ui.menu.ChatHost;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Takes what the player types in chat from the UI thread to the main thread, where the game's session and SDL are,
 * and sends or copies it from there.
 */
final class ChatBridge implements ChatHost {
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
    public void copy(String text) {
        // SDL expects its calls on the main thread.
        mainThread.accept(() -> org.lwjgl.sdl.SDLClipboard.SDL_SetClipboardText(text));
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
