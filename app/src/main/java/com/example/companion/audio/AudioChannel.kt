package com.example.companion.audio

/**
 * Legacy placeholder from Stage 1. The real audio layer is audio/transport (Stage 5: AudioTransport, AudioSource,
 * AudioOutput, AudioLink). Stage 7: audio/call/CallAudioManager is the unified entry point; it reports cellular call audio as UNAVAILABLE because no legitimate
 * path exists (Stage 6: HFP blocked; no supported cellular capture/injection API). Stage 4 only diagnoses (audio/diag).
 */
interface AudioChannel { fun start(); fun stop() }
