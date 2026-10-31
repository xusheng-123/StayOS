package com.stayos.stay.service;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class GuestProtectionCorrectionTest {
    final GuestProtection protection=new GuestProtection(Base64.getEncoder().encodeToString(new byte[32]));
    @Test void authenticatedCorrectionSnapshotsUseFreshIvAndDedicatedAad() {
        String snapshot="{\"phone\":\"13900000999\"}";
        String first=protection.encrypt(snapshot,"guest-correction"),second=protection.encrypt(snapshot,"guest-correction");
        assertThat(first).isNotEqualTo(second).doesNotContain("13900000999");
        assertThat(protection.decrypt(first,"guest-correction")).isEqualTo(snapshot);
        assertThatThrownBy(()->protection.decrypt(first,"document")).isInstanceOf(com.stayos.common.ApiException.class).hasMessageNotContaining("13900000999");
    }
    @Test void corruptionAndUnknownKeyFailClosedWithoutLeakingPayload() {
        String encoded=protection.encrypt("FICTIONAL-SECRET","document");
        var parts=encoded.split("\\.");byte[] ciphertext=Base64.getDecoder().decode(parts[2]);ciphertext[0]^=1;
        assertThatThrownBy(()->protection.decrypt(parts[0]+"."+parts[1]+"."+Base64.getEncoder().encodeToString(ciphertext),"document"))
            .isInstanceOf(com.stayos.common.ApiException.class).hasMessageNotContaining("FICTIONAL-SECRET");
        assertThatThrownBy(()->protection.decrypt(encoded.replaceFirst("v1","v2"),"document")).isInstanceOf(com.stayos.common.ApiException.class);
    }
    @Test void nullablePhoneRetainsExistingEmptyRepresentation() {
        assertThat(protection.encrypt("","phone")).isNull();assertThat(protection.decrypt(null,"phone")).isEmpty();
        String document=protection.encrypt("FICTIONAL-DOCUMENT","document");assertThat(protection.decrypt(document,"document")).isEqualTo("FICTIONAL-DOCUMENT");
    }
}
