package io.orphos.eip.dataingestion.controller;

import io.orphos.eip.dataingestion.service.JobService;
import io.orphos.eip.dataingestion.vo.JobDtos.JobListResponse;
import io.orphos.eip.dataingestion.vo.JobDtos.JobView;
import io.orphos.eip.dataingestion.vo.JobDtos.RunListResponse;
import io.orphos.eip.dataingestion.vo.JobDtos.RunView;
import io.orphos.eip.dataingestion.vo.JobDtos.SaveJobRequest;
import io.orphos.eip.dataingestion.vo.JobDtos.TriggerResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Batch-job management API (requirements 4 and 5). Jobs pair a saved mapping
 * with a schedule (CRON / ONE_TIME / NONE), a load mode (FULL / INCREMENTAL)
 * and a merge strategy (UPSERT / INSERT); the scheduler executes whatever is
 * due, {@code POST /{id}/run} executes immediately.
 *
 * <p>Requirement 4.10 — external-scheduler integration: {@code POST /{id}/run}
 * IS the integration point. An external scheduler (Airflow, cron + curl, ...)
 * authenticates like any admin client (bearer token with one of the roles
 * below) and triggers the job by id; the response carries the {@code runId}
 * to poll via {@code GET /runs/{runId}}.</p>
 *
 * <p>Validation problems reject with 422 and a human-readable message;
 * duplicate names with 409 — same contract as MappingController.</p>
 */
@RestController
@RequestMapping("/api/data-ingestion/admin")
@PreAuthorize("hasAnyRole('admin', 'platform_admin', 'data_ai_admin')")
public class JobController {

    private final JobService service;

    public JobController(JobService service) {
        this.service = service;
    }

    @GetMapping("/jobs")
    public JobListResponse list() {
        return new JobListResponse(service.list());
    }

    @GetMapping("/jobs/{id}")
    public JobView get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    public JobView create(@Valid @RequestBody SaveJobRequest req) {
        return service.create(req);
    }

    @PutMapping("/jobs/{id}")
    public JobView update(@PathVariable String id, @Valid @RequestBody SaveJobRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/jobs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    /**
     * Requirement 5.1 (and 4.10) — trigger an immediate asynchronous run.
     * The mapping is re-validated first (req 5.2); 422 when it has gone stale.
     */
    @PostMapping("/jobs/{id}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TriggerResponse run(@PathVariable String id) {
        return service.runNow(id);
    }

    /** Requirement 4.8 — pause: scheduler skips the job, configuration is kept. */
    @PostMapping("/jobs/{id}/pause")
    public JobView pause(@PathVariable String id) {
        return service.pause(id);
    }

    /** Requirement 4.8 — resume; CRON jobs get a fresh next occurrence from now. */
    @PostMapping("/jobs/{id}/resume")
    public JobView resume(@PathVariable String id) {
        return service.resume(id);
    }

    /** Requirement 4.4 — execution history, most recent first. */
    @GetMapping("/jobs/{id}/runs")
    public RunListResponse runs(@PathVariable String id,
                                @RequestParam(defaultValue = "50") int limit) {
        return service.listRuns(id, limit);
    }

    /**
     * Requirement 5.4 — poll a run's progress; terminal rows are the summary
     * (req 5.6). Anchored at /runs (not /jobs/runs) — this is the path the
     * admin UI and the trigger-response message advertise.
     */
    @GetMapping("/runs/{runId}")
    public RunView getRun(@PathVariable String runId) {
        return service.getRun(runId);
    }

    /** Requirement 5.5 — cooperative cancellation; the run stops at the next row boundary. */
    @PostMapping("/runs/{runId}/cancel")
    public TriggerResponse cancelRun(@PathVariable String runId) {
        return service.cancelRun(runId);
    }
}
