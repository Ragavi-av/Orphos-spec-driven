package io.orphos.eip.mcp.priority.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.mcp.priority.constants.AppConstant;
import io.orphos.eip.mcp.priority.exception.EIPMCPException;
import io.orphos.eip.mcp.priority.exception.Error;
import io.orphos.eip.mcp.priority.model.AlertsVo;
import io.orphos.eip.mcp.priority.model.PriorityQueueVo;
import io.orphos.eip.mcp.priority.model.SlaVo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Maps each priority capability onto its upstream endpoint and parses the response into a value
 * object. Parsing here (rather than forwarding the raw body) means a change in priority-engine's
 * contract surfaces as a failure in this server's logs instead of as a malformed tool result the
 * planner has to interpret.
 */
@Service
public class PriorityServiceImpl implements PriorityService {

    private static final Logger log = LogManager.getLogger(PriorityServiceImpl.class);

    private final PriorityEngineClient client;
    private final ObjectMapper mapper;

    public PriorityServiceImpl(PriorityEngineClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public PriorityQueueVo getQueue(String tenantId) throws EIPMCPException {
        return read(AppConstant.PRIORITY_QUEUE_PATH, tenantId, PriorityQueueVo.class);
    }

    @Override
    public SlaVo getSla(String tenantId) throws EIPMCPException {
        return read(AppConstant.PRIORITY_SLA_PATH, tenantId, SlaVo.class);
    }

    @Override
    public AlertsVo getAlerts(String tenantId) throws EIPMCPException {
        return read(AppConstant.PRIORITY_ALERTS_PATH, tenantId, AlertsVo.class);
    }

    private <T> T read(String path, String tenantId, Class<T> type) throws EIPMCPException {
        String body = client.get(path, tenantId);
        try {
            return mapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            log.warn("Unreadable priority-engine response for {}: {}", path, e.getMessage());
            throw new EIPMCPException(
                    new Error("priority-engine returned an unreadable response for " + path,
                            AppConstant.ERROR_CODE_RESPONSE_UNREADABLE),
                    e);
        }
    }
}
