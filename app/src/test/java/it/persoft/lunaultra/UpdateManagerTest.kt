package it.persoft.lunaultra

import it.persoft.lunaultra.update.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun `la release seleziona l apk e conserva commit e digest`() {
        val digest = "a".repeat(64)
        val release = UpdateManager.parseRelease(
            """
            {
              "target_commitish": "0123456789abcdef",
              "assets": [
                {"name":"note.txt","browser_download_url":"https://example.test/note"},
                {
                  "name":"app-debug.apk",
                  "browser_download_url":"https://github.com/example/app-debug.apk",
                  "digest":"sha256:$digest"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("0123456789abcdef", release.commitSha)
        assertEquals("https://github.com/example/app-debug.apk", release.downloadUrl)
        assertEquals(digest, release.sha256)
    }

    @Test
    fun `il confronto commit accetta anche sha abbreviati ma non la build locale`() {
        assertTrue(UpdateManager.sameCommit("0123456789abcdef", "0123456789abcdef"))
        assertTrue(UpdateManager.sameCommit("0123456789abcdef", "0123456"))
        assertFalse(UpdateManager.sameCommit("fedcba", "0123456"))
        assertFalse(UpdateManager.sameCommit("0123456789abcdef", "local"))
    }
}
