package com.solonav.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

class FloatingWindowService : Service() {

    companion object {
        const val ACTION_SHOW = "com.solonav.SHOW_FLOATING"
        const val ACTION_HIDE = "com.solonav.HIDE_FLOATING"
        const val ACTION_UPDATE_MY_POS = "com.solonav.UPDATE_MY_POS"
        const val ACTION_UPDATE_OTHER_POS = "com.solonav.UPDATE_OTHER_POS"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        
        private const val TAG = "FloatingWindow"
        private var instance: FloatingWindowService? = null
        fun isShowing(): Boolean = instance?.windowView != null
    }

    private var windowManager: WindowManager? = null
    private var windowView: View? = null
    private var floatingMap: MapView? = null
    private var myMarker: Marker? = null
    private var otherMarker: Marker? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().osmdroidTileCache = File(cacheDir, "osmdroid")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showWindow()
            ACTION_HIDE -> hideWindow()
            ACTION_UPDATE_MY_POS -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                val lon = intent.getDoubleExtra(EXTRA_LON, 0.0)
                updateMarker(myMarker, lat, lon)
            }
            ACTION_UPDATE_OTHER_POS -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                val lon = intent.getDoubleExtra(EXTRA_LON, 0.0)
                updateMarker(otherMarker, lat, lon)
            }
        }
        return START_STICKY
    }

    private fun showWindow() {
        if (windowView != null) return

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        windowView = inflater.inflate(R.layout.floating_map_window, null)

        floatingMap = windowView?.findViewById(R.id.floatingMapView)
        floatingMap?.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller?.setZoom(14.0)
        }

        val btnClose = windowView?.findViewById<ImageButton>(R.id.btnCloseFloating)
        btnClose?.setOnClickListener { hideWindow() }

        val params = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
        }

        params.gravity = Gravity.TOP or Gravity.END
        params.x = 16
        params.y = 80

        try {
            windowManager?.addView(windowView, params)
            setupDrag(params)
        } catch (e: Exception) {
            Log.e(TAG, "Impossible d'afficher la fenêtre", e)
            stopSelf()
        }
    }

    private fun setupDrag(params: WindowManager.LayoutParams) {
        var initialX = params.x
        var initialY = params.y
        var initialTouchX = 0f
        var initialTouchY = 0f

        windowView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt() * -1
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(windowView, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun hideWindow() {
        try {
            windowView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {}
        windowView = null
        floatingMap = null
        myMarker = null
        otherMarker = null
        stopSelf()
    }

    private fun updateMarker(marker: Marker?, lat: Double, lon: Double) {
        val point = GeoPoint(lat, lon)
        floatingMap?.let { map ->
            if (marker == null) {
                val newMarker = Marker(map).apply {
                    position = point
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                map.overlays.add(newMarker)
                if (marker === myMarker) myMarker = newMarker else otherMarker = newMarker
            } else {
                marker.position = point
            }
            map.controller?.animateTo(point)
            map.invalidate()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        hideWindow()
        instance = null
    }
}
