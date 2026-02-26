package com.poolaim.overlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.poolaim.overlay.databinding.ActivityMainBinding
import com.poolaim.overlay.service.OverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false

    companion object {
        private const val REQUEST_OVERLAY    = 1001
        private const val REQUEST_PROJECTION = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupButtons(); setupSettings(); updateUI()
    }

    override fun onResume() { super.onResume(); updateUI() }

    private fun setupButtons() {
        // Manual start (existing behavior)
        binding.btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) requestOverlayPermission() else startOverlayService()
        }
        binding.btnStop.setOnClickListener { stopOverlayService() }

        // CV Mode start
        binding.btnStartCv.setOnClickListener {
            when {
                !Settings.canDrawOverlays(this) -> requestOverlayPermission()
                !isServiceRunning               -> startOverlayService()   // start service first, then CV
            }
            requestMediaProjection()
        }
    }

    private fun setupSettings() {
        binding.seekBounces.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                binding.tvBounces.text = getString(R.string.label_max_bounces, p); if (u) saveSettings()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.seekThickness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { if (u) saveSettings() }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.seekBallRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                binding.tvBallRadius.text = "Ball Radius: ${p}px"; if (u) saveSettings()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val prefs = getSharedPreferences(OverlayService.PREFS_NAME, MODE_PRIVATE)
        binding.seekBounces.progress   = prefs.getInt("max_bounces", 3)
        binding.seekThickness.progress = prefs.getFloat("line_thickness", 3f).toInt()
        binding.seekBallRadius.progress = prefs.getFloat("ball_radius", 15f).toInt()
        binding.tvBounces.text    = getString(R.string.label_max_bounces, binding.seekBounces.progress)
        binding.tvBallRadius.text = "Ball Radius: ${binding.seekBallRadius.progress}px"
    }

    private fun saveSettings() {
        getSharedPreferences(OverlayService.PREFS_NAME, MODE_PRIVATE).edit()
            .putInt("max_bounces",     binding.seekBounces.progress)
            .putFloat("line_thickness", binding.seekThickness.progress.toFloat())
            .putFloat("ball_radius",    binding.seekBallRadius.progress.toFloat()).apply()
    }

    private fun requestOverlayPermission() {
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            REQUEST_OVERLAY
        )
    }

    private fun requestMediaProjection() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) startOverlayService()
                else Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_LONG).show()
            }
            REQUEST_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // Make sure the service is running before sending the projection token
                    if (!isServiceRunning) startOverlayService()
                    val cvIntent = Intent(this, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_START_CV
                        putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(OverlayService.EXTRA_RESULT_DATA, data)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(cvIntent)
                    else startService(cvIntent)
                    Toast.makeText(this, "⚡ CV Mode active! Switch to 8 Ball Pool", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        isServiceRunning = true; updateUI()
        Toast.makeText(this, "Overlay started! Switch to 8 Ball Pool", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlayService() {
        startService(Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_STOP })
        isServiceRunning = false; updateUI()
    }

    private fun updateUI() {
        val hasPerm = Settings.canDrawOverlays(this)
        binding.btnStart.isEnabled   = !isServiceRunning
        binding.btnStop.isEnabled    = isServiceRunning
        binding.btnStartCv.isEnabled = hasPerm   // CV button always available once overlay perm granted
        binding.tvStatus.text = when {
            !hasPerm        -> "⚠ Overlay permission needed"
            isServiceRunning -> getString(R.string.status_active)
            else             -> getString(R.string.status_inactive)
        }
        binding.btnStart.text = if (hasPerm) getString(R.string.btn_start) else getString(R.string.btn_permission)
    }
}
