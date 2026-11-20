package com.example.companion.contacts

/**
 * What Android's Contacts provider said about one phone number. Held in memory only, for a few minutes
 * ([ContactCache]); never written to a file or database and never sent anywhere.
 */
data class ContactMatch(
    val name: String,
    /** Content URI string of the contact's thumbnail (read through ContentResolver when drawn), or null when there is no photo. */
    val photoUri: String?
)
