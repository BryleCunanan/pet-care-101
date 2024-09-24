package com.example.petcare101.ui.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.petcare101.R
import com.example.petcare101.databinding.FragmentNotificationsBinding
import com.example.petcare101.utils.PermissionManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlin.random.Random


const val CAMERA_ZOOM_LEVEL = 15f
const val GEOFENCE_RADIUS = 100f
const val REQUEST_CODE_LOCATION_PERMISSION = 123
const val REQUEST_CODE_BACKGROUND_LOCATION_PERMISSION = 12345
private val TAG = NotificationsFragment::class.java.simpleName
private lateinit var geofenceLatLng: LatLng
private var currentGeofenceCircle: Circle? = null
private var currentGeofenceMarker: Marker? = null
private var lastGeofenceCircle: Circle? = null
private var entityMarker: Marker? = null

class NotificationsFragment : Fragment() {

    private var _binding: FragmentNotificationsBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private lateinit var database: DatabaseReference

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var mapView: MapView
    private lateinit var map: GoogleMap
    private lateinit var geofencingClient: GeofencingClient

    private var isEntityInsideGeofence = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val notificationsViewModel =
            ViewModelProvider(this).get(NotificationsViewModel::class.java)

        _binding = FragmentNotificationsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        initLocationClient()

        mapView = binding.mapViewGeofence

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync {
            map = it
            it.uiSettings.isZoomControlsEnabled = true

            if (!PermissionManager.isLocationPermissionGranted(requireContext())) {
                val permissions = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    permissions.toTypedArray(),
                    REQUEST_CODE_LOCATION_PERMISSION
                )
            } else {
                getLastKnownLocation()
            }
            setLongClick(map)

        }

        return root
    }

    private fun streamLocation(context: Context) {

        database = FirebaseDatabase.getInstance().getReference("Harness")

        database.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                // Assuming 'entityLatLng' represents the LatLng received from Firebase
                val entityLatLng = LatLng(
                    dataSnapshot.child("lat").value.toString().toDouble(),
                    dataSnapshot.child("lng").value.toString().toDouble()
                )

                val bitmapDescriptor = bitmapFromVector(context, R.drawable.ic_pet_marker_24)
                // Create or update the marker for the entity's location
                // Remove existing marker if any
                entityMarker?.remove()

                // Add/update marker with latest position
                entityMarker = map.addMarker(
                    MarkerOptions()
                        .position(entityLatLng)
                        .title("Package Location")
                        .icon(bitmapDescriptor)
                )

                // Check if geofenceLatLng is initialized
                if (!::geofenceLatLng.isInitialized) {
                    Log.e(TAG, "geofenceLatLng is not initialized")
                    return
                }

                val isInside = isEntityInsideGeofence(entityLatLng)

                // Check if the entity is within the geofence boundaries
                if (isEntityInsideGeofence != isInside) {
                    val message = if (isInside) "Pet is within reach" else "Pet is out of reach"
                    showNotification(context, message)
                }

                isEntityInsideGeofence = isInside
            }

            override fun onCancelled(databaseError: DatabaseError) {
                // Handle potential errors here
            }
        })

    }

    private fun isEntityInsideGeofence(entityLatLng: LatLng): Boolean {
        val distanceToGeofence = FloatArray(1)
        Location.distanceBetween(
            entityLatLng.latitude,
            entityLatLng.longitude,
            geofenceLatLng.latitude,
            geofenceLatLng.longitude,
            distanceToGeofence
        )
        return distanceToGeofence[0] <= GEOFENCE_RADIUS
    }


    private fun initLocationClient() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        geofencingClient = LocationServices.getGeofencingClient(requireActivity())
    }

    private fun getLastKnownLocation() {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            ActivityCompat.requestPermissions(
                requireActivity(),
                permissions.toTypedArray(),
                REQUEST_CODE_LOCATION_PERMISSION
            )
        }
        map.isMyLocationEnabled = true

        fusedLocationClient.lastLocation.addOnSuccessListener {
            if(it != null) {
                with(map) {
                    val latLng = LatLng(it.latitude, it.longitude)
                    moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, CAMERA_ZOOM_LEVEL))
                }
            } else {
                with(map) {
                    moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(14.95293, 120.89812), CAMERA_ZOOM_LEVEL))
                }
            }
        }

        database = FirebaseDatabase.getInstance().getReference("Harness")

        database.get().addOnSuccessListener {
            val lat = it.child("lat").value.toString().toDouble()
            val long = it.child("lng").value.toString().toDouble()

            entityMarker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(lat,long))
                    .title("Pet's Location")
                    .icon(bitmapFromVector(requireContext(), R.drawable.ic_pet_marker_24))
            )
        }

        if(::geofenceLatLng.isInitialized){
            val circle = map.addCircle(
                CircleOptions()
                    .center(geofenceLatLng)
                    .strokeColor(Color.argb(50, 70, 70, 70))
                    .fillColor(Color.argb(70, 150, 150, 150))
                    .radius(GEOFENCE_RADIUS.toDouble()))

            lastGeofenceCircle = circle
        }
    }

    private fun setLongClick(map: GoogleMap) {
        map.setOnMapLongClickListener {latLng ->
            lastGeofenceCircle?.remove()
            currentGeofenceCircle?.remove()
            currentGeofenceMarker?.remove()

            // Add a marker at the long-clicked position
            val marker = map.addMarker(MarkerOptions()
                .position(latLng)
                .title("Current Location"))
            marker?.showInfoWindow()

            // Add a circle representing the geofence
            val circle = map.addCircle(CircleOptions()
                .center(latLng)
                .strokeColor(Color.argb(50, 70, 70, 70))
                .fillColor(Color.argb(70, 150, 150, 150))
                .radius(GEOFENCE_RADIUS.toDouble()))

            // Store references to the current geofence circle and marker
            currentGeofenceCircle = circle
            currentGeofenceMarker = marker

            geofenceLatLng = latLng

            // Move the camera to the long-clicked position
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, CAMERA_ZOOM_LEVEL))

            if (::geofenceLatLng.isInitialized) {
                streamLocation(requireContext())
            }
        }

    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_BACKGROUND_LOCATION_PERMISSION) {
            if (permissions.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    requireContext(),
                    "This application needs background location to work on Android 10 and higher",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        if (requestCode == REQUEST_CODE_LOCATION_PERMISSION) {
            if (
                grantResults.isNotEmpty() && (
                        grantResults[0] == PackageManager.PERMISSION_GRANTED ||
                                grantResults[1] == PackageManager.PERMISSION_GRANTED)
            ) {
                if (ActivityCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
                map.isMyLocationEnabled = true
            } else {
                Toast.makeText(
                    requireContext(),
                    "The app needs location permission to function",
                    Toast.LENGTH_LONG
                ).show()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (grantResults.isNotEmpty() && grantResults[2] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(
                        requireContext(),
                        "This application needs background location to work on Android 10 and higher",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun bitmapFromVector(context: Context, vectorResId: Int): BitmapDescriptor {
        val vectorDrawable = ContextCompat.getDrawable(context, vectorResId)
        vectorDrawable?.setBounds(0, 0, vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
        val bitmap = Bitmap.createBitmap(vectorDrawable!!.intrinsicWidth, vectorDrawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        vectorDrawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onStart() {
        super.onStart()
        if (::mapView.isInitialized) {
            mapView.onStart()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) {
            mapView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::mapView.isInitialized) {
            mapView.onPause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::mapView.isInitialized) {
            mapView.onStop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::mapView.isInitialized) {
            mapView.onDestroy()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::mapView.isInitialized) {
            mapView.onLowMemory()
        }
    }

    companion object {
        fun showNotification(context: Context, message: String) {
            val CHANNEL_ID = "REMINDER_NOTIFICATION_CHANNEL"
            var notificationId = 5516
            notificationId += Random(notificationId).nextInt(1, 30)

            val notificationBuilder = NotificationCompat.Builder(context.applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alarm_24)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(message)
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(message)
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = context.getString(R.string.app_name) }

                notificationManager.createNotificationChannel(channel)
            }
            notificationManager.notify(notificationId, notificationBuilder.build())
        }
    }

}