package com.barrier.riskengine.screening.watchlist;

import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.springframework.web.client.RestClient;

/**
 * CSNU — Lista Consolidada de Sanções do Conselho de Segurança da ONU.
 *
 * <p>É a lista de cumprimento mais direto do conjunto: a Lei 13.810/2019 manda indisponibilizar
 * ativos de sancionados pelo CSNU <b>imediatamente</b> e sem decisão judicial prévia. Era também a
 * única obrigatória que não existia aqui — o motor decidia PLD-FT sem nunca consultá-la.
 *
 * <p>Publicada em XML único ({@code consolidated.xml}), com pessoas em {@code INDIVIDUALS} e
 * entidades em {@code ENTITIES}. O nome vem quebrado em até quatro campos, remontado na ordem; cada
 * <b>alias</b> vira uma entrada própria, como os {@code aka} da OFAC — a lista publica grafias
 * alternativas justamente porque transliteração do árabe/cirílico varia, e ignorá-las é perder o
 * apontamento por diferença de grafia.
 *
 * <p>Sem documento brasileiro: o CSNU identifica por nome, nacionalidade e data de nascimento, e
 * não publica CPF/CNPJ. Então o casamento é sempre por nome (indício), o que já é o comportamento
 * de {@code SanctionRiskRule} para {@code MatchBasis.NAME} — pontua alto e escala para revisão
 * humana, sem reprovar sozinho.
 *
 * <p>Desligada por padrão ({@code barrier.watchlist.un.enabled}); ligada no profile {@code prod}.
 */
@Component
@ConditionalOnProperty("barrier.watchlist.un.enabled")
class UnWatchlistSource implements WatchlistSource {

  private static final Logger log = LoggerFactory.getLogger(UnWatchlistSource.class);
  private static final String SOURCE = "CSNU";
  private static final String[] NAME_PARTS = {
    "FIRST_NAME", "SECOND_NAME", "THIRD_NAME", "FOURTH_NAME"
  };

  private final RestClient client;
  private final String path;

  UnWatchlistSource(
      @Qualifier("unRestClient") RestClient client,
      @Value("${barrier.watchlist.un.path:/resources/xml/en/consolidated.xml}") String path) {
    this.client = client;
    this.path = path;
  }

  @Override
  public String source() {
    return SOURCE;
  }

  @Override
  public Set<MatchType> provides() {
    return Set.of(MatchType.SANCTION);
  }

  @Override
  public WatchlistBatch fetch() {
    log.info("CSNU: baixando lista consolidada da ONU ({})", path);
    byte[] body = client.get().uri(path).retrieve().body(byte[].class);
    if (body == null || body.length == 0) {
      throw new IllegalStateException("Download da lista do CSNU veio vazio");
    }
    List<WatchlistRecord> records = parse(body);
    log.info("CSNU: {} entradas (nomes + apelidos)", records.size());
    return new WatchlistBatch("csnu-" + LocalDate.now(), records);
  }

  /** Visível para teste: o parsing é o que quebra quando a ONU muda o layout. */
  static List<WatchlistRecord> parse(byte[] xml) {
    Document document = read(xml);
    List<WatchlistRecord> records = new ArrayList<>();
    collect(document, "INDIVIDUAL", "INDIVIDUAL_ALIAS", records);
    collect(document, "ENTITY", "ENTITY_ALIAS", records);
    return records;
  }

  private static void collect(
      Document document, String tag, String aliasTag, List<WatchlistRecord> records) {
    NodeList nodes = document.getElementsByTagName(tag);
    for (int i = 0; i < nodes.getLength(); i++) {
      Element element = (Element) nodes.item(i);
      String detail = detail(element);

      String name =
          Stream.of(NAME_PARTS)
              .map(part -> text(element, part))
              .filter(value -> !value.isBlank())
              .reduce((a, b) -> a + " " + b)
              .orElse("")
              .trim();
      if (!name.isBlank()) {
        records.add(new WatchlistRecord(SOURCE, MatchType.SANCTION, null, name, detail));
      }

      // Cada apelido é uma linha própria: a lista publica grafias alternativas porque a
      // transliteração varia, e casar só o nome principal perde o apontamento por grafia.
      NodeList aliases = element.getElementsByTagName(aliasTag);
      for (int j = 0; j < aliases.getLength(); j++) {
        String alias = text((Element) aliases.item(j), "ALIAS_NAME");
        if (!alias.isBlank()) {
          records.add(
              new WatchlistRecord(SOURCE, MatchType.SANCTION, null, alias.trim(), detail + " (aka)"));
        }
      }
    }
  }

  /** Referência e regime de sanção — é o que o analista precisa para achar a entrada na ONU. */
  private static String detail(Element element) {
    String reference = text(element, "REFERENCE_NUMBER");
    String listType = text(element, "UN_LIST_TYPE");
    StringBuilder detail = new StringBuilder("CSNU/ONU");
    if (!reference.isBlank()) {
      detail.append(' ').append(reference);
    }
    if (!listType.isBlank()) {
      detail.append(" · ").append(listType);
    }
    return detail.toString();
  }

  /**
   * Só o filho <b>direto</b>: {@code getElementsByTagName} desceria para dentro dos aliases e de
   * outros blocos aninhados, e o {@code FIRST_NAME} de um alias viraria nome do sancionado.
   */
  private static String text(Element parent, String tag) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child.getNodeType() == Node.ELEMENT_NODE && tag.equals(child.getNodeName())) {
        String value = child.getTextContent();
        return value == null ? "" : value.trim();
      }
    }
    return "";
  }

  /**
   * XML de terceiro é entrada não confiável: DTD e entidades externas desligadas, senão um arquivo
   * publicado (ou interceptado) poderia ler arquivo local ou fazer o serviço bater em endereço
   * interno — XXE clássico.
   */
  private static Document read(byte[] xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
      document.getDocumentElement().normalize();
      return document;
    } catch (Exception e) {
      throw new IllegalStateException("Lista do CSNU ilegível: " + e.getMessage(), e);
    }
  }
}
