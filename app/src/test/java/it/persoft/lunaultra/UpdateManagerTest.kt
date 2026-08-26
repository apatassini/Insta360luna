package it.persoft.lunaultra

import it.persoft.lunaultra.update.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /** La data di pubblicazione è quella che l'app mostra al posto del commit. */
    @Test
    fun `la data di pubblicazione si legge e sopravvive quando manca`() {
        val withDate = UpdateManager.parseRelease(
            """
            {
              "target_commitish": "abc",
              "published_at": "2026-08-24T19:55:06Z",
              "assets": [{"name":"a.apk","browser_download_url":"https://x.test/a.apk"}]
            }
            """.trimIndent()
        )
        assertEquals(1787601306000L, withDate.publishedAtMs)

        val withoutDate = UpdateManager.parseRelease(
            """
            {
              "target_commitish": "abc",
              "assets": [{"name":"a.apk","browser_download_url":"https://x.test/a.apk"}]
            }
            """.trimIndent()
        )
        assertEquals(null, withoutDate.publishedAtMs)
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

    /**
     * Il secondo tentativo dello scaricamento serve a saltare una copia in cache: se
     * l'indirizzo restasse identico non salterebbe niente, e l'aggiornamento resterebbe
     * bloccato sull'impronta che non torna finché la cache non scade da sola.
     */
    @Test
    fun `il secondo tentativo chiede un indirizzo mai visto`() {
        val plain = "https://github.com/a/b/releases/download/apk-main/luna.apk"
        val busted = UpdateManager.cacheBusted(plain)
        assertTrue(busted.startsWith("$plain?fresh="))
        assertNotEquals(plain, busted)

        val withQuery = UpdateManager.cacheBusted("$plain?x=1")
        assertTrue(withQuery.startsWith("$plain?x=1&fresh="))
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
