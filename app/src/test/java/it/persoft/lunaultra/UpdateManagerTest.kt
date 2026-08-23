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

    /**
     * Il workflow pubblica su `apk-<branch>` con le barre sostituite da trattini. Se i due
     * calcoli divergono l'app non trova nulla e dice "app aggiornata" in buona fede: è
     * esattamente il modo in cui un branch nuovo spariva dal radar.
     */
    @Test
    fun `il tag della release segue il branch della build`() {
        assertEquals("apk-main", UpdateManager.releaseTag("main"))
        assertEquals("apk-a-b-c", UpdateManager.releaseTag("refs/heads/a/b/c"))
        assertEquals("apk-prova-x", UpdateManager.releaseTag("prova/x"))
    }

    @Test
    fun `senza branch si ricade sull ultimo ramo pubblicato`() {
        val fallback = "apk-" + UpdateManager.FALLBACK_BRANCH.replace('/', '-')
        assertEquals(fallback, UpdateManager.releaseTag(""))
        assertEquals(fallback, UpdateManager.releaseTag("   "))
        assertEquals(fallback, UpdateManager.releaseTag("local"))
    }

    @Test
    fun `l indirizzo della release e quello dei tag di questo repository`() {
        assertEquals(
            "https://api.github.com/repos/apatassini/Insta360luna/releases/tags/apk-main",
            UpdateManager.releaseApi("main"),
        )
    }

    @Test
    fun `il confronto commit accetta anche sha abbreviati ma non la build locale`() {
        assertTrue(UpdateManager.sameCommit("0123456789abcdef", "0123456789abcdef"))
        assertTrue(UpdateManager.sameCommit("0123456789abcdef", "0123456"))
        assertFalse(UpdateManager.sameCommit("fedcba", "0123456"))
        assertFalse(UpdateManager.sameCommit("0123456789abcdef", "local"))
    }
}
