package com.example.companion.ui

import android.app.Activity
import com.example.companion.data.Role

/** The first screen for a role: Main Phone gets the home screen, SIM Phone keeps the connection status screen. */
fun homeActivityFor(role: Role?): Class<out Activity> = when (role) {
    null -> RoleSelectActivity::class.java
    Role.MAIN_PHONE -> HomeActivity::class.java
    Role.SIM_PHONE -> StatusActivity::class.java
}

/**
 * Stage 22: where the app opens. No role yet: the role picker. Role chosen but its setup not finished (first run, or just
 * switched role): the Setup screen. Otherwise the role's home screen exactly as before.
 */
fun startActivityFor(role: Role?, setupCompleted: Boolean): Class<out Activity> = when {
    role == null -> RoleSelectActivity::class.java
    !setupCompleted -> SetupActivity::class.java
    else -> homeActivityFor(role)
}
