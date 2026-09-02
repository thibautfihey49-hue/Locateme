package com.solonav.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SOLONAV"
        private const val REQUEST_PERMISSIONS = 1001
        private const val PORT = 16968
        private val PHONE_PATTERN = Pattern.compile("^0[67]\\d{8}$")
        
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }.toTypedArray()
    }

    private lateinit var mapView: MapView
    private lateinit var btnDemander: MaterialButton
    private lateinit var btnArreter: MaterialButton
    private lateinit var btnShowFloating: MaterialButton
    private lateinit var btnMyLocation: FloatingActionButton
    private lateinit var etNumero: EditText
    
    private var myMarker: Marker? = null
    private var otherMarker: Marker? = null
    private var smsReceiver: BroadcastReceiver? = null
    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var autreNumero: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().osmdroidTileCache = File(cacheDir, "osmdroid")
        Configuration.getInstance().userAgentValue = packageName
        
        setContentView(R.layout.activity_main)
        initUI()
        initMyLocation()
        checkPermissions()
        setupSmsReceiver()
    }

    private fun initUI() {
        mapView = findViewById(R.id.mapView)
        btnDemander = findViewById(R.id.btnDemander)
        btnArreter = findViewById(R.id.btnArreter)
        btnShowFloating = findViewById(R.id.btnShowFloating)
        btnMyLocation = findViewById(R.id.btnMyLocation)
        etNumero = findViewById(R.id.etNumero)

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller?.setZoom(15.0)
        mapView.controller?.setCenter(GeoPoint(47.47, -0.55))

        btnDemander.setOnClickListener { demanderPositionDeLAutre() }
        btnArreter.setOnClickListener { arreterSuivi() }
        btnShowFloating.setOnClickListener { toggleFloatingWindow() }
        btnMyLocation.setOnClickListener { centrerSurMaPosition() }
        
        btnArreter.isEnabled = false
        btnDemander.isEnabled = false
        
        etNumero.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val num = s.toString().trim()
                btnDemander.isEnabled = PHONE_PATTERN.matcher(num).matches()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun initMyLocation() {
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                super.onLocationResult(result)
                result.lastLocation?.let { mettreAJourMaPosition(it) }
            }
        }
        demarrerLocalisation()
    }

    private fun demarrerLocalisation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) return

        val request = LocationRequest.Builder(10000)
            .setMinUpdateDistanceMeters(5f)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()

        fusedClient?.requestLocationUpdates(request, locationCallback!!, mainLooper)
        fusedClient?.lastLocation?.addOnSuccessListener { loc ->
            loc?.let { mettreAJourMaPosition(it) }
        }
    }

    private fun mettreAJourMaPosition(location: Location) {
        val point = GeoPoint(location.latitude, location.longitude)
        runOnUiThread {
            if (myMarker == null) {
                myMarker = Marker(mapView).apply {
                    position = point
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_map_marker_blue)
                    title = "🔵 MOI"
                }
                mapView.overlays.add(myMarker)
            } else {
                myMarker!!.position = point
            }
            mapView.invalidate()
        }
    }

    private fun centrerSurMaPosition() {
        myMarker?.position?.let {
            mapView.controller?.animateTo(it)
            Toast.makeText(this, "📍 Centré sur ma position", Toast.LENGTH_SHORT).show()
        } ?: Toast.makeText(this, "⏳ Position en attente...", Toast.LENGTH_SHORT).show()
    }

    private fun setupSmsReceiver() {
        smsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.getStringExtra("sms_data")?.let { data ->
                    when {
                        data.startsWith("SOLONAV_POS:") -> {
                            val coords = data.removePrefix("SOLONAV_POS:").split(",")
                            if (coords.size == 2) {
                                try {
                                    val lat = coords[0].toDouble()
                                    val lon = coords[1].toDouble()
                                    mettreAJourPositionAutre(lat, lon)
                                    if (FloatingWindowService.isShowing()) {
                                        mettreAJourFloatingAutre(lat, lon)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Erreur parsing", e)
                                }
                            }
                        }
                        data == "SOLONAV_DEMANDE_POS" -> {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "✅ DEMANDE REÇUE — Partage démarré ! 📍", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
        registerReceiver(smsReceiver, IntentFilter(SmsDataReceiver.ACTION_POSITION_UPDATE))
    }

    private fun checkPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) showPermissionDialog(missing)
    }

    private fun showPermissionDialog(missing: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("🔑 Permissions nécessaires")
            .setMessage("SOLONAV a besoin de :\n\n" +
                    "📡 Internet (pour la carte)\n" +
                    "📍 Localisation (toujours autoriser)\n" +
                    "📩 SMS (envoyer/recevoir invisible)\n" +
                    "🔔 Notifications\n" +
                    "🗺️ Afficher par-dessus les autres apps")
            .setPositiveButton("ACCORDER") { _, _ ->
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
            }
            .setCancelable(false)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                demarrerLocalisation()
            }
        }
    }

    private fun demanderPositionDeLAutre() {
        val numero = etNumero.text.toString().trim()
        if (!PHONE_PATTERN.matcher(numero).matches()) {
            Toast.makeText(this, "❌ Format invalide: 06XXXXXXXX", Toast.LENGTH_SHORT).show()
            return
        }
        autreNumero = numero

        try {
            val sms = SmsManager.getDefault()
            sms.sendDataMessage(numero, null, PORT.toShort(),
                "SOLONAV_DEMANDE_POS".toByteArray(Charsets.UTF_8), null, null)
            
            Toast.makeText(this, "✅ Demande envoyée à $numero 📩", Toast.LENGTH_LONG).show()
            btnArreter.isEnabled = true
            btnDemander.isEnabled = false
            etNumero.isEnabled = false
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Erreur SMS: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mettreAJourPositionAutre(lat: Double, lon: Double) {
        val point = GeoPoint(lat, lon)
        runOnUiThread {
            if (otherMarker == null) {
                otherMarker = Marker(mapView).apply {
                    position = point
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_map_marker_red)
                    title = "🔴 L'AUTRE"
                }
                mapView.overlays.add(otherMarker)
                Toast.makeText(this, "📍 Position reçue ! Suivi en cours...", Toast.LENGTH_LONG).show()
            } else {
                otherMarker!!.position = point
            }
            mapView.controller?.animateTo(point)
            mapView.invalidate()
        }
    }

    private fun mettreAJourFloatingAutre(lat: Double, lon: Double) {
        val intent = Intent(this, FloatingWindowService::class.java).apply {
            action = FloatingWindowService.ACTION_UPDATE_OTHER_POS
            putExtra(FloatingWindowService.EXTRA_LAT, lat)
            putExtra(FloatingWindowService.EXTRA_LON, lon)
        }
        startService(intent)
    }

    private fun arreterSuivi() {
        autreNumero = null
        startService(Intent(this, FloatingWindowService::class.java).apply {
            action = FloatingWindowService.ACTION_HIDE
        })
        btnShowFloating.text = "🗺️ CARTE FLOTTANTE"
        
        etNumero.setText("")
        etNumero.isEnabled = true
        btnDemander.isEnabled = true
        btnArreter.isEnabled = false
        
        otherMarker?.let { mapView.overlays.remove(it) }
        otherMarker = null
        mapView.invalidate()
        
        Toast.makeText(this, "🛑 Suivi arrêté", Toast.LENGTH_SHORT).show()
    }

    private fun toggleFloatingWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "⚠️ Autorisez l'affichage par-dessus les autres apps", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, FloatingWindowService::class.java)
        if (FloatingWindowService.isShowing()) {
            intent.action = FloatingWindowService.ACTION_HIDE
            startService(intent)
            btnShowFloating.text = "🗺️ CARTE FLOTTANTE"
        } else {
            intent.action = FloatingWindowService.ACTION_SHOW
            startService(intent)
            btnShowFloating.text = "❌ FERMER FLOTTANTE"
        }
    }

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    
    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedClient?.removeLocationUpdates(it) }
        try { smsReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
    }
}
