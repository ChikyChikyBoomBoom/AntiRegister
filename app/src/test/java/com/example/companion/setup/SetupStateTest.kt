package com.example.companion.setup

import com.example.companion.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the Stage 22 setup decisions. WRITTEN BUT NOT RUN. */
class SetupStateTest {

    private val granted = PermissionFacts.GRANTED
    private val fresh = PermissionFacts(granted = false, askedBefore = false, showRationale = false)
    private val deniedOnce = PermissionFacts(granted = false, askedBefore = true, showRationale = true)
    private val deniedForGood = PermissionFacts(granted = false, askedBefore = true, showRationale = false)

    private fun input(
        role: Role? = Role.MAIN_PHONE, sdk: Int = 34,
        bluetooth: PermissionFacts = granted, bluetoothSupported: Boolean = true, bluetoothEnabled: Boolean = true,
        callPhone: PermissionFacts = granted, callRoleHeld: Boolean = true, callRoleAvailable: Boolean = true, callRoleAsked: Boolean = false,
        sms: PermissionFacts = granted, notifications: PermissionFacts = granted, notificationsEnabled: Boolean = true,
        contacts: PermissionFacts = fresh
    ) = SetupInput(role, sdk, bluetooth, bluetoothSupported, bluetoothEnabled, callPhone, callRoleHeld, callRoleAvailable, callRoleAsked,
        sms, notifications, notificationsEnabled, contacts)

    private fun rows(i: SetupInput) = SetupState.rows(i)
    private fun row(i: SetupInput, item: SetupItem) = rows(i).first { it.item == item }

    // ---- which rows exist

    @Test fun mainPhoneShowsOnlyItsOwnRows() {
        assertEquals(
            listOf(SetupItem.ROLE, SetupItem.BLUETOOTH, SetupItem.NOTIFICATIONS, SetupItem.CONTACTS),
            rows(input(Role.MAIN_PHONE)).map { it.item }
        )
    }

    @Test fun simPhoneShowsCallSmsAndNoContacts() {
        assertEquals(
            listOf(SetupItem.ROLE, SetupItem.BLUETOOTH, SetupItem.CALL_PERMISSION, SetupItem.CALL_ROLE, SetupItem.SMS, SetupItem.NOTIFICATIONS),
            rows(input(Role.SIM_PHONE)).map { it.item }
        )
    }

    @Test fun noRoleShowsOnlyTheRoleRowAsNeedingAction() {
        val r = rows(input(role = null))
        assertEquals(1, r.size)
        assertEquals(RowStatus.NEEDS_ACTION, r[0].status)
    }

    @Test fun roleRowShowsTheSavedRole() {
        assertTrue(row(input(Role.SIM_PHONE), SetupItem.ROLE).statusText.startsWith("SIM Phone"))
        assertEquals(RowStatus.OK, row(input(Role.MAIN_PHONE), SetupItem.ROLE).status)
    }

    // ---- permission access

    @Test fun accessFollowsAskedAndRationale() {
        assertEquals(PermissionAccess.GRANTED, SetupState.access(granted))
        assertEquals(PermissionAccess.CAN_ASK, SetupState.access(fresh))
        assertEquals(PermissionAccess.CAN_ASK, SetupState.access(deniedOnce))
        assertEquals(PermissionAccess.SETTINGS_ONLY, SetupState.access(deniedForGood))
    }

    // ---- Bluetooth

    @Test fun bluetoothNotYetAskedOffersTheRequest() {
        val r = row(input(bluetooth = fresh), SetupItem.BLUETOOTH)
        assertEquals(RowStatus.NEEDS_ACTION, r.status)
        assertEquals(SetupAction.REQUEST_PERMISSION, r.action)
        assertFalse(r.askedBefore)
    }

