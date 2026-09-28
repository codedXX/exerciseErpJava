package com.example.demo.controller;

import com.example.demo.service.OrderRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Tag(name = "3.2 下单限流", description = "Redisson 限制同一用户提交订单的次数")
@RestController
@RequestMapping("/api/demo")
public class OrderSubmitController {
    private final OrderRateLimiter limiter;
    private final AtomicLong orderSequence = new AtomicLong(9000);

    public OrderSubmitController(OrderRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Operation(summary = "提交订单（Redisson 限流）", description = "按用户 ID 和 submit 接口限流，10 秒最多提交 2 次；Redis 中的额度由多个应用实例共享。X-Demo-User-Id 仅供演示，不是已认证身份。")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "提交成功，返回模拟订单 ID"),
            @ApiResponse(responseCode = "429", description = "超过限流额度，响应头含 Retry-After")})
    @PostMapping("/orders/submit")
    public ResponseEntity<?> submit(@Parameter(description = "仅用于演示的用户 ID；生产环境应取登录身份", example = "42")
                                    @RequestHeader("X-Demo-User-Id") String userId) {
        if (!userId.matches("[0-9]{1,18}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户 ID 无效");

        OrderRateLimiter.Decision decision = limiter.acquire(userId, "submit");
        if (!decision.allowed()) {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Retry-After", String.valueOf((decision.retryAfterMs() + 999) / 1000));
            return new ResponseEntity<>(
                    Map.of("error", "RATE_LIMITED", "message", "下单请求过于频繁，请稍后重试", "retryAfterMs", decision.retryAfterMs()),
                    headers, HttpStatus.TOO_MANY_REQUESTS);
        }

        return ResponseEntity.ok(Map.of(
                "orderId", orderSequence.incrementAndGet(),
                "userId", userId,
                "remainingRequests", decision.remaining()));
    }
}
