package com.example.companion.i18n

import com.example.companion.bluetooth.ConnectionManager.State
import com.example.companion.bluetooth.ReconnectPolicy
import com.example.companion.data.Role
import com.example.companion.setup.PermissionFacts
import com.example.companion.setup.SetupAction
import com.example.companion.setup.SetupInput
import com.example.companion.setup.SetupItem
import com.example.companion.setup.SetupState
import com.example.companion.ui.DeviceChangeBlock
import com.example.companion.ui.SettingsState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests of the Stage 15 localization of Settings, the first-run welcome, Setup and the device picker. WRITTEN BUT NOT RUN (no Gradle /
 * Android SDK was available). Checks: the original English wording is unchanged, every Stage 15 text has a Persian version, a missing
 * Persian text falls back to English, switching the language changes what these sections read, and the decisions behind them
 * (what is blocked, which rows exist, what is asked first) do not depend on the language.
 */
class Stage15LocalizationTest {

    private val en = AppLanguage.ENGLISH
    private val fa = AppLanguage.PERSIAN

    @After fun resetLanguage() { AppLocale.setForTest(AppLanguage.DEFAULT) }

    private val stage15Prefixes = listOf("ROLE_", "ONBOARD_", "SETTINGS_", "SETUP_", "PICKER_")
    private val stage15Keys get() = StringKey.values().filter { k -> stage15Prefixes.any { k.name.startsWith(it) } }

    private fun hasLatin(s: String) = Regex("[A-Za-z]").containsMatchIn(s.replace(Regex("%\\d+\\$[sd]"), ""))

    // ---------------------------------------------------------------- coverage and fallback

    @Test fun everyStage15KeyHasAPersianTextWithTheSamePlaceholders() {
        assertTrue(stage15Keys.size >= 140)
        val missing = stage15Keys.filter { !Strings.isTranslated(it, fa) }
        assertTrue("missing Persian: $missing", missing.isEmpty())
        val re = Regex("%\\d+\\$[sd]")
        stage15Keys.forEach { k ->
            val persian = Strings.text(k, fa)
            assertNotEquals(k.name, k.english, persian)
            assertFalse("${k.name} has Latin letters: $persian", hasLatin(persian))
            assertEquals(k.name, re.findAll(k.english).map { it.value }.toSet(), re.findAll(persian).map { it.value }.toSet())
        }
    }

    @Test fun missingPersianTextFallsBackToEnglish() {
        val empty = emptyMap<StringKey, String>()
        stage15Keys.forEach { assertEquals(it.name, it.english, Strings.text(it, fa, empty)) }
        val partial = PersianStrings.table - StringKey.SETTINGS_CONNECT
        assertEquals("Connect", Strings.text(StringKey.SETTINGS_CONNECT, fa, partial))
        assertEquals(Strings.text(StringKey.SETTINGS_DISCONNECT, fa), Strings.text(StringKey.SETTINGS_DISCONNECT, fa, partial))
    }

    @Test fun previousStagesTextsAreStillThere() {
        // Stage 12-14 keys keep their English and their Persian (nothing was renamed or removed).
        assertEquals("Settings", StringKey.HOME_TILE_SETTINGS.english)
        assertEquals("تنظیمات", Strings.text(StringKey.HOME_TILE_SETTINGS, fa))
        assertEquals("Language", StringKey.LANGUAGE_TITLE.english)
        assertEquals("Attempt %1\$s of %2\$s", StringKey.HOME_DETAIL_ATTEMPT_OF.english)
        assertEquals("آنتی رجیستر", Strings.text(StringKey.APP_NAME, fa))
    }

    // ---------------------------------------------------------------- Settings: English is unchanged (default language)

