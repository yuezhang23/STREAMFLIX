package com.streamflix.user.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds a large synthetic user base for the OLTP streaming load simulator so the interaction /
 * recommendation tables are realistically sized and writes spread across many user ids. Disabled by
 * default ({@code APP_SEED_USERS=0}) to preserve normal demo behavior; set it (e.g. 50000) to create
 * {@code loadtest+{n}@streamflix.dev} accounts that k6 logs in as.
 *
 * <p>All accounts share a single BCrypt hash of "password" (computed once — BCrypt is deliberately
 * slow, so hashing per-row would dominate seed time). Inserts are idempotent via ON CONFLICT and
 * chunked into JDBC batches. Runs after {@link DataInitializer} (Order LOWEST) so demo users exist
 * first; ordering is not strictly required since emails never collide.</p>
 */
@Component
@Order
public class BulkUserSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BulkUserSeeder.class);
    private static final int BATCH_SIZE = 1000;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final int seedUsers;

    public BulkUserSeeder(JdbcTemplate jdbc, PasswordEncoder encoder,
                          @Value("${app.seed.users:0}") int seedUsers) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.seedUsers = seedUsers;
    }

    @Override
    public void run(String... args) {
        if (seedUsers <= 0) {
            return;
        }
        String hash = encoder.encode("password");
        int inserted = 0;
        for (int start = 1; start <= seedUsers; start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE - 1, seedUsers);
            final int from = start;
            final int size = end - start + 1;
            int[] rows = jdbc.batchUpdate("""
                    INSERT INTO users (email, password_hash, display_name)
                    VALUES (?, ?, ?)
                    ON CONFLICT (email) DO NOTHING
                    """,
                    new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                            int n = from + i;
                            ps.setString(1, "loadtest+" + n + "@streamflix.dev");
                            ps.setString(2, hash);
                            ps.setString(3, "Load User " + n);
                        }

                        @Override
                        public int getBatchSize() {
                            return size;
                        }
                    });
            for (int r : rows) {
                if (r > 0) inserted++;
            }
        }
        log.info("BulkUserSeeder ensured {} synthetic load-test users ({} newly inserted)", seedUsers, inserted);
    }
}
