package io.flutter.plugins.geofencing

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.GeofencingEvent
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor.DartCallback
import io.flutter.embedding.engine.plugins.shim.ShimPluginRegistry
import io.flutter.plugin.common.*
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry.PluginRegistrantCallback
import io.flutter.view.FlutterCallbackInformation
import io.flutter.view.FlutterMain
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

// Copyright 2019 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.


/**
 * An background execution abstraction which handles initializing a background isolate running a
 * callback dispatcher, used to invoke Dart callbacks while backgrounded.
 */
class BackgroundExecutor : MethodCallHandler {
    /**
     * The [MethodChannel] that connects the Android side of this plugin with the background
     * Dart isolate that was created by this plugin.
     */
    private var backgroundChannel: MethodChannel? = null
    private var backgroundFlutterEngine: FlutterEngine? = null
    private val isCallbackDispatcherReady = AtomicBoolean(false)

    /** Returns true when the background isolate has started and is ready to handle alarms.  */
    val isRunning: Boolean
        get() = isCallbackDispatcherReady.get()

    private fun onInitialized() {
        isCallbackDispatcherReady.set(true)
        GeofencingService.onInitialized()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val method = call.method
        val arguments = call.arguments

        if (method == "GeofencingService.initialized") {
            // This message is sent by the background method channel as soon as the background isolate
            // is running. From this point forward, the Android side of this plugin can send
            // callback handles through the background method channel, and the Dart side will execute
            // the Dart methods corresponding to those callback handles.
            onInitialized()
            result.success(true)
        }
        else {
            result.notImplemented()
        }
    }

    /**
     * Starts running a background Dart isolate within a new [FlutterEngine] using a previously
     * used entrypoint.
     *
     *
     * The isolate is configured as follows:
     *
     *
     *  * Bundle Path: `FlutterMain.findAppBundlePath(context)`.
     *  * Entrypoint: The Dart method used the last time this plugin was initialized in the
     * foreground.
     *  * Run args: none.
     *
     *
     *
     * Preconditions:
     *
     *
     *  * The given callback must correspond to a registered Dart callback. If the handle does not
     * resolve to a Dart callback then this method does nothing.
     *  * A static [.pluginRegistrantCallback] must exist, otherwise a [       ] will be thrown.
     *
     */
    fun startBackgroundIsolate(context: Context) {
        if (!isRunning) {
            val p = context.getSharedPreferences(GeofencingService.SHARED_PREFERENCES_KEY, 0)
            val callbackHandle = p.getLong(CALLBACK_HANDLE_KEY, 0)
            startBackgroundIsolate(context, callbackHandle)
        }
    }

