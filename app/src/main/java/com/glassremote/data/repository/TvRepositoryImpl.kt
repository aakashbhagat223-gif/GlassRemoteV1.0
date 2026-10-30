package com.glassremote.data.repository

import com.glassremote.data.adb.AdbManager
import com.glassremote.data.adb.NativeActions
import com.glassremote.data.network.PairingClient
import com.glassremote.data.network.PairingSession
import com.glassremote.data.network.RemoteSession
import com.glassremote.data.network.TvAddressResolver
import com.glassremote.data.network.TvDiscovery
import com.glassremote.data.security.CertificateManager
import com.glassremote.domain.model.KeyDirection
import com.glassremote.domain.model.LinkPhase
import com.glassremote.domain.model.RemoteStatus
import com.glassremote.domain.model.TvDevice
import com.glassremote.domain.repository.TvRepository
import com.glassremote.proto.remote.RemoteDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvRepositoryImpl @Inject constructor(
    private val discovery: TvDiscovery,
    private val certs: CertificateManager,
    private val store: PairedDeviceStore,
    private val resolver: TvAddressResolver,
    private val adb: AdbManager,
    private val actions: NativeActions,
) : TvRepository {

    private val _status = MutableStateFlow(RemoteStatus())
    override val status: StateFlow<RemoteStatus> = _status.asStateFlow()

    @Volatile private var pairing: PairingSession? = null
    @Volatile private var remote: RemoteSession? = null

    override fun discover(): Flow<List<TvDevice>> = discovery.discover()

    override suspend fun beginPairing(device: TvDevice) {
        pairing?.close()
        pairing = PairingClient(certs).begin(device.host)
    }

    override suspend fun submitPairingCode(device: TvDevice, code: String) {
        val session = pairing ?: throw IllegalStateException("Pairing not started")
        session.submitCode(code)
        session.close()
        pairing = null
        store.save(device)
    }

    /** Connects; if the saved IP doesn't answer, the TV is looked up again by its name. */
    override suspend fun connect(device: TvDevice) = withContext(Dispatchers.IO) {
        disconnect()
        _status.value = RemoteStatus(LinkPhase.Connecting)

        var target = device
        var refreshed = false
        if (!resolver.reachable(target.host, target.port)) {
            target = resolver.refresh(device)
            refreshed = true
        }
        var failure: Throwable? = null
        for (attempt in 0..1) {
            try {
                openSession(target)
                return@withContext
            } catch (t: Throwable) {
                failure = t
                if (!refreshed) {
                    target = resolver.refresh(device)
                    refreshed = true
                } else {
                    break
                }
            }
        }
        _status.value = RemoteStatus(LinkPhase.Disconnected, error = failure?.message ?: "Connection failed")
        throw failure ?: IllegalStateException("Connection failed")
    }

    private fun openSession(device: TvDevice) {
        lateinit var session: RemoteSession
        session = RemoteSession(certs, device.host) { newStatus ->
            if (remote === session) _status.value = newStatus
        }
        remote = session
        try {
            session.connect()
        } catch (t: Throwable) {
            remote = null
            session.close()
            throw t
        }
    }

    /**
     * Key routing: ADB first if "keys over ADB" is on; otherwise the Wi-Fi remote link (fast);
     * and if that link is down or a send fails, ADB `input keyevent` takes over.
     */
    override suspend fun sendKey(keyCode: Int, direction: KeyDirection) {
        val session = remote
        val linkOk = session != null && session.isRunning.get() && _status.value.phase == LinkPhase.Ready
        val dir = when (direction) {
            KeyDirection.Short -> RemoteDirection.DIRECTION_SHORT
            KeyDirection.StartLong -> RemoteDirection.DIRECTION_START_LONG
            KeyDirection.EndLong -> RemoteDirection.DIRECTION_END_LONG
        }
        withContext(Dispatchers.IO) {
            if (adb.state.value.connected && actions.keysViaAdb()) {
                if (runCatching { actions.keyEvent(keyCode) }.isSuccess) return@withContext
            }
            if (linkOk && session != null) {
                try {
                    session.sendKey(keyCode, dir)
                    return@withContext
                } catch (_: IOException) {
                    session.close()
                }
            }
            if (adb.state.value.connected) runCatching { actions.keyEvent(keyCode) }
        }
    }

    override suspend fun launchApp(deepLink: String) {
        val session = remote ?: return
        val link = if (deepLink.contains("://") || deepLink.startsWith("intent:")) deepLink
        else "market://launch?id=$deepLink"
        withContext(Dispatchers.IO) {
            try {
                session.launchApp(link)
            } catch (_: IOException) {
                session.close()
            }
        }
    }

    override fun disconnect() {
        val old = remote
        remote = null
        old?.close()
    }

    override suspend fun resetPairing() {
        disconnect()
        pairing?.close()
        pairing = null
        certs.resetIdentity()
        store.clear()
        _status.value = RemoteStatus()
    }

    fun close() = disconnect()
}