    private fun ui(
        role: Role? = Role.MAIN_PHONE, state: State = State.DISCONNECTED, connectedName: String? = null,
        savedName: String? = "Pixel 7", savedAddress: String? = "AA:BB:CC:DD:EE:FF", retry: Int = 0, lastError: String? = null,
        language: AppLanguage = en
    ) = SettingsState.from(role, state, connectedName, savedName, savedAddress, retry, lastError, language)

    @Test fun settingsEnglishIsUnchanged() {
        assertEquals("Main Phone", ui(Role.MAIN_PHONE).roleLine)
        assertEquals("SIM Phone", ui(Role.SIM_PHONE).roleLine)
        assertEquals("Not selected", ui(null).roleLine)
        assertEquals("Connect", ui().connectLabel)
        assertEquals("Disconnect", ui(state = State.CONNECTED, connectedName = "Pixel 7").connectLabel)
        assertEquals("Change device", ui().changeDeviceLabel)
        assertEquals("Select SIM Phone", ui(savedName = null, savedAddress = null).changeDeviceLabel)
        assertEquals("Select a SIM Phone first.", ui(savedName = null, savedAddress = null).connectHint)
        assertEquals("No SIM Phone selected", ui(savedName = null, savedAddress = null).deviceLine)
        assertEquals("Pixel 7\nAA:BB:CC:DD:EE:FF", ui().deviceLine)
        assertEquals("No Main Phone connected", ui(Role.SIM_PHONE).deviceLine)
        assertEquals("Main Phone", ui(Role.SIM_PHONE, State.CONNECTED, connectedName = null).deviceLine)
        assertEquals("Confirm the pairing request on both phones.", ui(state = State.PAIRING).detail)
        assertEquals("If the link drops, the Main Phone keeps trying to reconnect. When off, press Connect yourself.", ui().autoReconnectNote)
        assertEquals("The SIM Phone always starts listening again after a drop, so this setting is not used on this phone.", ui(Role.SIM_PHONE).autoReconnectNote)
    }

    @Test fun settingsStatusTextEnglishIsUnchanged() {
        val expected = mapOf(
            State.CONNECTED to "Connected", State.CONNECTING to "Connecting…", State.PAIRING to "Pairing",
            State.RECONNECTING to "Reconnecting…", State.LISTENING to "Waiting for the Main Phone",
            State.DISCOVERING to "Scanning for devices…", State.DISCONNECTED to "Disconnected", State.ERROR to "Disconnected"
        )
        State.values().forEach { assertEquals(it.name, expected.getValue(it), SettingsState.statusText(it)) }
    }

    @Test fun attemptTextEnglishEqualsTheReconnectPolicyText() {
        (1..ReconnectPolicy.FAST_ATTEMPTS + 3).forEach { assertEquals(ReconnectPolicy.attemptText(it), SettingsState.attemptText(it)) }
        assertEquals("Attempt 2 of 6\nboom", ui(state = State.RECONNECTING, retry = 2, lastError = "boom").detail)
    }

    @Test fun appInfoEnglishIsUnchanged() {
        val rows = SettingsState.appInfo("0.35", 35L, "com.example.companion", "14", 34, "Google Pixel", null)
        assertEquals(
            listOf(
                "App" to "AntiRegister", "Version" to "0.35 (35)", "Package" to "com.example.companion", "Android" to "14 (API 34)",
                "This phone" to "Google Pixel", "Bluetooth name" to "-", "Data" to "Stored on this phone only. No account, no cloud."
            ), rows
        )
    }

    @Test fun roleChangeBlockEnglishIsUnchanged() {
        assertEquals("Finish the current call before changing the role.", SettingsState.roleChangeBlock(true))
        assertEquals(null, SettingsState.roleChangeBlock(false))
    }

    // ---------------------------------------------------------------- Settings: Persian

