package com.codescope.infra.config;

import com.codescope.client.llm.ClaudeLlmClient;
import com.codescope.client.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.profiles.active=test,monitoring")
@AutoConfigureMockMvc
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
class MonitoringProfileTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;

    @Test void exposesMetricsWithoutSwitchingRealLlmToStub() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("application=\"codescope\"")));
        assertThat(llm).isInstanceOf(ClaudeLlmClient.class);
    }

    @Test void doesNotOpenUserDataOrSensitiveActuatorEndpoints() throws Exception {
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