    @Test fun bluetoothBlockedOpensAppSettingsAndExplains() {
        val r = row(input(bluetooth = deniedForGood), SetupItem.BLUETOOTH)
        assertEquals(RowStatus.BLOCKED, r.status)
        assertEquals(SetupAction.OPEN_APP_SETTINGS, r.action)
        assertTrue(r.blockedHelp.isNotEmpty())
    }

    @Test fun bluetoothAllowedButAdapterOffOffersTurnOn() {
        val r = row(input(bluetoothEnabled = false), SetupItem.BLUETOOTH)
        assertEquals(RowStatus.NEEDS_ACTION, r.status)
        assertEquals(SetupAction.ENABLE_BLUETOOTH, r.action)
    }

    @Test fun bluetoothUnsupportedIsUnavailableWithNoAction() {
        val r = row(input(bluetoothSupported = false), SetupItem.BLUETOOTH)
        assertEquals(RowStatus.UNAVAILABLE, r.status)
        assertEquals(SetupAction.NONE, r.action)
    }

    @Test fun bluetoothReasonMentionsLocationOnlyForOldMainPhone() {
        assertTrue(row(input(Role.MAIN_PHONE, sdk = 30), SetupItem.BLUETOOTH).why.contains("Location"))
        assertFalse(row(input(Role.MAIN_PHONE, sdk = 34), SetupItem.BLUETOOTH).why.contains("Location"))
    }

    // ---- calls (SIM Phone)

    @Test fun callRoleStates() {
        val sim = Role.SIM_PHONE
        assertEquals(RowStatus.OK, row(input(sim, callRoleHeld = true), SetupItem.CALL_ROLE).status)
        val ask = row(input(sim, callRoleHeld = false), SetupItem.CALL_ROLE)
        assertEquals(SetupAction.REQUEST_CALL_ROLE, ask.action)
        assertEquals(RowStatus.UNAVAILABLE, row(input(sim, callRoleHeld = false, callRoleAvailable = false), SetupItem.CALL_ROLE).status)
    }

    @Test fun callPermissionBlockedOpensAppSettings() {
        val r = row(input(Role.SIM_PHONE, callPhone = deniedForGood), SetupItem.CALL_PERMISSION)
        assertEquals(RowStatus.BLOCKED, r.status)
        assertEquals(SetupAction.OPEN_APP_SETTINGS, r.action)
    }

    // ---- SMS (SIM Phone)

    @Test fun smsBlockedMentionsRestrictedSettings() {
        val r = row(input(Role.SIM_PHONE, sms = deniedForGood), SetupItem.SMS)
        assertEquals(SetupAction.OPEN_APP_SETTINGS, r.action)
        assertTrue(r.blockedHelp.contains("restricted settings"))
    }

    // ---- notifications

    @Test fun notificationsAreRecommendedNotRequired() {
        assertEquals(Importance.RECOMMENDED, row(input(), SetupItem.NOTIFICATIONS).importance)
    }

    @Test fun notificationsBlockedOpensNotificationSettings() {
        val r = row(input(notifications = deniedForGood), SetupItem.NOTIFICATIONS)
        assertEquals(RowStatus.BLOCKED, r.status)
        assertEquals(SetupAction.OPEN_NOTIFICATION_SETTINGS, r.action)
    }

    @Test fun notificationsAllowedButSwitchedOffInSystemNeedsAction() {
        val r = row(input(notificationsEnabled = false), SetupItem.NOTIFICATIONS)
        assertEquals(RowStatus.NEEDS_ACTION, r.status)
        assertEquals(SetupAction.OPEN_NOTIFICATION_SETTINGS, r.action)
    }

    @Test fun oldAndroidNeedsNoNotificationPermission() {
        assertEquals(RowStatus.OK, row(input(sdk = 30), SetupItem.NOTIFICATIONS).status)
    }

    // ---- contacts

    @Test fun contactsOffIsOptionalAndNeverActionable() {
        val r = row(input(contacts = fresh), SetupItem.CONTACTS)
        assertEquals(Importance.OPTIONAL, r.importance)
        assertEquals(RowStatus.OPTIONAL_OFF, r.status)
        assertEquals(SetupAction.REQUEST_PERMISSION, r.action)
        assertFalse(SetupState.actionable(r))
    }

