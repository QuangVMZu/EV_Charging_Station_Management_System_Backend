package com.swp391.gr3.ev_management.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenBlacklistService {

    // key = token, value = expiryTimeMillis
    private final Map<String, Long> blacklist = new ConcurrentHashMap<>();

    public void blacklist(String token, long ttlSeconds) {
        long expiryTimeMillis = System.currentTimeMillis() + ttlSeconds * 1000;
        blacklist.put(token, expiryTimeMillis);
    }

    // NEW: blacklist theo thời điểm hết hạn của JWT (epoch millis)
    public void blacklistUntil(String token, long expiryTimeMillis) {
        // nếu token đã hết hạn rồi thì không cần lưu
        if (expiryTimeMillis <= System.currentTimeMillis()) return;
        blacklist.put(token, expiryTimeMillis);
    }

    public boolean isBlacklisted(String token) {
        Long expiryTime = blacklist.get(token);
        if (expiryTime == null) return false;

        if (expiryTime < System.currentTimeMillis()) {
            blacklist.remove(token);
            return false;
        }
        return true;
    }
}
