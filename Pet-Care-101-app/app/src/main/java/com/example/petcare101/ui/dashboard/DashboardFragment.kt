package com.example.petcare101.ui.dashboard

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.petcare101.databinding.FragmentDashboardBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FirebaseStorage
import java.text.SimpleDateFormat
import java.util.Locale


class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null

    private val storage = FirebaseStorage.getInstance()

    // This property is only valid between onCreateView and
// onDestroyView.
    private val binding get() = _binding!!

    private lateinit var database: DatabaseReference
    private val storageRef = storage.reference
    private val imagesRef = storageRef.child("data/photo.jpg")

    private lateinit var timeButtons: Array<Button>
    private var timePicked: Array<String> = arrayOf("","","")
    private lateinit var timeDisplayed: Array<TextView>
    private val isTrue: Boolean = true
    private var postListener : ValueEventListener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
//        val dashboardViewModel =
//            ViewModelProvider(this).get(DashboardViewModel::class.java)

        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        val root: View = binding.root

            timeDisplayed = arrayOf(binding.textTimePicker1,binding.textTimePicker2,binding.textTimePicker3)


            readData()
            setWeight()
            setTime()
            streamPlayback(requireContext())


        return root
    }

    private fun streamPlayback(context: Context) {
        if (context == null) {
            // Fragment is not attached to a context, cannot proceed
            return
        }

        val database = FirebaseDatabase.getInstance().getReference("Camera")

        var initCounter = 0

        val strmFlag = true

        postListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {

                val counter = snapshot.value.toString().toInt()
                if (counter != initCounter) {
                    initCounter = counter

                    imagesRef.getBytes(Long.MAX_VALUE).addOnSuccessListener {
                        // Check if binding is still not null before updating UI
                        _binding?.cameraView?.setImageBitmap(
                            BitmapFactory.decodeByteArray(
                                it,
                                0,
                                it.size
                                    )
                                )
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(requireActivity(),"Failed",Toast.LENGTH_SHORT).show()
            }
        }

        database.child("counter").addValueEventListener(postListener!!)

        binding.btnStreamFlag.setOnClickListener {
            database.child("streamFlag").setValue(strmFlag).addOnSuccessListener {
                Toast.makeText(requireActivity(), "Waiting for image",Toast.LENGTH_SHORT).show()
            }.addOnFailureListener{
                Toast.makeText(requireActivity(), "Failed to request image", Toast.LENGTH_SHORT).show()
            }

        }
    }

    private fun setTime() {
        timeButtons = arrayOf(binding.btnTimePicker1, binding.btnTimePicker2, binding.btnTimePicker3)

        for(i in timeButtons.indices){
            timeButtons[i].setOnClickListener{timeButtonClicked(i)}
        }
    }

    private fun timeButtonClicked(i:Int) {
        val timePickerFragment = TimePickerFragment()
        val supportFragmentManager = requireActivity().supportFragmentManager


        supportFragmentManager.setFragmentResultListener(
            "REQUEST_KEY",
            viewLifecycleOwner
        ) { resultKey, bundle ->
            if (resultKey == "REQUEST_KEY") {
                val time = bundle.getString("SELECTED_TIME")
                val timeDisplay = bundle.getString("TIME_DISPLAYED")

                timeDisplayed[i].text = timeDisplay
                timePicked[i] = time.toString()

                database = FirebaseDatabase.getInstance().getReference("Clock")
                val n = i+1
                database.child("setTime$n").setValue(timePicked[i]).addOnFailureListener {
                    Toast.makeText(requireActivity(),"Update failed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        timePickerFragment.show(supportFragmentManager, "timePicker")
    }

    private fun readData() {
        database = FirebaseDatabase.getInstance().reference


        database.child("Clock/setTime1").get().addOnSuccessListener {
            convertTo12Hour(it,0)
        }.addOnFailureListener {
            Toast.makeText(requireActivity(),"Read failed", Toast.LENGTH_SHORT).show()
        }

        database.child("Clock/setTime2").get().addOnSuccessListener {
            convertTo12Hour(it,1)
        }.addOnFailureListener {
            Toast.makeText(requireActivity(),"Read failed", Toast.LENGTH_SHORT).show()
        }

        database.child("Clock/setTime3").get().addOnSuccessListener {
            convertTo12Hour(it,2)
        }.addOnFailureListener {
            Toast.makeText(requireActivity(),"Read failed", Toast.LENGTH_SHORT).show()
        }

        database.child("Dispenser/weight").get().addOnSuccessListener {
            val petWeight = it.value

            binding.textPetWeight.setText(petWeight.toString())
        }.addOnFailureListener {
            Toast.makeText(requireActivity(), "Failed to retrieve data.", Toast.LENGTH_SHORT).show()
        }


    }


    private fun setWeight() {
        database = FirebaseDatabase.getInstance().getReference("Dispenser")

        binding.btnSetWeight.setOnClickListener {
            if(binding.textPetWeight.text.toString() != "") {
                val petWeight = binding.textPetWeight.text.toString().toDouble()

                database.child("weight").setValue(petWeight).addOnSuccessListener {
                    Toast.makeText(requireActivity(), "Set successfully.", Toast.LENGTH_SHORT)
                        .show()
                }.addOnFailureListener {
                    Toast.makeText(requireActivity(), "Failed.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun convertTo12Hour(it: DataSnapshot, i: Int) {
        if (it.exists()) {
            val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH)
            val cloudTime: String = it.value.toString()
            val formattedTime = dateFormat.parse(cloudTime)
            val displayFormat = SimpleDateFormat("hh:mm a", Locale.ENGLISH)
            val displayTime = displayFormat.format(formattedTime!!)

            timeDisplayed[i].text = displayTime
            timePicked[i] = cloudTime
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
            streamPlayback(requireContext())
    }

    override fun onStop() {
        super.onStop()

        postListener?.let {
            database.removeEventListener(it)
        }
    }

}
