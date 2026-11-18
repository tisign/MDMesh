package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/** One network of the {@code config.apply} wifiNetworks section (proto/payloads/config-apply.schema.json). */
@Getter @Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DesiredWifiNetwork {
    private String ssid;
    /** {@code WPA}, {@code SAE}, {@code WEP} or {@code NONE}. */
    private String security;
    /** Absent for an open network. */
    private String password;
    /** Only sent when true. */
    private Boolean hidden;
}
