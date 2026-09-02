package com.solonav.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import org.osmdroid.util.GeoPoint

class LocationSharingService : Service() {

    companion object {
        private const val TAG = "LocationSharingService"
        private const val CHANNEL_ID = "SOLONAV_LOCATION"
        private const val NOTIFICATION_ID = 1001
        private const val PORT = 16968
        private var serviceRunning = false
    }

    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var targetNumber: String? = null
    private var lastLocation: GeoPoint? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (serviceRunning) return START_STICKY

        targetNumber = intent?.getStringExtra("target_number")?.replace("+33", "0")
        Log.d(TAG, "Démarrage partage avec : $targetNumber")

        startForeground(NOTIFICATION_ID, buildNotification())
        serviceRunning = true
        startLocationUpdates()

        return START_STICKY
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(10000)
            .setMinUpdateDistanceMeters(10f)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                super.onLocationResult(result)
                result.lastLocation?.let { sendLocation(it) }
            }
        }

        try {
            fusedClient?.requestLocationUpdates(request, locationCallback!!, mainLooper)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permissions localisation manquantes", e)
            stopSelf()
        }
    }

    private fun sendLocation(location: Location) {
        val current = GeoPoint(location.latitude, location.longitude)
        
        lastLocation?.let { last ->
            val dist = current.distanceAs(last)
            if (dist < 10) return
        }
        lastLocation = current

        val message = "SOLONAV_POS:${current.latitude},${current.longitude}"
        Log.d(TAG, "Envoi position : $message")

        targetNumber?.let { num ->
            try {
                val sms = SmsManager.getDefault()
                sms.sendDataMessage(
                    num, null, PORT.toShort(),
                    message.toByteArray(Charsets.UTF_8), null, null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Erreur envoi SMS", e)
            }
        }

        val broadcast = Intent(SmsDataReceiver.ACTION_POSITION_UPDATE)
        broadcast.setPackage(packageName)
        broadcast.putExtra("sms_data", message)
        sendBroadcast(broadcast)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📍 SOLONAV — Partage actif")
            .setContentText("Votre position est partagée en temps réel")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "SOLONAV Localisation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Partage de position en temps réel"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedClient?.removeLocationUpdates(it) }
        serviceRunning = false
        Log.d(TAG, "Service arrêté")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
