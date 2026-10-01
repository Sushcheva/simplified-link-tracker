package backend.academy.linktracker.scrapper.service;

import backend.academy.linktracker.scrapper.exception.ApiException;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LinkUrlNormalizer {
    private static final Pattern GITHUB_PATH = Pattern.compile("^/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/?$");
    private static final Pattern SO_PATH = Pattern.compile("^/questions/([1-9][0-9]{0,17})(?:/[^/]*)?/?$");

    public URI normalize(String input) {
        if (input == null) throw invalidUrl();
        try {
            URI uri = URI.create(input.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getPort() != -1) {
                throw invalidUrl();
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath();
            if ("github.com".equals(host) && GITHUB_PATH.matcher(path).matches()) {
                path = path.replaceFirst("/$", "").replaceFirst("\\.git$", "");
                String[] parts = path.substring(1).split("/");
                if (parts.length != 2 || parts[0].equals(".") || parts[0].equals("..")
                        || parts[1].equals(".") || parts[1].equals("..")) {
                    throw invalidUrl();
                }
                return URI.create("https://github.com" + path.toLowerCase(Locale.ROOT));
            }
            var question = SO_PATH.matcher(path);
            if ("stackoverflow.com".equals(host) && question.matches()) {
                return URI.create("https://stackoverflow.com/questions/" + question.group(1));
            }
        } catch (IllegalArgumentException exception) {
            throw invalidUrl();
        }
        throw invalidUrl();
    }

    private ApiException invalidUrl() {
        return new ApiException(HttpStatus.BAD_REQUEST,
                "Нужна HTTPS-ссылка на репозиторий GitHub или вопрос Stack Overflow.");
    }
}
