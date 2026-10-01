package backend.academy.linktracker.scrapper.client;

import backend.academy.linktracker.scrapper.properties.ExternalApiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration(proxyBeanMethods = false)
public class ClientConfiguration {
    @Bean
    GitHubClient gitHubClient(RestClient.Builder builder, ExternalApiProperties properties) {
        var client = configure(builder, properties).baseUrl(properties.githubUrl())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("User-Agent", "simplified-link-tracker");
        if (properties.githubToken() != null && !properties.githubToken().isBlank()) {
            client.defaultHeader("Authorization", "Bearer " + properties.githubToken());
        }
        return createClient(client.build(), GitHubClient.class);
    }

    @Bean
    StackOverflowClient stackOverflowClient(RestClient.Builder builder, ExternalApiProperties properties) {
        return createClient(configure(builder, properties).baseUrl(properties.stackoverflowUrl()).build(),
                StackOverflowClient.class);
    }

    private RestClient.Builder configure(RestClient.Builder builder, ExternalApiProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        return builder.clone().requestFactory(factory);
    }

    private <T> T createClient(RestClient client, Class<T> type) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build().createClient(type);
    }
}
