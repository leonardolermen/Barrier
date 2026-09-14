package com.barrier.riskengine.riskpolicy.controller.dto;

import java.util.List;

/**
 * O catálogo inteiro, com a versão contra a qual uma política nova é gravada ({@code
 * catalogVersion} em {@link PolicyResponse}) -- documentação viva, publicada desde o dia um
 * para que "o campo que eu preciso não existe" apareça cedo, não depois de o parceiro escrever
 * a regra.
 */
public record PolicyFieldCatalogResponse(int version, List<PolicyFieldResponse> fields) {}
