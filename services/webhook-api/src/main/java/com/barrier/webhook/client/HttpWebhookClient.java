package com.barrier.webhook.client;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Envio via {@link RestClient}; qualquer erro/HTTP não-2xx vira uma falha (para retry).
 *
 * <p>Os timeouts não são detalhe de afinação: este cliente chama um endpoint <b>de terceiro</b> na
 * thread do listener do Kafka. Com {@code RestClient.create()} — sem timeout algum, que era o
 * estado anterior — um cliente que aceita a conexão e não responde parava o consumo da partição
 * inteira, e com ela a entrega de todos os outros tenants.
 */
@Component
public class HttpWebhookClient implements WebhookClient {

  static final String SIGNATURE_HEADER = "X-Barrier-Signature";

  /** Só durante a janela de rotação do segredo; ver {@code WebhookEndpoint.rotateSecret}. */
  static final String PREVIOUS_SIGNATURE_HEADER = "X-Barrier-Signature-Previous";

  static final String EVENT_ID_HEADER = "X-Barrier-Event-Id";

  private final RestClient restClient;

  public HttpWebhookClient(
      @Value("${barrier.webhook.connect-timeout:PT2S}") Duration connectTimeout,
      @Value("${barrier.webhook.read-timeout:PT10S}") Duration readTimeout) {
    // Connect timeout vive no HttpClient da JDK, não no request factory.
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(readTimeout);
    this.restClient = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public WebhookSendResult send(WebhookRequest request) {
    try {
      var spec =
          restClient
              .post()
              .uri(request.url())
              .contentType(MediaType.APPLICATION_JSON)
              .header(EVENT_ID_HEADER, request.eventId())
              .header(SIGNATURE_HEADER, request.signature());
      if (request.previousSignature() != null) {
        spec = spec.header(PREVIOUS_SIGNATURE_HEADER, request.previousSignature());
      }
      var response = spec.body(request.body()).retrieve().toBodilessEntity();
      int status = response.getStatusCode().value();
      return response.getStatusCode().is2xxSuccessful()
          ? WebhookSendResult.ok(status)
          : WebhookSendResult.failure(status, "HTTP " + status);
    } catch (org.springframework.web.client.RestClientResponseException e) {
      return WebhookSendResult.failure(e.getStatusCode().value(), e.getMessage());
    } catch (RuntimeException e) {
      return WebhookSendResult.failure(0, e.getMessage());
    }
  }
}
