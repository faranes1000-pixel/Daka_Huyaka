package dji.sampleV5.aircraft.models

import android.util.Log
import androidx.lifecycle.MutableLiveData
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.flightcontroller.*
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Class Description
 *
 * @author Hoker
 * @date 2021/6/18
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
class VirtualStickVM : DJIViewModel() {

    val currentSpeedLevel = MutableLiveData(0.0)
    var useRcStick = MutableLiveData(false)
    val currentVirtualStickStateInfo = MutableLiveData(VirtualStickStateInfo())
    private var isCSCOn = false

    val virtualStickAdvancedParam = MutableLiveData(VirtualStickFlightControlParam()).apply {
        value?.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
        value?.verticalControlMode = VerticalControlMode.VELOCITY
        value?.yawControlMode = YawControlMode.ANGULAR_VELOCITY
        value?.rollPitchControlMode = RollPitchControlMode.ANGLE
    }

    // RC Stick Value
    var stickValue = MutableLiveData(RCStickValue(0, 0, 0, 0))

    // Heartbeat Loop for Virtual Stick
    private var scheduler: ScheduledExecutorService? = null

    init {
        VirtualStickManager.getInstance().init() // Force init
        currentSpeedLevel.value = VirtualStickManager.getInstance().speedLevel
        VirtualStickManager.getInstance().setVirtualStickStateListener(object :
            VirtualStickStateListener {
            override fun onVirtualStickStateUpdate(stickState: VirtualStickState) {
                currentVirtualStickStateInfo.postValue(currentVirtualStickStateInfo.value?.apply {
                    this.state = stickState
                })
                if (stickState.isVirtualStickEnable) {
                    startHeartbeat()
                } else {
                    stopHeartbeat()
                }
            }

            override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
                currentVirtualStickStateInfo.postValue(currentVirtualStickStateInfo.value?.apply {
                    this.reason = reason
                })
            }
        })
    }

    fun enableVirtualStick(callback: CommonCallbacks.CompletionCallback) {
        VirtualStickManager.getInstance().enableVirtualStick(callback)
    }

    fun disableVirtualStick(callback: CommonCallbacks.CompletionCallback) {
        VirtualStickManager.getInstance().disableVirtualStick(callback)
        stopHeartbeat()
    }

    private fun startHeartbeat() {
        if (scheduler == null || scheduler?.isShutdown == true) {
            scheduler = Executors.newSingleThreadScheduledExecutor()
            scheduler?.scheduleAtFixedRate({
                if (currentVirtualStickStateInfo.value?.state?.isVirtualStickEnable == true) {
                    virtualStickAdvancedParam.value?.let {
                        if (isCSCOn) {
                            // Programmatic CSC: Sticks Down-Inward (Mode 2)
                            // Left Stick: Throttle Down (-2), Yaw Right (100)
                            // Right Stick: Pitch Down (-30), Roll Left (-30)
                            it.pitch = -30.0
                            it.roll = -30.0
                            it.yaw = 100.0 // Yaw Right
                            it.verticalThrottle = -2.0
                        } else {
                            // Map UI Stick positions to Velocity values
                            // Stick range is +/- 660, Speed Level scales it
                            it.pitch = (VirtualStickManager.getInstance().rightStick.verticalPosition.toDouble() / 660.0) * currentSpeedLevel.value!! * 10.0
                            it.roll = (VirtualStickManager.getInstance().rightStick.horizontalPosition.toDouble() / 660.0) * currentSpeedLevel.value!! * 10.0
                            it.yaw = (VirtualStickManager.getInstance().leftStick.horizontalPosition.toDouble() / 660.0) * currentSpeedLevel.value!! * 30.0
                            it.verticalThrottle = (VirtualStickManager.getInstance().leftStick.verticalPosition.toDouble() / 660.0) * currentSpeedLevel.value!! * 4.0
                        }
                        
                        val isAdvEnabled = currentVirtualStickStateInfo.value?.state?.isVirtualStickAdvancedModeEnabled ?: false
                        Log.d("VirtualStickLog", "Sending: P=${it.pitch}, R=${it.roll}, Y=${it.yaw}, T=${it.verticalThrottle} | AdvMode=$isAdvEnabled | CSC=$isCSCOn")

                        // Post value back to LiveData so it updates on the screen
                        virtualStickAdvancedParam.postValue(it)
                        
                        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(it)
                    }
                }
            }, 0, 100, TimeUnit.MILLISECONDS) // 10Hz Heartbeat
        }
    }

    fun startMotorsByCSC(callback: CommonCallbacks.CompletionCallback) {
        enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                enableVirtualStickAdvancedMode()
                isCSCOn = true
                Log.d("VirtualStickLog", "CSC START: Sending motor start values...")
                
                // Keep CSC for 2.5 seconds, then reset to neutral
                scheduler?.schedule({
                    isCSCOn = false
                    Log.d("VirtualStickLog", "CSC END: Resetting to neutral")
                }, 2500, TimeUnit.MILLISECONDS)
                
                callback.onSuccess()
            }

            override fun onFailure(error: IDJIError) {
                callback.onFailure(error)
            }
        })
    }

    private fun stopHeartbeat() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    fun setSpeedLevel(speedLevel: Double) {
        VirtualStickManager.getInstance().speedLevel = speedLevel
        currentSpeedLevel.value = speedLevel
    }

    fun setLeftPosition(horizontal: Int, vertical: Int) {
        VirtualStickManager.getInstance().leftStick.horizontalPosition = horizontal
        VirtualStickManager.getInstance().leftStick.verticalPosition = vertical
    }

    fun setRightPosition(horizontal: Int, vertical: Int) {
        VirtualStickManager.getInstance().rightStick.horizontalPosition = horizontal
        VirtualStickManager.getInstance().rightStick.verticalPosition = vertical
    }

    fun sendVirtualStickAdvancedParam(param: VirtualStickFlightControlParam) {
        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
    }

    fun disableVirtualStickAdvancedMode() {
        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
    }

    fun enableVirtualStickAdvancedMode() {
        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
    }

    fun listenRCStick() {
        RemoteControllerKey.KeyStickLeftHorizontal.create().listen(this) {
            it?.let {
                stickValue.value?.leftHorizontal = it
            }
            tryUpdateVirtualStickByRc()
        }
        RemoteControllerKey.KeyStickLeftVertical.create().listen(this) {
            it?.let {
                stickValue.value?.leftVertical = it
            }
            tryUpdateVirtualStickByRc()
        }
        RemoteControllerKey.KeyStickRightHorizontal.create().listen(this) {
            it?.let {
                stickValue.value?.rightHorizontal = it
            }
            tryUpdateVirtualStickByRc()
        }
        RemoteControllerKey.KeyStickRightVertical.create().listen(this) {
            it?.let {
                stickValue.value?.rightVertical = it
            }
            tryUpdateVirtualStickByRc()
        }
    }

    private fun tryUpdateVirtualStickByRc() {
        stickValue.postValue(stickValue.value)
        if (useRcStick.value == true) {
            stickValue.value?.apply {
                setLeftPosition(leftHorizontal, leftVertical)
                setRightPosition(rightHorizontal, rightVertical)
            }
        }
    }

    override fun onCleared() {
        KeyManager.getInstance().cancelListen(this)
        VirtualStickManager.getInstance().clearAllVirtualStickStateListener()
    }

    data class VirtualStickStateInfo(
        var state: VirtualStickState = VirtualStickState(false, FlightControlAuthority.UNKNOWN, false),
        var reason: FlightControlAuthorityChangeReason = FlightControlAuthorityChangeReason.UNKNOWN
    )

    data class RCStickValue(
        var leftHorizontal: Int, var leftVertical:
        Int, var rightHorizontal: Int, var rightVertical: Int
    ) {
        override fun toString(): String {
            return "leftHorizontal=$leftHorizontal,leftVertical=$leftVertical,\n" +
                    "rightHorizontal=$rightHorizontal,rightVertical=$rightVertical"
        }
    }
}