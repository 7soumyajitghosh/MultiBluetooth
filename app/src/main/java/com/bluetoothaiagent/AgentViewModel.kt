package com.bluetoothaiagent

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ============================================================================
// AgentViewModel
// MVVM ViewModel that bridges the UI (MainActivity) with business logic
// (BluetoothAgentController + AgentExecutor).
// ============================================================================

/**
 * Uses [AndroidViewModel] (not plain ViewModel) because the controller needs
 * an application Context to initialise the BluetoothManager.
 *
 * All public methods are safe to call from the UI thread — heavy work
 * is dispatched onto background dispatchers via [viewModelScope].
 */
class AgentViewModel(application: Application) : AndroidViewModel(application) {

    // ---- Core components ----

    /** Central BLE controller managing scanning, connections, and data TX. */
    val controller = BluetoothAgentController(application)

    /** AI Agent that parses and executes natural-language commands. */
    private val agentExecutor = AgentExecutor(controller)

    // ---- Exposed StateFlows (from the controller) ----

    /** Discovered BLE devices from the latest scan. */
    val scannedDevices = controller.scannedDevices

    /** Map of MAC → ConnectedDevice for every active GATT connection. */
    val connectedDevices = controller.connectedDevices

    /** Whether a BLE scan is in progress. */
    val isScanning = controller.isScanning

    // ---- LiveData for one-shot UI events ----

    /** Agent log / response text shown in the AI Agent card. */
    private val _agentLog = MutableLiveData("Agent ready. Type a command above.")
    val agentLog: LiveData<String> = _agentLog

    /** Short status messages shown in the bottom status bar. */
    private val _statusMessage = MutableLiveData("Status: Ready")
    val statusMessage: LiveData<String> = _statusMessage

    /** The MAC address of the device the user has selected (checkbox) for targeted send. */
    val selectedDeviceAddress = MutableLiveData<String?>(null)

    // ====================================================================
    // Scan operations
    // ====================================================================

    /** Start a BLE scan. Updates status bar accordingly. */
    fun startScan() {
        controller.startScan()
        _statusMessage.value = "Scanning for BLE devices…"
    }

    /** Stop an in-progress BLE scan. */
    fun stopScan() {
        controller.stopScan()
        _statusMessage.value = "Scan stopped"
    }

    // ====================================================================
    // Connection operations
    // ====================================================================

    /** Connect to a discovered BLE device. */
    fun connectDevice(device: BluetoothDevice) {
        controller.connectDevice(device)
        _statusMessage.value = "Connecting to ${device.address}…"
    }

    /** Disconnect a single device by its MAC address. */
    fun disconnectDevice(address: String) {
        controller.disconnectDevice(address)
        _statusMessage.value = "Disconnected $address"
    }

    // ====================================================================
    // Data transmission
    // ====================================================================

    /**
     * Send [data] to a single device identified by [address].
     * Runs on the Default dispatcher since sendData may block briefly.
     */
    fun sendData(address: String, data: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val result = controller.sendData(address, data)
            _statusMessage.postValue(result)
        }
    }

    /**
     * Broadcast [data] to every connected device.
     */
    fun sendDataToAll(data: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val result = controller.sendDataToAll(data)
            _statusMessage.postValue(result)
        }
    }

    // ====================================================================
    // AI Agent
    // ====================================================================

    /**
     * Parse and execute a natural-language command via the [AgentExecutor].
     *
     * Runs on [Dispatchers.Default] so regex parsing and controller calls
     * don't block the UI. Results are posted back to [_agentLog] and
     * [_statusMessage] via LiveData.postValue (thread-safe).
     */
    fun executeAgentCommand(command: String) {
        viewModelScope.launch(Dispatchers.Default) {
            _agentLog.postValue("⏳ Processing: \"$command\"…")
            val result = agentExecutor.execute(command)
            _agentLog.postValue(result)
            _statusMessage.postValue("Agent command executed")
        }
    }

    // ====================================================================
    // Lifecycle
    // ====================================================================

    /**
     * Called when the ViewModel is about to be destroyed (Activity finished
     * or configuration change with no retained ViewModel).
     * Cleans up all BLE connections and cancels coroutines.
     */
    override fun onCleared() {
        super.onCleared()
        controller.cleanup()
    }
}
