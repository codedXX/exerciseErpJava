package com.example.demo.repository;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 用内存模拟 ERP 的商品主数据；重启即恢复样例数据。 */
@Component
public class ProductStore {
    public record Product(long id, String name, int priceCents) {}
    private final ConcurrentHashMap<Long, Product> products = new ConcurrentHashMap<>();
    private final AtomicInteger reads = new AtomicInteger();

    public ProductStore() {
        products.put(10200L, new Product(10200, "美容营养商品", 19900));
    }

    public Product find(long id) {
        reads.incrementAndGet();
        return products.get(id);
    }

    public void save(Product product) {
        products.put(product.id(), product);
    }

    public int readCount() { return reads.get(); }
}
