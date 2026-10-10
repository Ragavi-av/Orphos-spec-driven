package io.orphos.eip.decision.component;

import io.orphos.eip.common.tenant.TenantTaskExecutor;
import io.orphos.eip.decision.repository.DecisionRepository;
import io.orphos.eip.decision.service.DecisionService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Real_Time_Tracker escalation sweep (Req 10.5): flags decisions whose due
 * date passed without the implementation completing, marks them escalated
 * (once) and notifies stakeholders through the platform event bus.
 */
@Component
@ConditionalOnProperty(prefix = "decision-log.escalation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EscalationScheduler {

    private static final Logger log = LogManager.getLogger(EscalationScheduler.class);

    private final ObjectProvider<TenantTaskExecutor> tenants;
    private final DecisionRepository repo;
    private final DecisionService decisions;

    public EscalationScheduler(ObjectProvider<TenantTaskExecutor> tenants, DecisionRepository repo,
                               DecisionService decisions) {
        this.tenants = tenants;
        this.repo = repo;
        this.decisions = decisions;
    }

    @Scheduled(fixedDelayString = "${decision-log.escalation.interval-ms:300000}",
               initialDelayString = "${decision-log.escalation.initial-delay-ms:120000}")
    public void escalateOverdue() {
        TenantTaskExecutor executor = tenants.getIfAvailable();
        if (executor == null) return;
        executor.forEachTenant(tenantId -> {
            String now = Instant.now().toString();
            for (Map<String, Object> decision : repo.overdueDecisions(tenantId.toString(), now)) {
                decisions.escalate(tenantId.toString(), decision,
                    "Implementation overdue: due " + decision.get("dueDate")
                        + ", status " + decision.get("implementationStatus") + ".");
                log.info("Escalated overdue decision {} for tenant {}", decision.get("id"), tenantId);
            }
        });
    }
}
