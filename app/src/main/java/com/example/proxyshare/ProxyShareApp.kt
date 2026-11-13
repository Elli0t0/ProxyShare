package com.example.proxyshare

import android.app.Application
import com.example.proxyshare.service.ProxyController

class ProxyShareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProxyController.init(this)
    }
}
