package alkaline.client.ui;

/** Primary 1.8.9-style menu page. */
public final class MainMenuPage extends AbstractMenuPage {
    public MainMenuPage() {
        super(PageId.MAIN_MENU, PageAction.QUIT);
    }

    @Override
    protected PageLayout createLayout(int width, int height, int selectedIndex) {
        return PageLayoutFactory.mainMenu(width, height, selectedIndex);
    }
}
