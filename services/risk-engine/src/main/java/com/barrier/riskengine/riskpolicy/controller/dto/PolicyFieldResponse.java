package com.barrier.riskengine.riskpolicy.controller.dto;

/**
 * Um campo do catálogo, na forma que o parceiro precisa para escrever uma regra: id, tipo (que
 * decide os operadores válidos) e se o valor pode aparecer em evidência.
 *
 * @param elementOf {@code null} para campo de topo; para campo de elemento, o id da lista a que
 *     pertence ({@code company.partners[].foreign} -&gt; {@code company.partners}) -- é o que diz
 *     ao parceiro que este campo só é válido dentro do {@code AnyOf} daquela lista.
 */
public record PolicyFieldResponse(
    String id, String type, String evidenceExposure, String elementOf) {}
