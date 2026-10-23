package alkaline.client.ui;

/** Interface boundary for a page implementation independent of SDL, Vulkan, and page content. */
public interface ClientPage {
    PageId id();

    void enter();

    void exit();

    void resize(int width, int height);

    PageLayout layout();

    PageAction handle(PageInput input);
}
