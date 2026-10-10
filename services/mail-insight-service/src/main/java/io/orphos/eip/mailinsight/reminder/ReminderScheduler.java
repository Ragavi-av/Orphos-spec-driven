package io.orphos.eip.mailinsight.reminder;

import io.orphos.eip.common.tenant.TenantTaskExecutor;
import io.orphos.eip.mailinsight.datasource.DataSourceReader;
import io.orphos.eip.mailinsight.model.MailInsightDto;
import io.orphos.eip.mailinsight.repo.MailInsightRepository;
import io.orphos.eip.mailinsight.repo.OverrideRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Re-surfaces items whose user-set reminder has come due: flips them back to OPEN
 * and clears the reminder so they reappear at the top of the relevant tab. (MVP
 * behaviour — email delivery via notification-service is a future enhancement.)
 */
@Component
public class ReminderScheduler {

    private static final Logger log = LogManager.getLogger(ReminderScheduler.class);

    private final ObjectProvider<TenantTaskExecutor> tenants;
    private final MailInsightRepository repo;
    private final OverrideRepository overrideRepo;
    private final DataSourceReader dataSource;

    public ReminderScheduler(ObjectProvider<TenantTaskExecutor> tenants, MailInsightRepository repo,
                             OverrideRepository overrideRepo, DataSourceReader dataSource) {
        this.tenants = tenants;
        this.repo = repo;
        this.overrideRepo = overrideRepo;
        this.dataSource = dataSource;
    }

    @Scheduled(fixedDelayString = "${mail-insight.reminder.interval-ms:300000}",
               initialDelayString = "${mail-insight.reminder.initial-delay-ms:90000}")
    public void fireDueReminders() {
        TenantTaskExecutor executor = tenants.getIfAvailable();
        if (executor == null) return;
        String now = Instant.now().toString();
        executor.forEachTenant(tenantId -> {
            try {
                // (1) Email items: a due reminder ends the snooze — clear it so the
                //     item re-surfaces via the read filter. Status is untouched, so a
                //     reminder never resurrects an item the user already closed.
                List<MailInsightDto> dueEmail = repo.findDueReminders(now);
                for (MailInsightDto item : dueEmail) {
                    repo.clearReminder(item.id());
                }

                // (2) Data-source items (overlay): honor the reminder until the item
                //     is closed in the UI or resolved at the source.
                List<Map<String, Object>> dueOverlay = overrideRepo.findDueRemindersForTenant(now);
                int reSurfaced = 0, cleaned = 0;
                if (!dueOverlay.isEmpty()) {
                    Set<String> liveIds = dataSource.allItemIds();
                    for (Map<String, Object> ov : dueOverlay) {
                        String id = str(ov.get("id"));
                        String itemRef = str(ov.get("itemRef"));
                        String status = str(ov.get("status")).toUpperCase();
                        if ("DONE".equals(status) || "DISMISSED".equals(status)) {
                            overrideRepo.clearReminderById(id);          // closed in UI → stop
                            cleaned++;
                        } else if (!liveIds.contains(itemRef)) {
                            overrideRepo.deleteById(id);                 // resolved at source → cleanup
                            cleaned++;
                        } else {
                            overrideRepo.clearReminderById(id);          // snooze ended → re-surface
                            reSurfaced++;
                        }
                    }
                }

                int total = dueEmail.size() + reSurfaced;
                if (total > 0 || cleaned > 0) {
                    log.info("Reminders for tenant {}: re-surfaced {}, cleaned {}", tenantId, total, cleaned);
                }
            } catch (Exception e) {
                log.debug("Reminder sweep failed for tenant {}: {}", tenantId, e.getMessage());
            }
        });
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }
}
