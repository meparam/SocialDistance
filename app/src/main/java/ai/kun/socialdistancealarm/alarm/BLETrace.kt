package ai.kun.socialdistancealarm.alarm

import ai.kun.socialdistancealarm.dao.DeviceRepository
import ai.kun.socialdistancealarm.util.Constants
import ai.kun.socialdistancealarm.util.Constants.PREF_FILE_NAME
import ai.kun.socialdistancealarm.util.Constants.PREF_IS_PAUSED
import ai.kun.socialdistancealarm.util.Constants.PREF_UNIQUE_ID
import ai.kun.socialdistancealarm.util.Constants.RANGE_ENVIRONMENTAL
import android.app.AlarmManager
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.content.Context
import android.content.SharedPreferences
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.MutableLiveData
import java.util.*
import kotlin.math.pow


object BLETrace {
    private val mBleServer : BLEServer = BLEServer()
    private val mBleClient : BLEClient = BLEClient()
    private val TAG = "BLETrace"

    private var isInit = false
    private lateinit var context : Context
    lateinit var bluetoothGattServer : BluetoothGattServer
    lateinit var bluetoothManager : BluetoothManager
    lateinit var bluetoothLeScanner : BluetoothLeScanner
    lateinit var bluetoothLeAdvertiser : BluetoothLeAdvertiser

    var isBackground : Boolean = true
    val isStarted: MutableLiveData<Boolean> = MutableLiveData(false)
    var isPaused : Boolean
        get() {
            synchronized(this) {
                val sharedPrefs = context.getSharedPreferences(
                    PREF_FILE_NAME, Context.MODE_PRIVATE
                )
                return sharedPrefs.getBoolean(PREF_IS_PAUSED, false)
            }
        }
        set(value) {
            synchronized(this) {
                val sharedPrefs = context.getSharedPreferences(
                    PREF_FILE_NAME, Context.MODE_PRIVATE
                )
                val editor: SharedPreferences.Editor = sharedPrefs.edit()
                editor.putBoolean(PREF_IS_PAUSED, value)
                editor.apply()

            }
            if (value) {
                stop()
            } else {
                start(isBackground)
            }
        }

    public var uniqueId : String?
        get() {
            synchronized(this) {
                val sharedPrefs = context.getSharedPreferences(
                    PREF_FILE_NAME, Context.MODE_PRIVATE
                )
                return sharedPrefs.getString(PREF_UNIQUE_ID, null)
            }
        }
        set(value) {
            synchronized(this) {
                val sharedPrefs = context.getSharedPreferences(
                    PREF_FILE_NAME, Context.MODE_PRIVATE
                )
                val editor: SharedPreferences.Editor = sharedPrefs.edit()
                if (value != null) {
                    editor.putString(PREF_UNIQUE_ID, value)
                    editor.commit()
                    init(context)
                    deviceNameServiceUuid = UUID.nameUUIDFromBytes(value.toByteArray())
                } else {
                    editor.remove(PREF_UNIQUE_ID)
                    editor.commit()
                    stop()
                }
            }
        }

    public var deviceUuid : String? = null
        get() = if (uniqueId == null) {
            null
        } else {
            UUID.nameUUIDFromBytes(uniqueId?.toByteArray()).toString()
        }

    lateinit var deviceNameServiceUuid: UUID

    fun start(startingBackground: Boolean) {
        synchronized(this) {
            if (isStarted.value!!) stop()
            if (startingBackground) startBackground() else startForeground()
        }
    }

    fun stop() {
        synchronized(this) {
            if (isBackground) stopBackground() else stopForeground()
        }
    }

    /*
     * The following methods deal with the problem that your intent that you use to stop
     * an alarm manager has to be identical to the intent that you used to stop it.  So
     * for that to be true you have to cancel the alarm with the correct argument for
     * background vs foreground, and thus a bunch of code...
     */
    private fun startBackground() {
        if (isEnabled() && !isPaused) {
            isBackground = true
            isStarted.postValue(true)
            mBleServer.enable(Constants.REBROADCAST_PERIOD, context)
            mBleClient.enable(Constants.BACKGROUND_TRACE_INTERVAL, context)
        } else {
            isStarted.postValue(false)
        }
    }

    private fun startForeground() {
        if (isEnabled() && !isPaused) {
            isBackground = false
            isStarted.postValue(true)
            mBleServer.enable(Constants.REBROADCAST_PERIOD, context)
            mBleClient.enable(Constants.FOREGROUND_TRACE_INTERVAL, context)
        } else {
            isStarted.postValue(false)
        }
    }

    private fun stopBackground() {
        if (isEnabled()) {
            mBleServer.disable(Constants.REBROADCAST_PERIOD, context)
            mBleClient.disable(Constants.BACKGROUND_TRACE_INTERVAL, context)
        }
        isStarted.postValue(false)
    }

    private fun stopForeground() {
        if (isEnabled()) {
            mBleServer.disable(Constants.REBROADCAST_PERIOD, context)
            mBleClient.disable(Constants.FOREGROUND_TRACE_INTERVAL, context)
        }
        isStarted.postValue(false)
    }

    fun isEnabled() : Boolean {
        if (uniqueId == null || bluetoothManager.adapter == null || !bluetoothManager.adapter.isEnabled()) return false

        if (!isInit) init(context) // If bluetooth was off we need to complete the init

        return isInit  // && isLocationEnabled() Location doesn't need to be on
    }

    fun init(applicationContext: Context) {
        synchronized(this) {
            context = applicationContext
            DeviceRepository.init(applicationContext)

            if (!isInit && uniqueId != null) {
                bluetoothManager =
                    context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                if (bluetoothManager.adapter == null || !bluetoothManager.adapter.isEnabled()) return // bail if bluetooth isn't on
                bluetoothLeScanner = bluetoothManager.adapter.bluetoothLeScanner
                bluetoothGattServer = bluetoothManager.openGattServer(context, GattServerCallback)
                bluetoothLeAdvertiser = bluetoothManager.adapter.bluetoothLeAdvertiser

                deviceNameServiceUuid = UUID.nameUUIDFromBytes(uniqueId?.toByteArray())
                isInit = true
            }
        }
    }

    fun getAlarmManager(applicationContext: Context): AlarmManager {
        return applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    fun calculateDistance(rssi: Int, txPower: Int): Float? {
        if (txPower == -1) return null
        return 10f.pow(((0 - txPower - rssi).toFloat()).div(10 * RANGE_ENVIRONMENTAL)) * 100

    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(locationManager)
    }
}