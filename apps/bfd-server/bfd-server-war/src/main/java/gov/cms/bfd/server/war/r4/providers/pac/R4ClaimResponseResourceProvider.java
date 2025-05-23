package gov.cms.bfd.server.war.r4.providers.pac;

import static gov.cms.bfd.server.war.SpringConfiguration.PAC_OLD_MBI_HASH_ENABLED;
import static gov.cms.bfd.server.war.SpringConfiguration.SSM_PATH_PAC_CLAIM_SOURCE_TYPES;
import static gov.cms.bfd.server.war.SpringConfiguration.SSM_PATH_SAMHSA_V2_SHADOW;

import ca.uhn.fhir.rest.server.IResourceProvider;
import com.codahale.metrics.MetricRegistry;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import gov.cms.bfd.server.war.SamhsaV2InterceptorShadow;
import gov.cms.bfd.server.war.commons.SecurityTagsDao;
import gov.cms.bfd.server.war.r4.providers.pac.common.ResourceTypeV2;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** This FHIR {@link IResourceProvider} adds support for R4 {@link ClaimResponse} resources. */
@Component
public class R4ClaimResponseResourceProvider extends AbstractR4ResourceProvider<ClaimResponse> {

  /** The map of claim types. */
  private static final Map<String, ResourceTypeV2<ClaimResponse, ?>> claimTypeMap =
      ImmutableMap.of("fiss", ClaimResponseTypeV2.F, "mcs", ClaimResponseTypeV2.M);

  /**
   * Instantiates a new R4 claim response resource provider. This should be called by spring during
   * component initialization.
   *
   * @param metricRegistry the metric registry bean
   * @param samhsaMatcher the samhsa matcher bean
   * @param oldMbiHashEnabled true if old MBI hash should be used
   * @param fissClaimResponseTransformerV2 the fiss claim response transformer
   * @param mcsClaimResponseTransformerV2 the mcs claim response transformer
   * @param securityTagsDao security Tags Dao
   * @param claimSourceTypeNames determines the type of claim sources to enable for constructing PAC
   * @param samhsaV2Shadow the samhsa V2 Shadow flag resources ({@link org.hl7.fhir.r4.model.Claim}
   *     / {@link org.hl7.fhir.r4.model.ClaimResponse}
   * @param samhsaV2InterceptorShadow v2SamhsaConsentSimulation
   */
  public R4ClaimResponseResourceProvider(
      MetricRegistry metricRegistry,
      R4ClaimSamhsaMatcher samhsaMatcher,
      @Qualifier(PAC_OLD_MBI_HASH_ENABLED) Boolean oldMbiHashEnabled,
      FissClaimResponseTransformerV2 fissClaimResponseTransformerV2,
      McsClaimResponseTransformerV2 mcsClaimResponseTransformerV2,
      SamhsaV2InterceptorShadow samhsaV2InterceptorShadow,
      SecurityTagsDao securityTagsDao,
      @Value("${" + SSM_PATH_PAC_CLAIM_SOURCE_TYPES + ":}") String claimSourceTypeNames,
      @Value("${" + SSM_PATH_SAMHSA_V2_SHADOW + ":false}") boolean samhsaV2Shadow) {
    super(
        metricRegistry,
        samhsaMatcher,
        oldMbiHashEnabled,
        fissClaimResponseTransformerV2,
        mcsClaimResponseTransformerV2,
        claimSourceTypeNames,
        samhsaV2InterceptorShadow,
        securityTagsDao,
        samhsaV2Shadow);
  }

  /** {@inheritDoc} */
  @Override
  Optional<ResourceTypeV2<ClaimResponse, ?>> parseClaimType(String typeText) {
    return ClaimResponseTypeV2.parse(typeText);
  }

  /** {@inheritDoc} */
  @VisibleForTesting
  @Override
  Set<ResourceTypeV2<ClaimResponse, ?>> getDefinedResourceTypes() {
    return Sets.newHashSet(ClaimResponseTypeV2.values());
  }

  /** {@inheritDoc} */
  @VisibleForTesting
  @Override
  Map<String, ResourceTypeV2<ClaimResponse, ?>> getResourceTypeMap() {
    return claimTypeMap;
  }
}
