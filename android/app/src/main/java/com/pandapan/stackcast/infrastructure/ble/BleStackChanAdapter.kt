package com.pandapan.stackcast.infrastructure.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.pandapan.stackcast.domain.model.LipSyncMotion
import com.pandapan.stackcast.domain.model.LipSyncMotionPacketCodec
import com.pandapan.stackcast.domain.model.StackChanConnectionState
import com.pandapan.stackcast.domain.ports.StackChanPort
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Android BluetoothGattを用いたｽﾀｯｸﾁｬﾝ通信アダプター。
 *
 * スキャン結果の表示、GATT接続、MTU交渉、書き込みCharacteristicの探索を担当する。
 * パケット送信はWRITE_WITHOUT_RESPONSEを優先し、BLEスタックへの同時書き込みをMutexで直列化する。
 *
 * @property context アプリケーションコンテキスト。
 * @property serviceUuid 使用するGATTサービスUUID。未指定時は最初の書き込み可能サービスを探索する。
 * @property characteristicUuid 使用するCharacteristic UUID。未指定時は書き込み可能なものを探索する。
 * @property autoReconnect 意図しない切断時に最大3回再接続を試みるかどうか。
 */
class BleStackChanAdapter(
    context: Context,
    private val serviceUuid: UUID? = null,
    private val characteristicUuid: UUID? = null,
    private val autoReconnect: Boolean = true
) : StackChanPort {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter
    private val _connectionState =
        MutableStateFlow<StackChanConnectionState>(StackChanConnectionState.Disconnected)
    private val writeMutex = Mutex()
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    private var negotiatedMtu = LipSyncMotionPacketCodec.DEFAULT_MTU
    private var connectSignal: CompletableDeferred<Result<Unit>>? = null
    private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var lastAddress: String? = null
    private var intentionalDisconnect = true

    private val _scanResults = MutableStateFlow<List<BleScanResult>>(emptyList())

    /** スキャンで見つかったBLEデバイス一覧。 */
    val scanResults: StateFlow<List<BleScanResult>> = _scanResults.asStateFlow()

    /** 現在のGATT接続状態。 */
    override val connectionState: StateFlow<StackChanConnectionState> = _connectionState.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val item = BleScanResult(device.address, device.name.orEmpty())
            _scanResults.value = (_scanResults.value + item).distinctBy { it.address }
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = StackChanConnectionState.Error("BLEスキャン失敗: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                completeConnection(Result.failure(IllegalStateException("GATT接続失敗: $status")))
                return
            }
            when (newState) {
                android.bluetooth.BluetoothProfile.STATE_CONNECTED -> {
                    if (hasConnectPermission()) {
                        gatt.requestMtu(247)
                        gatt.discoverServices()
                    } else {
                        completeConnection(Result.failure(SecurityException("Bluetooth接続権限がありません。")))
                    }
                }

                android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> {
                    writeCharacteristic = null
                    _connectionState.value = StackChanConnectionState.Disconnected
                    completeConnection(Result.failure(IllegalStateException("ｽﾀｯｸﾁｬﾝとの接続が切断されました。")))
                    if (!intentionalDisconnect && autoReconnect) {
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else LipSyncMotionPacketCodec.DEFAULT_MTU
            if (hasConnectPermission()) gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                completeConnection(Result.failure(IllegalStateException("GATTサービス探索失敗: $status")))
                return
            }
            writeCharacteristic = findWritableCharacteristic(gatt.services)
            if (writeCharacteristic == null) {
                completeConnection(Result.failure(IllegalStateException("書き込み可能なCharacteristicが見つかりません。")))
                return
            }
            writeType = if (
                writeCharacteristic?.properties?.and(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            ) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            _connectionState.value = StackChanConnectionState.Connected(negotiatedMtu)
            completeConnection(Result.success(Unit))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val continuation = writeCompletion
            writeCompletion = null
            if (continuation != null) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWith(
                        Result.failure(IllegalStateException("BLEパケット書き込み失敗: $status"))
                    )
                }
            }
        }
    }

    /** BLEスキャン結果の表示用データ。 */
    data class BleScanResult(val address: String, val name: String)

    /** BLEスキャンを開始する。 */
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasScanPermission()) {
            _connectionState.value = StackChanConnectionState.Error("BLEスキャン権限がありません。")
            return
        }
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = StackChanConnectionState.Error("Bluetoothが利用できません。")
            return
        }
        _scanResults.value = emptyList()
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback
        )
    }

    /** BLEスキャンを停止する。 */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (hasScanPermission()) bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    /** 現在の接続を切断し、GATTリソースを解放する。 */
    @SuppressLint("MissingPermission")
    override suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        intentionalDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        disconnectGatt()
        Result.success(Unit)
    }

    /** GATT接続を切断する内部処理。 */
    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        stopScan()
        if (hasConnectPermission()) bluetoothGatt?.disconnect()
        if (hasConnectPermission()) bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
        _connectionState.value = StackChanConnectionState.Disconnected
    }

    /** 指定アドレスへ接続し、サービス探索とMTU交渉を完了する。 */
    @SuppressLint("MissingPermission")
    override suspend fun connect(deviceAddress: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!hasConnectPermission()) {
            return@withContext Result.failure(SecurityException("Bluetooth接続権限がありません。"))
        }
        val device = runCatching { bluetoothAdapter?.getRemoteDevice(deviceAddress) }.getOrNull()
            ?: return@withContext Result.failure(IllegalArgumentException("BLEアドレスが不正です。"))
        disconnectGatt()
        intentionalDisconnect = false
        lastAddress = deviceAddress
        _connectionState.value = StackChanConnectionState.Connecting
        val signal = CompletableDeferred<Result<Unit>>()
        connectSignal = signal
        bluetoothGatt = device.connectGatt(appContext, false, gattCallback)
        return@withContext try {
            withTimeout(CONNECTION_TIMEOUT_MS) { signal.await() }
        } catch (throwable: Throwable) {
            _connectionState.value = StackChanConnectionState.Error("BLE接続タイムアウト: ${throwable.message}")
            disconnectGatt()
            Result.failure(throwable)
        } finally {
            connectSignal = null
        }
    }

    /** モーション時系列をMTUに合わせて分割し、GATTへ順番に送信する。 */
    override suspend fun sendLipSyncMotion(motion: List<LipSyncMotion>): Result<Unit> {
        val packets = runCatching {
            LipSyncMotionPacketCodec.encode(motion, negotiatedMtu)
        }.getOrElse { return Result.failure(it) }
        if (!isConnected() || writeCharacteristic == null) {
            return Result.failure(IllegalStateException("ｽﾀｯｸﾁｬﾝが接続されていません。"))
        }
        return runCatching {
            writeMutex.withLock {
                packets.forEach { packet ->
                    var sent = false
                    var lastError: Throwable? = null
                    repeat(MAX_WRITE_RETRIES) {
                        if (!sent) {
                            runCatching { writePacket(packet) }
                                .onSuccess { sent = true }
                                .onFailure { lastError = it }
                        }
                    }
                    if (!sent) {
                        throw lastError ?: IllegalStateException("BLEパケット送信に失敗しました。")
                    }
                }
            }
        }
    }

    /** GATTが接続済みで書き込みCharacteristicを保持しているかを返す。 */
    override fun isConnected(): Boolean {
        return _connectionState.value is StackChanConnectionState.Connected &&
            writeCharacteristic != null
    }

    /** アプリ終了時にBLEリソースを解放する。 */
    @SuppressLint("MissingPermission")
    fun close() {
        intentionalDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        stopScan()
        if (hasConnectPermission()) bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
        _connectionState.value = StackChanConnectionState.Disconnected
    }

    /** 切断後に指数バックオフで再接続を試みる。 */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true || lastAddress.isNullOrBlank()) return
        reconnectJob = reconnectScope.launch {
            val delays = longArrayOf(1_000L, 2_000L, 4_000L)
            for (backoff in delays) {
                if (intentionalDisconnect) return@launch
                delay(backoff)
                val result = connect(lastAddress.orEmpty())
                if (result.isSuccess) return@launch
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writePacket(packet: ByteArray) {
        val gatt = bluetoothGatt ?: throw IllegalStateException("GATTがありません。")
        val characteristic = writeCharacteristic ?: throw IllegalStateException("書き込みCharacteristicがありません。")
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, packet, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.setValue(packet)
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        if (!accepted) throw IllegalStateException("BLEパケット書き込みを開始できません。")
        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            kotlinx.coroutines.delay(2)
        } else {
            suspendCancellableCoroutine<Unit> { continuation ->
                writeCompletion = continuation
            }
        }
    }

    private var writeCompletion: kotlin.coroutines.Continuation<Unit>? = null

    @Suppress("UNUSED_PARAMETER")
    private fun completeConnection(result: Result<Unit>) {
        if (result.isFailure && _connectionState.value is StackChanConnectionState.Connecting) {
            _connectionState.value = StackChanConnectionState.Error(
                result.exceptionOrNull()?.message ?: "BLE接続に失敗しました。"
            )
        }
        connectSignal?.complete(result)
    }

    private fun findWritableCharacteristic(
        services: List<BluetoothGattService>
    ): BluetoothGattCharacteristic? {
        val targetService = serviceUuid?.let { uuid -> services.firstOrNull { it.uuid == uuid } }
        val candidates = targetService?.characteristics ?: services.flatMap { it.characteristics }
        return characteristicUuid?.let { uuid -> candidates.firstOrNull { it.uuid == uuid } }
            ?: candidates.firstOrNull {
                it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            }
            ?: candidates.firstOrNull {
                it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            }
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val CONNECTION_TIMEOUT_MS = 15_000L
        const val MAX_WRITE_RETRIES = 3
    }
}
