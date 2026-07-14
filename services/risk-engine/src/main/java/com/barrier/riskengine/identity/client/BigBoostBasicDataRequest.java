package com.barrier.riskengine.identity.client;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Corpo da requisição ao endpoint {@code POST /pessoas} (dataset {@code basic_data}). */
record BigBoostBasicDataRequest(
    @JsonProperty("Datasets") String datasets, @JsonProperty("q") String q, @JsonProperty("Limit") int limit) {

  static BigBoostBasicDataRequest forCpf(String cpfDigits) {
    return new BigBoostBasicDataRequest("basic_data", "doc{" + cpfDigits + "}", 1);
  }
}
