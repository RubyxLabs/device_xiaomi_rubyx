/*
 * Copyright (C) 2025 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby.xiaomi.geq.data

data class Preset(
    var name: String,
    val bandGains: List<BandGain>,
    var isUserDefined: Boolean = false,
    var isMutated: Boolean = false
)
