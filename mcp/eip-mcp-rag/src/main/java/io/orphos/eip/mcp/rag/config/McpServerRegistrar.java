package io.orphos.eip.mcp.rag.config;

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

    private final RagMcpProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate http;

    public McpServerRegistrar(RagMcpProperties props, EipRestTemplates restTemplates) {
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
                new ToolDescriptor("rag.search_documents", "Semantic document search", false, false),
                new ToolDescriptor("rag.get_document", "Fetch a document by id", false, false),
                new ToolDescriptor("rag.answer", "Grounded RAG answer", false, false));

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
