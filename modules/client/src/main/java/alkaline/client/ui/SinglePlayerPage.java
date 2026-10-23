package alkaline.client.ui;

/** World-selection structure with provider-backed actions reserved for a later game adapter. */
public final class SinglePlayerPage extends AbstractMenuPage {
    public SinglePlayerPage() {
        super(PageId.SINGLE_PLAYER, PageAction.BACK);
    }

    @Override
    protected PageLayout createLayout(int width, int height, int selectedIndex) {
        return PageLayoutFactory.singlePlayer(width, height, selectedIndex);
    }
}
