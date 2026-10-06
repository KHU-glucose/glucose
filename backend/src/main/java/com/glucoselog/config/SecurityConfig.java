package com.glucoselog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.glucoselog.auth.JwtAuthenticationFilter;
import com.glucoselog.common.JsonAuthenticationEntryPoint;
import com.glucoselog.common.RequestIdFilter;

/**
 * health와 /v1/auth/**만 열고 나머지는 JWT 인증이 필요하다.
 * 필터 순서: RequestIdFilter → JwtAuthenticationFilter → (Spring Security 기본 체인)
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            RequestIdFilter requestIdFilter,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)           // 쿠키 세션이 아닌 토큰 방식이라 불필요
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/v1/auth/**").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(e -> e.authenticationEntryPoint(jsonAuthenticationEntryPoint))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(requestIdFilter, JwtAuthenticationFilter.class);
        return http.build();
    }
}
