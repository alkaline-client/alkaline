package alkaline.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPageRouterTest {
    @Test
    void followsMainMenuRoutesAndBackHistory() {
        ClientPageRouter router = new ClientPageRouter(1_152, 720);

        assertEquals(PageId.MAIN_MENU, router.currentPage());
        router.dispatch(new PageInput.KeyPressed(UiKey.DOWN, false));
        router.dispatch(new PageInput.KeyPressed(UiKey.ENTER, false));

        assertEquals(PageId.MULTIPLAYER, router.currentPage());
        router.dispatch(new PageInput.KeyPressed(UiKey.ESCAPE, false));
        assertEquals(PageId.MAIN_MENU, router.currentPage());
    }

    @Test
    void disabledProviderActionsDoNotTransition() {
        ClientPageRouter router = new ClientPageRouter(1_152, 720);
        router.dispatch(new PageInput.KeyPressed(UiKey.ENTER, false));
        assertEquals(PageId.SINGLE_PLAYER, router.currentPage());

        PageControl select = router.layout().controls().get(0);
        router.dispatch(new PageInput.MouseClicked(select.bounds().x(), select.bounds().y(), MouseButton.PRIMARY));
        assertEquals(PageId.SINGLE_PLAYER, router.currentPage());
    }

    @Test
    void mouseHitTestingAndResizeUpdateTheLayout() {
        ClientPageRouter router = new ClientPageRouter(1_152, 720);
        PageControl language = router.layout().controls().get(2);
        router.dispatch(new PageInput.MouseMoved(language.bounds().x() + 2, language.bounds().y() + 2));
        assertEquals(2, router.layout().selectedIndex());

        router.dispatch(new PageInput.MouseClicked(language.bounds().x() + 2, language.bounds().y() + 2,
                MouseButton.PRIMARY));
        assertEquals(PageId.LANGUAGE, router.currentPage());
        router.dispatch(new PageInput.Resized(800, 600));
        assertEquals(800, router.layout().width());
        assertEquals(600, router.layout().height());
        router.dispatch(new PageInput.KeyPressed(UiKey.ESCAPE, false));
        assertEquals(PageId.MAIN_MENU, router.currentPage());
        assertEquals(800, router.layout().width());
        assertEquals(600, router.layout().height());
    }

    @Test
    void escapeOnMainMenuRequestsQuit() {
        ClientPageRouter router = new ClientPageRouter(800, 600);
        assertFalse(router.quitRequested());
        router.dispatch(new PageInput.KeyPressed(UiKey.ESCAPE, false));
        assertTrue(router.quitRequested());
    }
}
