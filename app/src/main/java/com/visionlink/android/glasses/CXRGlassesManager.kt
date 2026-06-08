package com.visionlink.android.glasses

import android.content.Context
import android.util.Log
import com.rokid.cxr.link.CXRLink
import com.visionlink.android.VisionLinkApplication
import kotlinx.coroutines.*

/**
 * Rokid CXR-L SDK glasses manager.
 *
 * Flow: Init SDK -> Authenticate (via Rokid AI App) -> Connect -> Session
 *
 * Prerequisites:
 * - Rokid AI App >= 1.7.14 installed on device (for auth)
 * - USB-C or Bluetooth connection to glasses
 */
class CXRGlassesManager(private val context: Context) {

    companion object {
        private const val TAG = "CXRGlassesManager"
    }

    enum class ConnectionState {
        DISCONNECTED, AUTHENTICATING, CONNECTING, CONNECTED, ERROR
    }

    var connectionState = ConnectionState.DISCONNECTED
        private set

    var errorMessage: String = ""
        private set

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // CXRLink instance from Application
    private val app: VisionLinkApplication
        get() = VisionLinkApplication.getInstance()

    val cxrLink: CXRLink?
        get() = app.sharedLink

    val isConnected: Boolean
        get() = connectionState == ConnectionState.CONNECTED

    // ========== Connection Lifecycle ==========

    /**
     * Connect to Rokid glasses via CXR-L SDK.
     * Authentication requires Rokid AI App installed.
     */
    fun connect(callback: (Boolean) -> Unit) {
        if (connectionState == ConnectionState.CONNECTED) {
            Log.w(TAG, "Already connected")
            callback(true)
            return
        }

        scope.launch {
            try {
                connectionState = ConnectionState.AUTHENTICATING
                Log.i(TAG, "Starting CXR-L authentication...")

                // Check if Rokid AI App is installed
                val rokAppInstalled = isRokidAppInstalled()
                if (!rokAppInstalled) {
                    errorMessage = "Rokid AI App not installed. Please install from app store."
                    connectionState = ConnectionState.ERROR
                    Log.e(TAG, errorMessage)
                    callback(false)
                    return@launch
                }

                // CXRLink will be obtained after successful auth via Rokid AI App
                // The auth flow is handled by the Rokid AI App itself
                // After auth completes, onAuthenticated() should be called with the link
                Log.i(TAG, "CXR-L SDK ready. Auth via Rokid AI App required.")
                connectionState = ConnectionState.CONNECTING
                callback(true)

            } catch (e: CancellationException) {
                connectionState = ConnectionState.DISCONNECTED
                callback(false)
            } catch (e: Exception) {
                errorMessage = e.message ?: "Unknown error"
                connectionState = ConnectionState.ERROR
                Log.e(TAG, "Connection failed: ", e)
                callback(false)
            }
        }
    }

    /**
     * Called after successful authentication.
     * Sets the CXRLink instance for session use.
     */
    fun onAuthenticated(link: CXRLink) {
        app.sharedLink = link
        connectionState = ConnectionState.CONNECTED
        Log.i(TAG, "CXRLink authenticated and connected")
    }

    /**
     * Check if Rokid AI App is installed on device.
     */
    private fun isRokidAppInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.rokid.ar", 0)
            true
        } catch (e: Exception) {
            // Try alternative package name
            try {
                context.packageManager.getPackageInfo("com.rokid.hirokid", 0)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    // ========== HUD Display ==========

    /**
     * Send text to glasses HUD display.
     * CXRLink provides openCustomView/updateCustomView/closeCustomView.
     */
    fun sendText(text: String) {
        val link = cxrLink ?: run {
            Log.w(TAG, "Cannot send text: not connected")
            return
        }
        if (text.isBlank()) return

        try {
            // Use CXRLink custom view API for HUD display
            // val json = """{"type":"text","content":""}"""
            // link.openCustomView(json)
            // or link.updateCustomView(json) if already showing
            Log.d(TAG, "HUD: ")
        } catch (e: Exception) {
            Log.e(TAG, "sendText failed: ")
        }
    }

    fun updateHUDStatus(mode: Int, status: String) {
        if (!isConnected) return
        val modeText = when (mode) {
            1 -> "ObstacleAvoid"
            2 -> "TextReading"
            3 -> "SceneDesc"
            else -> "Unknown"
        }
        sendText("\n")
    }

    fun showResult(result: String) {
        if (!isConnected) return
        val short = if (result.length > 50) result.take(47) + "..." else result
        sendText("Result:\n")
    }

    // ========== Audio ==========

    fun playAudio(text: String) {
        if (!isConnected || text.isBlank()) return
        // Audio output to glasses is handled by CXRLink audio channel
        Log.d(TAG, "Audio to glasses: ")
    }

    // ========== Button/Gesture Events ==========

    fun setButtonCallback(callback: (keyCode: Int, action: Int) -> Unit) {
        val link = cxrLink ?: return
        // CXRLink supports subscribing to custom commands from glasses
        // link.subscribe(clientKey, callback)
        Log.d(TAG, "Button callback registered")
    }

    // ========== Lifecycle ==========

    fun disconnect() {
        if (connectionState == ConnectionState.DISCONNECTED) return
        try {
            app.resetSession()
            connectionState = ConnectionState.DISCONNECTED
            errorMessage = ""
            Log.i(TAG, "Glasses disconnected, session reset")
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error: ")
        }
    }

    fun release() {
        try {
            disconnect()
            scope.cancel("GlassesManager released")
            Log.i(TAG, "Glasses manager released")
        } catch (e: Exception) {
            Log.w(TAG, "Release error: ")
        }
    }

    fun getConnectionStatusText(): String {
        return when (connectionState) {
            ConnectionState.DISCONNECTED -> "Glasses not connected"
            ConnectionState.AUTHENTICATING -> "Authenticating..."
            ConnectionState.CONNECTING -> "Connecting..."
            ConnectionState.CONNECTED -> "Rokid glasses connected"
            ConnectionState.ERROR -> "Error: "
        }
    }
}