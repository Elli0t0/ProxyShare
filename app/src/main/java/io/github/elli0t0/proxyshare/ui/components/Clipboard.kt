package io.github.elli0t0.proxyshare.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import io.github.elli0t0.proxyshare.R

/**
 * Copy text to the clipboard (0.7.2: one shared helper — Home,
 * Connect, Connections and SessionLog each had their own copy).
 * Android 13+ shows the system copy confirmation itself; below 13
 * nothing does, so show a short toast.
 */
fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("ProxyShare", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
