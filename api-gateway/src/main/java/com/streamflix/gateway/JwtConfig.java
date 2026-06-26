package com.streamflix.gateway;

import com.streamflix.common.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {

    @Bean
    public JwtService jwtService(@Value("${jwt.secret}") String secret,
                                 @Value("${jwt.expiration-ms}") long expirationMs) {
        return new JwtService(secret, expirationMs);
    }
}
