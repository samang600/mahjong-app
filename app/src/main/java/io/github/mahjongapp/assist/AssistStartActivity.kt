package io.github.mahjongapp.assist

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import io.github.mahjongapp.R

/**
 * Starts the screen-recognition overlay: asks for "display over other apps" and for screen
 * capture consent, then hands the consent to [OverlayService]. Has no UI of its own.
 */
class AssistStartActivity : Activity() {

    private companion object {
        const val REQ_CAPTURE = 1
        const val REQ_NOTIFY = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.assist_need_overlay, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        } else {
            askCapture()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // The notification is optional (the service still runs); continue either way.
        if (requestCode == REQ_NOTIFY) askCapture()
    }

    private fun askCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                startForegroundService(
                    Intent(this, OverlayService::class.java)
                        .putExtra(OverlayService.EXTRA_CODE, resultCode)
                        .putExtra(OverlayService.EXTRA_DATA, data)
                )
                Toast.makeText(this, R.string.assist_started, Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }
}
