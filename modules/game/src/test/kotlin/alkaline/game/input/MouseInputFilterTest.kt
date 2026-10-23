package alkaline.game.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MouseInputFilterTest {
    @Test
    fun smoothingSequencesPreservePositiveAndNegativeSigns() {
        val positiveFilter = MouseInputFilter()
        val positiveResults = listOf(
            positiveFilter.smooth(8.0f, 0.5f),
            positiveFilter.smooth(0.0f, 0.5f),
            positiveFilter.smooth(0.0f, 0.5f),
            positiveFilter.smooth(0.0f, 0.5f)
        )

        val negativeFilter = MouseInputFilter()
        val negativeResults = listOf(
            negativeFilter.smooth(-8.0f, 0.5f),
            negativeFilter.smooth(0.0f, 0.5f),
            negativeFilter.smooth(0.0f, 0.5f),
            negativeFilter.smooth(0.0f, 0.5f)
        )

        assertEquals(listOf(2.0f, 2.5f, 1.75f, 0.875f), positiveResults)
        assertEquals(listOf(-2.0f, -2.5f, -1.75f, -0.875f), negativeResults)
    }

    @Test
    fun resetRestoresTheInitialSmoothingSequence() {
        val filter = MouseInputFilter()
        filter.smooth(8.0f, 0.5f)
        filter.smooth(0.0f, 0.5f)

        filter.reset()

        assertEquals(2.0f, filter.smooth(8.0f, 0.5f))
        assertEquals(2.5f, filter.smooth(0.0f, 0.5f))
    }

    @Test
    fun zeroDeltaAndSmoothingFactorsPreserveAccumulatedInput() {
        val filter = MouseInputFilter()

        assertEquals(0.0f, filter.smooth(0.0f, 0.5f))
        assertEquals(0.0f, filter.smooth(4.0f, 0.0f))
        assertEquals(1.0f, filter.smooth(0.0f, 0.5f))
    }

    @Test
    fun inputDeltaSignChangesAreSmoothedAcrossZero() {
        val filter = MouseInputFilter()

        assertEquals(2.0f, filter.smooth(8.0f, 0.5f))
        assertEquals(-1.5f, filter.smooth(-16.0f, 0.5f))
        assertEquals(-2.875f, filter.smooth(0.0f, 0.5f))
    }

    @Test
    fun negativeSmoothingFactorRetainsItsSignAcrossRepeatedSamples() {
        val filter = MouseInputFilter()

        assertEquals(-1.0f, filter.smooth(4.0f, -0.5f))
        assertEquals(-1.75f, filter.smooth(0.0f, -0.5f))
    }
}
