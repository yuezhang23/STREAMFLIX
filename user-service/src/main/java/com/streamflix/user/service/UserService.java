package com.streamflix.user.service;

import com.streamflix.common.security.JwtService;
import com.streamflix.user.domain.User;
import com.streamflix.user.dto.AuthDtos.*;
import com.streamflix.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.*;

@Service
public class UserService {

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public UserService(UserRepository repository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest req) {
        if (repository.existsByEmail(req.email())) {
            throw new ResponseStatusException(CONFLICT, "Email already registered");
        }
        User user = repository.save(new User(
                req.email(),
                passwordEncoder.encode(req.password()),
                req.displayName()));
        return toAuthResponse(user);
    }

    public AuthResponse login(LoginRequest req) {
        User user = repository.findByEmail(req.email())
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Invalid credentials"));
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(UNAUTHORIZED, "Invalid credentials");
        }
        return toAuthResponse(user);
    }

    public UserResponse getById(Long id) {
        User user = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName());
    }

    private AuthResponse toAuthResponse(User user) {
        String token = jwtService.generateToken(user.getId(), user.getEmail());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getDisplayName());
    }
}
