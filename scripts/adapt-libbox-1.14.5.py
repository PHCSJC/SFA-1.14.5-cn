"""Adapt the SFA tree to the libbox API shipped by sing-box v1.14.5.

sing-box v1.14.5 dropped `UseProcFS()` from libbox.PlatformInterface. SFA at
ae93152 still overrides it, so Kotlin fails with:

    PlatformInterfaceWrapper.kt:55:5 'useProcFS' overrides nothing.

Upstream SFA removed the same override in its 1.14.4 bump. This script applies
that single-line removal so the ae93152 tree can link against a 1.14.5 libbox
without pulling in the rest of upstream's 23 commits.

Run against a tree that already has scripts/custom.patch applied.
"""

import sys
from pathlib import Path

MARKER = "// @Override useProcFS removed for sing-box 1.14.5"

TARGET = "app/src/main/java/io/nekohasekai/sfa/bg/PlatformInterfaceWrapper.kt"

# The override plus its surrounding blank lines, exactly as it appears at
# ae93152. Kept as a literal so a mismatch is a loud failure rather than a
# silently skipped edit.
OLD = """    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

"""

NEW = """    // @Override useProcFS removed for sing-box 1.14.5: the method is gone from
    // libbox.PlatformInterface, and Kotlin rejects an override that no longer
    // exists. sing-box now decides on its own how to read connection owners.

"""


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: adapt-libbox-1.14.5.py <path-to-sfa-checkout>", file=sys.stderr)
        return 2

    path = Path(sys.argv[1]) / TARGET
    if not path.is_file():
        print(f"error: {path} not found", file=sys.stderr)
        return 2

    src = path.read_text(encoding="utf-8")

    if MARKER in src:
        print("already adapted")
        return 0

    if OLD not in src:
        print("error: useProcFS override not found in expected form", file=sys.stderr)
        print("       the tree may already differ from ae93152", file=sys.stderr)
        return 1

    src = src.replace(OLD, NEW, 1)
    path.write_text(src, encoding="utf-8")
    print(f"adapted {TARGET}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
