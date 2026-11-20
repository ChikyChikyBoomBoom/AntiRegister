#!/usr/bin/env bash
# Stage 35: assemble the experimental Magisk module from an APK YOU built. Runs on your computer, never on the phone.
#   magisk/build_module.sh <path-to-your-built.apk> [--allow-unverified-apk]
# Output: magisk/out/CompanionCall-priv-stage35.zip   (not committed, not part of the project ZIP)
#
# It refuses to build unless (a) the APK's package name is the project's applicationId, (b) every permission the APK declares is classified,
# (c) the allowlist XML lists exactly the privileged ones (see check_permissions.py), (d) the APK matches the source manifest.
# APK inspection needs aapt2 or aapt (Android SDK build-tools). Without it the build stops, unless --allow-unverified-apk is given:
# then only the SOURCE manifest is checked, which is weaker (the APK could come from a different source tree).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"

# Stage 2 guard: Magisk module building is switched OFF. Nothing below runs unless you opt in on purpose (see STAGE2_NOTES.md).
# Keep this in step with AudioTransferFeatures.MAGISK_ENABLED when re-enabling.
if [ "${COMPANION_MAGISK_ENABLED:-0}" != "1" ]; then
  echo "Magisk module build is disabled (Stage 2). Nothing was built." >&2
  echo "To re-enable deliberately: COMPANION_MAGISK_ENABLED=1 $0 <apk>   (see STAGE2_NOTES.md)" >&2
  exit 3
fi
APK=""; UNVERIFIED=0
for a in "$@"; do
  case "$a" in
    --allow-unverified-apk) UNVERIFIED=1 ;;
    -*) echo "unknown option: $a" >&2; exit 2 ;;
    *) [ -z "$APK" ] && APK="$a" || { echo "only one APK path allowed" >&2; exit 2; } ;;
  esac
done
[ -n "$APK" ] || { echo "usage: $0 <path-to-built.apk> [--allow-unverified-apk]" >&2; exit 2; }
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 2; }

command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }
command -v zip >/dev/null || { echo "zip is required" >&2; exit 2; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 2; }

# Package name: read from the project, never typed here.
PKG="$(sed -n 's/.*applicationId *= *"\([^"]*\)".*/\1/p' "$ROOT/app/build.gradle.kts" | head -n1)"
[ -n "$PKG" ] || { echo "cannot read applicationId from app/build.gradle.kts" >&2; exit 1; }
ALLOWLIST="$HERE/module-template/system/etc/permissions/privapp-permissions-$PKG.xml"
[ -f "$ALLOWLIST" ] || { echo "allowlist file for $PKG missing: $ALLOWLIST" >&2; exit 1; }
echo "applicationId (from app/build.gradle.kts): $PKG"

# Find aapt2 / aapt
AAPT=""
if command -v aapt2 >/dev/null; then AAPT="$(command -v aapt2)"
elif command -v aapt >/dev/null; then AAPT="$(command -v aapt)"
else
  for base in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [ -n "$base" ] && [ -d "$base/build-tools" ] || continue
    c="$(ls -d "$base"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n1 || true)"
    [ -z "$c" ] && c="$(ls -d "$base"/build-tools/*/aapt 2>/dev/null | sort -V | tail -n1 || true)"
    [ -n "$c" ] && { AAPT="$c"; break; }
  done
fi

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
SRC_PERMS="$TMP/source-perms.txt"
# (simple, dependency-free extraction of the source manifest permissions through the checker module)
python3 - "$HERE" "$ROOT/app/src/main/AndroidManifest.xml" > "$SRC_PERMS" <<'PY'
import sys, importlib.util, os
here, manifest = sys.argv[1], sys.argv[2]
spec = importlib.util.spec_from_file_location("cp", os.path.join(here, "check_permissions.py"))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
for p in sorted(m.manifest_permissions(manifest)): print(p)
PY

APK_PERMS="$TMP/apk-perms.txt"
if [ -n "$AAPT" ]; then
  echo "using $AAPT"
  "$AAPT" dump badging "$APK" > "$TMP/badging.txt"
  APK_PKG="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" "$TMP/badging.txt" | head -n1)"
  [ "$APK_PKG" = "$PKG" ] || { echo "ERROR: APK package is '$APK_PKG', expected '$PKG'" >&2; exit 1; }
  sed -n "s/^uses-permission\(-sdk-23\|-sdk-m\)\{0,1\}: name='\([^']*\)'.*/\2/p" "$TMP/badging.txt" | sort -u > "$APK_PERMS"
  [ -s "$APK_PERMS" ] || { echo "ERROR: could not read any permission from the APK (unexpected aapt output)" >&2; exit 1; }
  if ! cmp -s "$APK_PERMS" "$SRC_PERMS"; then
    echo "ERROR: the APK's permissions differ from app/src/main/AndroidManifest.xml. Rebuild the APK from this source tree." >&2
    diff "$SRC_PERMS" "$APK_PERMS" >&2 || true
    exit 1
  fi
  grep -q "^application-debuggable" "$TMP/badging.txt" && echo "WARNING: this is a DEBUGGABLE build. A debuggable privileged system app is extra attack surface; acceptable only on a dedicated development phone."
  echo "APK versionName/Code: $(sed -n "s/^package: .*versionCode='\([^']*\)' versionName='\([^']*\)'.*/\2 (\1)/p" "$TMP/badging.txt" | head -n1)"
elif [ "$UNVERIFIED" = 1 ]; then
  echo "WARNING: no aapt2/aapt found; APK package and permissions were NOT read from the APK. Only the source manifest is checked."
  cp "$SRC_PERMS" "$APK_PERMS"
else
  echo "ERROR: aapt2/aapt not found (Android SDK build-tools). Install them or re-run with --allow-unverified-apk (weaker check)." >&2
  exit 1
fi

python3 "$HERE/check_permissions.py" --declared-file "$APK_PERMS" --package "$PKG" --write-privileged "$TMP/privileged.txt"

# Assemble
STAGE="$TMP/module"
mkdir -p "$STAGE"
cp -a "$HERE/module-template/." "$STAGE/"
rm -f "$STAGE/system/priv-app/CompanionCall/README.txt"
cp "$APK" "$STAGE/system/priv-app/CompanionCall/CompanionCall.apk"
mkdir -p "$STAGE/companion_meta"
cp "$TMP/privileged.txt" "$STAGE/companion_meta/declared-privileged-permissions.txt"
( cd "$STAGE/system/priv-app/CompanionCall" && sha256sum CompanionCall.apk > "$STAGE/companion_meta/apk.sha256" )
echo "enabled by COMPANION_MAGISK_ENABLED=1 at build time" > "$STAGE/companion_meta/magisk-enabled"   # Stage 2: customize.sh refuses to install without this
echo "package=$PKG" > "$STAGE/companion_meta/build-info.txt"
echo "unverified_apk_inspection=$UNVERIFIED" >> "$STAGE/companion_meta/build-info.txt"
find "$STAGE" -type f -exec chmod 0644 {} +
chmod 0755 "$STAGE/customize.sh" "$STAGE/META-INF/com/google/android/update-binary"

mkdir -p "$HERE/out"
OUT="$HERE/out/CompanionCall-priv-stage35.zip"
rm -f "$OUT"
( cd "$STAGE" && zip -r -X -q "$OUT" META-INF module.prop customize.sh system companion_meta )
echo "Built: $OUT"
echo "Contents:"; unzip -l "$OUT" | sed 's/^/  /'
echo "NOT tested on a device. Read MAGISK_INSTALLATION.md (recovery section) BEFORE flashing."
