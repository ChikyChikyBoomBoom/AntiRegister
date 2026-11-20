package com.example.companion.data

enum class Role(val label: String) {
    SIM_PHONE("SIM Phone"),   // host: owns the SIM, listens for the main phone
    MAIN_PHONE("Main Phone")  // client: user interface, connects to the SIM phone
}
