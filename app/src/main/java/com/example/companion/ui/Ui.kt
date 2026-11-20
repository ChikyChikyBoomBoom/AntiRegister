package com.example.companion.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.companion.app.CompanionApp
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.tr

val Activity.app get() = application as CompanionApp

// =====================================================================================================================
// Stage 24: one small shared look for every screen (platform widgets only, no resources, no dependencies).
// The old helpers (vbox / label / button / show) keep their names and signatures; they now draw the shared look.
// =====================================================================================================================

/** The only colours the screens use. Colour is always a secondary cue: every state also has a word. */
object UiColors {
    val PRIMARY = Color.rgb(21, 101, 192)
    val SUCCESS = Color.rgb(46, 125, 50)
    val WARNING = Color.rgb(239, 108, 0)
    val CAUTION = Color.rgb(249, 168, 37)
    val ERROR = Color.rgb(176, 0, 32)
    val NEUTRAL = Color.rgb(97, 97, 97)          // dark neutral button fill
    val TEXT = Color.rgb(33, 33, 33)
    val TEXT_SECONDARY = Color.rgb(85, 85, 85)
    val TEXT_HINT = Color.rgb(117, 117, 117)     // darker than Color.GRAY so small hints stay readable on white
    val SURFACE = Color.rgb(245, 245, 245)
    val OUTLINE = Color.rgb(224, 224, 224)
    val DISABLED_FILL = Color.rgb(238, 238, 238)
    val DISABLED_TEXT = Color.rgb(158, 158, 158)
    val TINT_OK = Color.rgb(232, 245, 233)
    val TINT_BUSY = Color.rgb(255, 248, 225)
    val TINT_WARN = Color.rgb(255, 243, 224)
    val TINT_ERROR = Color.rgb(252, 228, 236)

    // Stage 22: message bubbles (SMS conversation) share the palette instead of keeping private colours.
    val BUBBLE_RECEIVED = Color.rgb(232, 232, 232)
    val BUBBLE_SENDING = Color.rgb(236, 242, 250)
    val BUBBLE_SENT = Color.rgb(212, 230, 252)
    val BUBBLE_FAILED = TINT_ERROR
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/**
 * Stage 22: the gap between two controls that sit side by side in one row (a text box and its button). 8dp, applied as a start
 * margin so it flips in RTL. Stacked controls need no margin: every button already carries 4dp above and below (see applyButtonStyle).
 */
fun Context.rowGap(): Int = dp(8)

// ---------------------------------------------------------------------------------------------------------------------
// Layout and text
// ---------------------------------------------------------------------------------------------------------------------

fun Context.vbox() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val p = dp(16)
    setPadding(p, p, p, p)
}

/** Body text, 16sp. Other sizes: screenTitle 22, sectionHeader 13, hintText 13, secondary lines 14. */
fun Context.label(t: String = "") = TextView(this).apply { text = t; textSize = 16f; setPadding(0, dp(4), 0, dp(4)); followLayoutStart() }

fun Context.screenTitle(t: String) = label(t).apply { textSize = 22f; setTypeface(null, Typeface.BOLD) }

fun Context.sectionHeader(t: String) = label(t.uppercase()).apply {
    textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(UiColors.TEXT_HINT)
    setPadding(0, dp(24), 0, dp(4))
}

/** Small grey explanation under a control. */
fun Context.hintText(t: String = "") = label(t).apply { textSize = 13f; setTextColor(UiColors.TEXT_HINT) }

/** Stage 22: a secondary line (14sp, secondary colour) such as a name under a title or an "about" row. */
fun Context.secondaryText(t: String = "") = label(t).apply { textSize = 14f; setTextColor(UiColors.TEXT_SECONDARY) }

/** Red problem text. */
fun Context.errorText(t: String = "") = label(t).apply { textSize = 14f; setTextColor(UiColors.ERROR) }

fun View.show(v: Boolean) { visibility = if (v) View.VISIBLE else View.GONE }

fun TextView.setTextOrHide(t: String) { text = t; show(t.isNotEmpty()) }