    @Test fun settingsPersianTextsAreInPersian() {
        val p = ui(language = fa)
        assertEquals("گوشی اصلی", p.roleLine)
        assertEquals("برقراری اتصال", p.connectLabel)
        assertEquals("قطع شده", p.statusLine)
        assertFalse(hasLatin(p.autoReconnectNote))
        assertFalse(hasLatin(ui(Role.SIM_PHONE, language = fa).autoReconnectNote))
        assertEquals("انتخاب نشده", ui(null, language = fa).roleLine)
        assertEquals("تلاش ۲ از ۶", SettingsState.attemptText(2, fa))
        assertEquals("تلاش ۹", SettingsState.attemptText(9, fa))
        assertEquals("پیش از تغییر نقش، تماس جاری را تمام کنید.", SettingsState.roleChangeBlock(true, fa))
    }

    @Test fun settingsAboutKeepsDataAsIsInsidePersianText() {
        val rows = SettingsState.appInfo("0.35", 35L, "com.example.companion", "14", 34, "Google Pixel", "My phone", fa)
        assertEquals("برنامه" to "آنتی رجیستر", rows[0])
        assertEquals("نسخه", rows[1].first)
        assertTrue(rows[1].second.contains("0.35 (35)"))
        assertTrue(rows[2].second.contains("com.example.companion"))
        assertTrue(rows[5].second.contains("My phone"))
        assertEquals(rows.size, 7)
    }

    @Test fun settingsDecisionsDoNotDependOnTheLanguage() {
        State.values().forEach { st ->
            Role.values().forEach { r ->
                val a = ui(r, st); val b = ui(r, st, language = fa)
                assertEquals(a.connectEnabled, b.connectEnabled)
                assertEquals(a.canChangeDevice, b.canChangeDevice)
                assertEquals(a.autoReconnectApplies, b.autoReconnectApplies)
            }
        }
        assertEquals(DeviceChangeBlock.CALL_IN_PROGRESS, SettingsState.deviceChangeBlock(Role.MAIN_PHONE, true, true, true, true))
        assertEquals(DeviceChangeBlock.NOT_MAIN_PHONE, SettingsState.deviceChangeBlock(Role.SIM_PHONE, false, true, true, true))
    }

    @Test fun roleLabelsFollowTheLanguageAndKeepTheEnglishLabelProperty() {
        assertEquals("SIM Phone", Role.SIM_PHONE.localizedLabel(en))
        assertEquals("Main Phone", Role.MAIN_PHONE.localizedLabel(en))
        assertEquals(Role.SIM_PHONE.label, Role.SIM_PHONE.localizedLabel(en))
        assertEquals("گوشی سیم‌کارت", Role.SIM_PHONE.localizedLabel(fa))
        assertEquals("SIM Phone", Role.SIM_PHONE.label)       // the property itself is untouched
    }

    // ---------------------------------------------------------------- Setup rows

    private val granted = PermissionFacts.GRANTED
    private val fresh = PermissionFacts(granted = false, askedBefore = false, showRationale = false)
    private val deniedForGood = PermissionFacts(granted = false, askedBefore = true, showRationale = false)

    private fun input(
        role: Role? = Role.MAIN_PHONE, sdk: Int = 34, bluetooth: PermissionFacts = granted, bluetoothSupported: Boolean = true,
        bluetoothEnabled: Boolean = true, callPhone: PermissionFacts = granted, callRoleHeld: Boolean = true, callRoleAvailable: Boolean = true,
        callRoleAsked: Boolean = false, sms: PermissionFacts = granted, notifications: PermissionFacts = granted,
        notificationsEnabled: Boolean = true, contacts: PermissionFacts = fresh
    ) = SetupInput(role, sdk, bluetooth, bluetoothSupported, bluetoothEnabled, callPhone, callRoleHeld, callRoleAvailable, callRoleAsked,
        sms, notifications, notificationsEnabled, contacts)

