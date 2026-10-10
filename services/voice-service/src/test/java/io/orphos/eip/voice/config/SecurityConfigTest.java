package io.orphos.eip.voice.config;

import io.orphos.eip.voice.client.WhisperClient;
import io.orphos.eip.voice.controller.VoiceController;
import io.orphos.eip.voice.service.VoiceFeatureGate;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs the real SecurityConfig: who may reach /api/voice/** at all (the tenant gate is tested elsewhere). */
@WebMvcTest(controllers = VoiceController.class)
@Import({ SecurityConfig.class, SecurityConfigTest.StubBeans.class })
@TestPropertySource(properties =
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration")
class SecurityConfigTest {

    @Autowired MockMvc mvc;

    @TestConfiguration
    static class StubBeans {
        @Bean AuthenticationManagerResolver<HttpServletRequest> jwtAuthenticationManagerResolver() {
            return request -> authentication -> authentication;
        }

        @Bean WhisperClient whisperClient() {
            return new WhisperClient("http://unused", 30) {
                @Override public boolean isReachable() { return true; }
                @Override public String transcribe(byte[] a, String f, String l) { return "ok"; }
            };
        }

        @Bean VoiceFeatureGate voiceFeatureGate() {
            return new VoiceFeatureGate("") {
                @Override public boolean isEnabled(String b, String t) { return true; }
            };
        }
    }

    private static MockMultipartFile clip() {
        return new MockMultipartFile("audio", "a.webm", "audio/webm", new byte[]{1});
    }

    @Test @WithAnonymousUser
    void anonymousStatusIsUnauthorized() throws Exception {
        mvc.perform(get("/api/voice/status")).andExpect(status().isUnauthorized());
    }

    @Test @WithAnonymousUser
    void anonymousTranscribeIsUnauthorized() throws Exception {
        mvc.perform(multipart("/api/voice/transcribe").file(clip())).andExpect(status().isUnauthorized());
    }

    @Test @WithMockUser(roles = "guest")
    void guestIsForbidden() throws Exception {
        mvc.perform(get("/api/voice/status")).andExpect(status().isForbidden());
    }

    @Test @WithMockUser(roles = "member")
    void memberMayReadStatus() throws Exception {
        mvc.perform(get("/api/voice/status")).andExpect(status().isOk());
    }

    @Test @WithMockUser(roles = "service_internal")
    void internalServiceMayReadStatus() throws Exception {
        mvc.perform(get("/api/voice/status")).andExpect(status().isOk());
    }

    @Test @WithMockUser(roles = "member")
    void memberMayTranscribe() throws Exception {
        mvc.perform(multipart("/api/voice/transcribe").file(clip())).andExpect(status().isOk());
    }
}
