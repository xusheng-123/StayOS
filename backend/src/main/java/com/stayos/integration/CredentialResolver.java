package com.stayos.integration;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Turns stored credential REFERENCES into secret values at use time. References name a JVM system
 * property or OS environment entry; the secret itself never lives in the database, Git or logs
 * (guide section 14: 凭据保持 Git 外配置，普通查询不返回).
 */
@Component
public class CredentialResolver {

    private final Environment environment;

    public CredentialResolver(Environment environment) { this.environment = environment; }

    public String resolve(String reference) {
        if (reference == null || reference.isBlank()) return null;
        String value = System.getProperty(reference);
        if (value == null || value.isEmpty()) value = System.getenv(reference);
        if (value == null || value.isEmpty()) value = environment.getProperty(reference);
        return (value == null || value.isEmpty()) ? null : value;
    }

    public SupplierAdapter.Credentials credentials(String credentialRef, String callbackSecretRef) {
        String credential = resolve(credentialRef);
        String callbackSecret = resolve(callbackSecretRef);
        if (credential == null || callbackSecret == null)
            throw new com.stayos.common.ApiException(409, "CREDENTIAL_UNRESOLVED",
                "渠道凭据引用无法解析，请先在运行环境配置对应密钥（不入库）");
        return new SupplierAdapter.Credentials(credentialRef, callbackSecretRef, credential, callbackSecret);
    }
}
