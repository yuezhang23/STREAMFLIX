package com.streamflix.user.web;

import com.streamflix.user.dto.AuthDtos.*;
import com.streamflix.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        return userService.register(req);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return userService.login(req);
    }

    /**
     * Current user. The API gateway validates the JWT and injects the identity as the
     * {@code X-User-Id} header; this service trusts it on the internal network.
     */
    @GetMapping("/me")
    public UserResponse me(@RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing identity");
        }
        return userService.getById(userId);
    }
}
