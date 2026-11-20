package com.example.companion.audio.hfp

/** Three-valued answer. UNKNOWN is a legitimate result and is never rounded up to YES. */
enum class Tri { YES, NO, UNKNOWN }

enum class Support { SUPPORTED, PARTIALLY_SUPPORTED, BLOCKED }

/** Bluetooth SIG 16-bit service classes, expanded. Used only to READ what a bonded device advertises. */
object HfpUuids {
    const val HFP_HF = "0000111e-0000-1000-8000-00805f9b34fb" // Hands-Free unit (the role we would need on the Main Phone)
    const val HFP_AG = "0000111f-0000-1000-8000-00805f9b34fb" // Hands-Free Audio Gateway (what phones implement)
    const val HSP_HS = "00001108-0000-1000-8000-00805f9b34fb"
    const val HSP_AG = "00001112-0000-1000-8000-00805f9b34fb"
}

/** A bonded device and the (cached) SDP UUIDs the platform holds for it. [uuids] == null: nothing cached / not readable. */
data class BondedPeer(
    val name: String,
    val address: String,
    val isSelectedPeer: Boolean,
    val uuids: List<String>?,
    val connectedToHeadsetService: Boolean?   // null = could not be determined
) {
    val offersHf get() = uuids?.contains(HfpUuids.HFP_HF) == true
    val offersAg get() = uuids?.contains(HfpUuids.HFP_AG) == true
}

/** Raw observations. Everything here was read through public SDK calls; nothing is inferred. */
data class HfpFacts(
    val sdkInt: Int,
    val release: String,
    val manufacturer: String,
    val model: String,
    val role: String,                 // "SIM Phone" / "Main Phone" / "-"
    val bluetoothSupported: Boolean,
    val bluetoothEnabled: Boolean,
    val connectPermission: Boolean,
    val telephonyFeature: Boolean,
    /** Did getProfileProxy(HEADSET) deliver a BluetoothHeadset? YES = obtained, NO = refused/timeout, UNKNOWN = not tried. */
    val headsetProxy: Tri,
    val headsetProxyDetail: String,
    val headsetProfileState: String,  // adapter.getProfileConnectionState(HEADSET)
    val a2dpProfileState: String,
    /** Read-only check. We never request this permission and a normal app cannot be granted it. */
    val bluetoothPrivilegedHeld: Boolean,
    val bondedPeers: List<BondedPeer>,
    val appConnection: String
)

/** The capability answer. See [HfpAssessor] for how each field is derived. */
data class HfpCapability(
    val hfpApisAvailable: Tri,
    val hfRolePossible: Tri,
    val agRolePossible: Tri,
    val audioRoutingPossible: Tri,
    val callAudioIntegrationPossible: Tri,
    val cellularCallAudioViaHfp: Tri,
    val requiresPrivilegedAccess: Boolean,
    val manufacturerDependent: Boolean,
    val normalAppSupport: Support,
    val mainLimitation: String,
    val reasons: List<String>
)

/**
 * Pure function: facts -> verdict. No Android calls, so it is unit-testable.
 *
 * Rules (documented platform design, NOT device-verified):
 *  - "HFP APIs available" means the PUBLIC BluetoothHeadset proxy was actually obtained. That class controls the
 *    phone's Audio-Gateway service. Its existence says nothing about the Hands-Free role.
 *  - The Hands-Free (HF) role is only reachable through BluetoothHeadsetClient, a system API behind
 *    BLUETOOTH_PRIVILEGED. This project excludes hidden APIs / reflection / root, so HF is NO for this app.
 *  - Audio routing / call integration / cellular audio via HFP are NO because both need the Main Phone to BE the HF
 *    endpoint (SCO link + AT layer), which an app cannot create.
 */
object HfpAssessor {
    fun assess(f: HfpFacts): HfpCapability {
        val apis = when {
            !f.bluetoothSupported -> Tri.NO
            f.headsetProxy == Tri.YES -> Tri.YES
            f.headsetProxy == Tri.NO -> Tri.NO
            else -> Tri.UNKNOWN
        }
        // AG is implemented by the platform itself on telephony devices; an app can neither add nor control it.
        val ag = when {
            !f.bluetoothSupported || !f.telephonyFeature -> Tri.NO
            apis == Tri.YES -> Tri.YES
            else -> Tri.UNKNOWN
        }

        val reasons = ArrayList<String>()
        reasons += "Phones implement HFP only as Audio Gateway. The public BluetoothHeadset class controls that AG service; it is not a Hands-Free client."
        reasons += "The Hands-Free role exists only as BluetoothHeadsetClient, a system API guarded by BLUETOOTH_PRIVILEGED " +
            "(held by this app: ${if (f.bluetoothPrivilegedHeld) "YES" else "NO"}; normal apps cannot be granted it). " +
            "Most phone ROMs do not even include the HF service (ROM-dependent). Using it needs hidden APIs, reflection or a custom ROM, which this project excludes."
        reasons += "No public API opens the SCO audio link, registers an HFP service record, or exchanges HFP AT commands. " +
            "An RFCOMM socket with an HFP UUID would not be HFP (no service-level connection, no SCO audio) and is not used."
        reasons += "Public routing APIs (AudioManager communication device / Bluetooth SCO, Telecom ROUTE_BLUETOOTH) route THIS phone's own call audio to a headset it is AG for. They cannot turn the Main Phone into that headset."
        reasons += "Telecom/InCallService on the Main Phone only sees calls of that phone; there is no API to attach a call owned by another phone's AG."
        if (f.bluetoothPrivilegedHeld) reasons += "BLUETOOTH_PRIVILEGED is unexpectedly held; it is still not used (that would require non-public APIs)."

        val peer = f.bondedPeers.firstOrNull { it.isSelectedPeer }
        if (peer != null) when {
            peer.connectedToHeadsetService == true ->
                reasons += "Selected peer is currently connected to this phone's HFP/HSP service. Unexpected for a phone; it would mean the peer ROM offers an HF stack, but this app still cannot use it."
            peer.offersHf ->
                reasons += "Cached SDP for the selected peer lists a Hands-Free unit record (0x111E). Informational only: a record does not prove a working HFP stack, and apps cannot use it."
            peer.uuids != null && peer.offersAg ->
                reasons += "Cached SDP for the selected peer lists only the Audio Gateway side (0x111F), consistent with a normal phone stack."
        }
        if (!f.telephonyFeature) reasons += "This device reports no telephony feature."
        if (!f.bluetoothSupported) reasons += "Bluetooth is not supported on this device."

        return HfpCapability(
            hfpApisAvailable = apis,
            hfRolePossible = Tri.NO,
            agRolePossible = ag,
            audioRoutingPossible = Tri.NO,
            callAudioIntegrationPossible = Tri.NO,
            cellularCallAudioViaHfp = Tri.NO,
            requiresPrivilegedAccess = true,
            manufacturerDependent = true,
            normalAppSupport = Support.BLOCKED,
            mainLimitation = "A normal third-party app cannot be an HFP Hands-Free unit: the HF client API is a privileged system API, " +
                "and no public API creates the HFP service connection or SCO call-audio link. The Main Phone therefore cannot present itself to the SIM Phone as a headset.",
            reasons = reasons
        )
    }
}
