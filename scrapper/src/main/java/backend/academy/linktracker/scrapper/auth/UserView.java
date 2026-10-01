package backend.academy.linktracker.scrapper.auth;

public record UserView(long id, String email) {
    public static UserView from(TrackerPrincipal principal) {
        return new UserView(principal.userId(), principal.getUsername());
    }
}
