package alkaline.client.ui;

/** Server-selection structure with network actions reserved for a later game adapter. */
public final class MultiplayerPage extends AbstractMenuPage {
    public MultiplayerPage() {
        super(PageId.MULTIPLAYER, PageAction.BACK);
    }

    @Override
    protected PageLayout createLayout(int width, int height, int selectedIndex) {
        return PageLayoutFactory.multiplayer(width, height, selectedIndex);
    }
}
