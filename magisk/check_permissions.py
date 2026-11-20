#!/usr/bin/env python3
"""Stage 35: completeness check of the privileged-permission allowlist.

Fails (exit 1) unless ALL of these hold:
  * every permission the app declares is classified in permissions/permission-classification.txt
  * every declared 'privileged' permission is in the allowlist XML
  * every allowlist entry is declared by the app AND classified 'privileged' (no speculative entries)
  * the allowlist XML is for the expected package

Declared permissions come from the source manifest, or from a list file made from the real APK (build_module.sh does that with aapt).
Usage:
  check_permissions.py --manifest app/src/main/AndroidManifest.xml
  check_permissions.py --declared-file perms.txt [--write-privileged out.txt]
  check_permissions.py --self-test
"""
import argparse, os, re, sys, tempfile
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
DEFAULT_CLASSIFICATION = os.path.join(HERE, "permissions", "permission-classification.txt")
DEFAULT_ALLOWLIST = os.path.join(HERE, "module-template", "system", "etc", "permissions",
                                 "privapp-permissions-com.example.companion.xml")
DEFAULT_MANIFEST = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
LEVELS = {"normal", "dangerous", "privileged"}


def manifest_permissions(path):
    root = ET.parse(path).getroot()
    out = set()
    for el in root:
        if el.tag in ("uses-permission", "uses-permission-sdk-23"):
            n = el.get(ANDROID_NS + "name")
            if n:
                out.add(n)
    return out


def read_declared_file(path):
    with open(path, encoding="utf-8") as f:
        return {l.strip() for l in f if l.strip() and not l.startswith("#")}


def read_classification(path):
    cls = {}
    with open(path, encoding="utf-8") as f:
        for no, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) != 2 or parts[1].strip() not in LEVELS:
                raise ValueError("%s:%d: expected '<name><TAB>normal|dangerous|privileged'" % (path, no))
            cls[parts[0].strip()] = parts[1].strip()
    return cls


def read_allowlist(path):
    root = ET.parse(path).getroot()
    if root.tag != "permissions":
        raise ValueError("allowlist root must be <permissions>")
    pkgs = {}
    for pp in root.findall("privapp-permissions"):
        pkg = pp.get("package")
        names = [p.get("name") for p in pp.findall("permission")]
        deny = [p.get("name") for p in pp.findall("deny-permission")]
        if deny:
            raise ValueError("deny-permission is not used by this module")
        pkgs.setdefault(pkg, []).extend(names)
    return pkgs


def check(declared, classification, allowlist_pkgs, package):
    """Returns (errors, privileged_declared_set)."""
    errors = []
    for p in sorted(declared):
        if p not in classification:
            errors.append("declared but NOT classified: %s (classify it in permission-classification.txt)" % p)
    privileged = {p for p in declared if classification.get(p) == "privileged"}
    if list(allowlist_pkgs.keys()) != [package]:
        errors.append("allowlist must contain exactly one <privapp-permissions> block for %s (found: %s)" % (package, sorted(map(str, allowlist_pkgs))))
        listed = set()
    else:
        names = allowlist_pkgs[package]
        listed = set(names)
        if len(names) != len(listed):
            errors.append("allowlist has duplicate entries")
    for p in sorted(privileged - listed):
        errors.append("privileged permission declared but MISSING from allowlist (boot risk): %s" % p)
    for p in sorted(listed - declared):
        errors.append("allowlist entry not declared by the app (speculative): %s" % p)
    for p in sorted((listed & declared) - privileged):
        errors.append("allowlist entry is not classified 'privileged': %s" % p)
    return errors, privileged


def run(declared, classification_path, allowlist_path, package, write_privileged=None):
    cls = read_classification(classification_path)
    al = read_allowlist(allowlist_path)
    errors, priv = check(declared, cls, al, package)
    print("declared permissions: %d, privileged: %s" % (len(declared), ", ".join(sorted(priv)) or "(none)"))
    if errors:
        for e in errors:
            print("ERROR: " + e)
        return 1
    if write_privileged:
        with open(write_privileged, "w", encoding="utf-8") as f:
            f.write("".join(p + "\n" for p in sorted(priv)))
    print("OK: allowlist is complete and contains nothing else")
    return 0


def self_test():
    pkg = "p.k.g"
    cls = {"a.NORMAL": "normal", "a.DANGER": "dangerous", "a.PRIV": "privileged", "a.PRIV2": "privileged"}
    ok = lambda d, al: check(set(d), cls, {pkg: al}, pkg)[0] == []
    assert ok(["a.NORMAL", "a.DANGER", "a.PRIV"], ["a.PRIV"])
    assert not ok(["a.PRIV", "a.PRIV2"], ["a.PRIV"]), "incomplete allowlist must fail"
    assert not ok(["a.PRIV"], ["a.PRIV", "a.PRIV2"]), "speculative entry must fail"
    assert not ok(["a.PRIV", "a.NEW"], ["a.PRIV"]), "unclassified permission must fail"
    assert not ok(["a.NORMAL"], ["a.NORMAL"]), "non-privileged entry must fail"
    assert not ok(["a.PRIV"], ["a.PRIV", "a.PRIV"]), "duplicates must fail"
    assert check({"a.PRIV"}, cls, {"other": ["a.PRIV"]}, pkg)[0], "wrong package must fail"
    assert ok(["a.NORMAL"], []), "no privileged permission and empty allowlist is consistent"
    with tempfile.TemporaryDirectory() as d:
        c = os.path.join(d, "c.txt"); open(c, "w").write("# x\na.PRIV\tprivileged\n")
        a = os.path.join(d, "a.xml")
        open(a, "w").write('<permissions><privapp-permissions package="p.k.g"><permission name="a.PRIV"/></privapp-permissions></permissions>')
        assert run({"a.PRIV"}, c, a, "p.k.g") == 0
        assert run({"a.PRIV", "a.X"}, c, a, "p.k.g") == 1
        bad = os.path.join(d, "bad.txt"); open(bad, "w").write("a.PRIV privileged\n")
        try:
            read_classification(bad); raise SystemExit("bad classification line not rejected")
        except ValueError:
            pass
    print("self-test OK")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest")
    ap.add_argument("--declared-file")
    ap.add_argument("--classification", default=DEFAULT_CLASSIFICATION)
    ap.add_argument("--allowlist", default=DEFAULT_ALLOWLIST)
    ap.add_argument("--package", default="com.example.companion")
    ap.add_argument("--write-privileged")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if bool(a.manifest) == bool(a.declared_file):
        ap.error("give exactly one of --manifest / --declared-file")
    declared = manifest_permissions(a.manifest) if a.manifest else read_declared_file(a.declared_file)
    return run(declared, a.classification, a.allowlist, a.package, a.write_privileged)


if __name__ == "__main__":
    sys.exit(main())