    /**
     * Starts running a background Dart isolate within a new [FlutterEngine].
     *
     *
     * The isolate is configured as follows:
     *
     *
     *  * Bundle Path: `FlutterMain.findAppBundlePath(context)`.
     *  * Entrypoint: The Dart method represented by `callbackHandle`.
     *  * Run args: none.
     *
     *
     *
     * Preconditions:
     *
     *
     *  * The given `callbackHandle` must correspond to a registered Dart callback. If the
     * handle does not resolve to a Dart callback then this method does nothing.
     *  * A static [.pluginRegistrantCallback] must exist, otherwise a [       ] will be thrown.
     *
     */
    fun startBackgroundIsolate(context: Context, callbackHandle: Long) {
        if (backgroundFlutterEngine != null) {
            Log.e(TAG, "Background isolate already started")
            return
        }

        Log.i(TAG, "Starting GeofenceService...")

        val appBundlePath = FlutterMain.findAppBundlePath(context)
        val assets = context.assets

        if (appBundlePath != null && !isRunning) {
            backgroundFlutterEngine = FlutterEngine(context)

            // We need to create an instance of `FlutterEngine` before looking up the
            // callback. If we don't, the callback cache won't be initialized and the
            // lookup will fail.
            val flutterCallback = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)

            if (flutterCallback == null) {
                Log.e(TAG, "Fatal: failed to find callback")
                return
            }

            val executor = backgroundFlutterEngine!!.dartExecutor

            initializeMethodChannel(executor)

            val dartCallback = DartCallback(assets, appBundlePath, flutterCallback)

            executor.executeDartCallback(dartCallback)

            // The pluginRegistrantCallback should only be set in the V1 embedding as
            // plugin registration is done via reflection in the V2 embedding.
            if (pluginRegistrantCallback != null) {
                pluginRegistrantCallback!!.registerWith(ShimPluginRegistry(backgroundFlutterEngine!!))
            }
        }
    }

    /**
     * Executes the desired Dart callback in a background Dart isolate.
     *
     *
     * The given `intent` should contain a `long` extra called "callbackHandle", which
     * corresponds to a callback registered with the Dart VM.
     */
    fun executeDartCallbackInBackgroundIsolate(intent: Intent, latch: CountDownLatch?) {
        // Grab the handle for the callback associated with this alarm. Pay close
        // attention to the type of the callback handle as storing this value in a
        // variable of the wrong size will cause the callback lookup to fail.
        val callbackHandle = intent.getLongExtra(GeofencingPlugin.CALLBACK_HANDLE_KEY, 0)

        // If another thread is waiting, then wake that thread when the callback returns a result.
        var result: MethodChannel.Result? = null

        if (latch != null) {
            result = object : MethodChannel.Result {
                override fun success(result: Any?) {
                    latch.countDown()
                }

                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    latch.countDown()
                }

                override fun notImplemented() {
                    latch.countDown()
                }
            }
        }

        val geofencingEvent = GeofencingEvent.fromIntent(intent)

        if (geofencingEvent.hasError()) {
            Log.e(TAG, "Geofencing error: ${geofencingEvent.errorCode}")
            return
        }

        val dartReturnVals = callbackArgs(callbackHandle, geofencingEvent)

        // Handle the alarm event in Dart. Note that for this plugin, we don't
        // care about the method name as we simply lookup and invoke the callback
        // provided.
        backgroundChannel!!.invokeMethod("invokeGeofencePluginCallback", dartReturnVals, result)
    }


    fun callbackArgs(callbackHandle: Long, geofencingEvent : GeofencingEvent) : List<Any> {
        // Get the transition type.
        val geofenceTransition = geofencingEvent.geofenceTransition

        // Get the geofences that were triggered. A single event can trigger
        // multiple geofences.
        val triggeringGeofences = geofencingEvent.triggeringGeofences.map {
            it.requestId
        }

        val location = geofencingEvent.triggeringLocation
        val locationList = listOf(location.latitude,
                location.longitude)

        return listOf(callbackHandle, triggeringGeofences, locationList, geofenceTransition)
    }

    private fun initializeMethodChannel(isolate: BinaryMessenger) {
        // backgroundChannel is the channel responsible for receiving the following messages from
        // the background isolate that was setup by this plugin:
        // - "AlarmService.initialized"
        //
        // This channel is also responsible for sending requests from Android to Dart to execute Dart
        // callbacks in the background isolate.
        backgroundChannel = MethodChannel(
                isolate,
                "plugins.flutter.io/geofencing_plugin_background")
//                ,StandardMessageCodec.INSTANCE)

        backgroundChannel!!.setMethodCallHandler(this)
    }

    companion object {
        private const val TAG = "BackgroundExecutor"
        private const val CALLBACK_HANDLE_KEY = "callback_handle"
        private var pluginRegistrantCallback: PluginRegistrantCallback? = null

        /**
         * Sets the `PluginRegistrantCallback` used to register plugins with the newly spawned
         * isolate.
         *
         *
         * Note: this is only necessary for applications using the V1 engine embedding API as plugins
         * are automatically registered via reflection in the V2 engine embedding API. If not set, alarm
         * callbacks will not be able to utilize functionality from other plugins.
         */
        fun setPluginRegistrant(callback: PluginRegistrantCallback?) {
            pluginRegistrantCallback = callback
        }

        /**
         * Sets the Dart callback handle for the Dart method that is responsible for initializing the
         * background Dart isolate, preparing it to receive Dart callback tasks requests.
         */
        fun setCallbackDispatcher(context: Context, callbackHandle: Long) {
            val prefs = context.getSharedPreferences(GeofencingService.SHARED_PREFERENCES_KEY, 0)
            prefs.edit().putLong(CALLBACK_HANDLE_KEY, callbackHandle).apply()
        }
    }
}