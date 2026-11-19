package com.sehat.fall.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
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
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

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
        webView.loadDataWithBaseURL("https://openstreetmap.org", htmlContent, "text/html", "UTF-8", null)
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
                
                L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    maxZoom: 19,
                    attribution: '© OpenStreetMap'
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
            return
        }

        myGpsBtn.text = "LOCATING..."
        try {
            val lastGps = if (hasFine) lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null
            val lastNet = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val best = lastGps ?: lastNet

            if (best != null) {
                selectedLat = best.latitude
                selectedLon = best.longitude
                coordsTextView.text = "📍 Pin: Lat ${"%.5f".format(best.latitude)}, Lon ${"%.5f".format(best.longitude)}"
                setPinOnMap(best.latitude, best.longitude, selectedRadiusMeters)
                myGpsBtn.text = "📍 MY GPS"
            } else {
                // Request single fix
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
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, singleListener)
                if (hasFine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, singleListener)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            myGpsBtn.text = "📍 MY GPS"
        }
    }

    private fun savePinLocation() {
        val settings = SehatApp.instance.settingsManager
        settings.homeLatitude = selectedLat
        settings.homeLongitude = selectedLon
        settings.homeRadiusMeters = selectedRadiusMeters

        Toast.makeText(
            this,
            "✅ Home location set to Pin:\nLat: ${"%.5f".format(selectedLat)}, Lon: ${"%.5f".format(selectedLon)}\nRadius: ${selectedRadiusMeters.toInt()}m",
            Toast.LENGTH_LONG
        ).show()

        finish()
    }
}
