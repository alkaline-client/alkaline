package alkaline.client;

import alkaline.client.account.AccountManager;
import alkaline.client.account.OAuthClient;
import alkaline.client.servers.ServerList;
import alkaline.client.servers.ServerPinger;
import alkaline.client.worlds.IntegratedServer;
import alkaline.client.worlds.WorldLibrary;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The client's non-rendering services the menus use: the Microsoft account, saved servers and their pings, and
 * the singleplayer world library and its local server. Files live in Alkaline's own directory; vanilla's game directory is only read.
 */
public final class ClientServices implements AutoCloseable {
    private final AccountManager accounts;
    private final ServerList servers;
    private final ServerPinger pinger = new ServerPinger();
    private final WorldLibrary worlds;
    private final IntegratedServer worldServer;

    public ClientServices(AccountManager accounts, ServerList servers, WorldLibrary worlds, IntegratedServer worldServer) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.worldServer = Objects.requireNonNull(worldServer, "worldServer");
    }

    /**
     * @param alkalineDirectory for example {@code ~/.alkaline/legacy}
     * @param minecraftDirectory the vanilla game directory ({@code .minecraft})
     */
    public static ClientServices open(Path alkalineDirectory, Path minecraftDirectory) {
        return new ClientServices(
                new AccountManager(alkalineDirectory.resolve("account.json"), OAuthClient.fromEnvironment()),
                new ServerList(alkalineDirectory.resolve("servers.json"), minecraftDirectory.resolve("servers.dat")),
                new WorldLibrary(minecraftDirectory.resolve("saves")),
                new IntegratedServer(alkalineDirectory.resolve("server")));
    }

    public AccountManager accounts() {
        return accounts;
    }

    public ServerList servers() {
        return servers;
    }

    public ServerPinger pinger() {
        return pinger;
    }

    public WorldLibrary worlds() {
        return worlds;
    }

    /** The local server that runs singleplayer worlds. */
    public IntegratedServer worldServer() {
        return worldServer;
    }

    @Override
    public void close() {
        accounts.close();
        servers.close();
        worldServer.close();
    }
}
