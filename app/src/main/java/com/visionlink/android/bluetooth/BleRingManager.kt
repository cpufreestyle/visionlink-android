package com.visionlink.android.bluetooth

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale
import import java.util.UUID

/**
 * 蓝牙指环管理器 (v4.5)
 * 
 * 支持主流 BLE 智能指环：
 * - Tap Strap (手势控制)
 * - Xen S1 Ring
 * - Fin Ring
 * - 国产平价指环 (通用 BLE)
 * 
 * 功能：
 * - 自动扫描附近指环设备
 * - 连接/断开管理
 * - 手势/按键事件回调
 */
class BleRingManager(
    private val context: Context,
    private val onEvent: (RingEvent) -> Unit
) {
    
    companion object {
        private const val TAG = "BleRingManager"
        
        // 常见指环设备名称（用于识别）
        val KNOWN_RING_NAMES = listOf(
            "tap", "tap strap", "xen", "fin", "ring", "smart ring",
            "myring", "orb", "go", "senser", "finger", "nfc ring"
        )
        
        // BLE 扫描超时
        private const val SCAN_TIMEOUT_MS = 15000L
        
        // 重连间隔
        private const val RECONNECT_INTERVAL_MS = 5000L
    }
    
    enum class RingEvent {
        DEVICE_FOUND,       // 发现设备
        DEVICE_CONNECTED,   // 已连接
        DEVICE_DISCONNECTED, // 断开连接
        TAP_DETECTED,       // 点击（单指）
        DOUBLE_TAP,         // 双击
        LONG_PRESS,         // 长按
        SWIPE_UP,           // 上滑
        SWIPE_DOWN,         // 下滑
        GESTURE_DETECTED,   // 手势识别
        BATTERY_LOW,        // 电量低
        UNKNOWN             // 未知事件
    }
    
    enum class ConnectionState {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        CONNECTED,
        RECONNECTING
    }
    
    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }
    
    private var scanner: BluetoothLeScanner? = null
    private var device: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    
    private var state = ConnectionState.DISCONNECTED
    private var deviceName: String? = null
    private var isScanning = false
    private var autoReconnect = true
    
    private val scanHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name?.lowercase() ?: ""
            Log.d(TAG, "Found device: ${result.device.name ?: "Unknown"} (${result.device.address})")
            
            // 检查是否是已知指环设备
            if (KNOWN_RING_NAMES.any { name.contains(it) }) {
                Log.d(TAG, "Ring device detected: ${result.device.name}")
                device = result.device
                deviceName = result.device.name
                onEvent(RingEvent.DEVICE_FOUND)
                
                // 自动停止扫描并连接
                stopScan()
                connect(result.device)
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            state = ConnectionState.DISCONNECTED
            isScanning = false
        }
    }
    
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to ${deviceName ?: "ring"}")
                    state = ConnectionState.CONNECTED
                    autoReconnect = true
                    gatt.discoverServices()
                    onEvent(RingEvent.DEVICE_CONNECTED)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from ${deviceName ?: "ring"}")
                    state = ConnectionState.DISCONNECTED
                    onEvent(RingEvent.DEVICE_DISCONNECTED)
                    
                    // 自动重连
                    if (autoReconnect && device != null) {
                        scheduleReconnect()
                    }
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered")
                // 订阅特征通知（具体 UUID 需根据设备调整）
                subscribeToNotifications(gatt)
            }
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            // 解析指环数据
            val event = parseRingData(value)
            if (event != RingEvent.UNKNOWN) {
                onEvent(event)
            }
        }
    }
    
    init {
        scanner = bluetoothAdapter?.bluetoothLeScanner
    }
    
    /**
     * 检查蓝牙权限
     */
    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * 启用/禁用自动重连
     */
    fun setAutoReconnect(enabled: Boolean) {
        autoReconnect = enabled
        if (!enabled) {
            reconnectHandler.removeCallbacksAndMessages(null)
        }
    }
    
    /**
     * 开始扫描指环设备
     */
    fun startScan() {
        if (!hasPermissions()) {
            Log.w(TAG, "Bluetooth permissions not granted")
            return
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth is not enabled")
            return
        }
        
        if (isScanning) return
        
        state = ConnectionState.SCANNING
        isScanning = true
        
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        try {
            scanner?.startScan(null, settings, scanCallback)
            Log.d(TAG, "Started scanning for ring devices")
            
            // 超时停止
            scanHandler.postDelayed({
                if (isScanning) {
                    stopScan()
                    Log.d(TAG, "Scan timeout, no ring device found")
                }
            }, SCAN_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Scan error: ${e.message}")
            state = ConnectionState.DISCONNECTED
            isScanning = false
        }
    }
    
    /**
     * 停止扫描
     */
    fun stopScan() {
        if (!isScanning) return
        
        try {
            scanner?.stopScan(scanCallback)
            scanHandler.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            Log.w(TAG, "Stop scan error: ${e.message}")
        }
        
        isScanning = false
        if (state == ConnectionState.SCANNING) {
            state = ConnectionState.DISCONNECTED
        }
        Log.d(TAG, "Stopped scanning")
    }
    
    /**
     * 连接设备
     */
    fun connect(device: BluetoothDevice) {
        state = ConnectionState.CONNECTING
        
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            Log.d(TAG, "Connecting to ${device.name ?: device.address}")
        } catch (e: Exception) {
            Log.e(TAG, "Connection error: ${e.message}")
            state = ConnectionState.DISCONNECTED
        }
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        autoReconnect = false
        reconnectHandler.removeCallbacksAndMessages(null)
        
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Disconnect error: ${e.message}")
        }
        
        gatt = null
        device = null
        state = ConnectionState.DISCONNECTED
        Log.d(TAG, "Disconnected")
    }
    
    /**
     * 订阅特征通知（简化版，实际需根据设备调整）
     */
    private fun subscribeToNotifications(gatt: BluetoothGatt) {
        // 常见的指环服务 UUID（需要根据实际设备调整）
        val serviceUuid = ParcelUuid.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val charUuid = ParcelUuid.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        
        val service = gatt.getService(serviceUuid.uuid)
        val characteristic = service?.getCharacteristic(charUuid.uuid)
        
        if (characteristic != null) {
            gatt.setCharacteristicNotification(characteristic, true)
            Log.d(TAG, "Subscribed to ring notifications")
        }
    }
    
    /**
     * 解析指环数据
     */
    private fun parseRingData(data: ByteArray): RingEvent {
        if (data.isEmpty()) return RingEvent.UNKNOWN
        
        // 简化解析（实际需根据指环协议调整）
        return when {
            data.size >= 2 && data[0].toInt() == 0x01 -> {
                when (data[1].toInt()) {
                    0x01 -> RingEvent.TAP_DETECTED
                    0x02 -> RingEvent.DOUBLE_TAP
                    0x03 -> RingEvent.LONG_PRESS
                    0x04 -> RingEvent.SWIPE_UP
                    0x05 -> RingEvent.SWIPE_DOWN
                    else -> RingEvent.GESTURE_DETECTED
                }
            }
            data.size >= 1 && data[0].toInt() == 0xFE -> RingEvent.BATTERY_LOW
            else -> RingEvent.UNKNOWN
        }
    }
    
    /**
     * 调度重连
     */
    private fun scheduleReconnect() {
        state = ConnectionState.RECONNECTING
        reconnectHandler.postDelayed({
            if (autoReconnect && state == ConnectionState.RECONNECTING && device != null) {
                Log.d(TAG, "Attempting to reconnect...")
                connect(device!!)
            }
        }, RECONNECT_INTERVAL_MS)
    }
    
    /**
     * 获取当前状态
     */
    fun getState(): ConnectionState = state
    
    /**
     * 获取已连接设备名称
     */
    fun getDeviceName(): String? = deviceName
    
    /**
     * 是否已连接
     */
    fun isConnected(): Boolean = state == ConnectionState.CONNECTED
    
    /**
     * 是否正在扫描
     */
    fun isScanning(): Boolean = isScanning
    
    /**
     * 释放资源
     */
    fun release() {
        autoReconnect = false
        scanHandler.removeCallbacksAndMessages(null)
        reconnectHandler.removeCallbacksAndMessages(null)
        disconnect()
        Log.d(TAG, "BleRingManager released")
    }
}