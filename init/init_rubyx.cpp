//
// SPDX-FileCopyrightText: 2023-2025 The LineageOS Project
// SPDX-License-Identifier: Apache-2.0
//

#include <cstdlib>
#define _REALLY_INCLUDE_SYS__SYSTEM_PROPERTIES_H_
#include <sys/_system_properties.h>
#include <sys/sysinfo.h>

#include <android-base/properties.h>
#include "property_service.h"
#include "vendor_init.h"

using android::base::GetProperty;
using std::string;


void property_override(const std::string& prop, const std::string& value, bool add = true) {
    auto pi = const_cast<prop_info*>(__system_property_find(prop.c_str()));
    if (pi != nullptr) {
        __system_property_update(pi, value.c_str(), value.size());
        return;
    }

    if (add) {
        __system_property_add(prop.c_str(), prop.size(), value.c_str(), value.size());
    }
}


void vendor_load_properties() {
    const std::string sku = GetProperty("ro.boot.product.hardware.sku", "");

    std::string market_name;
    std::string camera;

    if (sku == "ruby") {
        market_name = "Redmi Note 12 Pro 5G";
        camera = "50MP + 8MP + 2MP // 16 MP";
    } else if (sku == "rubypro") {
        market_name = "Redmi Note 12 Pro+ 5G";
        camera = "200MP + 8MP + 2MP // 16 MP";
    } else if (sku == "rubyplus") {
        market_name = "Redmi Note 12 Pro 5G Discovery Edition";
        camera = "200MP + 8MP + 2MP // 16 MP";
    } else {
        //generic fallback
        market_name = "Redmi Note 12 Pro/ Pro+ 5G/ Discovery Edition";
        camera = "200MP + 8MP + 2MP // 16 MP";
    }

    property_override("ro.product.marketname", market_name);
    property_override("ro.infinity.camera", camera);
}
