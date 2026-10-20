package app.mini.hub.android.ui

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the generated third-party notices bundle (assets/licenses) that ships in the APK:
 * every component has a licence, every referenced text exists and matches its recorded hash,
 * and the bundle makes no licence claim for MINI's own code.
 */
class ThirdPartyNoticeIndexTest {
    private val assets: File = sequenceOf(
        File("src/main/assets/licenses"),
        File("androidApp/src/main/assets/licenses"),
    ).first { it.isDirectory }

    private val raw = File(assets, "index.json").readText(Charsets.UTF_8).removePrefix("\uFEFF")
    private val index = ThirdPartyNoticeIndex.parse(raw)

    @Test
    fun everyComponentHasLicenceAndText() {
        assertTrue(index.components.size >= 100, "expected full APK inventory, got ${index.components.size}")
        assertTrue(index.unknownLicense.isEmpty(), "unknown licences: ${index.unknownLicense}")
        assertTrue(index.missingText.isEmpty(), "missing texts: ${index.missingText}")
        index.components.forEach { c ->
            assertTrue(c.license.isNotBlank() && c.license != "-", "no licence for ${c.name}")
            assertTrue(c.textPaths.isNotEmpty(), "no text for ${c.name}")
        }
    }

    @Test
    fun referencedTextsExistAndMatchRecordedHashes() {
        val root = org.json.JSONObject(raw)
        val comps = root.getJSONArray("components")
        for (i in 0 until comps.length()) {
            val files = comps.getJSONObject(i).getJSONArray("files")
            for (j in 0 until files.length()) {
                val f = files.getJSONObject(j)
                val file = File(assets, f.getString("path"))
                assertTrue(file.isFile, "missing ${file.path}")
                assertEquals(f.getString("sha256"), sha256(file), "hash mismatch for ${file.path}")
            }
        }
    }

    @Test
    fun copyleftRuntimeComponentsAreListedWithExactTexts() {
        val byName = index.components.associateBy { it.name }
        listOf("go.mau.fi/mautrix-meta", "go.mau.fi/mautrix-linkedin").forEach { n ->
            val c = byName.getValue(n)
            assertEquals("AGPL-3.0", c.license)
            assertTrue(c.notes!!.contains("does not grant MINI"), "exception caveat missing for $n")
            assertTrue(c.textPaths.size >= 2, "$n should ship LICENSE and LICENSE.exceptions")
        }
        assertEquals("GPL-3.0", byName.getValue("go.mau.fi/libsignal").license)
        assertEquals("MPL-2.0", byName.getValue("go.mau.fi/whatsmeow").license)
        assertTrue(byName.keys.any { it.startsWith("Go standard library") })
    }

    @Test
    fun bundleMakesNoClaimAboutMinisOwnLicence() {
        assertTrue(index.disclaimer.contains("does not state or imply a licence for MINI"))
        assertFalse(index.components.any { it.name.contains("app.mini.hub") || it.name == "project :shared" })
    }

    @Test
    fun groupingCoversAllComponents() {
        assertEquals(index.components.size, index.grouped().sumOf { it.second.size })
    }

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
}
