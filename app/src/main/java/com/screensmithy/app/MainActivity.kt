package com.screensmithy.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.screensmithy.app.databinding.ActivityMainBinding

/**
 * Foundation Activity for ScreenSmithy.
 * Confirms application launch, TV-friendly landscape display, and remote D-pad input handling.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDpadTest()
    }

    private fun setupDpadTest() {
        binding.btnTestDpad.setOnClickListener {
            binding.txtFeedback.apply {
                visibility = View.VISIBLE
                text = getString(R.string.dpad_feedback_pressed)
            }
        }

        // On TV devices, ensure the test button is focused by default
        binding.btnTestDpad.requestFocus()
    }
}
