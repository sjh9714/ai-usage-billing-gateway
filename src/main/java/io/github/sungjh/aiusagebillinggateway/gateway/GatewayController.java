package io.github.sungjh.aiusagebillinggateway.gateway;

import io.github.sungjh.aiusagebillinggateway.security.AuthenticatedApiKey;
import io.github.sungjh.aiusagebillinggateway.security.SecurityPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/gateway")
public class GatewayController {

    private final GatewayService gatewayService;

    public GatewayController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @PostMapping("/mock-completion")
    Map<String, Object> mockCompletion(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody GatewayRequest request) {
        AuthenticatedApiKey apiKey = SecurityPrincipal.currentApiKey();
        return gatewayService.mockCompletion(apiKey, idempotencyKey, request.prompt());
    }

    public record GatewayRequest(@NotBlank String prompt) {
    }
}
