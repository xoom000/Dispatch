package dev.digitalgnosis.dispatch.config

/**
 * Centralized Tailscale network configuration for Dispatch.
 *
 * All services communicate over the Tailscale mesh network.
 * Never touches the public internet (except FCM push).
 */
object TailscaleConfig {

    private const val DG_CORE_IP = "100.83.30.70"
    private const val OASIS_IP = "100.80.140.52"
    private const val POP_OS_IP = "100.122.241.82"

    const val ROUTE33_SERVER = "http://$DG_CORE_IP:8085"

    /**
     * Kokoro TTS GPU server — single WAV endpoint.
     * Returns one complete WAV file (44-byte header + PCM data).
     * Docker container on 0.0.0.0:8300, socat bridges 8400 on Tailscale IP.
     */
    const val TTS_SERVER = "http://$OASIS_IP:8400/api/tts"

    /**
     * File Bridge server on pop-os.
     * Handles file staging (download) and upload (to department cmail inboxes).
     * Runs as systemd service on port 8600.
     */
    const val FILE_BRIDGE_SERVER = "http://$POP_OS_IP:8600"

    /**
     * Living Sandbox — auto-generated architecture graph.
     * React frontend served by File Bridge at /sandbox/ path.
     */
    const val SANDBOX_URL = "http://$POP_OS_IP:8600/sandbox/"
}
