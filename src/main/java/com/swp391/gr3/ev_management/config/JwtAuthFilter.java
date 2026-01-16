package com.swp391.gr3.ev_management.config;

import com.swp391.gr3.ev_management.entity.User;
import com.swp391.gr3.ev_management.service.TokenBlacklistService;
import com.swp391.gr3.ev_management.service.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final TokenService tokenService;
    private final TokenBlacklistService tokenBlacklistService; // ✅ NEW
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public JwtAuthFilter(TokenService tokenService, TokenBlacklistService tokenBlacklistService) {
        this.tokenService = tokenService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    private static final String[] PUBLIC_PATHS = new String[]{
            "/", "/index.html", "/error",
            "/static/**", "/public/**",
            "/swagger-ui.html", "/swagger-ui/**",
            "/v3/api-docs/**", "/v3/api-docs.yaml",
            "/api/users/login", "/api/users/register", "/api/users/logout",
            "/actuator/**"
    };

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

        final String uri = request.getRequestURI();

        if (uri.startsWith("/api/payment/vnpay/")) return true;

        for (String pattern : PUBLIC_PATHS) {
            if (pathMatcher.match(pattern, uri)) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest req,
                                    @NonNull HttpServletResponse res,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(req, res);
            return;
        }

        final String auth = req.getHeader("Authorization");

        if (auth == null || !auth.startsWith("Bearer ")) {
            chain.doFilter(req, res);
            return;
        }

        final String token = auth.substring(7).trim();

        // ✅ NEW: chặn token đã logout (blacklist)
        if (tokenBlacklistService.isBlacklisted(token)) {
            chain.doFilter(req, res);
            return;
        }

        boolean valid = tokenService.validateToken(token);
        if (!valid) {
            chain.doFilter(req, res);
            return;
        }

        User u = tokenService.extractToken(token);
        if (u == null) {
            chain.doFilter(req, res);
            return;
        }

        String roleName = tokenService.extractClaim(token, c -> c.get("role", String.class));
        if (roleName == null || roleName.isBlank()) roleName = "DRIVER";
        if (!roleName.startsWith("ROLE_")) roleName = "ROLE_" + roleName;

        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(roleName));

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(String.valueOf(u.getUserId()), null, authorities);

        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        chain.doFilter(req, res);
    }
}
