package com.example.petcare101.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.petcare101.R
import com.example.petcare101.databinding.FragmentHomeBinding
import com.example.petcare101.ui.notifications.CAMERA_ZOOM_LEVEL
import com.example.petcare101.utils.PermissionManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener


class HomeFragment : Fragment(){

    private var _binding: FragmentHomeBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private lateinit var database: DatabaseReference
    private val REQUEST_CODE_LOCATION_PERMISSION = 1
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val locationRequest = LocationRequest.create().apply {
        interval = 5000 // Update interval in milliseconds
        fastestInterval = 5000 // Fastest update interval in milliseconds
        priority = LocationRequest.PRIORITY_HIGH_ACCURACY // Location accuracy priority
    }
    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap
    private var petMarker: Marker? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val homeViewModel =
            ViewModelProvider(this).get(HomeViewModel::class.java)

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        getBPM()
        initLocationClient()
        val isPermissionGranted = PermissionManager.checkLocationPermission(requireActivity())
        if (!isPermissionGranted) {
            // Location permission is not granted, request it
            PermissionManager.requestLocationPermission(requireActivity(),REQUEST_CODE_LOCATION_PERMISSION)
        } else {
            // Location permission is granted, proceed with your logic
            // For example, start location updates, show the map, etc.
            mapView = binding.mapViewMap

            mapView.onCreate(savedInstanceState)
            mapView.getMapAsync { map ->
                googleMap = map
                if (::googleMap.isInitialized) {
                    googleMap.isMyLocationEnabled = true
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
                }
            }

            startLocationUpdates()
        }

        return root
    }

    private fun getBPM() {
        database = FirebaseDatabase.getInstance().getReference("Harness/bpm")

       database.addValueEventListener(object :ValueEventListener{
           override fun onDataChange(snapshot: DataSnapshot) {
               val bpm = snapshot.value.toString()

               binding.txtBPM.text = bpm;
           }

           override fun onCancelled(error: DatabaseError) {
               Toast.makeText(requireContext(),"Failed to read bpm", Toast.LENGTH_SHORT).show()
           }
       })
    }

    private fun initLocationClient() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
    }

    private fun checkLocationPermission() {
        val isPermissionGranted = PermissionManager.checkLocationPermission(requireActivity())
        if (isPermissionGranted) {
            // Location permission is granted, proceed with your logic
            // For example, start location updates, show the map, etc.
            if (::googleMap.isInitialized) {
                googleMap.isMyLocationEnabled = true
            }
            startLocationUpdates()
        } else {
            // Location permission is not granted, request it
            PermissionManager.requestLocationPermission(requireActivity(),REQUEST_CODE_LOCATION_PERMISSION)
        }
    }

    private fun requestLocationPermission() {
      PermissionManager.requestLocationPermission(requireActivity(),REQUEST_CODE_LOCATION_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
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
                    Toast.makeText(
                        requireContext(),
                        "The app needs location permission to function",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
                if (::googleMap.isInitialized) {
                    googleMap.isMyLocationEnabled = true
                }
                startLocationUpdates()
            }
    }

    private fun updateMarker(location: LatLng) {
        database = FirebaseDatabase.getInstance().getReference("Harness")

        database.get().addOnSuccessListener { dataSnapshot ->
            val petLng = dataSnapshot.child("lng").value.toString().toDouble()
            val petLat = dataSnapshot.child("lat").value.toString().toDouble()
            val petLatLng = LatLng(petLat, petLng)
            binding.txtLat.text = petLat.toString()
            binding.txtLng.text = petLng.toString()

            if (isAdded) {
                // Remove existing marker if any
                petMarker?.remove()

                val bitmap = bitmapFromVector(requireContext(), R.drawable.ic_pet_marker_24)
                // Add/update marker with latest position
                petMarker = googleMap.addMarker(
                    MarkerOptions()
                        .position(petLatLng)
                        .title("Pet's Location")
                        .icon(bitmap)
                )
                // Proceed with updating the marker
            }



            // Zoom to fit both markers
            val builder = LatLngBounds.Builder()
            builder.include(location)
            builder.include(petLatLng)
            val bounds = builder.build()
            val padding = 100 // Padding around markers
            val cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, padding)
            googleMap.moveCamera(cameraUpdate)

        }.addOnFailureListener {
            Toast.makeText(requireActivity(),"Read failed", Toast.LENGTH_SHORT).show()
        }

    }

    private fun bitmapFromVector(context: Context?, vectorResId: Int): BitmapDescriptor {
        if (context == null) {
            // Log an error or handle the case where the context is null
            // Return a default BitmapDescriptor or handle the error appropriately
            return BitmapDescriptorFactory.defaultMarker()
        }

        // Below line is used to generate a drawable.
        val vectorDrawable = ContextCompat.getDrawable(context, vectorResId)

        // Below line is used to set bounds to our vector drawable.
        vectorDrawable?.setBounds(
            0, 0, vectorDrawable.intrinsicWidth,
            vectorDrawable.intrinsicHeight
        )

        // Below line is used to create a bitmap for our drawable.
        val bitmap = Bitmap.createBitmap(
            vectorDrawable!!.intrinsicWidth,
            vectorDrawable.intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )

        // Below line is used to add bitmap in our canvas.
        val canvas = Canvas(bitmap)

        // Below line is used to draw our vector drawable in canvas.
        vectorDrawable.draw(canvas)

        // After generating our bitmap, we are returning our bitmap descriptor.
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(
                requireActivity(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } else {
            // Request location permission if not granted
            if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                // Explain to the user why the permission is needed
                Toast.makeText(
                    requireContext(),
                    "Location permission is required for the app to function properly.",
                    Toast.LENGTH_LONG
                ).show()
            }
            requestLocationPermission()
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation.let { location ->
                val currLatLng = LatLng(location.latitude, location.longitude)
                updateMarker(currLatLng)
            }
        }
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

}