package com.contractguard.api;

import java.util.List;
import java.util.Map;

import com.contractguard.consumers.ConsumerDeclaration;
import com.contractguard.consumers.ConsumerParseException;
import com.contractguard.consumers.ConsumerRegistry;
import com.contractguard.consumers.DeclaredOperation;
import com.contractguard.impact.ImpactReport;
import com.contractguard.impact.ImpactService;
import com.contractguard.specs.SpecParseException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-side API for the consumer registry and consumer impact. Impact computation is
 * a dry-run: it never persists state or notifies anyone (the publishing workflow,
 * built on top of {@link ImpactReport}, arrives in the next milestone).
 */
@RestController
@RequestMapping("/api")
public class ImpactController {

    private final ImpactService impactService;
    private final ConsumerRegistry consumerRegistry;

    public ImpactController(ImpactService impactService, ConsumerRegistry consumerRegistry) {
        this.impactService = impactService;
        this.consumerRegistry = consumerRegistry;
    }

    /** Registered consumers with their declared bindings. */
    @GetMapping("/consumers")
    public List<Map<String, Object>> consumers() {
        return consumerRegistry.all().stream().map(ImpactController::consumerView).toList();
    }

    /**
     * Consumer impact for a candidate version transition. {@code service} is the
     * provider slug used in consumer files (e.g. "quote-api"); both specs are supplied
     * as YAML text (the registry/persistence milestone will resolve them by version).
     */
    @PostMapping("/impact")
    public ImpactReport impact(@Valid @RequestBody ImpactRequest request) {
        return impactService.compare(request.service().trim(), request.oldSpec(), request.newSpec());
    }

    private static Map<String, Object> consumerView(ConsumerDeclaration consumer) {
        return Map.of(
                "consumer", consumer.name(),
                "team", consumer.team(),
                "operations", consumer.operations().stream()
                        .map(ImpactController::operationView)
                        .toList());
    }

    private static Map<String, Object> operationView(DeclaredOperation op) {
        return Map.of(
                "provider", op.provider(),
                "method", op.method(),
                "path", op.rawPath(),
                "sends", op.sends(),
                "reads", op.reads());
    }

    /** Request body of POST /api/impact. */
    public record ImpactRequest(@NotBlank String service,
                                @NotBlank String oldSpec,
                                @NotBlank String newSpec) {
    }

    @ExceptionHandler({SpecParseException.class, ConsumerParseException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<Map<String, String>> badSpec(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /** Malformed request body (broken JSON): same error contract as invalid specs. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<Map<String, String>> unreadable(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "malformed request body: " + e.getMostSpecificCause().getMessage()));
    }
}
