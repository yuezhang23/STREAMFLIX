package com.streamflix.user.config;

import com.streamflix.user.domain.User;
import com.streamflix.user.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds demo login accounts on first boot (password = "password" for all), so the demo
 * works immediately. Behavioral seed data (watch/rating events) references a wider set of
 * synthetic user ids and lives in video-service — there are no cross-service FKs, matching
 * the schema-per-service ownership model.
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private final UserRepository repository;
    private final PasswordEncoder encoder;

    public DataInitializer(UserRepository repository, PasswordEncoder encoder) {
        this.repository = repository;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        if (repository.count() > 0) {
            return;
        }
        String hash = encoder.encode("password");
        List.of(
                new User("alice@streamflix.dev", hash, "Alice"),
                new User("bob@streamflix.dev", hash, "Bob"),
                new User("carol@streamflix.dev", hash, "Carol"),
                new User("dave@streamflix.dev", hash, "Dave"),
                new User("erin@streamflix.dev", hash, "Erin")
        ).forEach(repository::save);
    }
}
