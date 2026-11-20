package com.example.companion.ui

import com.example.companion.i18n.LocalizedActivity
import android.app.Activity
import android.content.Intent
import android.os.Bundle

class MainActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No role -> role picker; role without finished setup -> Setup; otherwise Main Phone -> HomeActivity, SIM Phone -> StatusActivity.
        val next = startActivityFor(app.prefs.role, app.prefs.setupCompleted)
        startActivity(Intent(this, next))
        finish()
    }
}
