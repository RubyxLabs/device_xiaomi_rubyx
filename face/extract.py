# SPDX-License-Identifier: Apache-2.0
"""Extract the original Ruby face SDK without installing MIUI's Application."""
import hashlib
from pathlib import Path
import zipfile

APK_SHA256 = "aa9421192249a83cc12f9d9249a55b2b2dcb116b404dd9de734d4cc471a89fa7"


def extract_face_sdk(vendor_path, apk_path=None):
    vendor = Path(vendor_path)
    apk = Path(apk_path) if apk_path else vendor / "proprietary/product/app/Biometric/Biometric.apk"
    with apk.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != APK_SHA256:
            raise ValueError("Ruby face SDK APK changed; audit its API before updating the port")
    output = vendor / "face/proprietary"
    output.mkdir(parents=True, exist_ok=True)
    # extract-utils cleans the entire vendor tree, including custom build files.
    (output.parent / "Android.bp").write_bytes(
        Path(__file__).with_name("vendor-Android.bp.in").read_bytes())
    with zipfile.ZipFile(apk) as source:
        info = zipfile.ZipInfo("classes.dex", (2026, 10, 13, 0, 0, 0))
        info.external_attr = 0o644 << 16
        # Align the uncompressed DEX data to four bytes for direct ART mapping.
        info.extra = b"\x00\xd9\x03\x00\x00\x00\x00"
        with zipfile.ZipFile(output / "ruby-face-sdk.jar", "w", zipfile.ZIP_STORED) as jar:
            jar.writestr(info, source.read("classes.dex"))
        for name in ["libjni_stfaceunlock_api.so", "libstfaceunlockocl.so"]:
            (output / name).write_bytes(source.read("lib/arm64-v8a/" + name))
    # Do not extract the APK's Adreno loader. Ruby supplies public vendor OpenCL.


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("vendor", type=Path)
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    extract_face_sdk(args.vendor, args.apk)
