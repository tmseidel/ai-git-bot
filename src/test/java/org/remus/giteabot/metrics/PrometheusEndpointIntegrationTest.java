package org.remus.giteabot.metrics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "management.endpoint.prometheus.enabled=true")
class PrometheusEndpointIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void prometheusEndpoint_returnsPrometheusTextFormat() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("giteabot_reviews")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("giteabot_findings")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("giteabot_ai_errors")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("giteabot_audit_tool_calls")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("giteabot_ai_usage_input_tokens")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("giteabot_ai_usage_output_tokens")));
    }
}
