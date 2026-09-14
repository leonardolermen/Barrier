package com.barrier.riskengine.riskpolicy.domain.catalog;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.risk.rule.context.ContextInput;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.screening.domain.ScreenedParty;
import com.barrier.riskengine.screening.domain.ScreeningHit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catálogo v1 de campos que uma política de parceiro pode referenciar.
 *
 * <p>É a interface pública da linguagem de política, e o que a desacopla do formato do {@link
 * RiskContext}: quando o domínio transacional chegar, o catálogo cresce e a linguagem não muda.
 * Cada campo declara o {@link ContextInput} de origem — é dele que o {@code requires()} de uma
 * política é derivado, em vez de declarado à mão — e se o valor pode aparecer em evidência ({@link
 * EvidenceExposure}).
 *
 * <p>Campo pode ser marcado obsoleto, nunca removido — mesma razão de migration Flyway ser
 * imutável: política antiga precisa continuar interpretável.
 */
public final class FieldCatalog {

  /**
   * Incrementa a cada campo adicionado ou marcado obsoleto. Política grava contra qual versão foi
   * escrita.
   */
  public static final int VERSION = 1;

  public static final FieldCatalog V1 = new FieldCatalog(VERSION, buildFields());

  private final int version;
  private final List<PolicyField> fields;
  private final Map<String, PolicyField> byId;

  private FieldCatalog(int version, List<PolicyField> fields) {
    this.version = version;
    this.fields = List.copyOf(fields);
    this.byId =
        this.fields.stream().collect(Collectors.toMap(PolicyField::id, Function.identity()));
  }

  public int version() {
    return version;
  }

  public List<PolicyField> all() {
    return fields;
  }

  public Optional<PolicyField> find(String id) {
    return Optional.ofNullable(byId.get(id));
  }

  /** Adapta um extrator tipado em {@link RiskContext} para o extrator de {@code Object} do campo. */
  private static Function<Object, Object> ctx(Function<RiskContext, Object> extractor) {
    return raiz -> extractor.apply((RiskContext) raiz);
  }

  private static PolicyField campo(
      String id,
      PolicyFieldType type,
      ContextInput input,
      EvidenceExposure exposure,
      Function<Object, Object> extractor) {
    return new PolicyField(id, type, input, exposure, null, extractor);
  }

  private static PolicyField campoDeElemento(
      String id,
      PolicyFieldType type,
      ContextInput input,
      EvidenceExposure exposure,
      String parentListId,
      Function<Object, Object> extractor) {
    return new PolicyField(id, type, input, exposure, parentListId, extractor);
  }

  /**
   * Extrai só o {@link ScreenedParty.Role} de um {@link ScreeningHit}, nunca o {@link
   * ScreenedParty} inteiro. {@code ScreenedParty} carrega {@code name} e {@code document} — devolver
   * o record exporia PII pela evidência da regra (marcada {@code BY_VALUE}), pelo {@code GET} da
   * avaliação e pelo dossiê de replay. {@code Role} é o único enum de verdade ali, e é o que faz a
   * comparação {@code EQ}/{@code IN} contra {@code TITULAR}/{@code SOCIO}/{@code
   * REPRESENTANTE_LEGAL} funcionar.
   */
  private static Object partyRole(Object elemento) {
    ScreenedParty party = ((ScreeningHit) elemento).party();
    return party == null ? null : party.role();
  }

