package dev.sshdeck

import android.app.Application
import dev.sshdeck.core.ConfigStore
import dev.sshdeck.core.SshRunner
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.Security

/** App-wide singletons; runs live here so they survive leaving their screen. */
class SshDeckApp : Application() {
    lateinit var store: ConfigStore
        private set
    lateinit var deviceKey: DeviceKey
        private set
    lateinit var runner: SshRunner
        private set
    lateinit var runs: RunManager
        private set

    override fun onCreate() {
        super.onCreate()
        // Android ships a stripped-down "BC" provider that lacks algorithms sshj needs
        // (e.g. X25519 for curve25519 key exchange). Replace it with the full BouncyCastle.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        // Same layout as the desktop's scripts/ folder, in app-private storage.
        store = ConfigStore(File(filesDir, "scripts").apply { mkdirs() })
        deviceKey = DeviceKey(this)
        runner = SshRunner(deviceKey::keyPair, PrefsKnownHosts(this))
        runs = RunManager(this)
    }
}
