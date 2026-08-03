package dev.vespian.health

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The real, human readable name of the tracker.
 *
 * Health Connect does not carry it. Mi Fitness writes only a manufacturer
 * string, which is why the last onboarding screen used to say "Xiaomi" and
 * nothing more. The full name, the one with the four hex characters at the
 * end, only exists in the Bluetooth bond on the phone, so that is where it is
 * read from.
 *
 * This is cosmetic. If the permission is missing or the band is bonded to the
 * vendor app in a way this cannot see, the caller falls back to whatever
 * Health Connect knows.
 */
object Band {

    private val HINTS = listOf(
        "band", "watch", "amazfit", "mi smart", "redmi", "xiaomi", "fit",
    )

    fun permission(): String = "android.permission.BLUETOOTH_CONNECT"

    /** True when the bonded list can be read at all. */
    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Picks the bonded device that looks like a wearable. The device class is
     * trusted first; the name is only sniffed when the class is missing, which
     * happens with some low energy bonds.
     */
    fun name(context: Context): String? {
        if (!allowed(context)) return null
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return null
        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        if (bonded.isEmpty()) return null

        val named = bonded.mapNotNull { device ->
            val label = runCatching { device.name }.getOrNull()?.trim()
            if (label.isNullOrEmpty()) null else device to label
        }

        val wearable = named.firstOrNull { (device, _) ->
            val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
            major == BluetoothClass.Device.Major.WEARABLE
        }
        if (wearable != null) return wearable.second

        val guessed = named.firstOrNull { (_, label) ->
            val low = label.lowercase()
            HINTS.any { low.contains(it) }
        }
        return guessed?.second
    }
}