  private static List<PolicyField> buildFields() {
    return List.of(
        // identity
        campo(
            "identity.status",
            PolicyFieldType.ENUM,
            ContextInput.IDENTITY,
            EvidenceExposure.BY_VALUE,
            ctx(c -> c.identity() == null ? null : c.identity().status())),
        campo(
            "identity.documentType",
            PolicyFieldType.STRING,
            ContextInput.IDENTITY,
            EvidenceExposure.BY_VALUE,
            ctx(c -> c.identity() == null ? null : c.identity().documentType())),
        campo(
            "identity.provider",
            PolicyFieldType.STRING,
            ContextInput.IDENTITY,
            EvidenceExposure.BY_VALUE,
            ctx(c -> c.identity() == null ? null : c.identity().provider())),

        // screening
        campo(
            "screening.status",
            PolicyFieldType.ENUM,
            ContextInput.SCREENING,
            EvidenceExposure.BY_VALUE,
            ctx(c -> c.screening() == null ? null : c.screening().status())),
        campo(
            "screening.hits",
            PolicyFieldType.LIST,
            ContextInput.SCREENING,
            EvidenceExposure.BY_VALUE,
            ctx(c -> c.screening() == null ? List.of() : c.screening().hits())),
        campoDeElemento(
            "screening.hits[].type",
            PolicyFieldType.ENUM,
            ContextInput.SCREENING,
            EvidenceExposure.BY_VALUE,
            "screening.hits",
            e -> ((ScreeningHit) e).type()),
        campoDeElemento(
            "screening.hits[].basis",
            PolicyFieldType.ENUM,
            ContextInput.SCREENING,
            EvidenceExposure.BY_VALUE,
            "screening.hits",
            e -> ((ScreeningHit) e).basis()),
        campoDeElemento(
            "screening.hits[].party",
            PolicyFieldType.ENUM,
            ContextInput.SCREENING,
            EvidenceExposure.BY_VALUE,
            "screening.hits",
            FieldCatalog::partyRole),
        campoDeElemento(
            "screening.hits[].source",
            PolicyFieldType.STRING,
            ContextInput.SCREENING,
            EvidenceExposure.BY_VALUE,
            "screening.hits",
            e -> ((ScreeningHit) e).source()),

        // company (perfil objetivo de PJ vindo do bureau)
        campo(
            "company.openingDate",
            PolicyFieldType.DATE,
            ContextInput.COMPANY,
            EvidenceExposure.BY_VALUE,
            ctx(c -> c.company() == null ? null : c.company().openingDate())),
        campo(
            "company.cnaeCode",
            PolicyFieldType.STRING,
            ContextInput.COMPANY,
            EvidenceExposure.BY_VALUE,
            ctx(c -> c.company() == null ? null : c.company().cnaeCode())),
        campo(
            "company.partners",
            PolicyFieldType.LIST,
            ContextInput.COMPANY,
            EvidenceExposure.BY_VALUE,
            ctx(c -> c.company() == null ? List.of() : c.company().partners())),
        campoDeElemento(
            "company.partners[].legalEntity",
            PolicyFieldType.BOOLEAN,
            ContextInput.COMPANY,
            EvidenceExposure.BY_VALUE,
            "company.partners",
            e -> ((CompanyProfile.Partner) e).legalEntity()),
        campoDeElemento(
            "company.partners[].foreign",
            PolicyFieldType.BOOLEAN,
            ContextInput.COMPANY,
            EvidenceExposure.BY_VALUE,
            "company.partners",
            e -> ((CompanyProfile.Partner) e).foreign()),
        campoDeElemento(
            "company.partners[].qualification",
            PolicyFieldType.STRING,
            ContextInput.COMPANY,
            EvidenceExposure.BY_VALUE,
            "company.partners",
            e -> ((CompanyProfile.Partner) e).qualification()),

        // profile (cadastro do subject, CMN 4.753)
        campo(
            "profile.birthDate",
            PolicyFieldType.DATE,
            ContextInput.PROFILE,
            EvidenceExposure.OUTCOME_ONLY,
            ctx(c -> c.profile() == null ? null : c.profile().birthDate())),
        campo(
            "profile.foundingDate",
            PolicyFieldType.DATE,
            ContextInput.PROFILE,
            EvidenceExposure.BY_VALUE,
            ctx(c -> c.profile() == null ? null : c.profile().foundingDate())),
        campo(
            "profile.nationality",
            PolicyFieldType.STRING,
            ContextInput.PROFILE,
            EvidenceExposure.BY_VALUE,
            ctx(c -> c.profile() == null ? null : c.profile().nationality())),
        campo(
            "profile.occupation",
            PolicyFieldType.STRING,
            ContextInput.PROFILE,
            EvidenceExposure.BY_VALUE,
            ctx(c -> c.profile() == null ? null : c.profile().occupation())),
        campo(
            "profile.declaredIncome",
            PolicyFieldType.NUMBER,
            ContextInput.PROFILE,
            EvidenceExposure.OUTCOME_ONLY,
            ctx(c -> c.profile() == null ? null : c.profile().declaredIncome())),
        campo(
            "profile.shareCapital",
            PolicyFieldType.NUMBER,
            ContextInput.PROFILE,
            EvidenceExposure.BY_VALUE,
            ctx(c -> c.profile() == null ? null : c.profile().shareCapital())),
        campo(
            "profile.cnaeCode",
            PolicyFieldType.STRING,
            ContextInput.PROFILE,
            EvidenceExposure.BY_VALUE,
            ctx(c -> c.profile() == null ? null : c.profile().cnaeCode())),
        campo(
            "profile.address.state",
            PolicyFieldType.STRING,
            ContextInput.PROFILE,
            EvidenceExposure.BY_VALUE,
            ctx(
                c ->
                    c.profile() == null || c.profile().address() == null
                        ? null
                        : c.profile().address().state())),
        campo(
            "profile.address.city",
            PolicyFieldType.STRING,
            ContextInput.PROFILE,
            EvidenceExposure.BY_VALUE,
            ctx(
                c ->
                    c.profile() == null || c.profile().address() == null
                        ? null
                        : c.profile().address().city())),

        // assurance (documentoscopia e biometria)
        campo(
            "assurance.document.outcome",
            PolicyFieldType.ENUM,
            ContextInput.ASSURANCE,
            EvidenceExposure.BY_VALUE,
            ctx(
                c ->
                    c.assurance() == null || c.assurance().document() == null
                        ? null
                        : c.assurance().document().outcome())),
        campo(
            "assurance.biometric.outcome",
            PolicyFieldType.ENUM,
            ContextInput.ASSURANCE,
            EvidenceExposure.BY_VALUE,
            ctx(
                c ->
                    c.assurance() == null || c.assurance().biometric() == null
                        ? null
                        : c.assurance().biometric().outcome())),
        campo(
            "assurance.biometricAttempts",
            PolicyFieldType.NUMBER,
            ContextInput.ASSURANCE,
            EvidenceExposure.BY_VALUE,
            ctx(c -> c.assurance() == null ? null : c.assurance().biometricAttempts())));
  }
}
