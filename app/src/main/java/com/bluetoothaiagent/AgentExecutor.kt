package com.bluetoothaiagent

import android.util.Log
import org.json.JSONObject

// ============================================================================
// AgentCommand — structured representation of a parsed user intent
// ============================================================================

/**
 * Data class that the AI Agent produces after parsing a natural-language command.
 *
 * @property action  One of: list, send, send_all, disconnect_all, disconnect, connect, scan, stop_scan, unknown
 * @property target  A device name / MAC address, "all", or empty string
 * @property payload The data to transmit (relevant for send / send_all actions)
 */
data class AgentCommand(
    val action: String,
    val target: String,
    val payload: String
)

// ============================================================================
// AgentExecutor — NLP-lite parser + command executor
// ============================================================================

/**
 * The AI Agent layer.
 *
 * 1. Receives a free-form English command from the user.
 * 2. Parses it into a structured [AgentCommand] using keyword / regex matching.
 * 3. Converts the command to JSON for transparency.
 * 4. Executes the action against the [BluetoothAgentController].
 * 5. Returns a human-readable result string.
 */
class AgentExecutor(private val controller: BluetoothAgentController) {

    companion object {
        private const val TAG = "AgentExecutor"
    }

    // ================================================================
    // Public entry point
    // ================================================================

    /**
     * Main entry point: parse → log JSON → execute → return result.
     */
    fun execute(input: String): String {
        val command = parseCommand(input)
        val json = toJson(command)
        Log.d(TAG, "Parsed command: $json")

        val result = executeCommand(command)
        return "📋 Command JSON:\n${json.toString(2)}\n\n📨 Result:\n$result"
    }

    // ================================================================
    // Natural-language parser
    // ================================================================

    /**
     * Converts a natural-language string into an [AgentCommand].
     *
     * The parser uses a priority-ordered chain of regex / keyword checks.
     * It is intentionally lenient — partial matches and mixed case are fine.
     *
     * Supported patterns (examples):
     *   "Show connected devices"            → list
     *   "Send LED_ON to all devices"        → send_all, payload=LED_ON
     *   "Send Hello to Device 1"            → send, target=Device 1, payload=Hello
     *   "Disconnect all devices"            → disconnect_all
     *   "Disconnect Device 1"              → disconnect, target=Device 1
     *   "Connect Device 1"                 → connect, target=Device 1
     *   "Scan" / "Search for devices"      → scan
     *   "Stop scanning"                    → stop_scan
     */
    fun parseCommand(input: String): AgentCommand {
        val trimmed = input.trim()
        val lower = trimmed.lowercase()

        // ---- 1. List / Show connected devices ----
        if (lower.containsAny("show", "list") && lower.containsAny("device", "connected")) {
            return AgentCommand(action = "list", target = "all", payload = "")
        }

        // ---- 2. Send to ALL devices ----
        // Patterns: "send X to all [devices]", "send all X", "send X all"
        if (lower.contains("send") && lower.contains("all")) {
            val payload = extractSendAllPayload(trimmed)
            return AgentCommand(action = "send_all", target = "all", payload = payload)
        }

        // ---- 3. Send to a SPECIFIC device ----
        // Patterns: "send X to DeviceName", "send X to device 1"
        if (lower.contains("send")) {
            val (target, payload) = extractSendTargetAndPayload(trimmed)
            return AgentCommand(action = "send", target = target, payload = payload)
        }

        // ---- 4. Disconnect all ----
        if (lower.contains("disconnect") && lower.contains("all")) {
            return AgentCommand(action = "disconnect_all", target = "all", payload = "")
        }

        // ---- 5. Disconnect specific device ----
        if (lower.contains("disconnect")) {
            val target = extractTarget(trimmed, "disconnect")
            return AgentCommand(action = "disconnect", target = target, payload = "")
        }

        // ---- 6. Connect specific device ----
        if (lower.contains("connect")) {
            val target = extractTarget(trimmed, "connect")
            return AgentCommand(action = "connect", target = target, payload = "")
        }

        // ---- 7. Scan / Search ----
        if (lower.containsAny("scan", "search", "find")) {
            return AgentCommand(action = "scan", target = "", payload = "")
        }

        // ---- 8. Stop scanning ----
        if (lower.contains("stop")) {
            return AgentCommand(action = "stop_scan", target = "", payload = "")
        }

        // ---- Fallback ----
        return AgentCommand(action = "unknown", target = "", payload = trimmed)
    }

    // ================================================================
    // JSON conversion
    // ================================================================

    /** Convert an [AgentCommand] to a JSONObject for logging / transparency. */
    fun toJson(command: AgentCommand): JSONObject {
        return JSONObject().apply {
            put("action", command.action)
            put("target", command.target)
            put("payload", command.payload)
        }
    }

    // ================================================================
    // Command execution against the controller
    // ================================================================

