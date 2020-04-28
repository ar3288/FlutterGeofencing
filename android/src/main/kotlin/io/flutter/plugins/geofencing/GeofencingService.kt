// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.geofencing

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.JobIntentService
import com.google.android.gms.location.GeofencingEvent
import io.flutter.plugin.common.PluginRegistry.PluginRegistrantCallback
import java.util.*
import java.util.concurrent.BlockingDeque
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.CountDownLatch

class GeofencingService : JobIntentService() /*FIXME ,MethodCallHandler*/ {

//FIXME    private lateinit var mBackgroundChannel: MethodChannel
//    private lateinit var mContext: Context

//FIXME    private fun startGeofencingService(context: Context) {
//        synchronized(sServiceStarted) {
//            mContext = context
////            if (sBackgroundFlutterView == null) {
//                val callbackHandle = context.getSharedPreferences(
//                        GeofencingPlugin.SHARED_PREFERENCES_KEY,
//                        Context.MODE_PRIVATE)
//                        .getLong(GeofencingPlugin.CALLBACK_DISPATCHER_HANDLE_KEY, 0)
//
//
//                val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)
//
//                if (callbackInfo == null) {
//                    Log.e(TAG, "Fatal: failed to find callback")
//                    return
//                }
//
//                Log.i(TAG, "Starting GeofencingService...")
////                sBackgroundFlutterView = FlutterNativeView(context, true)
//
////                val registry = sBackgroundFlutterView!!.pluginRegistry
//
//
//                // Only called if this plugin was setup up with Flutter V1, I think?
////                sPluginRegistrantCallback?.registerWith(registry)
//
//                val args = FlutterRunArguments()
//                args.bundlePath = FlutterMain.findAppBundlePath(context)
//                args.entrypoint = callbackInfo.callbackName
//                args.libraryPath = callbackInfo.callbackLibraryPath
//
////                sBackgroundFlutterView!!.runFromBundle(args)
////                IsolateHolderService.setBackgroundFlutterView(sBackgroundFlutterView)
//            }
////        }
//
////        mBackgroundChannel = MethodChannel(sBackgroundFlutterView,
////                "plugins.flutter.io/geofencing_plugin_background")
//        mBackgroundChannel.setMethodCallHandler(this)
//    }


//   override fun onMethodCall(call: MethodCall, result: Result) {
//       when(call.method) {
//            "GeofencingService.initialized" -> {
//                synchronized(sServiceStarted) {
//                    while (!geofenceQueue.isEmpty()) {
//                        mBackgroundChannel.invokeMethod("", geofenceQueue.remove())
////                        backgroundExecutor.executeDartCallbackInBackgroundIsolate(geofenceQueue.remove())
//                    }
//
//                    sServiceStarted.set(true)
//                }
//            }
//            "GeofencingService.promoteToForeground" -> {
//                mContext.startForegroundService(Intent(mContext, IsolateHolderService::class.java))
//            }
//            "GeofencingService.demoteToBackground" -> {
//                val intent = Intent(mContext, IsolateHolderService::class.java)
//
//                intent.action = IsolateHolderService.ACTION_SHUTDOWN
//
//                mContext.startForegroundService(intent)
//            }
//            else -> result.notImplemented()
//        }
//        result.success(null)
//    }

    override fun onCreate() {
        super.onCreate()

//FIXME        startGeofencingService(this)
        if (backgroundExecutor != null) {
            Log.w(TAG, "Attempted to start a duplicate background isolate. Returning...")

            return
        }

        backgroundExecutor = BackgroundExecutor()
        backgroundExecutor?.startBackgroundIsolate(applicationContext)
                ?: Log.e(TAG," FATAL: BackgroundExecutor was unexpectedly null when " +
                        "starting isolate without callback handle")
    }

