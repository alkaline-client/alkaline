package alkaline.client.ui;

/** In-game pause-menu structure; Options and its child pages are intentionally excluded. */
public final class PauseMenuPage extends AbstractMenuPage {
    public PauseMenuPage() {
        super(PageId.PAUSE_MENU, PageAction.BACK);
    }

    @Override
    protected PageLayout createLayout(int width, int height, int selectedIndex) {
        return PageLayoutFactory.pauseMenu(width, height, selectedIndex);
    }
}
