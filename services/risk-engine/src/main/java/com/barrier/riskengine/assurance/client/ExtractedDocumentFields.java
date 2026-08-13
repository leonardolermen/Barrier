package com.barrier.riskengine.assurance.client;

import java.time.LocalDate;

/**
 * Dados lidos do documento pela documentoscopia — nome, número do documento e data de nascimento
 * como o provedor os reconheceu na imagem.
 *
 * <p>Existe para uma task futura comparar o que o documento diz com o que o cadastro (CMN 4.753)
 * declara: divergência entre os dois é sinal, não os dados em si. Por isso não é persistido aqui
 * — quem grava a divergência, se houver, é quem consome este resultado.
 */
public record ExtractedDocumentFields(String name, String document, LocalDate birthDate) {}