    override fun onHandleWork(intent: Intent) {

        val callbackHandle = intent.getLongExtra(GeofencingPlugin.CALLBACK_HANDLE_KEY, 0)
        val geofencingEvent = GeofencingEvent.fromIntent(intent)

        if (geofencingEvent.hasError()) {
            Log.e(TAG, "Geofencing error: ${geofencingEvent.errorCode}")
            return
        }

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

        val geofenceUpdateList = listOf(callbackHandle,
                triggeringGeofences,
                locationList,
                geofenceTransition)

        synchronized(geofenceQueue) {
            if(backgroundExecutor?.isRunning!!){
                Log.i(TAG, "GeofencingService has not started yet, queuing geofence request")
                geofenceQueue.add(intent)
            }
        }

        val latch = CountDownLatch(1)

        // Callback method name is intentionally left blank.
        Handler(Looper.getMainLooper()).post {
            backgroundExecutor?.executeDartCallbackInBackgroundIsolate(intent, latch)
//FIXME            mBackgroundChannel.invokeMethod("", geofenceUpdateList)
        }

        try {
            latch.await()
        } catch (ex: InterruptedException) {
            Log.i(TAG, "Exception waiting to execute Dart callback", ex)
        }
    }

    companion object {
        private val geofenceQueue = LinkedBlockingDeque<Intent>()

        @JvmStatic
        val SHARED_PREFERENCES_KEY = "io.flutter.plugins.geofencing"
        @JvmStatic
        private val TAG = "GeofencingService"
        @JvmStatic
        private val JOB_ID = UUID.randomUUID().mostSignificantBits.toInt()
        @JvmStatic
        private var  backgroundExecutor : BackgroundExecutor? = null
        //        ///FIXME I think we remove sBackgroundView?
//        @JvmStatic
//        private var sBackgroundFlutterView: FlutterNativeView? = null
        //FIXME - delete I think?
//        @JvmStatic
//        private val sServiceStarted = AtomicBoolean(false)
        //FIXME I think this gets removed?
//        @JvmStatic
//        private var sPluginRegistrantCallback: PluginRegistrantCallback? = null

        /** Schedule the geofence to be handled by the [GeofencingService]. */
        @JvmStatic
        fun enqueueWork(context: Context, work: Intent) {
            enqueueWork(context, GeofencingService::class.java, JOB_ID, work)
        }

        /**
         * Starts the background isolate for the [GeofencingService].
         *
         * <p>Preconditions:
         *
         * <ul>
         *   <li>The given [callbackHandle] must correspond to a registered Dart callback. If the
         *       handle does not resolve to a Dart callback then this method does nothing.
         *   <li>A static [pluginRegistrantCallback] must exist, otherwise a
         *       [PluginRegistrantException] will be thrown.
         * </ul>
         */
        @JvmStatic
        fun startBackgroundIsolate(context : Context, callbackHandle: Long) {
            if (backgroundExecutor != null) {
                Log.w(TAG, "Attempted to start a duplicate background isolate. Returning...")

                return
            }

            backgroundExecutor = BackgroundExecutor()
            backgroundExecutor?.startBackgroundIsolate(context, callbackHandle)
                    ?: Log.e(TAG," FATAL: BackgroundExecutor was unexpectedly null when " +
                            "starting isolate")
        }

        /**
         * Called once the Dart isolate [backgroundExecutor] has finished initializing.
         *
         * <p>Invoked by [GeofencingService] when it receives the GeofencingService.initialized
         * message. Processes all alarm events that came in while the isolate was starting.
         */
        @JvmStatic
        fun onInitialized() {
            Log.i(TAG, "GeofencingService started!")

            synchronized(geofenceQueue) {
                val i: Iterator<Intent> = geofenceQueue.iterator()

                while (i.hasNext()) {
                    backgroundExecutor?.executeDartCallbackInBackgroundIsolate(i.next(), null)
                }

                geofenceQueue.clear()
            }
        }

        /**
         * Sets the Dart callback handle for the Dart method that is responsible for initializing the
         * background Dart isolate, preparing it to receive Dart callback tasks requests.
         */
        fun setCallbackDispatcher(context: Context, callbackHandle: Long) {
            BackgroundExecutor.setCallbackDispatcher(context, callbackHandle)
        }

        // FIXME remove later I think....
        @JvmStatic
        fun setPluginRegistrant(callback: PluginRegistrantCallback) {
            BackgroundExecutor.setPluginRegistrant(callback)
        }
    }
}
