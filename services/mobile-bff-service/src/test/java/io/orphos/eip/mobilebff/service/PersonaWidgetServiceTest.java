package io.orphos.eip.mobilebff.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.mobilebff.component.DownstreamClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Metrics tab shows the caller's own web board: the layout's widget list in board
 * order, live-feed widgets computed like the web tile, and ontology values otherwise.
 */
class PersonaWidgetServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String W = "http://widgets", B = "http://briefing", D = "http://decisions",
            P = "http://priority", N = "http://notification";

    private DownstreamClient ds;
    private PersonaWidgetService service;

    @BeforeEach
    void setUp() {
        ds = mock(DownstreamClient.class);
        service = new PersonaWidgetService(ds, W, B, D, P, N);
    }

    private static JsonNode json(String s) throws Exception {
        return JSON.readTree(s);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> widgets(Map<String, Object> board) {
        return (List<Map<String, Object>>) board.get("widgets");
    }

    @Test
    void followsTheLayoutOrderAndComputesLiveFeeds() throws Exception {
        when(ds.get(eq(W), eq("/api/dashboard/layout/me"), anyString(), anyString())).thenReturn(json(
                "{\"visibleIds\":[\"revenue-arr\",\"pending-decisions\"],"
                        + "\"order\":[\"pending-decisions\",\"uptime\",\"revenue-arr\"],\"source\":\"persona\"}"));
        when(ds.get(eq(D), eq("/api/decisions"), anyString(), anyString())).thenReturn(json(
                "[{\"status\":\"PENDING\"},{\"status\":\"APPROVED\"},{\"status\":\"PENDING\"}]"));
        when(ds.get(eq(W), eq("/api/dashboard/persona-metrics/revenue-arr"), anyString(), anyString())).thenReturn(json(
                "{\"widgetKey\":\"revenue-arr\",\"value\":37300000,\"unit\":\"CURRENCY\",\"currency\":\"INR\","
                        + "\"permitted\":true,\"available\":true}"));

        Map<String, Object> board = service.board(null, "Bearer t", "tenant");

        assertThat(board.get("source")).isEqualTo("persona");
        List<Map<String, Object>> ws = widgets(board);
        assertThat(ws).extracting(w -> w.get("id")).containsExactly("pending-decisions", "revenue-arr");
        assertThat(ws.get(0).get("value")).isEqualTo(2L);
        assertThat(ws.get(0).get("label")).isEqualTo("Pending Decisions");
        assertThat(ws.get(1).get("label")).isEqualTo("Recurring Revenue");
        assertThat(ws.get(1).get("area")).isEqualTo("Commercial");
        assertThat(((JsonNode) ws.get(1).get("value")).asLong()).isEqualTo(37300000L);
        // Live widgets never read the quarterly ontology observation while their feed is up.
        verify(ds, never()).get(eq(W), eq("/api/dashboard/persona-metrics/pending-decisions"), anyString(), anyString());
    }

    @Test
    void aLiveWidgetFallsBackToItsOntologyObservationWhenTheFeedIsDown() throws Exception {
        when(ds.get(eq(W), eq("/api/dashboard/layout/me"), anyString(), anyString())).thenReturn(json(
                "{\"visibleIds\":[\"incidents\"],\"order\":[\"incidents\"],\"source\":\"system\"}"));
        when(ds.get(eq(W), eq("/api/dashboard/persona-metrics/incidents"), anyString(), anyString())).thenReturn(json(
                "{\"value\":42,\"period\":\"FY2027 Q2\",\"permitted\":true,\"available\":true}"));

        Map<String, Object> w = widgets(service.board(null, "Bearer t", "tenant")).get(0);

        assertThat(((JsonNode) w.get("value")).asInt()).isEqualTo(42);
        assertThat(((JsonNode) w.get("period")).asText()).isEqualTo("FY2027 Q2");
    }

    @Test
    void anUnreachableMetricIsNotConnectedRatherThanZero() throws Exception {
        when(ds.get(eq(W), eq("/api/dashboard/layout/me"), anyString(), anyString())).thenReturn(json(
                "{\"visibleIds\":[\"payroll\"],\"order\":[],\"source\":\"user\"}"));

        Map<String, Object> w = widgets(service.board("INR", "Bearer t", "tenant")).get(0);

        assertThat(w).containsEntry("available", false).containsEntry("connected", false).doesNotContainKey("value");
    }

    @Test
    void hrWidgetsAreRefusedOutsideTheHrDomainWithoutFallingBackToTheOntology() throws Exception {
        when(ds.get(eq(W), eq("/api/dashboard/layout/me"), anyString(), anyString())).thenReturn(json(
                "{\"visibleIds\":[\"headcount\"],\"order\":[\"headcount\"],\"source\":\"user\"}"));
        io.orphos.eip.common.access.PersonaDomainPolicy.Scope ceo = new io.orphos.eip.common.access.PersonaDomainPolicy.Scope(
                false, java.util.Set.of("finance", "commercial", "risk"), true);

        Map<String, Object> w = widgets(service.board(ceo, null, "Bearer t", "tenant")).get(0);

        assertThat(w).containsEntry("permitted", false).doesNotContainKey("value");
        verify(ds, never()).get(eq(B), eq("/api/zenitus/hr/summary"), anyString(), anyString());
        verify(ds, never()).get(eq(W), eq("/api/dashboard/persona-metrics/headcount"), anyString(), anyString());
    }

    @Test
    void noLayoutMeansAnEmptyBoard() {
        assertThat(widgets(service.board(null, "Bearer t", "tenant"))).isEmpty();
    }
}
