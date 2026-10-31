package com.stayos.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component @Profile("prod")
public class ProductionKeyGuard {
    public ProductionKeyGuard(@Value("${stayos.guests.key-fingerprint:}") String fingerprint) {
        if(fingerprint==null || !fingerprint.matches("[a-fA-F0-9]{64}"))
            throw new IllegalStateException("Production requires a separately recorded SHA-256 guest-key fingerprint");
    }
}
