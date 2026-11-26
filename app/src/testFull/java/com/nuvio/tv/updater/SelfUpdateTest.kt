package com.nuvio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelfUpdateTest {
    private val endpoint = "https://n.bludev.com.br/update.json"

    @Test fun `newer build on the same host is offered`() {
        val u = SelfUpdate.parse("""{"versionCode":200000100,"versionName":"1.2.b100","url":"nuvio.apk","size":5}""", endpoint, 200000000)!!
        assertEquals("https://n.bludev.com.br/nuvio.apk", u.assetUrl)
        assertEquals(200000100L, u.versionCode)
        assertEquals(5L, u.assetSizeBytes)
    }

    @Test fun `same or older build is not`() {
        assertNull(SelfUpdate.parse("""{"versionCode":200000000,"url":"nuvio.apk"}""", endpoint, 200000000))
    }

    @Test fun `another host, plain http or junk is not`() {
        assertNull(SelfUpdate.parse("""{"versionCode":9999999999,"url":"https://evil.example/x.apk"}""", endpoint, 1))
        assertNull(SelfUpdate.parse("""{"versionCode":9999999999,"url":"http://n.bludev.com.br/x.apk"}""", endpoint, 1))
        assertNull(SelfUpdate.parse("<html>530</html>", endpoint, 1))
    }
}
