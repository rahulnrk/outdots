package app.mini.hub.android.connector

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises the packaged coordinated gomobile runtime (one libgojni.so) inside the app
 * process. WhatsApp loads through the production [GomobileWhatsAppClient.load] path.
 * Instagram is reached by reflection because no production Kotlin binds it yet.
 * No network, no pairing, no credentials.
 */
@RunWith(AndroidJUnit4::class)
class CoordinatedRuntimeDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun whatsappRuntimeStillLoadsThroughProductionPath() {
        val dir = File(context.noBackupFilesDir, "rtprobe-wa").apply { mkdirs() }
        try {
            val client = GomobileWhatsAppClient.load(dir.absolutePath)
            assertNotNull("GomobileWhatsAppClient.load returned null: runtime not packaged or broken", client)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun instagramProtocolRuntimeIsLinkedAndLoginAvailable() {
        val facade = Class.forName("app.mini.hub.wa.igruntime.Igruntime")
        val runtime = facade.getMethod("newRuntime").invoke(null)!!
        fun call(name: String): Any? = runtime.javaClass.getMethod(name).invoke(runtime)

        assertEquals("instagram", call("platform"))
        // A fresh runtime has no session, so it reports unavailable, but the real
        // owner-operated login flow is now available.
        assertEquals("unavailable", call("state"))
        assertTrue(call("loginAvailable") as Boolean)
        assertFalse(call("isAuthenticated") as Boolean)
        assertTrue(facade.getMethod("globalLoggingDisabled").invoke(null) as Boolean)
        assertEquals(
            "3c16226686c71af64df294df0803791685064e0b",
            facade.getField("PinnedMautrixMeta").get(null),
        )
        // The real login controller is constructible without network I/O.
        val controller = facade.methods
            .first { it.name == "newLoginController" && it.parameterTypes.size == 1 }
            .invoke(null, "instagram|pending")
        assertNotNull(controller)
    }

    @Test
    fun bothBindingsShareOneNativeLibrary() {
        val libDir = File(context.applicationInfo.nativeLibraryDir)
        val goLibs = libDir.listFiles().orEmpty().filter { it.name.startsWith("libgojni") }
        val apkLibs = runCatching {
            java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { zip ->
                zip.entries().toList().map { it.name }.filter { it.endsWith("/libgojni.so") }
            }
        }.getOrDefault(emptyList())
        assertEquals("exactly one gomobile library expected", 1, maxOf(goLibs.size, apkLibs.size))
        // Load order independence: WhatsApp after Instagram in the same process.
        Class.forName("app.mini.hub.wa.igruntime.Igruntime").getMethod("newRuntime").invoke(null)
        val dir = File(context.noBackupFilesDir, "rtprobe-wa2").apply { mkdirs() }
        try {
            assertNotNull(GomobileWhatsAppClient.load(dir.absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }
}
