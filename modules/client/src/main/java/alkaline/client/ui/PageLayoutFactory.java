package alkaline.client.ui;

import java.util.ArrayList;
import java.util.List;

final class PageLayoutFactory {
    private PageLayoutFactory() {
    }

    static PageLayout mainMenu(int width, int height, int selected) {
        PixelRect panel = centered(width, height, 460, 430);
        List<PageRegion> regions = List.of(
                region("main-panel", panel, PageRegion.RegionKind.PANEL),
                region("main-header", new PixelRect(panel.x() + 24, panel.y() + 24,
                        panel.width() - 48, 54), PageRegion.RegionKind.HEADER));
        List<PageControl> controls = verticalControls(panel, 112, 40, 10,
                new String[]{"single-player", "multiplayer", "language", "quit"},
                new boolean[]{true, true, true, true},
                new PageAction[]{PageAction.SINGLE_PLAYER, PageAction.MULTIPLAYER,
                        PageAction.LANGUAGE, PageAction.QUIT});
        return layout(PageId.MAIN_MENU, width, height, regions, controls, selected);
    }

    static PageLayout singlePlayer(int width, int height, int selected) {
        PixelRect panel = centered(width, height, 940, 520);
        PixelRect list = new PixelRect(panel.x() + 28, panel.y() + 86, panel.width() - 56, 300);
        List<PageRegion> regions = List.of(
                region("single-player-panel", panel, PageRegion.RegionKind.PANEL),
                region("single-player-header", new PixelRect(panel.x() + 28, panel.y() + 26,
                        panel.width() - 56, 42), PageRegion.RegionKind.HEADER),
                region("world-list", list, PageRegion.RegionKind.LIST),
                region("single-player-footer", new PixelRect(panel.x() + 28, panel.y() + 408,
                        panel.width() - 56, 72), PageRegion.RegionKind.FOOTER));
        List<PageControl> controls = rowControls(panel, 414, 170, 12,
                new String[]{"select-world", "create-world", "cancel"},
                new boolean[]{false, false, true},
                new PageAction[]{PageAction.NONE, PageAction.NONE, PageAction.BACK});
        return layout(PageId.SINGLE_PLAYER, width, height, regions, controls, selected);
    }

    static PageLayout multiplayer(int width, int height, int selected) {
        PixelRect panel = centered(width, height, 940, 520);
        PixelRect list = new PixelRect(panel.x() + 28, panel.y() + 86, panel.width() - 56, 300);
        List<PageRegion> regions = List.of(
                region("multiplayer-panel", panel, PageRegion.RegionKind.PANEL),
                region("multiplayer-header", new PixelRect(panel.x() + 28, panel.y() + 26,
                        panel.width() - 56, 42), PageRegion.RegionKind.HEADER),
                region("server-list", list, PageRegion.RegionKind.LIST),
                region("multiplayer-footer", new PixelRect(panel.x() + 28, panel.y() + 408,
                        panel.width() - 56, 72), PageRegion.RegionKind.FOOTER));
        List<PageControl> controls = rowControls(panel, 414, 112, 8,
                new String[]{"join", "direct-connect", "add", "edit", "delete", "refresh", "cancel"},
                new boolean[]{false, false, false, false, false, false, true},
                new PageAction[]{PageAction.NONE, PageAction.NONE, PageAction.NONE, PageAction.NONE,
                        PageAction.NONE, PageAction.NONE, PageAction.BACK});
        return layout(PageId.MULTIPLAYER, width, height, regions, controls, selected);
    }

    static PageLayout language(int width, int height, int selected) {
        PixelRect panel = centered(width, height, 760, 520);
        PixelRect list = new PixelRect(panel.x() + 28, panel.y() + 86, panel.width() - 56, 340);
        List<PageRegion> regions = List.of(
                region("language-panel", panel, PageRegion.RegionKind.PANEL),
                region("language-header", new PixelRect(panel.x() + 28, panel.y() + 26,
                        panel.width() - 56, 42), PageRegion.RegionKind.HEADER),
                region("language-list", list, PageRegion.RegionKind.LIST),
                region("language-footer", new PixelRect(panel.x() + 28, panel.y() + 448,
                        panel.width() - 56, 44), PageRegion.RegionKind.FOOTER));
        List<PageControl> controls = List.of(control("done", new PixelRect(panel.x() + panel.width() / 2 - 110,
                panel.y() + 448, 220, 44), true, PageAction.BACK));
        return layout(PageId.LANGUAGE, width, height, regions, controls, selected);
    }

    static PageLayout pauseMenu(int width, int height, int selected) {
        PixelRect panel = centered(width, height, 560, 430);
        List<PageRegion> regions = List.of(
                region("pause-panel", panel, PageRegion.RegionKind.PANEL),
                region("pause-header", new PixelRect(panel.x() + 24, panel.y() + 24,
                        panel.width() - 48, 54), PageRegion.RegionKind.HEADER));
        List<PageControl> controls = verticalControls(panel, 106, 40, 10,
                new String[]{"back-to-game", "achievements", "statistics", "open-to-lan", "disconnect"},
                new boolean[]{true, false, false, false, true},
                new PageAction[]{PageAction.BACK, PageAction.NONE, PageAction.NONE, PageAction.NONE,
                        PageAction.MAIN_MENU});
        return layout(PageId.PAUSE_MENU, width, height, regions, controls, selected);
    }

    private static PageLayout layout(PageId page, int width, int height, List<PageRegion> regions,
                                     List<PageControl> controls, int selected) {
        int safeSelected = selected >= 0 && selected < controls.size() ? selected : -1;
        return new PageLayout(page, width, height, regions, controls, safeSelected);
    }

    private static List<PageControl> verticalControls(PixelRect panel, int top, int controlHeight, int gap,
                                                        String[] ids, boolean[] enabled, PageAction[] actions) {
        int controlWidth = Math.min(panel.width() - 48, 360);
        int x = panel.x() + (panel.width() - controlWidth) / 2;
        List<PageControl> controls = new ArrayList<>(ids.length);
        for (int index = 0; index < ids.length; index++) {
            controls.add(control(ids[index], new PixelRect(x, panel.y() + top + index * (controlHeight + gap),
                    controlWidth, controlHeight), enabled[index], actions[index]));
        }
        return controls;
    }

    private static List<PageControl> rowControls(PixelRect panel, int top, int width, int gap,
                                                  String[] ids, boolean[] enabled, PageAction[] actions) {
        int total = ids.length * width + (ids.length - 1) * gap;
        int x = panel.x() + (panel.width() - total) / 2;
        List<PageControl> controls = new ArrayList<>(ids.length);
        for (int index = 0; index < ids.length; index++) {
            controls.add(control(ids[index], new PixelRect(x + index * (width + gap), panel.y() + top,
                    width, 44), enabled[index], actions[index]));
        }
        return controls;
    }

    private static PageControl control(String id, PixelRect bounds, boolean enabled, PageAction action) {
        return new PageControl(id, bounds, enabled, action);
    }

    private static PageRegion region(String id, PixelRect bounds, PageRegion.RegionKind kind) {
        return new PageRegion(id, bounds, kind);
    }

    private static PixelRect centered(int width, int height, int panelWidth, int panelHeight) {
        int actualWidth = Math.min(panelWidth, Math.max(160, width - 32));
        int actualHeight = Math.min(panelHeight, Math.max(140, height - 32));
        return new PixelRect((width - actualWidth) / 2, (height - actualHeight) / 2,
                actualWidth, actualHeight);
    }
}
