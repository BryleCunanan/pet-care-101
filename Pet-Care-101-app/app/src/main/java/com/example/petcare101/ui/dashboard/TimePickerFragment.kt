package com.example.petcare101.ui.dashboard

import android.app.Dialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.TimePicker
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class TimePickerFragment : DialogFragment(), TimePickerDialog.OnTimeSetListener {

    private val c = Calendar.getInstance()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {

        val hour = c.get(Calendar.HOUR)
        val minute = c.get(Calendar.MINUTE)
        return TimePickerDialog(requireActivity(),this,hour,minute,false)
    }

    override fun onTimeSet(view: TimePicker?, hourOfDay: Int, minute: Int) {
        c.set(Calendar.HOUR_OF_DAY, hourOfDay)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND,0)

        val selectedTime = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH).format(c.time)
        val timeDisplayed = SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(c.time)

        val selectedTimeBundle = Bundle()
        selectedTimeBundle.putString("SELECTED_TIME", selectedTime)
        selectedTimeBundle.putString("TIME_DISPLAYED", timeDisplayed)

        setFragmentResult("REQUEST_KEY",selectedTimeBundle)
    }
}