    @Test fun setupRowsEnglishIsUnchanged() {
        val main = SetupState.rows(input(bluetooth = fresh, notifications = fresh))
        assertEquals("Role", main[0].title)
        assertEquals("Main Phone (saved on this phone)", main[0].statusText)
        assertEquals("This phone is the one you use. It shows and controls the calls and texts of the SIM Phone.", main[0].why)
        assertEquals("Permission needed", main[1].statusText)
        assertEquals("Allow Bluetooth", main[1].actionLabel)
        assertEquals("Lets this phone find and connect to your SIM Phone over Bluetooth. Android lists this as \"Nearby devices\".", main[1].why)
        assertEquals("Contacts (optional)", main[3].title)
        assertEquals("Off. Phone numbers are shown", main[3].statusText)
        assertEquals("Show contact names", main[3].actionLabel)
        assertEquals(SetupState.SETTINGS_HELP, SetupState.rows(input(bluetooth = deniedForGood))[1].blockedHelp)
        assertEquals(SetupState.SETTINGS_HELP + SetupState.SMS_RESTRICTED_NOTE,
            SetupState.rows(input(Role.SIM_PHONE, sms = deniedForGood)).first { it.item == SetupItem.SMS }.blockedHelp)
        assertEquals("Android 11 and older only lets an app scan for Bluetooth devices when Location is allowed. AntiRegister does not read your location.",
            SetupState.rows(input(sdk = 30, bluetooth = fresh))[1].why)
        assertEquals("Bluetooth works without a runtime permission on this Android version.",
            SetupState.rows(input(Role.SIM_PHONE, sdk = 30))[1].why)
        assertEquals("On (no permission needed on this Android version)",
            SetupState.rows(input(sdk = 30))[2].statusText)
    }

    @Test fun constantsKeepTheEnglishCatalogText() {
        assertEquals(SetupState.SETTINGS_HELP, StringKey.SETUP_SETTINGS_HELP.english)
        assertEquals(SetupState.SMS_RESTRICTED_NOTE, StringKey.SETUP_SMS_RESTRICTED_NOTE.english)
    }

    @Test fun setupSummaryEnglishIsUnchangedAndPersianUsesPersianDigits() {
        val two = SetupState.rows(input(bluetooth = fresh, notifications = granted, contacts = fresh).copy(role = Role.SIM_PHONE, sms = fresh, callRoleHeld = false))
        assertEquals(SetupState.summaryLine(two), SetupState.summaryLine(two, en))
        assertEquals("Everything is set up.", SetupState.summaryLine(SetupState.rows(input())))
        assertEquals("1 required item still needs setup.", SetupState.summaryLine(SetupState.rows(input(Role.SIM_PHONE, sms = fresh))))
        assertEquals("Everything required is set up. 1 recommended item left.", SetupState.summaryLine(SetupState.rows(input(notifications = fresh))))
        assertEquals("همه‌چیز راه‌اندازی شده است.", SetupState.summaryLine(SetupState.rows(input(), fa), fa))
        val persian = SetupState.summaryLine(SetupState.rows(input(Role.SIM_PHONE, sms = fresh), fa), fa)
        assertTrue(persian, persian.startsWith("۱ "))
        assertFalse(persian, hasLatin(persian))
    }

