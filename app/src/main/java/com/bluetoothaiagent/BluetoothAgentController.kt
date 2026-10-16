package com.bluetoothaiagent

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ============================================================================
// Data classes representing BLE device states
// ============================================================================

/**
 * Represents a BLE device discovered during scanning.
 * Stores the scan-time info like RSSI and the underlying BluetoothDevice handle.
 */
data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val device: BluetoothDevice
)

/**
 * Represents a device that has been connected via GATT.
 * Tracks connection state and holds the GATT reference.
 */
data class ConnectedDevice(
    val name: String,
    val address: String,
    var connectionState: Int = BluetoothProfile.STATE_DISCONNECTED,
    var gatt: BluetoothGatt? = null
)

// ============================================================================
// BluetoothAgentController
// Core class managing BLE scanning, multi-device GATT connections, and data TX.
// ============================================================================

class BluetoothAgentController(context: Context) {

    companion object {
        private const val TAG = "BT_Controller"

        /** Scan automatically stops after this duration (ms) to conserve battery. */
        private const val SCAN_TIMEOUT = 10_000L

        /**
         * Generic BLE serial-service UUID (used by HM-10, AT-09, and many similar modules).
         * Change these if your hardware uses different UUIDs.
         */
        private val SERVICE_UUID: UUID =
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val CHARACTERISTIC_UUID: UUID =
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    }

    // ---- System services ----
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    // ---- Reactive state exposed to the ViewModel / UI ----

    /** List of devices found during the most recent scan. */
    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    /**
     * Map of MAC address → ConnectedDevice for every active GATT connection.
     * Using a Map keyed by address makes lookups O(1).
     */
    private val _connectedDevices = MutableStateFlow<Map<String, ConnectedDevice>>(emptyMap())
    val connectedDevices: StateFlow<Map<String, ConnectedDevice>> = _connectedDevices.asStateFlow()

    /** Whether a BLE scan is currently in progress. */
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // ---- Internal bookkeeping ----

    /**
     * ConcurrentHashMap is used because GATT callbacks arrive on binder threads
     * and we may also mutate from coroutines on Main/Default dispatchers.
     * This avoids ConcurrentModificationException without explicit locking on every read.
     */
    private val gattConnections = ConcurrentHashMap<String, BluetoothGatt>()

    /** Mutex serialises connect / disconnect operations so state updates are consistent. */
    private val connectionMutex = Mutex()

    /** Handler on the main looper used for the scan-timeout runnable. */
    private val scanHandler = Handler(Looper.getMainLooper())

    /** Dedicated scope for controller coroutines; cancelled in cleanup(). */
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Keep a reference to the application context to avoid leaking an Activity. */
    private val appContext: Context = context.applicationContext

    // ====================================================================
    // Scanning
    // ====================================================================

    /**
     * Start a BLE scan.
     * Uses LOW_LATENCY mode for fastest discovery; the scan auto-stops after [SCAN_TIMEOUT].
     *
     * @SuppressLint("MissingPermission") is safe here because the Activity checks
     * permissions before calling any controller method.
     */
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (_isScanning.value) {
            Log.w(TAG, "Scan already in progress")
            return
        }

        val scanner = bleScanner
        if (scanner == null) {
            Log.e(TAG, "BLE scanner not available (is Bluetooth ON?)")
            return
        }

        // Clear previous results so the UI starts fresh
        _scannedDevices.value = emptyList()
        _isScanning.value = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
        Log.d(TAG, "BLE scan started")

