package com.example.companion.calls

import com.example.companion.protocol.Message

/**
 * Bridge between the Bluetooth message layer and call logic.
 * Host (SIM phone): [HostCallController] executes call commands via [CallManager].
 * Client (main phone): [ClientCallController] mirrors call state and sends commands.
 */
interface CallController { fun onMessage(message: Message) }