    @Test fun setupRowsInPersianHaveNoEnglishAndTheSameDecisions() {
        val inputs = listOf(
            input(bluetooth = fresh, notifications = fresh), input(Role.SIM_PHONE, sms = fresh, callRoleHeld = false, callRoleAsked = true),
            input(bluetooth = deniedForGood, sdk = 30), input(Role.SIM_PHONE, bluetooth = deniedForGood, sms = deniedForGood, notifications = deniedForGood),
            input(bluetoothEnabled = false), input(bluetoothSupported = false), input(callRoleAvailable = false, role = Role.SIM_PHONE),
            input(role = null), input(contacts = deniedForGood), input(contacts = granted), input(notificationsEnabled = false)
        )
        inputs.forEach { i ->
            val e = SetupState.rows(i); val p = SetupState.rows(i, fa)
            assertEquals(e.size, p.size)
            e.zip(p).forEach { (a, b) ->
                assertEquals(a.item, b.item); assertEquals(a.importance, b.importance); assertEquals(a.status, b.status)
                assertEquals(a.action, b.action); assertEquals(a.askedBefore, b.askedBefore)
                assertEquals(a.actionLabel.isEmpty(), b.actionLabel.isEmpty())
                assertEquals(a.blockedHelp.isEmpty(), b.blockedHelp.isEmpty())
                listOf(b.title, b.statusText, b.why, b.blockedHelp, b.actionLabel).forEach { assertFalse("Latin in: $it", hasLatin(it)) }
            }
            assertEquals(SetupState.guidedQueue(e), SetupState.guidedQueue(p))
            assertEquals(SetupState.blockingRows(e).map { it.item }, SetupState.blockingRows(p).map { it.item })
        }
    }

    @Test fun guidedSetupStillStartsWithTheSameFirstItemInBothLanguages() {
        val i = input(Role.SIM_PHONE, bluetooth = fresh, callPhone = fresh, sms = fresh, notifications = fresh)
        assertEquals(SetupItem.BLUETOOTH, SetupState.guidedQueue(SetupState.rows(i, fa)).first())
        assertTrue(SetupState.rows(i, fa).first { it.item == SetupItem.BLUETOOTH }.action == SetupAction.REQUEST_PERMISSION)
    }

    @Test fun homeBannerKeepsUsingTheEnglishRowTitlesByDefault() {
        // Stage 13 builds the banner from the default (English) rows; Stage 15 did not change that.
        assertEquals("Setup incomplete: Bluetooth", SetupState.bannerText(SetupState.rows(input(bluetooth = deniedForGood))))
    }

    // ---------------------------------------------------------------- Language switching

    @Test fun switchingTheLanguageChangesWhatTheseSectionsRead() {
        AppLocale.setForTest(en)
        assertEquals("Welcome to AntiRegister", tr(StringKey.ONBOARD_HEADING))
        assertEquals("Select SIM phone", tr(StringKey.PICKER_TITLE))
        assertEquals("Switch to SIM Phone", tr(StringKey.SETTINGS_SWITCH_TO, Role.SIM_PHONE.localizedLabel(AppLocale.current)))
        AppLocale.setForTest(fa)
        assertEquals("به آنتی رجیستر خوش آمدید", tr(StringKey.ONBOARD_HEADING))
        assertEquals("انتخاب گوشی سیم‌کارت", tr(StringKey.PICKER_TITLE))
        assertEquals("تغییر به گوشی سیم‌کارت", tr(StringKey.SETTINGS_SWITCH_TO, Role.SIM_PHONE.localizedLabel(AppLocale.current)))
        AppLocale.setForTest(en)
        assertEquals("Welcome to AntiRegister", tr(StringKey.ONBOARD_HEADING))
    }

    @Test fun languageSelectorTextsAreTranslatedAndOptionsKeepTheirOwnNames() {
        assertEquals(listOf("English", "فارسی"), AppLanguage.values().map { it.nativeName })
        assertEquals("زبان: فارسی", Strings.text(StringKey.LANGUAGE_CURRENT, fa, "فارسی"))
        assertEquals("Language: English", Strings.text(StringKey.LANGUAGE_CURRENT, en, "English"))
        assertEquals("لغو", Strings.text(StringKey.ACTION_CANCEL, fa))
    }

    @Test fun formattedTextsSurviveAWrongNumberOfArguments() {
        // Strings.text never throws: a missing argument returns the unformatted pattern instead of crashing a dialog.
        assertEquals(StringKey.SETTINGS_SWITCH_TITLE.english, Strings.text(StringKey.SETTINGS_SWITCH_TITLE, en))
        assertNotEquals("", Strings.text(StringKey.SETTINGS_SWITCH_TITLE, fa))
    }
}
