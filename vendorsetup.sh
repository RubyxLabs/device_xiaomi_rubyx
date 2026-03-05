#!/bin/bash
set -e

BUILD_TOP="${ANDROID_BUILD_TOP:-$(pwd)}"
CLANG_DIR="$BUILD_TOP/prebuilts/clang/host/linux-x86/clang-r563880"
CLANG_REPO="https://github.com/Aeron-Aeron/linux-x86-clang-21.0.0-r563880"

if [ ! -d "$CLANG_DIR/bin" ]; then
    echo "[vendorsetup] Clang r563880 not found, cloning..."
    mkdir -p "$(dirname "$CLANG_DIR")"
    git clone --depth=1 "$CLANG_REPO" "$CLANG_DIR"
else
    echo "[vendorsetup] Clang r563880 found. Skipping cloning."
fi