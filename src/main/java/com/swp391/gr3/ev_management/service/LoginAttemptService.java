package com.swp391.gr3.ev_management.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service để theo dõi failed login attempts mà không cần thay đổi database.
 * Sử dụng in-memory cache (ConcurrentHashMap) để lưu trữ.
 * 
 * Lưu ý: Dữ liệu sẽ mất khi restart server.
 * Nếu cần persistent, hãy sử dụng Redis hoặc thêm columns vào database.
 */
@Slf4j
@Service
public class LoginAttemptService {

    // Lưu số lần failed attempts theo phoneNumber
    private final Map<String, Integer> failedAttemptsCache = new ConcurrentHashMap<>();
    
    // Lưu thời gian khóa tài khoản theo phoneNumber
    private final Map<String, LocalDateTime> accountLockedUntilCache = new ConcurrentHashMap<>();
    
    // Lưu số lần đã bị khóa theo phoneNumber (để tính thời gian khóa)
    private final Map<String, Integer> lockCountCache = new ConcurrentHashMap<>();
    
    // Lưu danh sách tài khoản bị khóa vĩnh viễn
    private final Map<String, Boolean> permanentlyLockedCache = new ConcurrentHashMap<>();

    /**
     * Ghi nhận đăng nhập thất bại
     */
    public void loginFailed(String phoneNumber) {
        int attempts = failedAttemptsCache.getOrDefault(phoneNumber, 0) + 1;
        failedAttemptsCache.put(phoneNumber, attempts);
        log.warn("Login failed for {}, attempts: {}", phoneNumber, attempts);
    }

    /**
     * Reset failed attempts khi đăng nhập thành công
     */
    public void loginSucceeded(String phoneNumber) {
        failedAttemptsCache.remove(phoneNumber);
        log.info("Login succeeded for {}, attempts reset", phoneNumber);
    }

    /**
     * Lấy số lần đã failed
     */
    public int getFailedAttempts(String phoneNumber) {
        return failedAttemptsCache.getOrDefault(phoneNumber, 0);
    }

    /**
     * Khóa tài khoản với thời gian tùy theo số lần đã bị khóa
     */
    public void lockAccount(String phoneNumber) {
        int lockCount = lockCountCache.getOrDefault(phoneNumber, 0) + 1;
        lockCountCache.put(phoneNumber, lockCount);

        LocalDateTime lockUntil;
        String message;

        if (lockCount == 1) {
            // Lần khóa thứ 1: 1 phút
            lockUntil = LocalDateTime.now().plusMinutes(1);
            message = "1 phút";
        } else if (lockCount == 2) {
            // Lần khóa thứ 2: 5 phút
            lockUntil = LocalDateTime.now().plusMinutes(5);
            message = "5 phút";
        } else if (lockCount == 3) {
            // Lần khóa thứ 3: 30 phút
            lockUntil = LocalDateTime.now().plusMinutes(30);
            message = "30 phút";
        } else {
            // Lần khóa thứ 4+: Vĩnh viễn
            permanentlyLockedCache.put(phoneNumber, true);
            accountLockedUntilCache.remove(phoneNumber);
            log.error("Account {} permanently locked after {} lock attempts", phoneNumber, lockCount);
            return;
        }

        accountLockedUntilCache.put(phoneNumber, lockUntil);
        log.warn("Account {} locked until {} (lock count: {}, duration: {})", 
                phoneNumber, lockUntil, lockCount, message);
    }

    /**
     * Kiểm tra tài khoản có bị khóa không
     */
    public boolean isAccountLocked(String phoneNumber) {
        // Kiểm tra khóa vĩnh viễn
        if (isPermanentlyLocked(phoneNumber)) {
            return true;
        }

        // Kiểm tra khóa tạm thời
        LocalDateTime lockedUntil = accountLockedUntilCache.get(phoneNumber);
        if (lockedUntil != null) {
            if (LocalDateTime.now().isBefore(lockedUntil)) {
                return true; // Vẫn còn trong thời gian khóa
            } else {
                // Hết thời gian khóa -> reset
                accountLockedUntilCache.remove(phoneNumber);
                failedAttemptsCache.remove(phoneNumber);
                log.info("Account {} unlocked after timeout", phoneNumber);
                return false;
            }
        }

        return false;
    }

    /**
     * Lấy thời gian còn lại bị khóa (phút)
     */
    public long getMinutesUntilUnlock(String phoneNumber) {
        LocalDateTime lockedUntil = accountLockedUntilCache.get(phoneNumber);
        if (lockedUntil == null) {
            return 0;
        }
        
        long minutes = java.time.Duration.between(LocalDateTime.now(), lockedUntil).toMinutes();
        return Math.max(0, minutes + 1); // +1 để làm tròn lên
    }

    /**
     * Kiểm tra tài khoản có bị khóa vĩnh viễn không
     */
    public boolean isPermanentlyLocked(String phoneNumber) {
        return permanentlyLockedCache.getOrDefault(phoneNumber, false);
    }

    /**
     * Mở khóa tài khoản (dành cho Admin)
     */
    public void unlockAccount(String phoneNumber) {
        failedAttemptsCache.remove(phoneNumber);
        accountLockedUntilCache.remove(phoneNumber);
        lockCountCache.remove(phoneNumber);
        permanentlyLockedCache.remove(phoneNumber);
        log.info("Account {} manually unlocked by admin", phoneNumber);
    }

    /**
     * Lấy số lần đã bị khóa
     */
    public int getLockCount(String phoneNumber) {
        return lockCountCache.getOrDefault(phoneNumber, 0);
    }
}
