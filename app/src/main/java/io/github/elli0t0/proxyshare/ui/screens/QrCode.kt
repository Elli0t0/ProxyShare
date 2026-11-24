package io.github.elli0t0.proxyshare.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * The QR matrix for [text] as black/white pixels, row-major. Pure
 * ZXing, so it runs on the JVM (unit-tested); the Bitmap wrapper
 * below is Android-only.
 */
fun qrPixels(text: String, size: Int = 512): IntArray {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    return IntArray(size * size) { i ->
        if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
}

/** The QR code for [text] as a bitmap. */
fun qrBitmap(text: String, size: Int = 512): ImageBitmap =
    Bitmap.createBitmap(qrPixels(text, size), size, size, Bitmap.Config.ARGB_8888).asImageBitmap()

/**
 * A QR code on a white background (so it still scans on a dark
 * theme), 200 dp wide.
 */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val bmp = remember(text) { qrBitmap(text) }
    Image(bmp, contentDescription = null, modifier = modifier.size(200.dp).background(Color.White).padding(8.dp))
}
