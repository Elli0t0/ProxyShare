package io.github.elli0t0.proxyshare

import android.app.Application
import io.github.elli0t0.proxyshare.data.NetworkWatcher
import io.github.elli0t0.proxyshare.data.VpnWatcher
import io.github.elli0t0.proxyshare.service.ProxyController

class ProxyShareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProxyController.init(this)
        NetworkWatcher.init(this)
        VpnWatcher.init(this)
    }
}
