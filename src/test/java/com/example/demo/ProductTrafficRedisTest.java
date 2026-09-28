package com.example.demo;

import com.example.demo.repository.ProductStore;
import com.example.demo.service.ProductCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "demo.redis.read-write-split.enabled=false",
        "demo.product-refresh.token=test-refresh-secret",
        "demo.product-limits.read-product-per-second=2"})
@AutoConfigureMockMvc
class ProductTrafficRedisTest {
    @Autowired MockMvc mvc;
    @Autowired ProductStore store;
    @Autowired ProductCacheService cache;
    @Autowired StringRedisTemplate redis;

    @Test
    void bothReadRoutesUseTheSameProductCache() throws Exception {
        long id = ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999);
        store.save(new ProductStore.Product(id, "shared", 100));
        try {
            mvc.perform(get("/api/demo/products/{id}", id)).andExpect(status().isOk());
            mvc.perform(get("/api/demo/channel/products/{id}", id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.product.name").value("shared"));
        } finally {
            redis.delete(ProductCacheService.cacheKey(id));
            redis.delete("mall:product:rate:read:" + id + ":v1");
        }
    }

    @Test
    void refreshRequiresSecretAndRejectsRepeatedDeletion() throws Exception {
        long id = ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999);
        store.save(new ProductStore.Product(id, "before", 100));
        try {
            mvc.perform(get("/api/demo/products/{id}", id)).andExpect(status().isOk());
            store.save(new ProductStore.Product(id, "after", 200));
            mvc.perform(post("/api/demo/channel/products/{id}/refresh", id)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/demo/channel/products/{id}", id))
                    .andExpect(jsonPath("$.product.name").value("before"));
            mvc.perform(post("/api/demo/channel/products/{id}/refresh", id)
                            .header("Authorization", "Bearer test-refresh-secret"))
                    .andExpect(status().isOk());
            cache.get(id);
            mvc.perform(post("/api/demo/channel/products/{id}/refresh", id)
                            .header("Authorization", "Bearer test-refresh-secret"))
                    .andExpect(status().isTooManyRequests());
            assertTrue(redis.opsForValue().get(ProductCacheService.cacheKey(id)).contains("after"));
        } finally {
            redis.delete(ProductCacheService.cacheKey(id));
            redis.delete("mall:product:rate:read:" + id + ":v1");
            redis.delete("mall:product:rate:refresh:" + id + ":v1");
        }
    }
}
