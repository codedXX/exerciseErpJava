package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "demo.redis.read-write-split.enabled=false")
@AutoConfigureMockMvc
class Knife4jDocsTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void mallEndpointsAndDemoRefreshHeaderAppearInOpenApi() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode api = json.readTree(body);
        assertEquals("3.1 商品详情缓存", api.at("/paths/~1api~1demo~1products~1{id}/get/tags/0").asText());
        assertEquals("3.2 下单限流", api.at("/paths/~1api~1demo~1orders~1submit/post/tags/0").asText());
        assertEquals("3.3 商品强制刷新", api.at("/paths/~1api~1demo~1products~1{id}~1refresh/post/tags/0").asText());
        assertTrue(api.at("/paths/~1api~1demo~1orders~1submit/post/summary").asText().contains("限流"));
        JsonNode parameters = api.at("/paths/~1api~1demo~1products~1{id}~1refresh/post/parameters");
        JsonNode header = null;
        for (JsonNode parameter : parameters) {
            if ("X-Demo-Refresh".equals(parameter.path("name").asText())) header = parameter;
            assertNotEquals("expectedVersion", parameter.path("name").asText());
        }
        assertNotNull(header);
        assertEquals("header", header.path("in").asText());
    }

    @Test
    void knife4jPageIsAvailable() throws Exception {
        mvc.perform(get("/doc.html")).andExpect(status().isOk());
    }
}