// ---------------------------------------------------------------------------------------------------------------------
// Stage 16: direction helpers. Every screen's root already has the app language's direction (LocalizedActivity), so the helpers below
// only have to avoid LEFT/RIGHT: they use start/end, which Android flips in RTL. In English (LTR) every result is what it was.
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Text sits at the start edge of the VIEW (right in RTL, left in LTR) instead of the start edge of its own first letter.
 * Without this an English line on a Persian screen would stick to the left while the Persian lines next to it sit on the right.
 * The paragraph direction still follows the text itself (first strong letter), so a Persian sentence stays right-to-left and a phone
 * number stays left-to-right; only the side it hugs is the view's.
 *
 * Stage 17: the direction rule is [firstStrongOrLtr], not plain first-strong. In an RTL layout Android's plain first-strong rule falls
 * back to RIGHT-TO-LEFT for text with no strong letter, and a phone number has none: "+98 912 345 6789" would be drawn with its
 * groups in reverse order and the "+" at the end. Stage 16 assumed the opposite; this corrects it for every screen that uses label().
 */
fun TextView.followLayoutStart() {
    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    firstStrongOrLtr()
}

/**
 * Stage 17: "first strong letter decides, and text with no strong letter (a phone number, a time, a Bluetooth address) is left-to-right".
 * LTR layout: plain first-strong, which is already what Android does there, so English screens are exactly as they were.
 * RTL layout: Android 10+ has this rule built in (FIRST_STRONG_LTR). Android 8-9 do not, so there it uses "right-to-left only if the
 * text contains any right-to-left letter", which gives the same answer for numbers, Latin names and Persian text.
 */
fun TextView.firstStrongOrLtr() {
    textDirection = when {
        !com.example.companion.i18n.AppLocale.isRtl -> View.TEXT_DIRECTION_FIRST_STRONG
        Build.VERSION.SDK_INT >= 29 -> View.TEXT_DIRECTION_FIRST_STRONG_LTR
        else -> View.TEXT_DIRECTION_ANY_RTL
    }
}

/**
 * For a line the APP composes from several parts (for example "time · status"): the paragraph direction is the app language's, and
 * the parts that must keep their order (times, numbers) are isolated by the caller with TextDirection.ltrData. Not for message text.
 */
fun TextView.followAppDirection() {
    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    textDirection = if (com.example.companion.i18n.AppLocale.isRtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
}

/** Text typed or received from outside (SMS bodies, names): each message decides its own paragraph direction and alignment. */
fun TextView.followContentDirection() {
    firstStrongOrLtr()      // Stage 21: not plain FIRST_STRONG, which is right-to-left for digit-only text in an RTL layout (an SMS "123 456" came out "456 123")
    textAlignment = View.TEXT_ALIGNMENT_GRAVITY      // gravity START = start of the PARAGRAPH: Persian text right, English text left
}

/**
 * A phone-number box: the digits always run left-to-right and are never reordered, but the box still starts at the start edge of the
 * screen. A Persian hint is shown right-to-left because direction is decided by the first strong letter, and digits are not strong.
 */
fun EditText.styleAsPhoneField() {
    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    firstStrongOrLtr()      // Stage 21: digits, spaces and "+" have no strong letter; plain FIRST_STRONG made them right-to-left ("+98 912 345" -> "345 912 98+") in Persian
    gravity = Gravity.START or Gravity.CENTER_VERTICAL
}

/** A free-text box (message composer): follows the layout start; what is typed picks its own paragraph direction. */
fun EditText.styleAsTextField() {
    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    firstStrongOrLtr()      // Stage 21: same rule as the phone field; typed Persian or Latin letters still decide their own direction
    gravity = Gravity.START or Gravity.CENTER_VERTICAL
}

/** Margins by start/end (flipped in RTL) instead of left/right. [start] / [end] are in pixels. */
fun LinearLayout.LayoutParams.marginsRelative(start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams = apply {
    setMargins(start, top, end, bottom)
    marginStart = start
    marginEnd = end
}

/** Padding by start/end instead of left/right. */
fun View.setPaddingStartEnd(start: Int, top: Int, end: Int, bottom: Int) = setPaddingRelative(start, top, end, bottom)

/** A rounded surface that groups related lines. */
fun Context.card(tint: Int = UiColors.SURFACE) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(16))
    background = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(tint)
        setStroke(dp(1), UiColors.OUTLINE)
    }
}

/** Re-tints a surface made by [card] (or any view whose background is a [GradientDrawable]). */
fun View.setCardTint(tint: Int) { (background as? GradientDrawable)?.setColor(tint) }

/**
 * On a wide screen (tablet, landscape, split-screen) keeps the content to a readable column in the middle instead of
 * stretching every line across the screen. On a phone it returns [content] unchanged.
 */
