package com.sehat.fall.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.sehat.fall.SehatApp
import com.sehat.fall.alert.AlertManager
import com.sehat.fall.location.LocationFenceManager

class MapPickerActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var coordsTextView: TextView
    private lateinit var radiusTextView: TextView
    private lateinit var radiusSeekBar: SeekBar
    private lateinit var savePinBtn: Button
    private lateinit var myGpsBtn: Button

    private var selectedLat: Double = 28.6139
    private var selectedLon: Double = 77.2090
    private var selectedRadiusMeters: Float = 100f

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settings = SehatApp.instance.settingsManager
        if (settings.isHomeConfigured) {
            selectedLat = settings.homeLatitude
            selectedLon = settings.homeLongitude
        } else {
            // Automatically initialize map pin at user's current GPS position
            val best = LocationFenceManager.getBestLastKnownLocation(this)
            if (best != null) {
                selectedLat = best.latitude
                selectedLon = best.longitude
            }
        }
        selectedRadiusMeters = settings.homeRadiusMeters

        buildLayout()
        initWebView()
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding(24, 36, 24, 24)
        }

        // Header
        val header = TextView(this).apply {
            text = "Set Home Location Pin"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        root.addView(header)

        val subheader = TextView(this).apply {
            text = "Tap anywhere on the map or drag the pin to mark your house."
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 2, 0, 12)
        }
        root.addView(subheader)

        // Coordinates Bar
        coordsTextView = TextView(this).apply {
            text = "📍 Pin: Lat ${"%.5f".format(selectedLat)}, Lon ${"%.5f".format(selectedLon)}"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(18, 12, 18, 12)
        }
        root.addView(coordsTextView)

        // Map WebView
        webView = WebView(this)
        val webParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { setMargins(0, 10, 0, 10) }
        root.addView(webView, webParams)

        // Radius Slider Bar
        radiusTextView = TextView(this).apply {
            text = "Safe Zone Radius: ${selectedRadiusMeters.toInt()} meters"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 4, 0, 2)
        }
        root.addView(radiusTextView)

        radiusSeekBar = SeekBar(this).apply {
            max = 18 // 50m to 500m (step 25m)
            progress = ((selectedRadiusMeters - 50) / 25).toInt().coerceIn(0, 18)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val r = (50 + progress * 25).toFloat()
                    selectedRadiusMeters = r
                    radiusTextView.text = "Safe Zone Radius: ${r.toInt()} meters"
                    if (fromUser) {
                        updateMapRadius(r)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(radiusSeekBar)

        // Action Buttons Row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 0)
        }

        myGpsBtn = Button(this).apply {
            text = "📍 MY GPS"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#334155"))
            setOnClickListener { centerOnMyGps() }
        }
        val gpsParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, 6, 0)
        }
        btnRow.addView(myGpsBtn, gpsParams)

        savePinBtn = Button(this).apply {
            text = "✔️ SAVE PIN AS HOME"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#10B981")) // Emerald Green
            setOnClickListener { savePinLocation() }
        }
        val saveParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f).apply {
            setMargins(6, 0, 0, 0)
        }
        btnRow.addView(savePinBtn, saveParams)

        root.addView(btnRow)

        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        // Set realistic mobile browser user agent so tile CDNs do not block requests
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 SEHAT-ERS/1.0"

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Set initial pin after page loads
                setPinOnMap(selectedLat, selectedLon, selectedRadiusMeters)
            }
        }

        webView.addJavascriptInterface(AndroidMapBridge(), "AndroidBridge")

        val htmlContent = buildLeafletMapHtml(selectedLat, selectedLon, selectedRadiusMeters)
        webView.loadDataWithBaseURL("https://cartocdn.com", htmlContent, "text/html", "UTF-8", null)
    }

    private fun buildLeafletMapHtml(lat: Double, lon: Double, radius: Float): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                body, html, #map { margin: 0; padding: 0; height: 100%; width: 100%; background: #0B0F19; }
                .leaflet-bar { border: none !important; }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                var map = L.map('map', { zoomControl: true }).setView([$lat, $lon], 16);
                
                // CARTO Voyager tiles: Fast, modern, high-res raster tiles with no mobile 418 blocking
                L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png', {
                    maxZoom: 20,
                    subdomains: 'abcd',
                    attribution: '&copy; OpenStreetMap &copy; CARTO'
                }).addTo(map);

                var marker = L.marker([$lat, $lon], { draggable: true }).addTo(map);
                var circle = L.circle([$lat, $lon], {
                    color: '#38BDF8',
                    fillColor: '#38BDF8',
                    fillOpacity: 0.25,
                    radius: $radius
                }).addTo(map);

                function notifyAndroid(lat, lng) {
                    if (window.AndroidBridge && window.AndroidBridge.onPinMoved) {
                        window.AndroidBridge.onPinMoved(lat, lng);
                    }
                }

                marker.on('drag', function(e) {
                    var pos = marker.getLatLng();
                    circle.setLatLng(pos);
                    notifyAndroid(pos.lat, pos.lng);
                });

                map.on('click', function(e) {
                    marker.setLatLng(e.latlng);
                    circle.setLatLng(e.latlng);
                    notifyAndroid(e.latlng.lat, e.latlng.lng);
                });

                window.setPin = function(lat, lng, r) {
                    marker.setLatLng([lat, lng]);
                    circle.setLatLng([lat, lng]);
                    if (r) circle.setRadius(r);
                    map.setView([lat, lng], 16);
                };

                window.setRadius = function(r) {
                    circle.setRadius(r);
                };
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    private inner class AndroidMapBridge {
        @JavascriptInterface
        fun onPinMoved(lat: Double, lon: Double) {
            mainHandler.post {
                selectedLat = lat
                selectedLon = lon
                coordsTextView.text = "📍 Pin: Lat ${"%.5f".format(lat)}, Lon ${"%.5f".format(lon)}"
            }
        }
    }

    private fun setPinOnMap(lat: Double, lon: Double, radius: Float) {
        webView.evaluateJavascript("if (window.setPin) { window.setPin($lat, $lon, $radius); }", null)
    }

    private fun updateMapRadius(radius: Float) {
        webView.evaluateJavascript("if (window.setRadius) { window.setRadius($radius); }", null)
    }

    private fun centerOnMyGps() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show()
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 103)
            return
        }

        // Check if device location is enabled in system settings
        val isLocEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }

        if (!isLocEnabled) {
            Toast.makeText(this, "⚠️ Device Location is OFF. Please turn ON Location in Settings.", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (e: Exception) {}
            return
        }

        // 1. Immediately apply best known location so user never has to wait
        val best = LocationFenceManager.getBestLastKnownLocation(this)
        if (best != null) {
            selectedLat = best.latitude
            selectedLon = best.longitude
            coordsTextView.text = "📍 Pin: Lat ${"%.5f".format(best.latitude)}, Lon ${"%.5f".format(best.longitude)}"
            setPinOnMap(best.latitude, best.longitude, selectedRadiusMeters)
            Toast.makeText(this, "Centered on current device location", Toast.LENGTH_SHORT).show()
        }

        // 2. Concurrently poll fresh high-accuracy fix in background
        myGpsBtn.text = "LOCATING..."

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
                    LocationManager.FUSED_PROVIDER
                } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    LocationManager.NETWORK_PROVIDER
                } else {
                    LocationManager.GPS_PROVIDER
                }
                lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(this)) { loc ->
                    if (loc != null) {
                        selectedLat = loc.latitude
                        selectedLon = loc.longitude
                        coordsTextView.text = "📍 Pin: Lat ${"%.5f".format(loc.latitude)}, Lon ${"%.5f".format(loc.longitude)}"
                        setPinOnMap(loc.latitude, loc.longitude, selectedRadiusMeters)
                        myGpsBtn.text = "📍 MY GPS"
                    }
                }
            } catch (e: Exception) {}
        }

        val singleListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                try { lm.removeUpdates(this) } catch (e: Exception) {}
                selectedLat = loc.latitude
                selectedLon = loc.longitude
                coordsTextView.text = "📍 Pin: Lat ${"%.5f".format(loc.latitude)}, Lon ${"%.5f".format(loc.longitude)}"
                setPinOnMap(loc.latitude, loc.longitude, selectedRadiusMeters)
                myGpsBtn.text = "📍 MY GPS"
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        try {
            for (p in lm.allProviders) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 0L, 0f, singleListener)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        mainHandler.postDelayed({
            myGpsBtn.text = "📍 MY GPS"
        }, 5000L)
    }

    private fun savePinLocation() {
        LocationFenceManager.setHomeLocation(selectedLat, selectedLon, selectedRadiusMeters)
        AlertManager.startPromptVibration(this)

        Toast.makeText(
            this,
            "✅ Home location set to Pin:\nLat: ${"%.5f".format(selectedLat)}, Lon: ${"%.5f".format(selectedLon)}\nRadius: ${selectedRadiusMeters.toInt()}m",
            Toast.LENGTH_LONG
        ).show()

        finish()
    }
}