        // Auto-stop after timeout to save battery
        scanHandler.postDelayed({ stopScan() }, SCAN_TIMEOUT)
    }

    /**
     * Stop an ongoing BLE scan.
     */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!_isScanning.value) return

        bleScanner?.stopScan(scanCallback)
        scanHandler.removeCallbacksAndMessages(null)
        _isScanning.value = false
        Log.d(TAG, "BLE scan stopped. Found ${_scannedDevices.value.size} device(s).")
    }

    /**
     * ScanCallback adds discovered devices to the list, de-duplicating by MAC address.
     * If a device is seen again its RSSI is updated in-place.
     */
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "Unknown"
            val address = device.address
            val rssi = result.rssi

            val current = _scannedDevices.value.toMutableList()
            val existingIndex = current.indexOfFirst { it.address == address }

            if (existingIndex >= 0) {
                // Update RSSI for an already-discovered device
                current[existingIndex] = current[existingIndex].copy(rssi = rssi)
            } else {
                current.add(ScannedDevice(name, address, rssi, device))
            }

            _scannedDevices.value = current
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            _isScanning.value = false
        }
    }

    // ====================================================================
    // Connecting
    // ====================================================================

    /**
     * Connect to a BLE device.
     *
     * Each device gets its own [BluetoothGattCallback] instance so callbacks
     * for Device-A never interfere with Device-B.
     * The GATT object is stored in [gattConnections] for later data transmission.
     */
    @SuppressLint("MissingPermission")
    fun connectDevice(device: BluetoothDevice) {
        coroutineScope.launch {
            connectionMutex.withLock {
                val address = device.address

                // Avoid duplicate connections
                if (gattConnections.containsKey(address)) {
                    Log.w(TAG, "Already connected to $address")
                    return@withLock
                }

                val deviceName = device.name ?: "Unknown"
                Log.d(TAG, "Connecting to $deviceName ($address)…")

                // Create a per-device callback
                val callback = createGattCallback(address, deviceName)

                // connectGatt with TRANSPORT_LE ensures we use BLE (not classic BT)
                val gatt: BluetoothGatt? = device.connectGatt(
                    appContext,
                    false,          // autoConnect = false for faster initial connection
                    callback,
                    BluetoothDevice.TRANSPORT_LE
                )

                if (gatt != null) {
                    gattConnections[address] = gatt
                    updateConnectedDevice(address, deviceName, BluetoothProfile.STATE_CONNECTING, gatt)
                } else {
                    Log.e(TAG, "connectGatt returned null for $address")
                }
            }
        }
    }

    /**
     * Creates a [BluetoothGattCallback] dedicated to a single device.
     * Having one callback per device keeps connection-state management clean
     * when multiple devices are connected simultaneously.
     */
    @SuppressLint("MissingPermission")
    private fun createGattCallback(
        address: String,
        deviceName: String
    ): BluetoothGattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            coroutineScope.launch {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "✅ Connected to $deviceName ($address)")
                        updateConnectedDevice(address, deviceName, BluetoothProfile.STATE_CONNECTED, gatt)
                        // Discover services so we can write characteristics later
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "❌ Disconnected from $deviceName ($address), status=$status")
                        gatt.close()
                        gattConnections.remove(address)
                        removeConnectedDevice(address)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val services = gatt.services.joinToString { it.uuid.toString() }
                Log.d(TAG, "Services discovered for $address: $services")
            } else {
                Log.e(TAG, "Service discovery failed for $address, status=$status")
            }
        }

        @Deprecated("Deprecated in API 33, but kept for backward-compat below API 33")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "✔ Write success → $address")
            } else {
                Log.e(TAG, "✘ Write failed → $address, status=$status")
            }
        }
    }

    // ====================================================================
    // Disconnecting
    // ====================================================================

    /**
     * Disconnect a single device by its MAC address.
     */
    @SuppressLint("MissingPermission")
    fun disconnectDevice(address: String) {
        coroutineScope.launch {
            connectionMutex.withLock {
                val gatt = gattConnections.remove(address)
                if (gatt != null) {
                    gatt.disconnect()
                    gatt.close()
                    removeConnectedDevice(address)
                    Log.d(TAG, "Disconnected $address")
                } else {
                    Log.w(TAG, "No active connection for $address")
                }
            }
        }
    }

    /**
     * Disconnect every connected device and clear all GATT references.
     */
    @SuppressLint("MissingPermission")
    fun disconnectAll() {
        coroutineScope.launch {
            connectionMutex.withLock {
                gattConnections.forEach { (address, gatt) ->
                    gatt.disconnect()
                    gatt.close()
                    Log.d(TAG, "Disconnected $address")
                }
                gattConnections.clear()
                _connectedDevices.value = emptyMap()
            }
        }
    }

    // ====================================================================
    // Sending data
    // ====================================================================

    /**
     * Send a UTF-8 string to a specific device identified by MAC address.
     *
     * The method looks up the GATT connection, finds the target service and
     * characteristic by UUID, writes the data bytes, and returns a status message.
     */
    @SuppressLint("MissingPermission")
    fun sendData(address: String, data: String): String {
        val gatt = gattConnections[address]
            ?: return "Error: Device $address is not connected."

        val service = gatt.getService(SERVICE_UUID)
            ?: return "Error: Service not found on $address. Device may not support serial communication."

        val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
            ?: return "Error: Characteristic not found on $address."

        // Use the modern writeCharacteristic API for Android 13+ (API 33),
        // fall back to the legacy setter+write for older versions.
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = gatt.writeCharacteristic(
                    characteristic,
                    data.toByteArray(Charsets.UTF_8),
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                if (result == BluetoothStatusCodes.SUCCESS) {
                    "Sent \"$data\" → $address"
                } else {
                    "Error: writeCharacteristic returned code $result for $address"
                }
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = data.toByteArray(Charsets.UTF_8)
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                val ok = gatt.writeCharacteristic(characteristic)
                if (ok) "Sent \"$data\" → $address"
                else "Error: writeCharacteristic failed for $address"
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendData error", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Broadcast a UTF-8 string to ALL currently connected devices.
     * Returns a combined status message.
     */
    fun sendDataToAll(data: String): String {
        if (gattConnections.isEmpty()) {
            return "No devices connected."
        }
        val results = gattConnections.keys.map { address -> sendData(address, data) }
        return results.joinToString("\n")
    }

    // ====================================================================
    // Helpers / queries
    // ====================================================================

    /** Returns a snapshot list of currently connected devices. */
    fun getConnectedDevicesList(): List<ConnectedDevice> {
        return _connectedDevices.value.values.toList()
    }

    /**
     * Find a connected device whose name contains [name] (case-insensitive).
     * Useful for the AI Agent to resolve "Device 1" → actual device.
     */
    fun getDeviceByName(name: String): ConnectedDevice? {
        return _connectedDevices.value.values.firstOrNull {
            it.name.contains(name, ignoreCase = true)
        }
    }

    // ---- Private state helpers ----

    /** Insert or update a device entry in the connected-devices map. */
    private fun updateConnectedDevice(
        address: String,
        name: String,
        state: Int,
        gatt: BluetoothGatt?
    ) {
        val updated = _connectedDevices.value.toMutableMap()
        updated[address] = ConnectedDevice(name, address, state, gatt)
        _connectedDevices.value = updated
    }

    /** Remove a device from the connected-devices map. */
    private fun removeConnectedDevice(address: String) {
        val updated = _connectedDevices.value.toMutableMap()
        updated.remove(address)
        _connectedDevices.value = updated
    }

    // ====================================================================
    // Lifecycle cleanup
    // ====================================================================

    /**
     * Call from ViewModel.onCleared() or Activity.onDestroy().
     * Disconnects everything and cancels the coroutine scope.
     */
    @SuppressLint("MissingPermission")
    fun cleanup() {
        stopScan()
        gattConnections.forEach { (_, gatt) ->
            gatt.disconnect()
            gatt.close()
        }
        gattConnections.clear()
        _connectedDevices.value = emptyMap()
        coroutineScope.cancel()
        Log.d(TAG, "Controller cleaned up")
    }
}
