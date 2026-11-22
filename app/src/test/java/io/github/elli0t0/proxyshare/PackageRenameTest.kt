package io.github.elli0t0.proxyshare

import org.junit.Assert.assertEquals
import org.junit.Test

class PackageRenameTest {

    @Test
    fun applicationIdIsNewPackage() {
        assertEquals("io.github.elli0t0.proxyshare", BuildConfig.APPLICATION_ID)
    }
}
