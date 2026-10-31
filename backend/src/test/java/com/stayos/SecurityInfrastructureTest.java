package com.stayos;

import com.stayos.auth.ClientAddressResolver;
import com.stayos.common.ReadinessController;
import com.stayos.stay.service.GuestProtection;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecurityInfrastructureTest {
    MockHttpServletRequest request(String peer,String chain){var r=new MockHttpServletRequest();r.setRemoteAddr(peer);r.addHeader("X-Forwarded-For",chain);return r;}
    @Test void untrustedClientCannotSpoofItsThrottlingAddress(){assertThat(new ClientAddressResolver("").resolve(request("192.0.2.4","198.51.100.8"))).isEqualTo("192.0.2.4");}
    @Test void trustedProxyChainUsesNearestUntrustedClientRatherThanSpoofedLeftEdge(){
        var resolver=new ClientAddressResolver("127.0.0.1/32,10.0.0.0/8");
        assertThat(resolver.resolve(request("127.0.0.1","198.51.100.99,192.0.2.8,10.1.2.3"))).isEqualTo("192.0.2.8");
        assertThat(resolver.resolve(request("127.0.0.1","192.0.2.9"))).isEqualTo("192.0.2.9");
    }
    @Test void malformedOrOversizedForwardedHeaderCannotBecomeANewRateLimitBucket(){
        var resolver=new ClientAddressResolver("127.0.0.1");
        for(var chain:List.of("attacker.example","999.1.1.1","192.0.2.1,,10.0.0.1","192.0.2.1,".repeat(100)))assertThat(resolver.resolve(request("127.0.0.1",chain))).isEqualTo("127.0.0.1");
        assertThatThrownBy(()->new ClientAddressResolver("attacker.example")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void readinessReturns503WithoutDatabaseExceptionDetails() throws Exception {
        var source=mock(DataSource.class);when(source.getConnection()).thenThrow(new SQLException("private JDBC credential detail"));
        var response=new ReadinessController(source).ready();assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().toString()).doesNotContain("JDBC","credential","SQLException");
    }
    @Test void testProfilePinsItsOwnFixtureKeyAndFingerprint(){
        var yaml=new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.ClassPathResource("application-test.yml"));
        var properties=yaml.getObject();assertThat(properties).isNotNull();
        String key=properties.getProperty("stayos.guests.encryption-key"),fingerprint=properties.getProperty("stayos.guests.key-fingerprint");
        assertThat(fingerprint).matches("[a-f0-9]{64}");
        assertThat(new GuestProtection(key,fingerprint).keyFingerprint()).isEqualTo(fingerprint);
    }
    @Test void validButWrongEncryptionKeyIsRejectedWhenFingerprintIsPinned(){
        byte[] first=new byte[32],second=new byte[32];new java.security.SecureRandom().nextBytes(first);new java.security.SecureRandom().nextBytes(second);
        String key=Base64.getEncoder().encodeToString(first),other=Base64.getEncoder().encodeToString(second);
        var original=new GuestProtection(key);
        assertThat(new GuestProtection(key,original.keyFingerprint()).keyFingerprint()).isEqualTo(original.keyFingerprint());
        assertThatThrownBy(()->new GuestProtection(other,original.keyFingerprint())).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(key).hasMessageNotContaining(other);
        assertThatThrownBy(()->new com.stayos.auth.ProductionKeyGuard("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new com.stayos.auth.ProductionKeyGuard("v1")).isInstanceOf(IllegalStateException.class);
    }
}