fun Context.constrainWidth(content: View, maxDp: Int = 640): View {
    if (resources.configuration.screenWidthDp <= maxDp) return content
    return FrameLayout(this).apply {
        addView(content, FrameLayout.LayoutParams(dp(maxDp), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Stage 12: the same helpers taking a central text key (StringKey) instead of a literal. Ui.kt stays the one place screens build UI
// from; a screen is migrated by changing label("Calls") to label(StringKey.X). The String versions above are unchanged.
// ---------------------------------------------------------------------------------------------------------------------

fun Context.label(key: StringKey) = label(tr(key))
fun Context.screenTitle(key: StringKey) = screenTitle(tr(key))
fun Context.sectionHeader(key: StringKey) = sectionHeader(tr(key))
fun Context.hintText(key: StringKey) = hintText(tr(key))
fun Context.errorText(key: StringKey) = errorText(tr(key))
fun Context.button(key: StringKey, onClick: () -> Unit): Button = button(tr(key), onClick)
fun Context.styledButton(key: StringKey, style: ButtonStyle = ButtonStyle.SECONDARY, onClick: () -> Unit): Button =
    styledButton(tr(key), style, onClick)

// ---------------------------------------------------------------------------------------------------------------------
// Buttons: one shape, one text size, one disabled look.
// ---------------------------------------------------------------------------------------------------------------------

enum class ButtonStyle {
    /** The main action on a screen: filled. */
    PRIMARY,
    /** Everything else: white with a coloured outline. */
    SECONDARY,
    /** Destructive or hang-up style actions: filled red. */
    DANGER
}

/**
 * Applies the shared button look. Enabled and disabled are told apart by fill, outline AND text colour (not by colour
 * alone): a disabled button is flat, grey and has no coloured outline.
 */
fun Button.applyButtonStyle(style: ButtonStyle) {
    val ctx = context
    val radius = ctx.dp(12).toFloat()
    val fill: Int
    val stroke: Int?
    val textColor: Int
    when (style) {
        ButtonStyle.PRIMARY -> { fill = UiColors.PRIMARY; stroke = null; textColor = Color.WHITE }
        ButtonStyle.SECONDARY -> { fill = Color.WHITE; stroke = UiColors.PRIMARY; textColor = UiColors.PRIMARY }
        ButtonStyle.DANGER -> { fill = UiColors.ERROR; stroke = null; textColor = Color.WHITE }
    }
    val enabledBg = GradientDrawable().apply {
        cornerRadius = radius; setColor(fill)
        if (stroke != null) setStroke(ctx.dp(1) + 1, stroke)
    }
    val disabledBg = GradientDrawable().apply {
        cornerRadius = radius; setColor(UiColors.DISABLED_FILL); setStroke(ctx.dp(1), UiColors.OUTLINE)
    }
    val states = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), disabledBg)
        addState(intArrayOf(), enabledBg)
    }
    val mask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.BLACK) }
    val ripple = RippleDrawable(ColorStateList.valueOf(Color.argb(48, 0, 0, 0)), states, mask)
    // The inset keeps a little air between stacked buttons without needing layout margins (callers add these views anywhere).
    background = InsetDrawable(ripple, 0, ctx.dp(4), 0, ctx.dp(4))
    setTextColor(
        ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(UiColors.DISABLED_TEXT, textColor)
        )
    )
    isAllCaps = false
    textSize = 16f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    minimumHeight = ctx.dp(56)          // 48dp visible + the 4dp inset above and below
    stateListAnimator = null
    setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8))
}

fun Context.styledButton(t: String, style: ButtonStyle = ButtonStyle.SECONDARY, onClick: () -> Unit): Button =
    Button(this).apply {
        text = t
        applyButtonStyle(style)
        setOnClickListener { onClick() }
    }

/** Unchanged signature: every existing button() call now gets the shared secondary look. */
fun Context.button(t: String, onClick: () -> Unit): Button = styledButton(t, ButtonStyle.SECONDARY, onClick)

/** For buttons that keep their own coloured fill (dial pad, call screen): the same dimmed look everywhere. */
fun Button.setEnabledLook(enabled: Boolean) { isEnabled = enabled; alpha = if (enabled) 1f else 0.38f }

// ---------------------------------------------------------------------------------------------------------------------
// Link status: dot, or a small spinner while connecting.
// ---------------------------------------------------------------------------------------------------------------------

