package com.barrier.riskengine.riskpolicy.controller;

import com.barrier.riskengine.riskpolicy.controller.dto.PolicyDtoMapper;
import com.barrier.riskengine.riskpolicy.controller.dto.PolicyFieldCatalogResponse;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catálogo de campos de política -- documentação viva, publicada desde o dia um: "o campo que
 * eu preciso não existe" é um limite honesto do produto, e o objetivo é que o parceiro descubra
 * isso cedo, antes de escrever a regra, não como um 400 de {@code POST /v1/policies}.
 *
 * <p>Não é escopado por tenant -- o catálogo é o mesmo para todos -- mas a rota vive sob {@code
 * /v1/} e portanto exige credencial como qualquer outra (o filtro de tenant decide pelo prefixo
 * da URI, não pelo que o controller consome).
 */
@RestController
@RequestMapping("/v1/policy-fields")
public class PolicyFieldController {

  private final FieldCatalog catalog;
  private final PolicyDtoMapper mapper;

  public PolicyFieldController(FieldCatalog catalog, PolicyDtoMapper mapper) {
    this.catalog = catalog;
    this.mapper = mapper;
  }

  @GetMapping
  public PolicyFieldCatalogResponse fields() {
    return mapper.toResponse(catalog);
  }
}
