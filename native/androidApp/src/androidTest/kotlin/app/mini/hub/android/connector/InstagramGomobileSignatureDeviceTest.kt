package app.mini.hub.android.connector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class InstagramGomobileSignatureDeviceTest {
    @Test
    fun goIntFacadeUsesJavaLongParameters() {
        val method = FakeInstagramFacade::class.java.getMethod(
            "readRecentInboxJSON",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        assertEquals(ByteArray::class.java, method.returnType)
        assertArrayEquals(byteArrayOf(1, 2, 3), method.invoke(FakeInstagramFacade(), 20L, 20L) as ByteArray)
    }

    private class FakeInstagramFacade {
        @Suppress("UNUSED_PARAMETER")
        fun readRecentInboxJSON(maxThreads: Long, maxMessages: Long): ByteArray = byteArrayOf(1, 2, 3)
    }
}
