package com.example.demo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 演示用内部刷新接口的共享密钥。 */
@Component
public class RefreshAuthorization {
    private final byte[] secret;

    public RefreshAuthorization(@Value("${demo.product-refresh.token:}") String token) {
        this.secret = token.getBytes(StandardCharsets.UTF_8);
    }

    public void require(String authorization) {
        if (secret.length == 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "尚未配置刷新令牌");
        }
        if (authorization == null || !authorization.startsWith("Bearer ") ||
                !MessageDigest.isEqual(secret,
                        authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "刷新接口身份验证失败");
        }
    }
}