    /**
     * Dispatches [command] to the appropriate controller method and returns
     * a human-readable result string.
     */
    private fun executeCommand(command: AgentCommand): String {
        return try {
            when (command.action) {
                "list" -> {
                    val devices = controller.getConnectedDevicesList()
                    if (devices.isEmpty()) {
                        "No devices currently connected."
                    } else {
                        devices.joinToString("\n") { d ->
                            "• ${d.name} (${d.address}) — ${stateText(d.connectionState)}"
                        }
                    }
                }

                "send" -> {
                    // Resolve the target device by name
                    val device = controller.getDeviceByName(command.target)
                    if (device != null) {
                        controller.sendData(device.address, command.payload)
                    } else {
                        "Error: Could not find a connected device matching \"${command.target}\"."
                    }
                }

                "send_all" -> {
                    controller.sendDataToAll(command.payload)
                }

                "disconnect_all" -> {
                    controller.disconnectAll()
                    "All devices disconnected."
                }

                "disconnect" -> {
                    val device = controller.getDeviceByName(command.target)
                    if (device != null) {
                        controller.disconnectDevice(device.address)
                        "Disconnected ${device.name} (${device.address})."
                    } else {
                        "Error: Could not find a connected device matching \"${command.target}\"."
                    }
                }

                "connect" -> {
                    // For connect we search scanned (not yet connected) devices
                    val scanned = controller.scannedDevices.value.firstOrNull {
                        it.name.contains(command.target, ignoreCase = true) ||
                                it.address.contains(command.target, ignoreCase = true)
                    }
                    if (scanned != null) {
                        controller.connectDevice(scanned.device)
                        "Connecting to ${scanned.name} (${scanned.address})…"
                    } else {
                        "Error: No scanned device matching \"${command.target}\". Try scanning first."
                    }
                }

                "scan" -> {
                    controller.startScan()
                    "Scanning for BLE devices…"
                }

                "stop_scan" -> {
                    controller.stopScan()
                    "Scan stopped."
                }

                else -> {
                    buildString {
                        appendLine("❓ Unknown command. Try one of these:")
                        appendLine("  • \"Show connected devices\"")
                        appendLine("  • \"Send LED_ON to all devices\"")
                        appendLine("  • \"Send Hello to Device 1\"")
                        appendLine("  • \"Disconnect all devices\"")
                        appendLine("  • \"Scan for devices\"")
                        appendLine("  • \"Stop scanning\"")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Execution error", e)
            "Error executing command: ${e.message}"
        }
    }

    // ================================================================
    // Text extraction helpers
    // ================================================================

    /**
     * Extracts the payload from a "send … to all" style command.
     * Examples:
     *   "Send LED_ON to all devices" → "LED_ON"
     *   "Send hello world to all"    → "hello world"
     */
    private fun extractSendAllPayload(input: String): String {
        // Try pattern: "send <payload> to all"
        val regex1 = Regex("(?i)send\\s+(.+?)\\s+to\\s+all")
        regex1.find(input)?.let { return it.groupValues[1].trim() }

        // Try pattern: "send all <payload>"
        val regex2 = Regex("(?i)send\\s+all\\s+(.+)")
        regex2.find(input)?.let { return it.groupValues[1].trim() }

        // Fallback: everything after "send"
        val idx = input.lowercase().indexOf("send")
        return if (idx >= 0) input.substring(idx + 4).trim().removeSuffix("to all").trim()
        else ""
    }

    /**
     * Extracts target device name and payload from a "send X to DeviceName" command.
     * Returns Pair(target, payload).
     *
     * Examples:
     *   "Send Hello to Device 1"      → ("Device 1", "Hello")
     *   "Send LED_OFF to myModule"     → ("myModule", "LED_OFF")
     */
    private fun extractSendTargetAndPayload(input: String): Pair<String, String> {
        // Pattern: "send <payload> to <target>"
        val regex = Regex("(?i)send\\s+(.+?)\\s+to\\s+(.+)")
        regex.find(input)?.let {
            val payload = it.groupValues[1].trim()
            val target = it.groupValues[2].trim()
                .removeSuffix("device").removeSuffix("devices").trim()
            return Pair(target, payload)
        }

        // Fallback: no "to" keyword; treat everything after "send" as payload
        val idx = input.lowercase().indexOf("send")
        val rest = if (idx >= 0) input.substring(idx + 4).trim() else input
        return Pair("", rest)
    }

    /**
     * Extracts a target name after a keyword like "disconnect" or "connect".
     * Example: "Disconnect Device 1" → "Device 1"
     */
    private fun extractTarget(input: String, keyword: String): String {
        val idx = input.lowercase().indexOf(keyword)
        return if (idx >= 0) input.substring(idx + keyword.length).trim()
        else ""
    }

    /** Converts a BluetoothProfile connection-state int to readable text. */
    private fun stateText(state: Int): String = when (state) {
        android.bluetooth.BluetoothProfile.STATE_CONNECTED -> "Connected"
        android.bluetooth.BluetoothProfile.STATE_CONNECTING -> "Connecting"
        android.bluetooth.BluetoothProfile.STATE_DISCONNECTING -> "Disconnecting"
        else -> "Disconnected"
    }

    /** Extension: returns true if the string contains ANY of the given keywords. */
    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it, ignoreCase = true) }
}
