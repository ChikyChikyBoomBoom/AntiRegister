#!/system/bin/sh
# Stage 35 - install-time checks only. Nothing here runs at boot. Nothing here touches SELinux, props, partitions or other apps.
# Variables provided by Magisk: MODPATH, API (Android SDK int), MAGISK_VER_CODE, ui_print, abort.

PKG="com.example.companion"
APK_REL="system/priv-app/CompanionCall/CompanionCall.apk"
ALLOWLIST_REL="system/etc/permissions/privapp-permissions-${PKG}.xml"
META="$MODPATH/companion_meta"

# --- device-specific assumptions (documented in MAGISK_INSTALLATION.md; edit here only if you know why) -------------------
EXPECTED_DEVICE="alioth"   # ro.product.device of POCO F3 per the project owner's target; confirm with: adb shell getprop ro.product.device
MIN_API=30                 # POCO F3 shipped with Android 11 (API 30); older releases are out of scope
MIN_MAGISK=24000

ui_print "- AntiRegister privileged module (EXPERIMENTAL, not verified on a device)"

# Stage 2 guard: refuse to install unless the module was built with the opt-in (magisk/build_module.sh writes this marker only when
# COMPANION_MAGISK_ENABLED=1). Zips built before Stage 2 have no marker and are refused too. Nothing has been installed at this point.
[ -f "$META/magisk-enabled" ] || abort "! Magisk module install is disabled (Stage 2). Nothing was installed. See STAGE2_NOTES.md."

[ "$MAGISK_VER_CODE" -ge "$MIN_MAGISK" ] 2>/dev/null || abort "! Magisk $MIN_MAGISK or newer required (found: $MAGISK_VER_CODE)"
[ "$API" -ge "$MIN_API" ] 2>/dev/null || abort "! Android API $MIN_API or newer required (found: $API)"

DEV="$(getprop ro.product.device)"
ui_print "- Device codename: $DEV, Android API: $API"
[ "$DEV" = "$EXPECTED_DEVICE" ] || abort "! This module targets '$EXPECTED_DEVICE' only (found '$DEV'). Nothing was installed."

[ -f "$MODPATH/$APK_REL" ] || abort "! APK missing in module. Build the module with magisk/build_module.sh"
[ -f "$MODPATH/$ALLOWLIST_REL" ] || abort "! Allowlist XML missing in module"
[ -f "$META/apk.sha256" ] && [ -f "$META/declared-privileged-permissions.txt" ] || abort "! Build metadata missing; build the module with magisk/build_module.sh"

# 1. The APK inside the zip must be the one that was permission-checked at build time.
EXPECT_SHA="$(cut -d' ' -f1 "$META/apk.sha256")"
ACTUAL_SHA="$(sha256sum "$MODPATH/$APK_REL" | cut -d' ' -f1)"
[ -n "$EXPECT_SHA" ] && [ "$EXPECT_SHA" = "$ACTUAL_SHA" ] || abort "! APK does not match the checked build (sha256 mismatch). Nothing was installed."

# 2. The allowlist must name exactly the privileged permissions that were found in the APK at build time.
grep -o 'permission name="[^"]*"' "$MODPATH/$ALLOWLIST_REL" | sed 's/permission name="//; s/"$//' | sort > "$META/allowlist.sorted"
sort "$META/declared-privileged-permissions.txt" > "$META/declared.sorted"
cmp -s "$META/allowlist.sorted" "$META/declared.sorted" || abort "! Allowlist differs from the APK's privileged permissions. Nothing was installed (a wrong allowlist can prevent booting)."
[ -s "$META/declared.sorted" ] || abort "! Empty privileged-permission list: refusing to install an unneeded privileged app."
grep -q "package=\"$PKG\"" "$MODPATH/$ALLOWLIST_REL" || abort "! Allowlist is not for package $PKG"

# 3. A user-installed copy of the same package can shadow the system copy (warn only; cannot be fixed from here).
for d in /data/app/*/${PKG}-* /data/app/${PKG}-*; do
  if [ -e "$d" ]; then
    ui_print "! A user-installed copy of $PKG exists. Uninstall it (adb shell pm uninstall $PKG) so the system copy is the one in use."
    break
  fi
done

rm -f "$META/allowlist.sorted" "$META/declared.sorted"
rm -f "$MODPATH/system/priv-app/CompanionCall/README.txt"
ui_print "- Privileged permissions allowlisted:"
while read -r p; do ui_print "    $p"; done < "$META/declared-privileged-permissions.txt"
ui_print "- Reboot to apply. To undo: disable/remove the module (see MAGISK_INSTALLATION.md, section 10/11)."
