package com.example.companion.audio.hfp

/** Plain-text diagnostic. Pure; shown on screen and copied as-is. */
object HfpReport {
    private fun yn(b: Boolean) = if (b) "YES" else "NO"

    fun render(f: HfpFacts, c: HfpCapability, d: CallAudioDecision): String {
        val sb = StringBuilder()
        fun line(s: String = "") { sb.append(s).append('\n') }

        line("HFP CAPABILITY")
        line("HFP APIs available: ${c.hfpApisAvailable}")
        line("HF role possible: ${c.hfRolePossible}")
        line("AG role possible: ${c.agRolePossible}")
        line("Audio routing possible: ${c.audioRoutingPossible}")
        line("Call audio integration possible: ${c.callAudioIntegrationPossible}")
        line("Requires privileged/system access: ${yn(c.requiresPrivilegedAccess)}")
        line("Manufacturer dependent: ${yn(c.manufacturerDependent)}")
        line()
        line("Bluetooth HFP")
        line("HFP supported: ${c.hfpApisAvailable}   (public BluetoothHeadset = AG-side API)")
        line("Hands-Free role: ${c.hfRolePossible}")
        line("Audio Gateway role: ${c.agRolePossible}   (system-provided, not controllable by an app)")
        line("Bluetooth audio routing: ${c.audioRoutingPossible}")
        line()
        line("Cellular call audio through HFP: ${c.cellularCallAudioViaHfp}")
        line("Reason: ${c.mainLimitation}")
        c.reasons.forEach { line("  - $it") }
        line()
        line("Device")
        line("Android: ${f.release} (API ${f.sdkInt})")
        line("Manufacturer: ${f.manufacturer}")
        line("Model: ${f.model}")
        line("App role: ${f.role}")
        line("Telephony feature: ${yn(f.telephonyFeature)}")
        line()
        line("Bluetooth profiles (public APIs)")
        line("Bluetooth supported/enabled: ${yn(f.bluetoothSupported)}/${yn(f.bluetoothEnabled)}  BLUETOOTH_CONNECT: ${yn(f.connectPermission)}")
        line("BluetoothHeadset (HFP/HSP, AG side): proxy=${f.headsetProxy} (${f.headsetProxyDetail}), connection=${f.headsetProfileState}")
        line("A2DP: connection=${f.a2dpProfileState}")
        line("HF client profile (BluetoothHeadsetClient): not probed - system API; privileged permission held: ${yn(f.bluetoothPrivilegedHeld)}")
        line()
        line("Bonded devices (cached SDP; empty = never fetched)")
        if (f.bondedPeers.isEmpty()) line("  none / not readable")
        f.bondedPeers.forEach { p ->
            val u = when {
                p.uuids == null -> "no UUIDs cached"
                else -> "AG(111F)=${yn(p.offersAg)} HF(111E)=${yn(p.offersHf)} HSP-AG(1112)=${yn(p.uuids.contains(HfpUuids.HSP_AG))}"
            }
            val hs = when (p.connectedToHeadsetService) { true -> " connected-to-HFP-service"; false -> ""; null -> "" }
            line("  ${if (p.isSelectedPeer) "* " else "  "}${p.name} ${p.address}: $u$hs")
        }
        line()
        line("App connection (Stage 2-5 RFCOMM): ${f.appConnection}")
        line()
        line("Call audio path decision (not wired to any audio start/stop)")
        line("Path: ${d.path}   Audio session wanted: ${yn(d.wantAudio)}")
        line("Why: ${d.reason}")
        line()
        line("HFP RESULT")
        line("SIM Phone as Audio Gateway: ${c.agRolePossible} (system-provided only)")
        line("Main Phone as Hands-Free: ${c.hfRolePossible}")
        line("Two-way cellular call audio: ${c.cellularCallAudioViaHfp} via HFP")
        line("Normal third-party app: ${c.normalAppSupport}")
        line()
        line("Conclusions come from documented Android platform design and the public-API probes above.")
        line("They were NOT verified on a physical device unless this screen is a real run on one.")
        return sb.toString()
    }
}
