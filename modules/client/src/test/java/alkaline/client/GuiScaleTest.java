package alkaline.client;

import alkaline.client.settings.GuiScale;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuiScaleTest {
    @Test
    void autoPicksLargestStepThatFits() {
        assertEquals(1.0f, GuiScale.scale(GuiScale.AUTO, 1280, 760));
        assertEquals(1.5f, GuiScale.scale(GuiScale.AUTO, 1920, 1080));
        assertEquals(3.0f, GuiScale.scale(GuiScale.AUTO, 3840, 2160));
    }

    @Test
    void manualStepsAreCappedByTheWindow() {
        assertEquals(1.25f, GuiScale.requested(3));
        assertEquals(1.25f, GuiScale.scale(3, 1920, 1080));
        assertEquals(1.5f, GuiScale.scale(10, 1920, 1080), "3.0 asked, 1.5 fits");
    }

    @Test
    void tinyWindowsKeepTheSmallestStep() {
        assertEquals(0.75f, GuiScale.scale(GuiScale.AUTO, 640, 400));
        assertEquals(0.75f, GuiScale.scale(1, 640, 400));
    }
}
