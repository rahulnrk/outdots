package app.mini.hub.android.connector

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * rtprobe-only trampoline. It exists solely in debug/rtprobe builds and simply
 * forwards the owner into the isolated [InstagramLoginActivity] on the dedicated
 * `:iglogin` process. A fresh provisional slot key is used; the stored account
 * is rebound to the upstream-verified user id on success.
 *
 * Launch from the owner's device:
 *   adb shell am start -n app.mini.hub.rtprobe/app.mini.hub.android.connector.InstagramLoginProbeLauncher
 * or tap "Mini IG Probe" from the launcher on the rtprobe build.
 */
class InstagramLoginProbeLauncher : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val slotKey = "instagram|probe-${System.currentTimeMillis()}"
        startActivity(
            Intent(this, InstagramLoginActivity::class.java)
                .putExtra(InstagramLoginActivity.EXTRA_SLOT_KEY, slotKey),
        )
        finish()
    }
}
