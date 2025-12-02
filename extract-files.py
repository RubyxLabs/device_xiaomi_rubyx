#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: 2016 The CyanogenMod Project
# SPDX-FileCopyrightText: 2017-2025 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

from extract_utils.file import File
from extract_utils.fixups_blob import (
    BlobFixupCtx,
    blob_fixup,
    blob_fixups_user_type,
)
from extract_utils.fixups_lib import (
    lib_fixups,
    lib_fixups_user_type,
)
from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)

namespace_imports = [
    'device/xiaomi/rubyx',
    'hardware/mediatek',
    'hardware/mediatek/libmtkperf_client',
    'hardware/xiaomi',
]

lib_fixups: lib_fixups_user_type = {
    **lib_fixups,
}

blob_fixups: blob_fixups_user_type = {
    'vendor/bin/mi_thermald': blob_fixup()
        .binary_regex_replace(b'%d/on', b'%d/..'),
    ('vendor/bin/hw/android.hardware.gnss-service.mediatek',
    'vendor/lib64/hw/android.hardware.gnss-impl-mediatek.so'): blob_fixup()
        .replace_needed('android.hardware.gnss-V1-ndk_platform.so', "android.hardware.gnss-V1-ndk.so"),
    
    'vendor/bin/hw/android.hardware.media.c2@1.2-mediatek-64b': blob_fixup()
        .add_needed('libstagefright_foundation-v33.so')
        .replace_needed('libavservices_minijail_vendor.so', 'libavservices_minijail.so'),
    'vendor/etc/init/android.hardware.neuralnetworks@1.3-service-mtk-neuron.rc': blob_fixup()
        .regex_replace('start', 'enable'),
    ('vendor/firmware/txpowerctrl_gl.cfg',
     'vendor/firmware/txpowerctrl_gl_u.cfg',
     'vendor/firmware/txpowerctrl_in.cfg',
     'vendor/firmware/txpowerctrl_in_u.cfg'): blob_fixup()
        .regex_replace(r'\t', ''),
    
    ('vendor/bin/mnld',
     'vendor/lib/libaalservice.so',
     'vendor/lib64/libaalservice.so'): blob_fixup()
        .replace_needed('libsensorndkbridge.so', 'android.hardware.sensors@1.0-convert-shared.so'),

    ('vendor/lib/hw/audio.primary.mt6877.so',
     'vendor/lib64/hw/audio.primary.mt6877.so'): blob_fixup()
        .add_needed('libstagefright_foundation-v33.so')
        .replace_needed('libalsautils.so', 'libalsautils-v31.so'),
    
    'vendor/lib64/libmnl.so': blob_fixup()
        .add_needed('libcutils.so'),
    
    ('vendor/lib/libteei_daemon_vfs.so',
     'vendor/lib64/libteei_daemon_vfs.so',
     'vendor/lib64/libSQLiteModule_VER_ALL.so',
     'vendor/lib64/lib3a.flash.so',
     'vendor/lib64/lib3a.ae.stat.so',
     'vendor/lib64/lib3a.sensors.color.so',
     'vendor/lib64/lib3a.sensors.flicker.so',
     'vendor/lib64/libaaa_ltm.so'): blob_fixup()
        .add_needed('liblog.so'),
    
    'vendor/lib64/hw/vendor.mediatek.hardware.pq@2.14-impl.so': blob_fixup()
        .replace_needed('libutils.so', 'libutils-v32.so')
        .replace_needed('libsensorndkbridge.so', 'android.hardware.sensors@1.0-convert-shared.so'),
    
    ('vendor/lib64/libmtkcam_stdutils.so',
     'vendor/lib64/hw/android.hardware.camera.provider@2.6-impl-mediatek.so'): blob_fixup()
        .replace_needed('libutils.so', 'libutils-v32.so'),
    
    ('vendor/lib64/libdlbdsservice.so',
     'vendor/lib/soundfx/libswdap.so',
     'vendor/lib64/soundfx/libswdap.so'): blob_fixup()
        .replace_needed('libstagefright_foundation.so', 'libstagefright_foundation-v33.so'),

    ('vendor/lib/libnvram.so',
     'vendor/lib64/libnvram.so',
     'vendor/lib64/libsysenv.so',
     'vendor/bin/hw/android.hardware.neuralnetworks@1.3-service-mtk-neuron'): blob_fixup()
        .add_needed('libbase_shim.so'),

    'vendor/bin/hw/mtkfusionrild': blob_fixup()
        .add_needed('libutils-v32.so'),

    'vendor/etc/init/android.hardware.neuralnetworks@1.3-service-mtk-neuron.rc': blob_fixup()
        .regex_replace('start', 'enable'),
        
    ('vendor/lib64/libcam.hal3a.v3.so',
    'vendor/lib64/libeffecthal.base.so',
    'vendor/lib64/libmtkcam_grallocutils.so',
    'vendor/lib64/libmtkcam_3rdparty.vidhance.so'): blob_fixup()
        .replace_needed('libui.so', 'libui-v34.so'),
}

module = ExtractUtilsModule(
    'rubyx',
    'xiaomi',
    blob_fixups=blob_fixups,
    lib_fixups=lib_fixups,
    namespace_imports=namespace_imports,
    add_firmware_proprietary_file=True,
)

if __name__ == '__main__':
    utils = ExtractUtils.device(module)
    utils.run()