    @Test fun contactsBlockedOpensAppSettings() {
        assertEquals(SetupAction.OPEN_APP_SETTINGS, row(input(contacts = deniedForGood), SetupItem.CONTACTS).action)
    }

    @Test fun contactsGrantedIsOk() {
        assertEquals(RowStatus.OK, row(input(contacts = granted), SetupItem.CONTACTS).status)
    }

    // ---- guided setup (no repeated requests)

    @Test fun guidedQueueHasOnlyNeverAskedRequestableItemsInOrder() {
        val i = input(
            Role.SIM_PHONE, bluetooth = fresh, callPhone = fresh, callRoleHeld = false, callRoleAsked = false,
            sms = fresh, notifications = fresh
        )
        assertEquals(
            listOf(SetupItem.BLUETOOTH, SetupItem.CALL_PERMISSION, SetupItem.CALL_ROLE, SetupItem.SMS, SetupItem.NOTIFICATIONS),
            SetupState.guidedQueue(rows(i))
        )
    }

    @Test fun guidedQueueSkipsWhatWasAlreadyDeniedOrBlocked() {
        val i = input(Role.SIM_PHONE, bluetooth = deniedOnce, callPhone = deniedForGood, callRoleHeld = false, callRoleAsked = true, sms = fresh, notifications = deniedOnce)
        assertEquals(listOf(SetupItem.SMS), SetupState.guidedQueue(rows(i)))
    }

    @Test fun guidedQueueNeverIncludesContactsOrSettingsPages() {
        val q = SetupState.guidedQueue(rows(input(Role.MAIN_PHONE, bluetooth = fresh, notifications = fresh, contacts = fresh)))
        assertEquals(listOf(SetupItem.BLUETOOTH, SetupItem.NOTIFICATIONS), q)
        assertTrue(SetupState.guidedQueue(rows(input(bluetoothEnabled = false))).isEmpty())
    }

    @Test fun guidedQueueEmptyWhenEverythingIsSet() {
        assertTrue(SetupState.guidedQueue(rows(input(Role.SIM_PHONE))).isEmpty())
    }

    // ---- summary / banner

    @Test fun nothingActionableWhenAllGrantedEvenIfContactsOff() {
        assertTrue(rows(input()).none { SetupState.actionable(it) })
    }

    @Test fun summaryCountsRequiredBeforeRecommended() {
        val i = input(Role.SIM_PHONE, bluetooth = fresh, sms = fresh, notifications = fresh)
        assertEquals("2 required items still need setup.", SetupState.summaryLine(rows(i)))
        assertEquals("1 required item still needs setup.", SetupState.summaryLine(rows(input(Role.SIM_PHONE, sms = fresh))))
        assertEquals("Everything required is set up. 1 recommended item left.", SetupState.summaryLine(rows(input(notifications = fresh))))
        assertEquals("Everything is set up.", SetupState.summaryLine(rows(input())))
    }

    @Test fun bannerNamesBlockingItemsOnly() {
        assertEquals("", SetupState.bannerText(rows(input(notifications = fresh, bluetoothEnabled = false))))   // recommended + adapter off: no banner
        assertEquals("Setup incomplete: Bluetooth", SetupState.bannerText(rows(input(bluetooth = deniedForGood))))
        assertEquals("Setup incomplete: Default phone app, SMS",
            SetupState.bannerText(rows(input(Role.SIM_PHONE, callRoleHeld = false, sms = fresh))))
    }

    @Test fun unavailableItemsDoNotBlockOrCount() {
        val i = input(Role.SIM_PHONE, callRoleHeld = false, callRoleAvailable = false)
        assertTrue(SetupState.blockingRows(rows(i)).isEmpty())
        assertEquals("Everything is set up.", SetupState.summaryLine(rows(i)))
    }
}
