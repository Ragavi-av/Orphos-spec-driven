package io.orphos.eip.voice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Voice Service — speech-to-text for every Ask Orphos affordance.
 *
 * <p>This is the browser-facing half of voice: the SPA records from the
 * microphone and POSTs the clip here, and this service relays it to the Whisper
 * ASR sidecar and returns the transcript. It is deliberately SEPARATE from
 * doc-ingest's own {@code TranscriptionClient}, which transcribes audio FILES
 * already on disk as part of the ingestion pipeline. The two share a Whisper
 * container but nothing else, and either can run without the other.</p>
 *
 * <p>Voice is an opt-in platform functionality: this service and its Whisper
 * sidecar are started only when a platform admin enables voice (Admin → Voice),
 * and it answers for a given tenant only when that tenant has been enabled too.
 * Everything needed to decide that is behind {@code GET /api/voice/status}.</p>
 */
@SpringBootApplication
@EnableScheduling
public class VoiceApplication {

    public static void main(String[] args) {
        // Read by log4j2-json-layout.json's "hostIpAddress" field. Must be set before Spring
        // (and therefore Log4j2) starts, since it's resolved once at config load and reused for
        // every log line for the life of the process.
        System.setProperty("HOST_IP", resolveHostIp());
        SpringApplication.run(VoiceApplication.class, args);
    }

    private static String resolveHostIp() {
        String podIp = System.getenv("POD_IP");
        if (podIp != null && !podIp.isBlank()) {
            return podIp;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
