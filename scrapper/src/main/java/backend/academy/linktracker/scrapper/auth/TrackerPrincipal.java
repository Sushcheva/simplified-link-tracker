package backend.academy.linktracker.scrapper.auth;

import java.io.Serial;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

public final class TrackerPrincipal extends User {
    @Serial private static final long serialVersionUID = 1L;
    private final long userId;

    public TrackerPrincipal(long userId, String email, String passwordHash) {
        super(email, passwordHash, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.userId = userId;
    }

    public long userId() { return userId; }
}
