package com.meda.portico

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.meda.portico.home.HomeActivity
import com.meda.portico.setup.SetupActivity
import com.meda.portico.store.Settings

/**
 * The one launcher activity. Once this phone has an enrollment (an HA address and a token) it opens
 * Home, the cameras and buttons Home Assistant built for it; before that (a first install, nothing
 * scanned yet) it opens Setup, the only screen that can fix that. Home is where a phone with a
 * working enrollment lands every time, with Setup one tap away.
 *
 * First-install state is the case that was never tested on an upgraded phone: an unreadable Settings
 * (the Keystore failed) or an empty one is "not set up", never "set up".
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // APP-L5: the Keystore open is off the main thread; the launcher shows only its window background until it is done.
        PorticoApp.graph(this).withSettings { s ->
            val enrolled = isEnrolled(s)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val target = if (enrolled) HomeActivity::class.java else SetupActivity::class.java
                startActivity(Intent(this, target))
                finish()
            }
        }
    }

    private fun isEnrolled(s: Settings?): Boolean = try {
        s?.enrolled == true
    } catch (_: Exception) {
        false
    }
}
