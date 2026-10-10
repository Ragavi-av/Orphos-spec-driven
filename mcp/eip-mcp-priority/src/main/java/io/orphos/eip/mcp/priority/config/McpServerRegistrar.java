package io.orphos.eip.mcp.priority.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.agent.spec.McpServerRegistration;
import io.orphos.eip.agent.spec.ToolDescriptor;
import io.orphos.eip.common.http.EipRestTemplates;
import io.orphos.eip.mcp.priority.constants.AppConstant;
import io.orphos.eip.mcp.spec.McpHeaders;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

/**
 * Self-registers this MCP server with platform-registry at startup (TO-BE design §6): advertises the
 * server endpoint, health URL, and the tools it exposes. Best-effort — a registry that is briefly
 * unavailable must not stop the server from booting.
 */
@Component
@Order(20)
public class McpServerRegistrar implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(McpServerRegistrar.class);

    private final PriorityMcpProperties props;
    private final ObjectMapper mapper;
    private final RestTemplate http;

    public McpServerRegistrar(PriorityMcpProperties props, ObjectMapper mapper, EipRestTemplates restTemplates) {
        this.props = props;
        this.mapper = mapper;
        this.http = restTemplates.createLenient(Duration.ofSeconds(10), Duration.ofSeconds(15));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isRegisterOnStartup()) {
            log.info("MCP-server self-registration disabled.");
            return;
        }
        // Names come from AppConstant so what's advertised here cannot drift from what
        // PriorityTools actually exposes. All read-only, no approval.
        List<ToolDescriptor> tools = List.of(
                new ToolDescriptor(AppConstant.TOOL_GET_QUEUE,
                        AppConstant.TOOL_GET_QUEUE_SHORT_DESCRIPTION, false, false),
                new ToolDescriptor(AppConstant.TOOL_GET_SLA,
                        AppConstant.TOOL_GET_SLA_SHORT_DESCRIPTION, false, false),
                new ToolDescriptor(AppConstant.TOOL_GET_ALERTS,
                        AppConstant.TOOL_GET_ALERTS_SHORT_DESCRIPTION, false, false));

        McpServerRegistration registration = new McpServerRegistration(
                props.getServerId(), props.getMcpEndpoint(), tools, props.getHealthUrl());

        try {
            String body = mapper.writeValueAsString(registration);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(McpHeaders.INTERNAL_TOKEN, props.getInternalToken());
            ResponseEntity<String> res = http.postForEntity(
                    base() + AppConstant.MCP_SERVER_REGISTER_PATH, new HttpEntity<>(body, headers), String.class);
            if (res.getStatusCode().is2xxSuccessful()) {
                log.info("MCP server '{}' registered with platform-registry.", props.getServerId());
            } else {
                log.warn("MCP-server registration returned {}: {}", res.getStatusCode().value(), res.getBody());
            }
        } catch (Exception e) {
            log.warn("MCP-server registration failed (registry unreachable?): {}", e.getMessage(), e);
        }
    }

    private String base() {
        String b = props.getRegistryBaseUrl();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }
}
