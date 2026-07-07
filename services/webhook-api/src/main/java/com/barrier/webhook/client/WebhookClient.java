package com.barrier.webhook.client;

/** Envio HTTP do webhook ao cliente, atrás de interface. */
public interface WebhookClient {

  /** Faz o POST no endpoint; nunca lança — erros viram {@link WebhookSendResult#failure}. */
  WebhookSendResult send(WebhookRequest request);
}
