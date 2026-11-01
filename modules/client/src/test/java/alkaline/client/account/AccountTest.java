package alkaline.client.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountTest {
    @Test
    void parsesRedirectQueries() {
        assertEquals(Map.of("code", "M.C1 2", "state", "ab"), MicrosoftAuth.parseQuery("code=M.C1%202&state=ab"));
        assertTrue(MicrosoftAuth.parseQuery(null).isEmpty());
    }

    @Test
    void explainsXstsRefusals() {
        assertTrue(MicrosoftAuth.xstsError("{\"XErr\":2148916233}").contains("no Xbox profile"));
        assertTrue(MicrosoftAuth.xstsError("{\"XErr\":2148916238}").contains("child account"));
    }

    @Test
    void faceCompositesTheHatOverTheHead() {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        skin.setRGB(8, 8, 0xFF112233);
        skin.setRGB(9, 8, 0xFF112233);
        skin.setRGB(41, 8, 0xFFAABBCC); // opaque hat pixel over (1, 0)
        int[] face = AccountManager.face(skin);
        assertEquals(0xFF112233, face[0]);
        assertEquals(0xFFAABBCC, face[1]);
    }

    @Test
    void secretsStayOutOfToStrings() {
        MinecraftSession session = new MinecraftSession(new MinecraftProfile("id", "Steve", null), "secret-token", 0L);
        assertTrue(!session.toString().contains("secret") && !new MicrosoftAuth.Result(session, "refresh-secret")
                .toString().contains("secret"));
    }

    @Test
    void takesTheLaunchersAccountWithoutSavingIt(@TempDir Path directory) {
        AccountManager accounts = new AccountManager(directory.resolve("account.json"), OAuthClient.fromEnvironment());
        assertFalse(accounts.useLauncherAccount(Map.of()));
        assertTrue(accounts.useLauncherAccount(Map.of(
                AccountManager.NAME_VARIABLE, "Steve", AccountManager.UUID_VARIABLE, "0123-4567")));
        assertEquals("Steve", accounts.session().profile().name());
        assertEquals("01234567", accounts.session().profile().uuid());
        assertTrue(accounts.session().accessToken().isEmpty());
        assertTrue(accounts.state() instanceof AccountManager.State.SignedIn);
        assertFalse(Files.exists(directory.resolve("account.json")));
    }
}
