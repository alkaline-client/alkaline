package alkaline.client.ui;

/** Language-selection structure without copying vanilla language data or settings pages. */
public final class LanguagePage extends AbstractMenuPage {
    public LanguagePage() {
        super(PageId.LANGUAGE, PageAction.BACK);
    }

    @Override
    protected PageLayout createLayout(int width, int height, int selectedIndex) {
        return PageLayoutFactory.language(width, height, selectedIndex);
    }
}
