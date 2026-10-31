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
    /** Plugins' commands: true when one handled the line, which then never reaches the server. */
    private final java.util.function.Predicate<String> commands;

    ChatBridge(Supplier<PlaySession> play, Consumer<Runnable> mainThread, java.util.function.Predicate<String> commands) {
        this.play = play;
        this.mainThread = mainThread;
        this.commands = commands;
    }

    @Override
    public void send(String message) {
        onPlay(session -> {
            if (!commands.test(message)) {
                session.sendChat(message);
            }
        });
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
