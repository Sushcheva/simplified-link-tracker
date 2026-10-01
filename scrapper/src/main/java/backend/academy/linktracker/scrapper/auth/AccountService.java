package backend.academy.linktracker.scrapper.auth;

import backend.academy.linktracker.scrapper.exception.ApiException;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AccountService implements UserDetailsService {
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;

    public AccountService(JdbcClient jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    public UserView register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        String hash = encoder.encode(request.password());
        try {
            return jdbc.sql("INSERT INTO users (email, password_hash) VALUES (:email, :hash) RETURNING id, email")
                    .param("email", email).param("hash", hash)
                    .query((rs, row) -> new UserView(rs.getLong("id"), rs.getString("email"))).single();
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "Этот email уже зарегистрирован.");
        }
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return jdbc.sql("SELECT id, email, password_hash FROM users WHERE email = :email")
                .param("email", normalizeEmail(username))
                .query((rs, row) -> new TrackerPrincipal(rs.getLong("id"),
                        rs.getString("email"), rs.getString("password_hash")))
                .optional().orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
