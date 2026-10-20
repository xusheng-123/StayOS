package com.stayos.stay.service;

import com.stayos.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Component
public class GuestProtection {
    public static final String KEY_ID="v1";
    private final SecretKeySpec key;
    private final byte[] hashKey;
    private final SecureRandom random=new SecureRandom();
    public GuestProtection(@Value("${stayos.guests.encryption-key:}") String encoded) {
        try {
            byte[] bytes=Base64.getDecoder().decode(encoded);
            if(bytes.length!=32) throw new IllegalArgumentException();
            key=new SecretKeySpec(bytes,"AES");
            Mac derivation=Mac.getInstance("HmacSHA256"); derivation.init(new SecretKeySpec(bytes,"HmacSHA256"));
            hashKey=derivation.doFinal("StayOS/check-in/request-hash/v1".getBytes(StandardCharsets.UTF_8));
        } catch(Exception ignored) { throw new IllegalStateException("Configure a private 32-byte Base64 STAYOS_GUEST_ENCRYPTION_KEY; see scripts/init-guest-key.ps1"); }
    }
    public String encrypt(String value,String field) {
        if(value.isEmpty()) return null;
        try {
            byte[] iv=new byte[12]; random.nextBytes(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));
            cipher.updateAAD(("StayOS/"+KEY_ID+"/"+field).getBytes(StandardCharsets.UTF_8));
            return KEY_ID+"."+Base64.getEncoder().encodeToString(iv)+"."+Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch(GeneralSecurityException ignored) { throw new ApiException(500,"PROTECTION_ERROR","住客资料保护失败，请联系管理员"); }
    }
    public String fingerprint(byte[] input) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(hashKey,"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input));
        } catch(GeneralSecurityException ignored) { throw new IllegalStateException("Request protection unavailable"); }
    }
    public String mask(String value) {
        if(value.isEmpty()) return "";
        return value.length()<=4?"****":value.substring(0,2)+"****"+value.substring(value.length()-2);
    }
}
