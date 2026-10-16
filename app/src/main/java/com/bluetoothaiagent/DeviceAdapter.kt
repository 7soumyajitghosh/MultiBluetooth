package com.bluetoothaiagent

import android.bluetooth.BluetoothProfile
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

// ============================================================================
// DeviceAdapter
// RecyclerView adapter using ListAdapter + DiffUtil for efficient list updates.
// Each item shows a discovered BLE device with its connection state and controls.
// ============================================================================

class DeviceAdapter :
    ListAdapter<ScannedDevice, DeviceAdapter.DeviceViewHolder>(DeviceDiffCallback()) {

    // ---- External state injected by the Activity/Fragment ----

    /**
     * Map of MAC address → ConnectedDevice.
     * Updated whenever the connectedDevices StateFlow emits.
     * Used to show "Connected" / "Disconnected" status per item.
     */
    var connectedDevices: Map<String, ConnectedDevice> = emptyMap()
        private set

    /** MAC address of the device the user has selected via the CheckBox. */
    var selectedAddress: String? = null
        private set

    // ---- Callbacks wired up by the Activity ----

    /** Called when the user taps "Connect" on a not-yet-connected device. */
    var onConnectClick: ((ScannedDevice) -> Unit)? = null

    /** Called when the user taps "Disconnect" on a connected device. */
    var onDisconnectClick: ((String) -> Unit)? = null

    /** Called when the user selects a device via its CheckBox. */
    var onDeviceSelected: ((String?) -> Unit)? = null

    // ====================================================================
    // ViewHolder
    // ====================================================================

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvDeviceName: TextView = itemView.findViewById(R.id.tvDeviceName)
        val tvDeviceAddress: TextView = itemView.findViewById(R.id.tvDeviceAddress)
        val tvRssi: TextView = itemView.findViewById(R.id.tvRssi)
        val tvConnectionStatus: TextView = itemView.findViewById(R.id.tvConnectionStatus)
        val btnConnect: MaterialButton = itemView.findViewById(R.id.btnConnect)
        val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)
    }

    // ====================================================================
    // Adapter overrides
    // ====================================================================

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val scannedDevice = getItem(position)
        val address = scannedDevice.address
        val isConnected = connectedDevices[address]?.connectionState ==
                BluetoothProfile.STATE_CONNECTED

        // ---- Populate text fields ----
        holder.tvDeviceName.text =
            scannedDevice.name.ifBlank { "Unknown Device" }
        holder.tvDeviceAddress.text = address
        holder.tvRssi.text = "📶 ${scannedDevice.rssi} dBm"

        // ---- Connection status with colour coding ----
        if (isConnected) {
            holder.tvConnectionStatus.text = "● Connected"
            holder.tvConnectionStatus.setTextColor(Color.parseColor("#2E7D32")) // green
            holder.btnConnect.text = "Disconnect"
        } else {
            holder.tvConnectionStatus.text = "○ Not Connected"
            holder.tvConnectionStatus.setTextColor(Color.GRAY)
            holder.btnConnect.text = "Connect"
        }

        // ---- CheckBox for device selection ----
        // Remove listener before setting checked to avoid feedback loop
        holder.cbSelect.setOnCheckedChangeListener(null)
        holder.cbSelect.isChecked = (selectedAddress == address)
        holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
            selectedAddress = if (isChecked) address else null
            onDeviceSelected?.invoke(selectedAddress)
            // Refresh all items so only one checkbox is active at a time
            notifyDataSetChanged()
        }

        // ---- Connect / Disconnect button ----
        holder.btnConnect.setOnClickListener {
            if (isConnected) {
                onDisconnectClick?.invoke(address)
            } else {
                onConnectClick?.invoke(scannedDevice)
            }
        }
    }

    // ====================================================================
    // Public helpers
    // ====================================================================

    /**
     * Update the connected-devices map and refresh the list.
     * Called by the Activity whenever the connectedDevices StateFlow emits.
     */
    fun updateConnectedDevices(devices: Map<String, ConnectedDevice>) {
        connectedDevices = devices
        notifyDataSetChanged()
    }

    // ====================================================================
    // DiffUtil
    // ====================================================================

    /**
     * DiffUtil compares items by their MAC address (stable identity)
     * and checks content equality for efficient partial rebinds.
     */
    class DeviceDiffCallback : DiffUtil.ItemCallback<ScannedDevice>() {
        override fun areItemsTheSame(old: ScannedDevice, new: ScannedDevice): Boolean =
            old.address == new.address

        override fun areContentsTheSame(old: ScannedDevice, new: ScannedDevice): Boolean =
            old == new
    }
}
