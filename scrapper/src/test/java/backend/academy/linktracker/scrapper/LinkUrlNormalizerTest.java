package backend.academy.linktracker.scrapper;

import backend.academy.linktracker.scrapper.exception.ApiException;
import backend.academy.linktracker.scrapper.service.LinkUrlNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class LinkUrlNormalizerTest {
    private final LinkUrlNormalizer normalizer = new LinkUrlNormalizer();

    @Test
    void normalizesEquivalentGithubUrls() {
        assertThat(normalizer.normalize(" https://GitHub.com/Spring-Projects/Spring-Boot.git/?tab=readme#top ").toString())
                .isEqualTo("https://github.com/spring-projects/spring-boot");
    }

    @Test
    void removesQuestionSlugAndQuery() {
        assertThat(normalizer.normalize("https://stackoverflow.com/questions/12345/a-question?x=1#answer").toString())
                .isEqualTo("https://stackoverflow.com/questions/12345");
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "http://github.com/a/b", "https://github.com.evil.test/a/b",
            "https://github.com@evil.test/a/b", "https://evil.test/github.com/a/b", "https://github.com/a",
            "https://github.com/a/b/issues/1", "https://github.com:444/a/b", "https://github.com/../b",
            "https://github.com/a/%2e%2e", "https://stackoverflow.com/questions/0", "not a url"})
    void rejectsUnsupportedOrMisleadingUrls(String url) {
        assertThatThrownBy(() -> normalizer.normalize(url)).isInstanceOf(ApiException.class);
    }
}
