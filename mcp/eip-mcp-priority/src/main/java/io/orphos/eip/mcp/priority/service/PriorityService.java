package io.orphos.eip.mcp.priority.service;

import io.orphos.eip.mcp.priority.exception.EIPMCPException;
import io.orphos.eip.mcp.priority.model.AlertsVo;
import io.orphos.eip.mcp.priority.model.PriorityQueueVo;
import io.orphos.eip.mcp.priority.model.SlaVo;

/**
 * The priority capabilities this server exposes, in typed form. Sits between the MCP tool surface
 * ({@code PriorityTools}) and the transport ({@code PriorityEngineClient}): it owns which upstream
 * endpoint answers which capability and what the response means, leaving the tools layer with only
 * tenant resolution and result formatting.
 */
public interface PriorityService {

    /** The tenant's EPQ-scored work queue. */
    PriorityQueueVo getQueue(String tenantId) throws EIPMCPException;

    /** SLA metrics and breach status for the tenant's tracked work. */
    SlaVo getSla(String tenantId) throws EIPMCPException;

    /** Active priority alerts for the tenant. */
    AlertsVo getAlerts(String tenantId) throws EIPMCPException;
}
