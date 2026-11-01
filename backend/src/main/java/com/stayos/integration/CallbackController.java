package com.stayos.integration;

import com.stayos.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.util.Map;

/**
 * Supplier callback entry on the ONE exempt path (guide: 只对精确回调路径应用并由签名认证保护).
 * The raw body is read before anything else so the adapter can verify the signature; no state
 * changes before verification succeeds.
 */
@RestController
public class CallbackController {

    final ChannelService channels;

    public CallbackController(ChannelService channels) { this.channels = channels; }

    @PostMapping("/api/integration/callbacks/{supplierCode}")
    public ApiResponse<?> callback(@PathVariable String supplierCode, HttpServletRequest request) throws Exception {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (body.length() > 0) body.append('\n');
                body.append(line);
            }
        }
        Map<String, String> headers = new java.util.HashMap<>();
        var names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return ApiResponse.ok(channels.receiveCallback(supplierCode, headers, body.toString()));
    }
}
