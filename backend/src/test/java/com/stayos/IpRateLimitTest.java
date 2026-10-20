package com.stayos;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import java.net.*;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="stayos.auth.ip-limit=2")
@ActiveProfiles("test")
class IpRateLimitTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Test void thirdAttemptFromSameIpIsRejectedAndForwardedHeaderCannotBypass() throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var root="http://127.0.0.1:"+port;
        for(int i=0;i<3;i++) {
            var csrf=client.send(HttpRequest.newBuilder(URI.create(root+"/api/auth/csrf")).build(),HttpResponse.BodyHandlers.ofString());
            var token=json.readTree(csrf.body()).get("data");
            var response=client.send(HttpRequest.newBuilder(URI.create(root+"/api/auth/login"))
                .header(token.get("headerName").asText(),token.get("token").asText())
                .header("X-Forwarded-For","192.0.2."+i).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"nonexistent\",\"password\":\"incorrect-password\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(i<2?401:429);
        }
    }
}
