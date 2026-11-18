package com.contractguard.impact;

import com.contractguard.consumers.ConsumerDeclaration;
import com.contractguard.consumers.ConsumerRegistry;
import com.contractguard.specs.SpecDocument;
import com.contractguard.specs.SpecParser;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Pipeline splice: parse two spec versions → diff (compatibility engine) → classify
 * consumer impact (registry × findings). The returned {@link ImpactReport} is the
 * unit the publishing-policy milestone will consume: findings for the gate rules,
 * the impact summary for the "breaking with affected consumers" condition.
 *
 * Pure computation — no persistence, no state changes (that is the publishing
 * workflow's job in a later milestone).
 */
@Service
public class ImpactService {

    private final SpecParser specParser;
    private final com.contractguard.compatibility.CompatibilityEngine compatibilityEngine
            = new com.contractguard.compatibility.CompatibilityEngine();
    private final ConsumerImpactAnalyzer impactAnalyzer = new ConsumerImpactAnalyzer();
    private final ConsumerRegistry consumerRegistry;

    public ImpactService(SpecParser specParser, ConsumerRegistry consumerRegistry) {
        this.specParser = specParser;
        this.consumerRegistry = consumerRegistry;
    }

    /**
     * @param service provider service slug as used in consumer files (e.g. "quote-api")
     * @param oldYaml currently published spec YAML
     * @param newYaml candidate spec YAML
     */
    public ImpactReport compare(String service, String oldYaml, String newYaml) {
        SpecDocument oldDoc = specParser.parse(oldYaml);
        SpecDocument newDoc = specParser.parse(newYaml);

        List<com.contractguard.compatibility.CompatibilityFinding> findings =
                compatibilityEngine.diff(oldDoc.getEffectiveSchemas(), newDoc.getEffectiveSchemas(),
                        oldDoc.getPathAliases(), newDoc.getPathAliases());

        List<ConsumerDeclaration> consumers = consumerRegistry.all();
        ImpactSummary summary = impactAnalyzer.analyze(service, oldDoc, newDoc, findings, consumers);

        return new ImpactReport(service, oldDoc.getSpecVersion(), newDoc.getSpecVersion(),
                findings, summary);
    }
}
