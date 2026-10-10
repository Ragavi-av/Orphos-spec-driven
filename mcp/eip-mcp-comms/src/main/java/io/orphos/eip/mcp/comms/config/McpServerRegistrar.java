package io.orphos.eip.mcp.comms.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.agent.spec.McpServerRegistration;
import io.orphos.eip.agent.spec.ToolDescriptor;
import io.orphos.eip.common.http.EipRestTemplates;
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

/** Self-registers this MCP server with platform-registry at startup (TO-BE design §6). */
@Component
@Order(20)
public class McpServerRegistrar implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(McpServerRegistrar.class);

    private final CommsMcpProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate http;

    public McpServerRegistrar(CommsMcpProperties props, EipRestTemplates restTemplates) {
        this.props = props;
        this.http = restTemplates.createLenient(Duration.ofSeconds(10), Duration.ofSeconds(15));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isRegisterOnStartup()) {
            log.info("MCP-server self-registration disabled.");
            return;
        }
        List<ToolDescriptor> tools = List.of(
                new ToolDescriptor("comms.list_tasks", "List channel-derived tasks", false, false),
                new ToolDescriptor("comms.list_notifications", "List channel notifications", false, false),
                new ToolDescriptor("comms.draft_reply", "Draft a reply (no send)", false, false),
                new ToolDescriptor("comms.prepare_send", "Re-mint a send confirmation token (approval edit flow)", false, false),
                // send_message is bound to a confirmation token; the edit-before-approve flow re-issues it
                // via the companion comms.prepare_send tool into the "confirmationToken" argument.
                new ToolDescriptor("comms.send_message", "Send a drafted channel reply", true, true,
                        "comms.prepare_send", "confirmationToken"));

        McpServerRegistration registration = new McpServerRegistration(
                props.getServerId(), props.getMcpEndpoint(), tools, props.getHealthUrl());
        try {
            String body = mapper.writeValueAsString(registration);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(McpHeaders.INTERNAL_TOKEN, props.getInternalToken());
            ResponseEntity<String> res = http.postForEntity(
                    base() + "/api/platform/mcp-servers/register", new HttpEntity<>(body, headers), String.class);
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
