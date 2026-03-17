package com.swp391.gr3.ev_management.service;

import java.time.Instant;
import java.util.Date;
import java.util.function.Function;

import javax.crypto.SecretKey;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.swp391.gr3.ev_management.entity.User;
import com.swp391.gr3.ev_management.repository.UserRepository;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenService {

    private final UserRepository userRepository;
    private final TokenBlacklistService tokenBlacklistService; // NEW
    private static final long JWT_TTL_SECONDS = 3600;

    @Value("${app.jwtSecret}")
    private String jwtSecret;

    private SecretKey getSignInKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(User user) {
        String roleName = "DRIVER";
        try {
            if (user.getRole() != null && user.getRole().getRoleName() != null) {
                roleName = user.getRole().getRoleName();
            } else {
                User fresh = userRepository.findUserByUserId(user.getUserId());
                if (fresh != null && fresh.getRole() != null && fresh.getRole().getRoleName() != null) {
                    roleName = fresh.getRole().getRoleName();
                }
            }
        } catch (Exception e) {
            roleName = "DRIVER";
        }

        return Jwts.builder()
                .setSubject(String.valueOf(user.getUserId()))
                .claim("fullName", user.getName())
                .claim("role", roleName)
                .setIssuedAt(new Date())
                .setExpiration(Date.from(Instant.now().plusSeconds(JWT_TTL_SECONDS)))
                .signWith(getSignInKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public User extractToken(String token) {
        String raw = stripBearer(token);
        String value = extractClaim(raw, Claims::getSubject);
        long userId = Long.parseLong(value);
        return userRepository.findUserByUserId(userId);
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSignInKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public <T> T extractClaim(String token, Function<Claims, T> resolver) {
        Claims claims = extractAllClaims(token);
        return resolver.apply(claims);
    }

    public Instant getExpirationFromJwt(String token) {
        Claims claims = extractAllClaims(token);
        Date exp = claims.getExpiration();
        return (exp == null) ? null : exp.toInstant();
    }

    /**
     * NEW: blacklist token (logout/revoke)
     * - Dựa vào exp trong JWT để lưu TTL chính xác (đỡ truyền ttlSeconds).
     */
    public void blacklistToken(String token) {
        String raw = stripBearer(token);
        if (raw == null || raw.isBlank()) return;

        try {
            Instant exp = getExpirationFromJwt(raw);
            if (exp == null) return;

            tokenBlacklistService.blacklistUntil(raw, exp.toEpochMilli());
        } catch (JwtException | IllegalArgumentException ex) {
            // token lỗi thì khỏi blacklist cũng được (vì validate đã fail)
        }
    }

    /**
     * Validate token:
     * - parse/verify chữ ký + expiry
     * - check blacklist: nếu token đã bị blacklist => false
     */
    public boolean validateToken(String token) {
        try {
            Claims c = extractAllClaims(stripBearer(token));
            log.info("JWT exp = {}, now = {}",
                    c.getExpiration(),
                    new Date()
            );
            return true;
        } catch (Exception e) {
            log.error("JWT invalid: {}", e.getMessage());
            return false;
        }
    }

    private String stripBearer(String token) {
        if (token == null) return null;
        token = token.trim();
        if (token.toLowerCase().startsWith("bearer ")) {
            return token.substring(7).trim();
        }
        return token;
    }

    public Long extractUserIdFromRequest(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new JwtException("Invalid Authorization Header");
        }

        String token = stripBearer(header);

        // NEW: chặn luôn nếu bị blacklist
        if (tokenBlacklistService.isBlacklisted(token)) {
            throw new JwtException("Token is blacklisted");
        }

        Claims claims = extractAllClaims(token);
        return Long.parseLong(claims.getSubject());
    }
}
