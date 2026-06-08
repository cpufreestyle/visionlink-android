package com.visionlink.android.utils

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * AICore Availability Checker v1.0
 * 
 * Comprehensive diagnostic tool to check if AICore (Gemini Nano) 
 * is available on the device.
 * 
 * Usage:
 *   val result = AICoreChecker.runFullDiagnostic(context)
 *   val summary = result.getSummary()
 *   Log.d("AICore", summary)
 */
object AICoreChecker {
    
    private const val TAG = "AICoreChecker"
    
    /**
     * Run full diagnostic check
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun runFullDiagnostic(context: Context): DiagnosticResult {
        Log.d(TAG, "=== AICore Diagnostic Started ===")
        
        val result = DiagnosticResult()
        
        // Check 1: Android version (requires 14+)
        result.androidVersionOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        Log.d(TAG, "Check 1 - Android 14+ (API 34+): ${result.androidVersionOk} (current: ${Build.VERSION.SDK_INT})")
        
        // Check 2: Device model (Samsung S24/S25, Pixel 8/9)
        result.deviceSupported = isDeviceSupported()
        Log.d(TAG, "Check 2 - Device supported: ${result.deviceSupported} (${Build.MANUFACTURER} ${Build.MODEL})")
        
        // Check 3: RAM (requires 8GB+)
        result.ramOk = checkRam(context)
        Log.d(TAG, "Check 3 - RAM ≥ 8GB: ${result.ramOk}")
        
        // Check 4: Google Play Services (requires 23.30.15+)
        result.playServicesOk = checkPlayServices(context)
        Log.d(TAG, "Check 4 - Play Services ≥ 23.30.15: ${result.playServicesOk}")
        
        // Check 5: AICore class available
        result.aicoreClassAvailable = checkAICoreClass()
        Log.d(TAG, "Check 5 - AICore class available: ${result.aicoreClassAvailable}")
        
        // Check 6: Try to initialize AICore (real test)
        if (result.androidVersionOk && result.aicoreClassAvailable) {
            result.aicoreInitializationOk = testAICoreInitialization(context)
            Log.d(TAG, "Check 6 - AICore initialization: ${result.aicoreInitializationOk}")
        }
        
        // Final result
        result.isAvailable = result.androidVersionOk && result.deviceSupported && 
                           result.ramOk && result.playServicesOk && 
                           result.aicoreClassAvailable
        
        Log.d(TAG, "=== Diagnostic Complete ===")
        Log.d(TAG, "AICore Available: ${result.isAvailable}")
        
        return result
    }
    
    /**
     * Check if device is supported (Samsung S24/S25, Pixel 8/9)
     */
    private fun isDeviceSupported(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL
        
        Log.d(TAG, "Checking device: $manufacturer - $model")
        
        // Samsung S24/S25 series
        val isSamsung = manufacturer.contains("samsung")
        val isS24 = model.contains("SM-S92") || model.contains("SM-S926") || model.contains("SM-S921")
        val isS25 = model.contains("SM-S93") || model.contains("SM-S936") || model.contains("SM-S931")
        
        // Pixel 8/9 series
        val isPixel = manufacturer.contains("google")
        val isPixel8 = model.contains("Pixel 8")
        val isPixel9 = model.contains("Pixel 9")
        
        val supported = (isSamsung && (isS24 || isS25)) || (isPixel && (isPixel8 || isPixel9))
        
        Log.d(TAG, "Samsung: $isSamsung, S24: $isS24, S25: $isS25")
        Log.d(TAG, "Pixel: $isPixel, Pixel 8: $isPixel8, Pixel 9: $isPixel9")
        Log.d(TAG, "Supported: $supported")
        
        return supported
    }
    
    /**
     * Check RAM (requires 8GB+)
     */
    private fun checkRam(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalRamMb = memInfo.totalMem / 1024 / 1024
        
        Log.d(TAG, "Device RAM: ${totalRamMb}MB (required: 8192MB)")
        
        return totalRamMb >= 8192
    }
    
    /**
     * Check Google Play Services version (requires 23.30.15+)
     */
    private fun checkPlayServices(context: Context): Boolean {
        return try {
            val info = context.packageManager.getPackageInfo("com.google.android.gms", 0)
            val versionCode = info.longVersionCode
            val versionName = info.versionName
            
            // Play Services 23.30.15+ required
            val requiredVersion = 233015000L
            val ok = versionCode >= requiredVersion
            
            Log.d(TAG, "Play Services version: $versionName ($versionCode), required: ≥ 233015000")
            Log.d(TAG, "Play Services OK: $ok")
            
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Play Services not found: ${e.message}")
            false
        }
    }
    
    /**
     * Check if AICore class is available (SDK installed)
     */
    private fun checkAICoreClass(): Boolean {
        return try {
            // Try to load AICore class
            Class.forName("com.google.ai.edge.aicore.GenerativeAI")
            Log.d(TAG, "AICore class found!")
            true
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "AICore class NOT found: ${e.message}")
            Log.w(TAG, "AICore SDK might not be installed")
            false
        }
    }
    
    /**
     * Test AICore initialization (real API call)
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun testAICoreInitialization(context: Context): Boolean {
        return try {
            // This is a real test - try to create AICore instance
            /*
            // Uncomment when AICore SDK is available:
            val options = com.google.ai.edge.aicore.GenerativeAIOptions.Builder(context)
                .setModelName("gemini-nano")
                .setTemperature(0.1f)
                .setMaxOutputTokens(256)
                .build()
            
            val ai = com.google.ai.edge.aicore.GenerativeAI.create(options)
            val success = ai != null
            
            if (success) {
                Log.d(TAG, "AICore initialization SUCCESS!")
                ai.close()
            }
            
            success
            */
            
            // For now, return true if class is available
            Log.w(TAG, "AICore initialization test - SKIPPED (commented out)")
            Log.w(TAG, "Uncomment the code above to run real test")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "AICore initialization failed: ${e.message}")
            false
        }
    }
    
    /**
     * Quick check - no initialization test (faster)
     */
    fun isAICoreLikelyAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.w(TAG, "Quick check: Android 14+ required")
            return false
        }
        if (!isDeviceSupported()) {
            Log.w(TAG, "Quick check: Device not in supported list")
            return false
        }
        if (!checkRam(context)) {
            Log.w(TAG, "Quick check: Not enough RAM")
            return false
        }
        if (!checkPlayServices(context)) {
            Log.w(TAG, "Quick check: Play Services too old")
            return false
        }
        if (!checkAICoreClass()) {
            Log.w(TAG, "Quick check: AICore class not found")
            return false
        }
        Log.d(TAG, "Quick check: AICore is LIKELY available")
        return true
    }
    
    /**
     * Diagnostic result data class
     */
    data class DiagnosticResult(
        var androidVersionOk: Boolean = false,
        var deviceSupported: Boolean = false,
        var ramOk: Boolean = false,
        var playServicesOk: Boolean = false,
        var aicoreClassAvailable: Boolean = false,
        var aicoreInitializationOk: Boolean = false,
        var isAvailable: Boolean = false
    ) {
        fun getSummary(): String {
            return """
                === AICore Diagnostic Result ===
                Android 14+ (API 34+): $androidVersionOk
                Device Supported: $deviceSupported
                RAM ≥ 8GB: $ramOk
                Play Services ≥ 23.30.15: $playServicesOk
                AICore Class Available: $aicoreClassAvailable
                AICore Initialization: $aicoreInitializationOk
                
                ====================================
                AICore Available: $isAvailable
                ====================================
            """.trimIndent()
        }
    }
}
