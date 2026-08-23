package it.persoft.lunaultra

import it.persoft.lunaultra.preview.correctedPreviewAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewAspectRatioTest {
    @Test
    fun `corregge il flusso Luna 1440x720 privo di SAR`() {
        assertEquals(16f / 9f, correctedPreviewAspectRatio(1440, 720, 1, 1), 0.001f)
    }

    @Test
    fun `conserva crop e SAR dichiarati dagli altri formati`() {
        assertEquals(4f / 3f, correctedPreviewAspectRatio(1440, 1080, 1, 1), 0.001f)
        assertEquals(16f / 9f, correctedPreviewAspectRatio(1440, 720, 8, 9), 0.001f)
    }
}