/** A coloured dot that turns into a small spinner while the link is being opened. Purely decorative (the words say it). */
class StatusIndicator(context: Context, dotDp: Int = 14) : FrameLayout(context) {
    private val dot = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } }
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
        isIndeterminate = true
        visibility = View.GONE
    }
    private val box = context.dp(dotDp + 8)

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(dot, LayoutParams(context.dp(dotDp), context.dp(dotDp), Gravity.CENTER))
        addView(spinner, LayoutParams(box, box, Gravity.CENTER))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(box, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(box, MeasureSpec.EXACTLY))
    }

    fun set(color: Int, busy: Boolean) {
        (dot.background as GradientDrawable).setColor(color)
        spinner.indeterminateTintList = ColorStateList.valueOf(color)
        spinner.show(busy)
        dot.show(!busy)
    }
}

fun LinkStatus.color(): Int = when (this) {
    LinkStatus.CONNECTED -> UiColors.SUCCESS
    LinkStatus.CONNECTING -> UiColors.CAUTION
    LinkStatus.RECONNECTING -> UiColors.WARNING
    LinkStatus.DISCONNECTED -> UiColors.TEXT_HINT
}

fun LinkStatus.tint(): Int = when (this) {
    LinkStatus.CONNECTED -> UiColors.TINT_OK
    LinkStatus.CONNECTING -> UiColors.TINT_BUSY
    LinkStatus.RECONNECTING -> UiColors.TINT_WARN
    LinkStatus.DISCONNECTED -> UiColors.SURFACE
}

/**
 * The one-line "SIM Phone: connected / unavailable" strip at the top of Calls, Messages, Recent Calls and the Dial Pad.
 * It only draws what it is given (the existing link text and the existing link status); it owns no state.
 */
class LinkBanner(context: Context) : LinearLayout(context) {
    private val indicator = StatusIndicator(context, 10)
    private val textV = TextView(context).apply {
        textSize = 14f
        setTextColor(UiColors.TEXT)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        followLayoutStart()
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
        background = GradientDrawable().apply { cornerRadius = context.dp(12).toFloat(); setColor(UiColors.SURFACE) }
        addView(indicator, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(8) })
        addView(textV, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    fun update(status: LinkStatus, text: String) {
        indicator.show(true)
        indicator.set(status.color(), UiPolish.isBusy(status))
        (background as GradientDrawable).setColor(status.tint())
        textV.text = text
    }

    /** A plain note with no link colour (for a phone that does not use this screen's link at all). */
    fun note(text: String) {
        indicator.show(false)
        (background as GradientDrawable).setColor(UiColors.SURFACE)
        textV.text = text
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Empty states
// ---------------------------------------------------------------------------------------------------------------------

/** Centered icon, title, one explaining sentence and an optional single action. Used when a list or device is missing. */
fun Context.emptyState(
    icon: UiIcon,
    content: EmptyText,
    onAction: (() -> Unit)? = null
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    setPadding(dp(32), dp(24), dp(32), dp(24))
    val circle = ImageView(context).apply {
        setImageDrawable(IconDrawable(icon, UiColors.TEXT_HINT, dp(36)))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(UiColors.SURFACE) }
        scaleType = ImageView.ScaleType.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO     // the title says it
    }
    addView(circle, LinearLayout.LayoutParams(dp(88), dp(88)))
    val titleV = TextView(context).apply {
        text = content.title; textSize = 18f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
        setTextColor(UiColors.TEXT)
    }
    addView(titleV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
    val messageV = TextView(context).apply {
        text = content.message; textSize = 14f; gravity = Gravity.CENTER; setTextColor(UiColors.TEXT_SECONDARY)
    }
    addView(messageV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
    val actionLabel = content.actionLabel
    if (actionLabel != null && onAction != null) {
        addView(styledButton(actionLabel, ButtonStyle.PRIMARY, onAction),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }
}

/** The empty state of a list screen: takes the space the list would have had. */
fun fillRemaining() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

/** The status the link banners show: "up" is the existing linkUp flag; otherwise the existing connection state decides connecting vs disconnected. */
fun Activity.linkStatusFor(linkUp: Boolean): LinkStatus {
    if (linkUp) return LinkStatus.CONNECTED
    val st = HomeStatus.linkStatus(app.connection.state)
    return if (st == LinkStatus.CONNECTED) LinkStatus.DISCONNECTED else st    // the flag says down, so never draw "connected"
}

/** Smaller button for list rows (Call / SMS / Delete). Same look, 13sp text; minimum height 48dp (40dp visible + the 4dp inset above and below). */
fun Button.makeCompact() {
    textSize = 13f
    minimumHeight = context.dp(48)
    minimumWidth = context.dp(72)
    setPadding(context.dp(12), context.dp(4), context.dp(12), context.dp(4))
}
