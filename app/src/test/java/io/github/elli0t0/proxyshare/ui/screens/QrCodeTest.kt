package io.github.elli0t0.proxyshare.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {

    @Test
    fun qrPixelsIs512SquareAndNotAllWhite() {
        val px = qrPixels("http://192.168.1.2:8899/", 512)
        assertEquals(512 * 512, px.size)
        assertTrue("a QR code has black modules", px.any { it == 0xFF000000.toInt() })
        assertTrue("a QR code has white modules", px.any { it == 0xFFFFFFFF.toInt() })
    }

    @Test
    fun qrPixelsIsDeterministic() {
        val text = "http://192.168.1.2:8899/"
        assertTrue(qrPixels(text).contentEquals(qrPixels(text)))
    }

    @Test
    fun qrPixelsDiffersPerText() {
        assertTrue(!qrPixels("http://192.168.1.2:8899/").contentEquals(qrPixels("http://10.0.0.2:8899/")))
    }
}
