package com.ltots.app.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the sound is going right now, with advice on getting the best quality there. */
data class OutputDevice(
    val name: String = "Phone speaker",
    val kind: Kind = Kind.SPEAKER,
    val info: AudioDeviceInfo? = null,
) {
    enum class Kind { SPEAKER, BLUETOOTH_CLASSIC, BLUETOOTH_LE, USB, WIRED, OTHER }

    val kindLabel: String
        get() = when (kind) {
            Kind.SPEAKER -> "Speaker"
            Kind.BLUETOOTH_CLASSIC -> "Bluetooth"
            Kind.BLUETOOTH_LE -> "Bluetooth LE Audio (LC3)"
            Kind.USB -> "USB DAC"
            Kind.WIRED -> "Wired"
            Kind.OTHER -> "Other"
        }

    /** One line of practical advice for this output. */
    val tip: String
        get() = when (kind) {
            Kind.BLUETOOTH_CLASSIC ->
                "For the best quality, set the Bluetooth codec to LDAC at 990 kbps (Best quality) in Developer options. Android doesn't let apps choose the codec."
            Kind.BLUETOOTH_LE ->
                "These earbuds are connected over LE Audio (LC3). For higher quality, turn off LE Audio for them in the Sony app or Bluetooth settings so they use LDAC."
            Kind.USB ->
                "USB DAC detected. Turn on bit-perfect output to send audio to the DAC without Android's mixer touching it."
            Kind.WIRED -> "Wired output — no Bluetooth compression in the way."
            Kind.SPEAKER -> "Playing on the phone speaker."
            Kind.OTHER -> ""
        }
}

class OutputMonitor(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _device = MutableStateFlow(OutputDevice())
    val device: StateFlow<OutputDevice> = _device

    private val _bitPerfectStatus = MutableStateFlow("Off")
    val bitPerfectStatus: StateFlow<String> = _bitPerfectStatus

    /** Called on device changes so the engine can re-apply bit-perfect routing. */
    var onDeviceChanged: (() -> Unit)? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    init {
        am.registerAudioDeviceCallback(callback, handler)
        refresh()
    }

    fun refresh() {
        val d = currentDevice()
        if (d.info?.id != _device.value.info?.id || d.kind != _device.value.kind) {
            _device.value = d
            onDeviceChanged?.invoke()
        }
    }

    private fun currentDevice(): OutputDevice {
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val routed: AudioDeviceInfo? = if (Build.VERSION.SDK_INT >= 33) {
            am.getAudioDevicesForAttributes(attrs).firstOrNull()
        } else {
            // Best guess on older Android: prefer USB > wired > Bluetooth > speaker.
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            outs.firstOrNull { isUsb(it.type) } ?: outs.firstOrNull { isWired(it.type) }
            ?: outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            ?: outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        }
        // getAudioDevicesForAttributes returns a stripped-down device; find the full one by id.
        val full = routed?.let { r -> am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == r.id } } ?: routed
            ?: return OutputDevice()
        val kind = when {
            isUsb(full.type) -> OutputDevice.Kind.USB
            isWired(full.type) -> OutputDevice.Kind.WIRED
            full.type == AudioDeviceInfo.TYPE_BLE_HEADSET || full.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                OutputDevice.Kind.BLUETOOTH_LE
            full.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> OutputDevice.Kind.BLUETOOTH_CLASSIC
            full.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputDevice.Kind.SPEAKER
            else -> OutputDevice.Kind.OTHER
        }
        val name = full.productName?.toString()?.takeIf { it.isNotBlank() && kind != OutputDevice.Kind.SPEAKER }
            ?: OutputDevice(kind = kind).kindLabel
        return OutputDevice(name, kind, full)
    }

    private fun isUsb(t: Int) = t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_USB_DEVICE ||
        t == AudioDeviceInfo.TYPE_USB_ACCESSORY

    private fun isWired(t: Int) = t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET

    private var bitPerfectDevice: AudioDeviceInfo? = null
    private var bitPerfectRate = 0

    /**
     * Ask Android (14+) to route our stream to the USB DAC bit-perfectly at [sampleRate], 16-bit.
     * Returns true if the routing changed and the audio track needs to be re-created.
     */
    fun applyBitPerfect(enabled: Boolean, sampleRate: Int, attrs: android.media.AudioAttributes): Boolean {
        val dev = _device.value
        if (!enabled) {
            val had = clearBitPerfect(attrs)
            _bitPerfectStatus.value = "Off"
            return had
        }
        if (Build.VERSION.SDK_INT < 34) {
            _bitPerfectStatus.value = "Needs Android 14 or newer"
            return false
        }
        val info = dev.info
        if (dev.kind != OutputDevice.Kind.USB || info == null) {
            val had = clearBitPerfect(attrs)
            _bitPerfectStatus.value = "Waiting for a USB DAC"
            return had
        }
        if (sampleRate <= 0) {
            _bitPerfectStatus.value = "Waiting for playback"
            return false
        }
        if (bitPerfectDevice?.id == info.id && bitPerfectRate == sampleRate) return false

        val candidates = am.getSupportedMixerAttributes(info).filter {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                it.format.sampleRate == sampleRate &&
                it.format.encoding == AudioFormat.ENCODING_PCM_16BIT &&
                it.format.channelMask == AudioFormat.CHANNEL_OUT_STEREO
        }
        val pick = candidates.firstOrNull()
        if (pick == null) {
            _bitPerfectStatus.value = "This DAC/phone doesn't offer bit-perfect at ${"%.1f".format(sampleRate / 1000.0)} kHz / 16-bit — using the normal mixer"
            return clearBitPerfect(attrs)
        }
        val ok = runCatching { am.setPreferredMixerAttributes(attrs, info, pick) }.getOrDefault(false)
        return if (ok) {
            bitPerfectDevice = info
            bitPerfectRate = sampleRate
            _bitPerfectStatus.value = "Active — ${"%.1f".format(sampleRate / 1000.0)} kHz / 16-bit to ${dev.name}"
            true
        } else {
            _bitPerfectStatus.value = "Android refused bit-perfect mode for this DAC"
            false
        }
    }

    private fun clearBitPerfect(attrs: android.media.AudioAttributes): Boolean {
        val d = bitPerfectDevice ?: return false
        if (Build.VERSION.SDK_INT >= 34) runCatching { am.clearPreferredMixerAttributes(attrs, d) }
        bitPerfectDevice = null
        bitPerfectRate = 0
        return true
    }
}
