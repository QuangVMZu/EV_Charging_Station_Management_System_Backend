package com.swp391.gr3.ev_management.config;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import static org.springframework.security.config.Customizer.withDefaults;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.swp391.gr3.ev_management.entity.User;
import com.swp391.gr3.ev_management.repository.UserRepository;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import jakarta.servlet.http.HttpServletResponse;

@Configuration // Đánh dấu đây là class cấu hình Spring chung (bean, security, ...)
@EnableWebSecurity // Bật Spring Security cho toàn bộ ứng dụng
@EnableMethodSecurity(securedEnabled = true) // Cho phép dùng @Secured, @PreAuthorize... trên method
public class SecurityConfig {

    /**
     * UserDetailsService:
     * - Spring Security dùng để load thông tin user khi authenticate (login bằng username/password).
     * - Ở đây username chính là phoneNumber trong hệ thống.
     */
    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepository) {
        return username -> {
            // Tìm user trong DB theo phoneNumber
            User u = userRepository.findUsersByPhoneNumber(username);
            if (u == null) throw new UsernameNotFoundException("User not found: " + username);

            // Lấy tên role từ entity User (ví dụ: ADMIN / STAFF / DRIVER)
            String roleName = (u.getRole() != null && u.getRole().getRoleName() != null)
                    ? u.getRole().getRoleName()
                    : "USER";
            // Spring Security yêu cầu dạng ROLE_XYZ
            if (!roleName.startsWith("ROLE_")) roleName = "ROLE_" + roleName;

            // Build đối tượng UserDetails mà Security cần (username + password + authorities)
            return org.springframework.security.core.userdetails.User
                    .withUsername(u.getPhoneNumber())    // username = phoneNumber
                    .password(u.getPasswordHash())       // mật khẩu đã mã hoá
                    .authorities(roleName)               // quyền/role
                    .accountExpired(false)
                    .accountLocked(false)
                    .credentialsExpired(false)
                    .disabled(false)
                    .build();
        };
    }

    /**
     * OidcUserService mặc định cho OAuth2 (Google).
     * - Khi dùng `oauth2Login`, Spring sẽ dùng service này để load profile OIDC từ Google.
     * - Ở đây cho dùng default behavior (CustomOidcUserService đã nằm ở chỗ khác nếu cần).
     */
    @Bean
    public OidcUserService oidcUserService() {
        return new OidcUserService(); // dùng default là đủ
    }

    /**
     * PasswordEncoder:
     * - BCrypt dùng để mã hoá password trước khi lưu DB và để so sánh khi login.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * AuthenticationProvider:
     * - Kết nối UserDetailsService + PasswordEncoder vào Spring Security.
     * - DaoAuthenticationProvider biết cách kiểm tra username/password dựa trên DB.
     */
    @Bean
    public AuthenticationProvider authenticationProvider(UserDetailsService uds, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(uds);  // dùng UserDetailsService custom ở trên
        provider.setPasswordEncoder(encoder); // cấu hình encoder để verify password
        return provider;
    }

    /**
     * AuthenticationManager:
     * - Cho phép ta inject AuthenticationManager để dùng trong service (vd: login thủ công).
     * - Lấy từ AuthenticationConfiguration do Spring build sẵn.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    // ====== CORS cho Frontend dev (5173/3000) ======
    /**
     * Cấu hình CORS:
     * - Cho phép các origin FE (localhost:5173, :3000, v.v...) gọi API.
     * - Cho phép gửi cookie/Authorization, các method HTTP cơ bản, và mọi header.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Các origin FE được phép — cần thêm origin khác thì thêm vào list này
        config.setAllowedOrigins(List.of(
                "http://localhost:5173",
                "http://127.0.0.1:5173",
                "http://localhost:5174",    // 👈 thêm dòng này
                "http://127.0.0.1:5174",    // 👈 thêm dòng này
                "http://evcharge-files-storage.s3-website-ap-southeast-1.amazonaws.com",
                "https://evm-flax.vercel.app",
                "https://evcsystem.online",
                "http://evcsystem.online",
                "https://d77t38tltzbkw.cloudfront.net",
                "http://localhost:3000",
                "http://127.0.0.1:3000"
        ));
        // Cho phép gửi cookie/Authorization trong request cross-origin
        config.setAllowCredentials(true);
        // Các HTTP method được phép sử dụng
        config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        // Các header được phép gửi lên server (cho phép toàn bộ)
        config.setAllowedHeaders(List.of("*"));
        // Các header server cho phép expose về FE (nếu cần đọc từ JS)
        config.setExposedHeaders(List.of("Location"));

        // Áp cấu hình CORS này cho toàn bộ endpoint (/**)
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * SecurityFilterChain:
     * - Cấu hình toàn bộ luật bảo mật HTTP, filter, CORS, OAuth2, JWT...
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AuthenticationProvider provider,
                                                   JwtAuthFilter jwtAuthFilter,
                                                   OAuth2SuccessHandler oAuth2SuccessHandler,
                                                   OidcUserService oidcUserService,
                                                   AccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                // Tắt CSRF (vì API dùng JWT, không dùng form truyền thống)
                .csrf(AbstractHttpConfigurer::disable)
                // Bật CORS với cấu hình ở trên
                .cors(withDefaults())
                // Đăng ký AuthenticationProvider custom (dùng DB)
                .authenticationProvider(provider)
                // Tắt httpBasic (không dùng Basic Auth)
                .httpBasic(AbstractHttpConfigurer::disable)
                // Tắt formLogin mặc định (dùng REST login)
                .formLogin(AbstractHttpConfigurer::disable)

                // ⚠️ OAuth2 code flow cần session tạm thời để lưu state khi redirect Google.
                // Vì vậy, dùng IF_REQUIRED (tạo session khi cần), KHÔNG đặt STATELESS toàn cục.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

                // Cấu hình quyền truy cập theo URL
                .authorizeHttpRequests(auth -> auth
                                // Các endpoint public, không cần login
                                .requestMatchers(
                                        "/", "/index.html", "/static/**", "/public/**", "/error",
                                        "/swagger-ui.html", "/swagger-ui/**",
                                        "/v3/api-docs/**", "/v3/api-docs.yaml",
                                        "/api/users/login", "/api/users/logout", "/api/users/register/**",

                                        // 👇 Cho phép các endpoint OAuth2 (Google login)
                                        "/oauth2/**", "/login/oauth2/**", "/oauth2/authorization/**",
                                        // 👇 Cho phép public VNPay callback/return
                                        "/api/payment/vnpay/**", "/api/users/forgot-password", "/api/users/reset-password",
                                        
                                        // 👇 Cho phép xem danh sách trạm sạc và connector types (không cần đăng nhập)
                                        "/api/charging-stations", "/api/charging-stations/**",
                                        "/api/connector-types", "/api/connector-types/**",
                                        "/api/charging-points/station/**",
                                        
                                        // 👇 Cho phép xem điều khoản/chính sách (không cần đăng nhập)
                                        "/api/policies", "/api/policies/**"
                                ).permitAll()
                                // Actuator (health, metrics) public
                                .requestMatchers("/actuator/**").permitAll()
                                // Cho phép OPTIONS (preflight) cho mọi path
                                .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
//                        .requestMatchers("/api/admin/**").hasRole("ADMIN") // ví dụ rule cho admin
                                // Các request còn lại bắt buộc phải authenticated
                                .anyRequest().authenticated()
                )

                // Cấu hình xử lý exception (401/403)
                .exceptionHandling(ex -> ex
                        // Khi chưa login mà gọi endpoint cần auth -> trả 401 JSON
                        .authenticationEntryPoint((req, res, e) -> {
                            res.setStatus(401);
                            res.setContentType("application/json;charset=UTF-8"); // 👈 UTF-8 cho tiếng Việt
                            res.getWriter().write("{\"message\":\"Unauthorized\"}");
                        })
                        // Khi đã login nhưng thiếu quyền -> dùng AccessDeniedHandler riêng bên dưới
                        .accessDeniedHandler(accessDeniedHandler)
                )
                // Tắt logout mặc định (nếu muốn custom logout qua REST riêng)
                .logout(AbstractHttpConfigurer::disable)

                // 👇 Bật oauth2Login, gắn successHandler để phát JWT & redirect về FE
                .oauth2Login(oauth -> oauth
                        // Dùng OidcUserService để lấy thông tin user từ Google
                        .userInfoEndpoint(u -> u.oidcUserService(oidcUserService))
                        // Khi OAuth2 login thành công -> dùng handler để xử lý (tạo user, phát token,...)
                        .successHandler(oAuth2SuccessHandler)
                )

                // Thêm JwtAuthFilter trước UsernamePasswordAuthenticationFilter
                // -> Mọi request kèm Bearer token sẽ được kiểm tra JWT trước
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        // Build SecurityFilterChain hoàn chỉnh
        return http.build();
    }

    /**
     * Cấu hình OpenAPI/Swagger:
     * - Thêm security scheme "bearerAuth" dạng HTTP Bearer JWT.
     * - Để Swagger UI hiển thị ô nhập token và gửi Authorization: Bearer ... trong request.
     */
    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }

    /**
     * AccessDeniedHandler custom:
     * - Khi user đã login nhưng không đủ quyền (403) -> trả JSON tiếng Việt cho FE.
     */
    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN); // 403
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(
                    "{\"message\":\"Bạn không có quyền thực hiện hành động này\"}"
                            .getBytes(StandardCharsets.UTF_8)
            );
        };
    }
}
