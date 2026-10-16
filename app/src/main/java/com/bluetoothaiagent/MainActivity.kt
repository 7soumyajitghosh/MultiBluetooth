package com.bluetoothaiagent

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.bluetoothaiagent.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

// ============================================================================
// MainActivity
// The single-activity UI for Bluetooth AI Agent.
// Handles runtime permissions, sets up the RecyclerView, and observes the
// ViewModel's reactive streams (StateFlow / LiveData) following MVVM.
// ============================================================================

class MainActivity : AppCompatActivity() {

    // ---- View Binding (replaces findViewById) ----
    private lateinit var binding: ActivityMainBinding

    // ---- ViewModel (survives configuration changes) ----
    private val viewModel: AgentViewModel by viewModels()

    // ---- RecyclerView adapter ----
    private lateinit var deviceAdapter: DeviceAdapter

    // ====================================================================
    // Permission handling
    // ====================================================================

    /**
     * Returns the array of runtime permissions required for BLE operations.
     *
     * Android 12 (API 31) introduced granular Bluetooth permissions:
     *   - BLUETOOTH_SCAN   — to discover nearby devices
     *   - BLUETOOTH_CONNECT — to connect / communicate with devices
     *
     * On Android 11 and below the legacy approach requires location permission
     * because BLE scan results can reveal the user's location.
     */
    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    /** Returns true if ALL required permissions are already granted. */
    private fun hasPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Launcher for the multi-permission request dialog.
     * When results come back we either proceed or show a rationale Snackbar.
     */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                // All permissions granted — check Bluetooth is enabled
                ensureBluetoothEnabled()
            } else {
                Snackbar.make(
                    binding.root,
                    "Bluetooth & location permissions are required to scan and connect to BLE devices.",
                    Snackbar.LENGTH_LONG
                ).setAction("Retry") { requestPermissions() }.show()
            }
        }

    /** Kick off the runtime-permission request. */
    private fun requestPermissions() {
        permissionLauncher.launch(requiredPermissions())
    }

    // ====================================================================
    // Bluetooth enable check
    // ====================================================================

    /**
     * Launcher for the system "Turn on Bluetooth?" dialog.
     */
    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Toast.makeText(this, "Bluetooth enabled ✓", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Bluetooth is required for this app", Toast.LENGTH_LONG).show()
            }
        }

    /** Prompts the user to enable Bluetooth if it's currently off. */
    private fun ensureBluetoothEnabled() {
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBtLauncher.launch(intent)
        }
    }

    // ====================================================================
    // Activity lifecycle
    // ====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inflate layout via View Binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Toolbar setup
        setSupportActionBar(binding.toolbar)

        // RecyclerView setup
        setupRecyclerView()

        // Check permissions on launch
        if (hasPermissions()) {
            ensureBluetoothEnabled()
        } else {
            requestPermissions()
        }

        // Wire up button click listeners
        setupClickListeners()

        // Start observing ViewModel streams
        observeViewModel()
    }

    // ====================================================================
    // RecyclerView setup
    // ====================================================================

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter().apply {
            // When user taps "Connect" on a device item
            onConnectClick = { scannedDevice ->
                if (!hasPermissions()) {
                    requestPermissions()
                    return@apply
                }
                viewModel.connectDevice(scannedDevice.device)
            }

            // When user taps "Disconnect" on a connected device item
            onDisconnectClick = { address ->
                viewModel.disconnectDevice(address)
            }

            // When user selects a device via its checkbox
            onDeviceSelected = { address ->
                viewModel.selectedDeviceAddress.value = address
            }
        }

        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
            addItemDecoration(
                DividerItemDecoration(this@MainActivity, DividerItemDecoration.VERTICAL)
            )
        }
    }

    // ====================================================================
    // Button click listeners
    // ====================================================================

    private fun setupClickListeners() {
        // ---- Scan button ----
        binding.btnScan.setOnClickListener {
            if (!hasPermissions()) {
                requestPermissions()
                return@setOnClickListener
            }
            ensureBluetoothEnabled()
            viewModel.startScan()
        }

        // ---- Stop Scan button ----
        binding.btnStopScan.setOnClickListener {
            viewModel.stopScan()
        }

        // ---- Send to Selected device ----
        binding.btnSendSelected.setOnClickListener {
            val data = binding.etSendData.text?.toString()?.trim()
            if (data.isNullOrEmpty()) {
                Toast.makeText(this, "Enter data to send", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val address = viewModel.selectedDeviceAddress.value
            if (address == null) {
                Toast.makeText(this, "Select a device first (checkbox)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.sendData(address, data)
            binding.etSendData.text?.clear()
        }

        // ---- Send to All connected devices ----
        binding.btnSendAll.setOnClickListener {
            val data = binding.etSendData.text?.toString()?.trim()
            if (data.isNullOrEmpty()) {
                Toast.makeText(this, "Enter data to send", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (viewModel.connectedDevices.value.isEmpty()) {
                Toast.makeText(this, "No devices connected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.sendDataToAll(data)
            binding.etSendData.text?.clear()
        }

        // ---- AI Agent Execute button ----
        binding.btnExecuteAgent.setOnClickListener {
            val command = binding.etAgentCommand.text?.toString()?.trim()
            if (command.isNullOrEmpty()) {
                Toast.makeText(this, "Enter a command for the AI Agent", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.executeAgentCommand(command)
            binding.etAgentCommand.text?.clear()
        }
    }

    // ====================================================================
    // ViewModel observation (MVVM data binding via coroutines & LiveData)
    // ====================================================================

    /**
     * Observes all reactive streams from the ViewModel.
     *
     * Uses repeatOnLifecycle(STARTED) so collection is automatically paused
     * when the Activity goes to the background and resumed when it comes
     * back — preventing unnecessary work and potential crashes.
     */
    private fun observeViewModel() {
        // ---- StateFlows (collected in lifecycle-aware coroutines) ----
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe discovered devices from scan
                launch {
                    viewModel.scannedDevices.collect { devices ->
                        deviceAdapter.submitList(devices.toList())
                    }
                }

                // Observe connected devices and update adapter + status
                launch {
                    viewModel.connectedDevices.collect { devices ->
                        deviceAdapter.updateConnectedDevices(devices)
                        val count = devices.values.count {
                            it.connectionState == android.bluetooth.BluetoothProfile.STATE_CONNECTED
                        }
                        binding.tvStatus.text = "Connected devices: $count"
                    }
                }

                // Observe scanning state for UI feedback
                launch {
                    viewModel.isScanning.collect { scanning ->
                        binding.progressScanning.isVisible = scanning
                        binding.btnScan.isEnabled = !scanning
                        binding.btnStopScan.isEnabled = scanning
                    }
                }
            }
        }

        // ---- LiveData (observed the traditional way) ----

        // Agent log output
        viewModel.agentLog.observe(this) { log ->
            binding.tvAgentLog.text = log
        }

        // Status bar messages
        viewModel.statusMessage.observe(this) { message ->
            binding.tvStatus.text = message
        }
    }
}
