package com.barrier.webhook.client;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Envio via {@link RestClient}; qualquer erro/HTTP não-2xx vira uma falha (para retry). */
@Component
public class HttpWebhookClient implements WebhookClient {

  static final String SIGNATURE_HEADER = "X-Barrier-Signature";
  static final String EVENT_ID_HEADER = "X-Barrier-Event-Id";

  private final RestClient restClient = RestClient.create();

  @Override
  public WebhookSendResult send(WebhookRequest request) {
    try {
      var response =
          restClient
              .post()
              .uri(request.url())
              .contentType(MediaType.APPLICATION_JSON)
              .header(EVENT_ID_HEADER, request.eventId())
              .header(SIGNATURE_HEADER, request.signature())
              .body(request.body())
              .retrieve()
              .toBodilessEntity();
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
