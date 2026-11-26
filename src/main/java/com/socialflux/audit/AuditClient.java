package com.socialflux.audit;

import com.socialflux.events.logging.CorrelationIdClientInterceptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Cliente HTTP do audit-service ({@code POST /v1/events}). Chave da aplicação no {@code Authorization: Bearer}, timeouts
 * curtos e o {@code X-Correlation-Id} do escopo do relay repassado (o audit-service está na rede interna, não é terceiro).
 */
public class AuditClient {

    /** Status HTTP e corpo; a chave nunca aparece aqui nem em log. */
    public record Response(int status, String body) {
        public boolean ok() {
            return status / 100 == 2;
        }
    }

    private final RestClient client;
    private final String authorization;

    public AuditClient(SocialFluxAuditProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder().baseUrl(properties.url()).requestFactory(factory)
                .requestInterceptor(new CorrelationIdClientInterceptor())
                .build();
        this.authorization = "Bearer " + properties.apiKey();
    }

    /** Envia um lote (corpo {@code {"events":[...]}}). Erro de rede sobe como exceção; status ≠ 2xx volta na resposta. */
    public Response send(String json) {
        return client.post().uri("/v1/events")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange((request, response) -> new Response(response.getStatusCode().value(),
                        response.bodyTo(String.class)));
    }
}
