package gov.cms.bfd.server.war.r4.providers;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import gov.cms.bfd.model.codebook.data.CcwCodebookMissingVariable;
import gov.cms.bfd.model.codebook.data.CcwCodebookVariable;
import gov.cms.bfd.model.codebook.model.CcwCodebookInterface;
import gov.cms.bfd.model.codebook.model.Value;
import gov.cms.bfd.model.rif.Beneficiary;
import gov.cms.bfd.model.rif.parse.InvalidRifValueException;
import gov.cms.bfd.server.war.FDADrugDataUtilityApp;
import gov.cms.bfd.server.war.commons.CCWProcedure;
import gov.cms.bfd.server.war.commons.Diagnosis;
import gov.cms.bfd.server.war.commons.Diagnosis.DiagnosisLabel;
import gov.cms.bfd.server.war.commons.LinkBuilder;
import gov.cms.bfd.server.war.commons.MedicareSegment;
import gov.cms.bfd.server.war.commons.OffsetLinkBuilder;
import gov.cms.bfd.server.war.commons.ProfileConstants;
import gov.cms.bfd.server.war.commons.TransformerConstants;
import gov.cms.bfd.server.war.commons.carin.C4BBAdjudication;
import gov.cms.bfd.server.war.commons.carin.C4BBAdjudicationDiscriminator;
import gov.cms.bfd.server.war.commons.carin.C4BBClaimIdentifierType;
import gov.cms.bfd.server.war.commons.carin.C4BBClaimInstitutionalCareTeamRole;
import gov.cms.bfd.server.war.commons.carin.C4BBIdentifierType;
import gov.cms.bfd.server.war.commons.carin.C4BBOrganizationIdentifierType;
import gov.cms.bfd.server.war.commons.carin.C4BBPractitionerIdentifierType;
import gov.cms.bfd.server.war.commons.carin.C4BBSupportingInfoType;
import gov.cms.bfd.server.war.r4.providers.BeneficiaryTransformerV2.CurrencyIdentifier;
import gov.cms.bfd.server.war.stu3.providers.TransformerUtils;
import gov.cms.bfd.sharedutils.exceptions.BadCodeMonkeyException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBaseExtension;
import org.hl7.fhir.instance.model.api.IBaseHasExtensions;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.AdjudicationComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.BenefitBalanceComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.BenefitComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.CareTeamComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.DiagnosisComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.ExplanationOfBenefitStatus;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.ItemComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.ProcedureComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.SupportingInformationComponent;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.TotalComponent;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Money;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.PositiveIntType;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.SimpleQuantity;
import org.hl7.fhir.r4.model.UnsignedIntType;
import org.hl7.fhir.r4.model.codesystems.ClaimCareteamrole;
import org.hl7.fhir.r4.model.codesystems.ExBenefitcategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Contains shared methods used to transform CCW JPA entities (e.g. {@link Beneficiary}) into FHIR
 * resources (e.g. {@link Patient}).
 */
public final class TransformerUtilsV2 {
  private static final Logger LOGGER = LoggerFactory.getLogger(TransformerUtilsV2.class);

  /**
   * Tracks the {@link CcwCodebookInterface} that have already had code lookup failures due to
   * missing {@link Value} matches. Why track this? To ensure that we don't spam log events for
   * failed lookups over and over and over. This was needed to fix CBBF-162, where those log events
   * were flooding our logs and filling up the drive.
   *
   * @see #calculateCodingDisplay(IAnyResource, CcwCodebookInterface, String)
   */
  private static final Set<CcwCodebookInterface> codebookLookupMissingFailures = new HashSet<>();

  /**
   * Tracks the {@link CcwCodebookInterface} that have already had code lookup failures due to
   * duplicate {@link Value} matches. Why track this? To ensure that we don't spam log events for
   * failed lookups over and over and over. This was needed to fix CBBF-162, where those log events
   * were flooding our logs and filling up the drive.
   *
   * @see #calculateCodingDisplay(IAnyResource, CcwCodebookInterface, String)
   */
  private static final Set<CcwCodebookInterface> codebookLookupDuplicateFailures = new HashSet<>();

  /** Stores the PRODUCTNDC and SUBSTANCENAME from the downloaded NDC file. */
  private static Map<String, String> ndcProductMap = null;

  /** Tracks the national drug codes that have already had code lookup failures. */
  private static final Set<String> drugCodeLookupMissingFailures = new HashSet<>();

  /** Stores the diagnosis ICD codes and their display values */
  private static Map<String, String> icdMap = null;

  /** Stores the procedure codes and their display values */
  private static Map<String, String> procedureMap = null;

  /** Tracks the procedure codes that have already had code lookup failures. */
  private static final Set<String> procedureLookupMissingFailures = new HashSet<>();

  /** Stores the NPI codes and their display values */
  private static Map<String, String> npiMap = null;

  /** Tracks the NPI codes that have already had code lookup failures. */
  private static final Set<String> npiCodeLookupMissingFailures = new HashSet<>();

  /**
   * Adds an {@link Extension} to the specified {@link DomainResource}. {@link Extension#getValue()}
   * will be set to a {@link CodeableConcept} containing a single {@link Coding}, with the specified
   * system and code.
   *
   * <p>Data Architecture Note: The {@link CodeableConcept} might seem extraneous -- why not just
   * add the {@link Coding} directly to the {@link Extension}? The main reason for doing it this way
   * is consistency: this is what FHIR seems to do everywhere.
   *
   * @param fhirElement the FHIR element to add the {@link Extension} to
   * @param extensionUrl the {@link Extension#getUrl()} to use
   * @param codingSystem the {@link Coding#getSystem()} to use
   * @param codingDisplay the {@link Coding#getDisplay()} to use
   * @param codingCode the {@link Coding#getCode()} to use
   */
  static void addExtensionCoding(
      IBaseHasExtensions fhirElement,
      String extensionUrl,
      String codingSystem,
      String codingDisplay,
      String codingCode) {
    IBaseExtension<?, ?> extension = fhirElement.addExtension();
    extension.setUrl(extensionUrl);
    if (codingDisplay == null)
      extension.setValue(new Coding().setSystem(codingSystem).setCode(codingCode));
    else
      extension.setValue(
          new Coding().setSystem(codingSystem).setCode(codingCode).setDisplay(codingDisplay));
  }

  /**
   * Adds an {@link Extension} to the specified {@link DomainResource}. {@link Extension#getValue()}
   * will be set to a {@link Quantity} with the specified system and value.
   *
   * @param fhirElement the FHIR element to add the {@link Extension} to
   * @param extensionUrl the {@link Extension#getUrl()} to use
   * @param quantitySystem the {@link Quantity#getSystem()} to use
   * @param quantityValue the {@link Quantity#getValue()} to use
   */
  static void addExtensionValueQuantity(
      IBaseHasExtensions fhirElement,
      String extensionUrl,
      String quantitySystem,
      BigDecimal quantityValue) {
    IBaseExtension<?, ?> extension = fhirElement.addExtension();
    extension.setUrl(extensionUrl);
    extension.setValue(new Quantity().setSystem(extensionUrl).setValue(quantityValue));

    // CodeableConcept codeableConcept = new CodeableConcept();
    // extension.setValue(codeableConcept);
    //
    // Coding coding = codeableConcept.addCoding();
    // coding.setSystem(codingSystem).setCode(codingCode);
  }

  /**
   * Adds an {@link Extension} to the specified {@link DomainResource}. {@link Extension#getValue()}
   * will be set to a {@link Identifier} with the specified url, system, and value.
   *
   * @param fhirElement the FHIR element to add the {@link Extension} to
   * @param extensionUrl the {@link Extension#getUrl()} to use
   * @param extensionSystem the {@link Identifier#getSystem()} to use
   * @param extensionValue the {@link Identifier#getValue()} to use
   */
  static void addExtensionValueIdentifier(
      IBaseHasExtensions fhirElement,
      String extensionUrl,
      String extensionSystem,
      String extensionValue) {
    IBaseExtension<?, ?> extension = fhirElement.addExtension();
    extension.setUrl(extensionUrl);

    Identifier valueIdentifier = new Identifier();
    valueIdentifier.setSystem(extensionSystem).setValue(extensionValue);

    extension.setValue(valueIdentifier);
  }

  /**
   * @param beneficiary the {@link Beneficiary} to calculate the {@link Patient#getId()} value for
   * @return the {@link Patient#getId()} value that will be used for the specified {@link
   *     Beneficiary}
   */
  public static IdDt buildPatientId(Beneficiary beneficiary) {
    return buildPatientId(beneficiary.getBeneficiaryId());
  }

  /**
   * @param beneficiaryId the {@link Beneficiary#getBeneficiaryId()} to calculate the {@link
   *     Patient#getId()} value for
   * @return the {@link Patient#getId()} value that will be used for the specified {@link
   *     Beneficiary}
   */
  public static IdDt buildPatientId(String beneficiaryId) {
    return new IdDt(Patient.class.getSimpleName(), beneficiaryId);
  }

  /**
   * @param localDate the {@link LocalDate} to convert
   * @return a {@link Date} version of the specified {@link LocalDate}
   */
  static Date convertToDate(LocalDate localDate) {
    /*
     * We use the system TZ here to ensure that the date doesn't shift at all, as FHIR will just use
     * this as an unzoned Date (I think, and if not, it's almost certainly using the same TZ as this
     * system).
     */
    return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
  }

  /**
   * @param codingSystem the {@link Coding#getSystem()} to use
   * @param codingCode the {@link Coding#getCode()} to use
   * @return a {@link CodeableConcept} with the specified {@link Coding}
   */
  static CodeableConcept createCodeableConcept(String codingSystem, String codingCode) {
    return createCodeableConcept(codingSystem, null, null, codingCode);
  }

  /**
   * @param codingSystem the {@link Coding#getSystem()} to use
   * @param codingVersion the {@link Coding#getVersion()} to use
   * @param codingDisplay the {@link Coding#getDisplay()} to use
   * @param codingCode the {@link Coding#getCode()} to use
   * @return a {@link CodeableConcept} with the specified {@link Coding}
   */
  static CodeableConcept createCodeableConcept(
      String codingSystem, String codingVersion, String codingDisplay, String codingCode) {
    CodeableConcept codeableConcept = new CodeableConcept();
    Coding coding = codeableConcept.addCoding().setSystem(codingSystem).setCode(codingCode);
    if (codingVersion != null) coding.setVersion(codingVersion);
    if (codingDisplay != null) coding.setDisplay(codingDisplay);
    return codeableConcept;
  }

  /**
   * Used for creating Identifier references for Organizations and Facilities
   *
   * @param identifierSystem the {@link Identifier#getSystem()} to use in {@link
   *     Reference#getIdentifier()}
   * @param identifierValue the {@link Identifier#getValue()} to use in {@link
   *     Reference#getIdentifier()}
   * @return a {@link Reference} with the specified {@link Identifier}
   */
  static Reference createIdentifierReference(String identifierSystem, String identifierValue) {
    return new Reference()
        .setIdentifier(new Identifier().setSystem(identifierSystem).setValue(identifierValue))
        .setDisplay(retrieveNpiCodeDisplay(identifierValue));
  }

  /**
   * Used for creating Identifier references for Practitioners
   *
   * @param type the {@link C4BBPractitionerIdentifierType} to use in {@link
   *     Reference#getIdentifier()}
   * @param value the {@link Identifier#getValue()} to use in {@link Reference#getIdentifier()}
   * @return a {@link Reference} with the specified {@link Identifier}
   */
  static Reference createPractitionerIdentifierReference(
      C4BBPractitionerIdentifierType type, String value) {
    Reference response =
        new Reference()
            .setIdentifier(
                new Identifier()
                    .setType(
                        new CodeableConcept()
                            .addCoding(
                                new Coding(type.getSystem(), type.toCode(), type.getDisplay())))
                    .setValue(value));

    // If this is an NPI perform the extra lookup
    if (C4BBPractitionerIdentifierType.NPI.equals(type)) {
      response.setDisplay(retrieveNpiCodeDisplay(value));
    }

    return response;
  }

  /**
   * @return a Reference to the {@link Organization} for CMS, which will only be valid if {@link
   *     #upsertSharedData()} has been run
   */
  static Reference createReferenceToCms() {
    return new Reference("Organization?name=" + urlEncode(TransformerConstants.COVERAGE_ISSUER));
  }

  /**
   * @param concept the {@link CodeableConcept} to check
   * @param codingSystem the {@link Coding#getSystem()} to match
   * @param codingCode the {@link Coding#getCode()} to match
   * @return <code>true</code> if the specified {@link CodeableConcept} contains the specified
   *     {@link Coding}, <code>false</code> if it does not
   */
  static boolean isCodeInConcept(CodeableConcept concept, String codingSystem, String codingCode) {
    return isCodeInConcept(concept, codingSystem, null, codingCode);
  }

  /**
   * @param concept the {@link CodeableConcept} to check
   * @param codingSystem the {@link Coding#getSystem()} to match
   * @param codingSystem the {@link Coding#getVersion()} to match
   * @param codingCode the {@link Coding#getCode()} to match
   * @return <code>true</code> if the specified {@link CodeableConcept} contains the specified
   *     {@link Coding}, <code>false</code> if it does not
   */
  static boolean isCodeInConcept(
      CodeableConcept concept, String codingSystem, String codingVersion, String codingCode) {
    return concept.getCoding().stream()
        .anyMatch(
            c -> {
              if (!codingSystem.equals(c.getSystem())) return false;
              if (codingVersion != null && !codingVersion.equals(c.getVersion())) return false;
              if (!codingCode.equals(c.getCode())) return false;

              return true;
            });
  }

  /**
   * @param ccwVariable the {@link CcwCodebookInterface} being mapped
   * @param identifierValue the value to use for {@link Identifier#getValue()} for the resulting
   *     {@link Identifier}
   * @return the output {@link Extension}, with {@link Extension#getValue()} set to represent the
   *     specified input values
   */
  static Extension createExtensionIdentifier(
      CcwCodebookInterface ccwVariable, Optional<String> identifierValue) {
    if (!identifierValue.isPresent()) throw new IllegalArgumentException();

    Identifier identifier = createIdentifier(ccwVariable, identifierValue.get());

    String extensionUrl = calculateVariableReferenceUrl(ccwVariable);
    Extension extension = new Extension(extensionUrl, identifier);

    return extension;
  }

  /**
   * @param ccwVariable the {@link CcwCodebookInterface} being mapped
   * @param identifierValue the value to use for {@link Identifier#getValue()} for the resulting
   *     {@link Identifier}
   * @return the output {@link Extension}, with {@link Extension#getValue()} set to represent the
   *     specified input values
   */
  static Extension createExtensionIdentifier(
      CcwCodebookInterface ccwVariable, String identifierValue) {
    return createExtensionIdentifier(ccwVariable, Optional.of(identifierValue));
  }

  /**
   * @param ccwVariable the {@link CcwCodebookInterface} being mapped
   * @param identifierValue the value to use for {@link Identifier#getValue()} for the resulting
   *     {@link Identifier}
   * @return the output {@link Identifier}
   */
  static Identifier createIdentifier(CcwCodebookInterface ccwVariable, String identifierValue) {
    if (identifierValue == null) throw new IllegalArgumentException();

    Identifier identifier =
        new Identifier()
            .setSystem(calculateVariableReferenceUrl(ccwVariable))
            .setValue(identifierValue);
    return identifier;
  }

  /**
   * Converts a value from the {@link C4BBSupportingInfoType} enumeration into a {@link Coding}
   *
   * @param slice the {@link C4BBSupportingInfoType} being mapped
   * @return the resulting {@link Coding}
   */
  static Coding createC4BBSupportingInfoCoding(C4BBSupportingInfoType slice) {
    return new Coding(slice.getSystem(), slice.toCode(), slice.getDisplay());
  }

  /**
   * Helper function to create a {@link CodeableConcept} from a {@link C4BBClaimIdentifierType}.
   * Since this type only has one value this uses a hardcoded value.
   */
  static CodeableConcept createC4BBClaimCodeableConcept() {
    return new CodeableConcept()
        .setCoding(
            Arrays.asList(
                new Coding(
                    C4BBClaimIdentifierType.UC.getSystem(),
                    C4BBClaimIdentifierType.UC.toCode(),
                    C4BBClaimIdentifierType.UC.getDisplay())));
  }

  /**
   * @param ccwVariable the {@link CcwCodebookInterface} being mapped
   * @param identifierValue the value to use for {@link Identifier#getValue()} for the resulting
   *     {@link Identifier}
   * @return the output {@link Identifier}
   */
  static Identifier createClaimIdentifier(
      CcwCodebookInterface ccwVariable, String identifierValue) {
    if (identifierValue == null) {
      throw new IllegalArgumentException();
    }

    Identifier identifier =
        new Identifier()
            .setSystem(calculateVariableReferenceUrl(ccwVariable))
            .setValue(identifierValue)
            .setType(createC4BBClaimCodeableConcept());

    return identifier;
  }

  /**
   * @param ccwVariable the {@link CcwCodebookInterface} being mapped
   * @param dateYear the value to use for {@link Coding#getCode()} for the resulting {@link Coding}
   * @return the output {@link Extension}, with {@link Extension#getValue()} set to represent the
   *     specified input values
   */
  static Extension createExtensionDate(
      CcwCodebookInterface ccwVariable, Optional<BigDecimal> dateYear) {

    Extension extension = null;
    try {
      String stringDate = dateYear.get().toString() + "-01-01";
      Date date1 = new SimpleDateFormat("yyyy-MM-dd").parse(stringDate);
      DateType dateYearValue = new DateType(date1, TemporalPrecisionEnum.YEAR);
      String extensionUrl = calculateVariableReferenceUrl(ccwVariable);
      extension = new Extension(extensionUrl, dateYearValue);

    } catch (ParseException e) {
      throw new InvalidRifValueException(
          String.format("Unable to parse reference year: '%s'.", dateYear.get()), e);
    }

    return extension;
  }

  /**
   * @param ccwVariable the {@link CcwCodebookInterface} being mapped
   * @param quantityValue the value to use for {@link Coding#getCode()} for the resulting {@link
   *     Coding}
   * @return the output {@link Extension}, with {@link Extension#getValue()} set to represent the
   *     specified input values
   */
  static Extension createExtensionQuantity(
      CcwCodebookInterface ccwVariable, Optional<? extends Number> quantityValue) {
    if (!quantityValue.isPresent()) {
      throw new IllegalArgumentException();
    }

    Quantity quantity;
    if (quantityValue.get() instanceof BigDecimal) {
      quantity = new Quantity().setValue((BigDecimal) quantityValue.get());
    } else {
      throw new BadCodeMonkeyException();
    }

    String extensionUrl = calculateVariableReferenceUrl(ccwVariable);
    Extension extension = new Extension(extensionUrl, quantity);

    return extension;
  }

  /**
   * @param ccwVariable the {@link CcwCodebookInterface} being mapped
   * @param quantityValue the value to use for {@link Coding#getCode()} for the resulting {@link
   *     Coding}
   * @return the output {@link Extension}, with {@link Extension#getValue()} set to represent the
   *     specified input values
   */
  static Extension createExtensionQuantity(CcwCodebookInterface ccwVariable, Number quantityValue) {
    return createExtensionQuantity(ccwVariable, Optional.of(quantityValue));
  }

  /**
   * Sets the {@link Quantity} fields related to the unit for the amount: {@link
   * Quantity#getSystem()}, {@link Quantity#getCode()}, and {@link Quantity#getUnit()}.
   *
   * @param ccwVariable the {@link CcwCodebookInterface} for the unit coding
   * @param unitCode the value to use for {@link Quantity#getCode()}
   * @param rootResource the root FHIR {@link IAnyResource} that is being mapped
   * @param quantity the {@link Quantity} to modify
   */
  static void setQuantityUnitInfo(
      CcwCodebookInterface ccwVariable,
      Optional<?> unitCode,
      IAnyResource rootResource,
      Quantity quantity) {
    if (!unitCode.isPresent()) return;

    quantity.setSystem(calculateVariableReferenceUrl(ccwVariable));

    String unitCodeString;
    if (unitCode.get() instanceof String) unitCodeString = (String) unitCode.get();
    else if (unitCode.get() instanceof Character)
      unitCodeString = ((Character) unitCode.get()).toString();
    else throw new IllegalArgumentException();

    quantity.setCode(unitCodeString);

    Optional<String> unit = calculateCodingDisplay(rootResource, ccwVariable, unitCodeString);
    if (unit.isPresent()) quantity.setUnit(unit.get());
  }

  /**
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link Extension}
   *     will be contained in
   * @param ccwVariable the {@link CcwCodebookInterface} being coded
   * @param code the value to use for {@link Coding#getCode()} for the resulting {@link Coding}
   * @return the output {@link Extension}, with {@link Extension#getValue()} set to a new {@link
   *     Coding} to represent the specified input values
   */
  static Extension createExtensionCoding(
      IAnyResource rootResource, CcwCodebookInterface ccwVariable, Optional<?> code) {
    if (!code.isPresent()) {
      throw new IllegalArgumentException();
    }

    Coding coding = createCoding(rootResource, ccwVariable, code.get());

    String extensionUrl = calculateVariableReferenceUrl(ccwVariable);
    Extension extension = new Extension(extensionUrl, coding);

    return extension;
  }

  /**
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link Extension}
   *     will be contained in
   * @param ccwVariable the {@link CcwCodebookInterface} being coded
   * @param code the value to use for {@link Coding#getCode()} for the resulting {@link Coding}
   * @return the output {@link Extension}, with {@link Extension#getValue()} set to a new {@link
   *     Coding} to represent the specified input values
   */
  static Extension createExtensionCoding(
      IAnyResource rootResource,
      CcwCodebookInterface ccwVariable,
      String yearMonth,
      Optional<?> code) {
    if (!code.isPresent()) throw new IllegalArgumentException();

    Coding coding = createCoding(rootResource, ccwVariable, yearMonth, code.get());

    String extensionUrl =
        String.format("%s/%s", calculateVariableReferenceUrl(ccwVariable), yearMonth);
    Extension extension = new Extension(extensionUrl, coding);

    return extension;
  }

  /**
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link Extension}
   *     will be contained in
   * @param ccwVariable the {@link CcwCodebookInterface} being coded
   * @param code the value to use for {@link Coding#getCode()} for the resulting {@link Coding}
   * @return the output {@link Extension}, with {@link Extension#getValue()} set to a new {@link
   *     Coding} to represent the specified input values
   */
  static Extension createExtensionCoding(
      IAnyResource rootResource, CcwCodebookInterface ccwVariable, Object code) {
    // Jumping through hoops to cope with overloaded method:
    Optional<?> codeOptional = code instanceof Optional ? (Optional<?>) code : Optional.of(code);
    return createExtensionCoding(rootResource, ccwVariable, codeOptional);
  }

  /**
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link
   *     CodeableConcept} will be contained in
   * @param ccwVariable the {@link CcwCodebookInterface} being coded
   * @param code the value to use for {@link Coding#getCode()} for the resulting (single) {@link
   *     Coding}, wrapped within the resulting {@link CodeableConcept}
   * @return the output {@link CodeableConcept} for the specified input values
   */
  static CodeableConcept createCodeableConcept(
      IAnyResource rootResource, CcwCodebookInterface ccwVariable, Optional<?> code) {
    if (!code.isPresent()) {
      throw new IllegalArgumentException();
    }

    Coding coding = createCoding(rootResource, ccwVariable, code.get());

    CodeableConcept concept = new CodeableConcept();
    concept.addCoding(coding);

    return concept;
  }

  /**
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link
   *     CodeableConcept} will be contained in
   * @param ccwVariable the {@link CcwCodebookInterface} being coded
   * @param code the value to use for {@link Coding#getCode()} for the resulting (single) {@link
   *     Coding}, wrapped within the resulting {@link CodeableConcept}
   * @return the output {@link CodeableConcept} for the specified input values
   */
  static CodeableConcept createCodeableConcept(
      IAnyResource rootResource, CcwCodebookInterface ccwVariable, Object code) {
    // Jumping through hoops to cope with overloaded method:
    Optional<?> codeOptional = code instanceof Optional ? (Optional<?>) code : Optional.of(code);
    return createCodeableConcept(rootResource, ccwVariable, codeOptional);
  }

  /**
   * Unlike {@link #createCodeableConcept(IAnyResource, CcwCodebookInterface, Optional)}, this
   * method creates a {@link CodeableConcept} that's intended for use as a field ID/discriminator:
   * the {@link Variable#getId()} will be used for the {@link Coding#getCode()}, rather than the
   * {@link Coding#getSystem()}.
   *
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link
   *     CodeableConcept} will be contained in
   * @param codingSystem the {@link Coding#getSystem()} to use
   * @param ccwVariable the {@link CcwCodebookInterface} being coded
   * @return the output {@link CodeableConcept} for the specified input values
   */
  private static CodeableConcept createCodeableConceptForFieldId(
      IAnyResource rootResource, String codingSystem, CcwCodebookInterface ccwVariable) {
    String code = calculateVariableReferenceUrl(ccwVariable);

    Coding carinCoding =
        new Coding()
            .setCode("info")
            .setSystem(TransformerConstants.CARIN_SUPPORTING_INFO_TYPE)
            .setDisplay("Information");
    Coding cmsBBcoding = new Coding(codingSystem, code, ccwVariable.getVariable().getLabel());

    CodeableConcept categoryCodeableConcept = new CodeableConcept();
    categoryCodeableConcept.addCoding(carinCoding);
    categoryCodeableConcept.addCoding(cmsBBcoding);

    return categoryCodeableConcept;
  }

  /**
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link Coding} will
   *     be contained in
   * @param ccwVariable the {@link CcwCodebookInterface} being coded
   * @param code the value to use for {@link Coding#getCode()}
   * @return the output {@link Coding} for the specified input values
   */
  private static Coding createCoding(
      IAnyResource rootResource, CcwCodebookInterface ccwVariable, Object code) {
    /*
     * The code parameter is an Object to avoid needing multiple copies of this and related methods.
     * This if-else block is the price to be paid for that, though.
     */
    String codeString;
    if (code instanceof Character) codeString = ((Character) code).toString();
    else if (code instanceof String) codeString = code.toString().trim();
    else throw new BadCodeMonkeyException("Unsupported: " + code);

    String system = calculateVariableReferenceUrl(ccwVariable);

    String display;
    if (ccwVariable.getVariable().getValueGroups().isPresent())
      display = calculateCodingDisplay(rootResource, ccwVariable, codeString).orElse(null);
    else display = null;

    return new Coding(system, codeString, display);
  }

  /**
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link Coding} will
   *     be contained in
   * @param ccwVariable the {@link CcwCodebookInterface} being coded
   * @param code the value to use for {@link Coding#getCode()}
   * @return the output {@link Coding} for the specified input values
   */
  private static Coding createCoding(
      IAnyResource rootResource, CcwCodebookInterface ccwVariable, Optional<?> code) {
    return createCoding(rootResource, ccwVariable, code.get());
  }

  /**
   * @param ccwVariable the {@link CcwCodebookInterface} being mapped
   * @return the public URL at which documentation for the specified {@link CcwCodebookInterface} is
   *     published
   */
  static String calculateVariableReferenceUrl(CcwCodebookInterface ccwVariable) {
    return String.format(
        "%s/%s",
        TransformerConstants.BASE_URL_CCW_VARIABLES,
        ccwVariable.getVariable().getId().toLowerCase());
  }

  /**
   * @param ccwVariable the {@link CcwCodebookInterface} being mapped
   * @return the {@link AdjudicationComponent#getCategory()} {@link CodeableConcept} to use for the
   *     specified {@link CcwCodebookInterface}
   */
  static CodeableConcept createAdjudicationCategory(CcwCodebookInterface ccwVariable) {
    /*
     * Adjudication.category is mapped a bit differently than other Codings/CodeableConcepts: they
     * all share the same Coding.system and use the CcwCodebookInterface reference URL as their
     * Coding.code. This looks weird, but makes it easy for API developers to find more information
     * about what the specific adjudication they're looking at means.
     */

    String conceptCode = calculateVariableReferenceUrl(ccwVariable);
    CodeableConcept categoryConcept =
        createCodeableConcept(TransformerConstants.CODING_CCW_ADJUDICATION_CATEGORY, conceptCode);
    categoryConcept.getCodingFirstRep().setDisplay(ccwVariable.getVariable().getLabel());
    return categoryConcept;
  }

  /**
   * @param ccwVariable the {@link CcwCodebookInterface} being mapped
   * @return the {@link AdjudicationComponent#getCategory()} {@link CodeableConcept} to use for the
   *     specified {@link CcwCodebookVariable}
   */
  static CodeableConcept createAdjudicationCategory(
      CcwCodebookInterface ccwVariable, String carinAdjuCode, String carinAdjuCodeDisplay) {
    /*
     * Adjudication.category is mapped a bit differently than other
     * Codings/CodeableConcepts: they all share the same Coding.system and use the
     * CcwCodebookVariable reference URL as their Coding.code. This looks weird, but
     * makes it easy for API developers to find more information about what the
     * specific adjudication they're looking at means.
     */

    String conceptCode = calculateVariableReferenceUrl(ccwVariable);
    CodeableConcept categoryConcept =
        createCodeableConcept(TransformerConstants.CODING_CCW_ADJUDICATION_CATEGORY, conceptCode);
    categoryConcept.getCodingFirstRep().setDisplay(ccwVariable.getVariable().getLabel());

    categoryConcept
        .addCoding()
        .setSystem(TransformerConstants.CARIN_ADJUDICATION_CODE)
        .setCode(carinAdjuCode)
        .setDisplay(carinAdjuCodeDisplay);

    return categoryConcept;
  }

  /**
   * @param ccwVariable the {@link CcwCodebookVariable} being mapped
   * @return the {@link AdjudicationComponent#getCategory()} {@link CodeableConcept} to use for the
   *     specified {@link CcwCodebookVariable}
   */
  static CodeableConcept createAdjudicationCategory(
      CcwCodebookVariable ccwVariable, String carinAdjuCode, String carinAdjuCodeDisplay) {
    /*
     * Adjudication.category is mapped a bit differently than other Codings/CodeableConcepts: they
     * all share the same Coding.system and use the CcwCodebookVariable reference URL as their
     * Coding.code. This looks weird, but makes it easy for API developers to find more information
     * about what the specific adjudication they're looking at means.
     */

    String conceptCode = calculateVariableReferenceUrl(ccwVariable);
    CodeableConcept categoryConcept =
        createCodeableConcept(TransformerConstants.CODING_CCW_ADJUDICATION_CATEGORY, conceptCode);
    categoryConcept.getCodingFirstRep().setDisplay(ccwVariable.getVariable().getLabel());

    categoryConcept
        .addCoding()
        .setSystem(C4BBAdjudication.SUBMITTED.getSystem())
        .setCode(carinAdjuCode)
        .setDisplay(carinAdjuCodeDisplay);

    return categoryConcept;
  }

  /**
   * Optionally adds an {@link AdjudicationComponent} to an {@link ItemComponent#getAdjudication()}
   *
   * @param item {@link ItemComponent} to add the {@link AdjudicationComponent} to
   * @param adjudication Optional {@link AdjudicationComponent}
   */
  static void addAdjudication(ItemComponent item, Optional<AdjudicationComponent> adjudication) {
    if (adjudication.isPresent()) {
      item.addAdjudication(adjudication.get());
    }
  }

  /**
   * Optionally adds an {@link AdjudicationComponent} to an {@link
   * ExplanationOfBenefit#getAdjudication()}
   *
   * @param eob {@link ExplanationOfBenefit} to add the {@link AdjudicationComponent} to
   * @param adjudication Optional {@link AdjudicationComponent}
   */
  static void addAdjudication(
      ExplanationOfBenefit eob, Optional<AdjudicationComponent> adjudication) {
    if (adjudication.isPresent()) {
      eob.addAdjudication(adjudication.get());
    }
  }

  /**
   * Optionally adds an {@link TotalComponent} to an {@link ExplanationOfBenefit#getTotal()}
   *
   * @param eob {@link ExplanationOfBenefit} to add the {@link TotalComponent} to
   * @param total Optional {@link TotalComponent}
   */
  static void addTotal(ExplanationOfBenefit eob, Optional<TotalComponent> total) {
    if (total.isPresent()) {
      eob.addTotal(total.get());
    }
  }

  /**
   * Creates a C4BB Adjudication `adjudicationamounttype` {@link CodeableConcept} slice for use in
   * multiple places
   *
   * @param ccwVariable The CCW Variable that represents what the amount is
   * @param code The C4BBAdjudication code that represents this amount
   * @param amount A dollar amount
   * @return The created {@link AdjudicationComponent}
   */
  private static CodeableConcept createAdjudicationAmtSliceCategory(
      CcwCodebookInterface ccwVariable, C4BBAdjudication code) {
    return new CodeableConcept()
        // Indicate the required coding for CC4BB adjudicationamounttype slice
        .addCoding(new Coding(code.getSystem(), code.toCode(), code.getDisplay()))
        // Indicate the correct CCW variable
        .addCoding(
            new Coding(
                TransformerConstants.CODING_CCW_ADJUDICATION_CATEGORY,
                calculateVariableReferenceUrl(ccwVariable),
                ccwVariable.getVariable().getLabel()));
  }

  /**
   * Optionally Creates an `adjudicationamounttype` {@link AdjudicationComponent} slice
   *
   * @param ccwVariable The CCW Variable that represents what the amount is
   * @param code The C4BBAdjudication code that represents this amount
   * @param amount A dollar amount
   * @return The created {@link AdjudicationComponent}
   */
  static Optional<AdjudicationComponent> createAdjudicationAmtSlice(
      CcwCodebookInterface ccwVariable, C4BBAdjudication code, Optional<BigDecimal> amount) {
    return amount.map(
        amt ->
            new AdjudicationComponent()
                .setCategory(createAdjudicationAmtSliceCategory(ccwVariable, code))
                .setAmount(createMoney(amt)));
  }

  /**
   * Optionally Creates an `adjudicationamounttype` {@link AdjudicationComponent} slice
   *
   * @param ccwVariable The CCW Variable that represents what the amount is
   * @param cod The C4BBAdjudication code that represents this amount
   * @param amount A dollar amount
   * @return The created {@link AdjudicationComponent}
   */
  static Optional<AdjudicationComponent> createAdjudicationAmtSlice(
      CcwCodebookInterface ccwVariable, C4BBAdjudication code, BigDecimal amount) {
    return createAdjudicationAmtSlice(ccwVariable, code, Optional.of(amount));
  }

  /**
   * Optionally Creates an `denialreason` {@link AdjudicationComponent} slice
   *
   * @param eob The base {@link ExplanationOfBenefit} resource
   * @param ccwVariable The CCW Variable that represents what the reason is
   * @param reasonCode The coded denial reason
   * @return The created {@link AdjudicationComponent}
   */
  static Optional<AdjudicationComponent> createAdjudicationDenialReasonSlice(
      ExplanationOfBenefit eob, CcwCodebookInterface ccwVariable, Optional<String> reasonCode) {
    return reasonCode.map(
        reason ->
            new AdjudicationComponent()
                // Set category for `denialreason` slice
                .setCategory(
                    new CodeableConcept()
                        .setCoding(
                            Arrays.asList(
                                new Coding(
                                    C4BBAdjudicationDiscriminator.DENIAL_REASON.getSystem(),
                                    C4BBAdjudicationDiscriminator.DENIAL_REASON.toCode(),
                                    C4BBAdjudicationDiscriminator.DENIAL_REASON.getDisplay()))))
                // Set BB coding for Reason
                .setReason(createCodeableConcept(eob, ccwVariable, reason)));
  }

  /**
   * Optionally Creates an `denialreason` {@link AdjudicationComponent} slice
   *
   * @param eob The base {@link ExplanationOfBenefit} resource
   * @param ccwVariable The CCW Variable that represents what the reason is
   * @param reasonCode The coded denial reason
   * @return The created {@link AdjudicationComponent}
   */
  static Optional<AdjudicationComponent> createAdjudicationDenialReasonSlice(
      ExplanationOfBenefit eob, CcwCodebookInterface ccwVariable, String reasonCode) {
    return createAdjudicationDenialReasonSlice(eob, ccwVariable, Optional.of(reasonCode));
  }

  /**
   * Optionally Creates an `adjudicationamounttype` {@link TotalComponent} slice. This looks similar
   * to the code to generate the {@link AdjudicationComponent} slice of the same name, but
   * unfortunately can't be reused because they are different types.
   *
   * @param eob The base {@link ExplanationOfBenefit} resource
   * @param ccwVariable The CCW Variable that represents what the reason is
   * @param code The C4BBAdjudication code that represents this amount
   * @param amount A dollar amount
   * @return The created {@link TotalComponent}
   */
  static Optional<TotalComponent> createTotalAdjudicationAmountSlice(
      ExplanationOfBenefit eob,
      CcwCodebookInterface ccwVariable,
      C4BBAdjudication code,
      Optional<BigDecimal> amount) {
    return amount.map(
        amt ->
            new TotalComponent()
                .setCategory(createAdjudicationAmtSliceCategory(ccwVariable, code))
                .setAmount(createMoney(amount)));
  }

  /**
   * Optionally Creates an `adjudicationamounttype` {@link TotalComponent} slice. This looks similar
   * to the code to generate the {@link AdjudicationComponent} slice of the same name, but
   * unfortunately can't be reused because they are different types.
   *
   * @param eob The base {@link ExplanationOfBenefit} resource
   * @param ccwVariable The CCW Variable that represents what the reason is
   * @param code The C4BBAdjudication code that represents this amount
   * @param amount A dollar amount
   * @return The created {@link TotalComponent}
   */
  static Optional<TotalComponent> createTotalAdjudicationAmountSlice(
      ExplanationOfBenefit eob,
      CcwCodebookInterface ccwVariable,
      C4BBAdjudication code,
      BigDecimal amount) {
    return createTotalAdjudicationAmountSlice(eob, ccwVariable, code, Optional.of(amount));
  }

  /**
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link Coding} will
   *     be contained in
   * @param ccwVariable the {@link CcwCodebookInterface} being coded
   * @param code the FHIR {@link Coding#getCode()} value to determine a corresponding {@link
   *     Coding#getDisplay()} value for
   * @return the {@link Coding#getDisplay()} value to use for the specified {@link
   *     CcwCodebookInterface} and {@link Coding#getCode()}, or {@link Optional#empty()} if no
   *     matching display value could be determined
   */
  private static Optional<String> calculateCodingDisplay(
      IAnyResource rootResource, CcwCodebookInterface ccwVariable, String code) {
    if (rootResource == null) throw new IllegalArgumentException();
    if (ccwVariable == null) throw new IllegalArgumentException();
    if (code == null) throw new IllegalArgumentException();
    if (!ccwVariable.getVariable().getValueGroups().isPresent())
      throw new BadCodeMonkeyException("No display values for Variable: " + ccwVariable);

    /*
     * We know that the specified CCW Variable is coded, but there's no guarantee that the Coding's
     * code matches one of the known/allowed Variable values: data is messy. When that happens, we
     * log the event and return normally. The log event will at least allow for further
     * investigation, if warranted. Also, there's a chance that the CCW Variable data itself is
     * messy, and that the Coding's code matches more than one value -- we just log those events,
     * too.
     */
    List<Value> matchingVariableValues =
        ccwVariable.getVariable().getValueGroups().get().stream()
            .flatMap(g -> g.getValues().stream())
            .filter(v -> v.getCode().equals(code))
            .collect(Collectors.toList());
    if (matchingVariableValues.size() == 1) {
      return Optional.of(matchingVariableValues.get(0).getDescription());
    } else if (matchingVariableValues.isEmpty()) {
      if (!codebookLookupMissingFailures.contains(ccwVariable)) {
        // Note: The race condition here (from concurrent requests) is harmless.
        codebookLookupMissingFailures.add(ccwVariable);
        if (ccwVariable instanceof CcwCodebookVariable) {
          LOGGER.info(
              "No display value match found for {}.{} in resource '{}/{}'.",
              CcwCodebookVariable.class.getSimpleName(),
              ccwVariable.name(),
              rootResource.getClass().getSimpleName(),
              rootResource.getId());
        } else {
          LOGGER.info(
              "No display value match found for {}.{} in resource '{}/{}'.",
              CcwCodebookMissingVariable.class.getSimpleName(),
              ccwVariable.name(),
              rootResource.getClass().getSimpleName(),
              rootResource.getId());
        }
      }
      return Optional.empty();
    } else if (matchingVariableValues.size() > 1) {
      if (!codebookLookupDuplicateFailures.contains(ccwVariable)) {
        // Note: The race condition here (from concurrent requests) is harmless.
        codebookLookupDuplicateFailures.add(ccwVariable);
        if (ccwVariable instanceof CcwCodebookVariable) {
          LOGGER.info(
              "Multiple display value matches found for {}.{} in resource '{}/{}'.",
              CcwCodebookVariable.class.getSimpleName(),
              ccwVariable.name(),
              rootResource.getClass().getSimpleName(),
              rootResource.getId());
        } else {
          LOGGER.info(
              "Multiple display value matches found for {}.{} in resource '{}/{}'.",
              CcwCodebookMissingVariable.class.getSimpleName(),
              ccwVariable.name(),
              rootResource.getClass().getSimpleName(),
              rootResource.getId());
        }
      }
      return Optional.empty();
    } else {
      throw new BadCodeMonkeyException();
    }
  }

  /**
   * @param patientId the {@link #TransformerConstants.CODING_SYSTEM_CCW_BENE_ID} ID value for the
   *     beneficiary to match
   * @return a {@link Reference} to the {@link Patient} resource that matches the specified
   *     parameters
   */
  static Reference referencePatient(String patientId) {
    return new Reference(String.format("Patient/%s", patientId));
  }

  /**
   * @param beneficiary the {@link Beneficiary} to generate a {@link Patient} {@link Reference} for
   * @return a {@link Reference} to the {@link Patient} resource for the specified {@link
   *     Beneficiary}
   */
  static Reference referencePatient(Beneficiary beneficiary) {
    return referencePatient(beneficiary.getBeneficiaryId());
  }

  /**
   * @param practitionerNpi the {@link Practitioner#getIdentifier()} value to match (where {@link
   *     Identifier#getSystem()} is {@value #TransformerConstants.CODING_SYSTEM_NPI_US})
   * @return a {@link Reference} to the {@link Practitioner} resource that matches the specified
   *     parameters
   */
  static Reference referencePractitioner(String practitionerNpi) {
    return createIdentifierReference(TransformerConstants.CODING_NPI_US, practitionerNpi);
  }

  /**
   * @param period the {@link Period} to adjust
   * @param date the {@link LocalDate} to set the {@link Period#getEnd()} value with/to
   */
  static void setPeriodEnd(Period period, LocalDate date) {
    period.setEnd(
        Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()),
        TemporalPrecisionEnum.DAY);
  }

  /**
   * @param period the {@link Period} to adjust
   * @param date the {@link LocalDate} to set the {@link Period#getStart()} value with/to
   */
  static void setPeriodStart(Period period, LocalDate date) {
    period.setStart(
        Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()),
        TemporalPrecisionEnum.DAY);
  }

  /**
   * @param urlText the URL or URL portion to be encoded
   * @return a URL-encoded version of the specified text
   */
  static String urlEncode(String urlText) {
    try {
      return URLEncoder.encode(urlText, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      throw new BadCodeMonkeyException(e);
    }
  }

  /**
   * validate the from/thru dates to ensure the from date is before or the same as the thru date
   *
   * @param dateFrom start date {@link LocalDate}
   * @param dateThrough through date {@link LocalDate} to verify
   */
  static void validatePeriodDates(LocalDate dateFrom, LocalDate dateThrough) {
    if (dateFrom == null) return;
    if (dateThrough == null) return;
    // FIXME see CBBD-236 (ETL service fails on some Hospice claims "From
    // date is after the Through Date")
    // We are seeing this scenario in production where the from date is
    // after the through date so we are just logging the error for now.
    if (dateFrom.isAfter(dateThrough))
      LOGGER.debug(
          String.format(
              "Error - From Date '%s' is after the Through Date '%s'", dateFrom, dateThrough));
  }

  /**
   * validate the <Optional>from/<Optional>thru dates to ensure the from date is before or the same
   * as the thru date
   *
   * @param <Optional>dateFrom start date {@link <Optional>LocalDate}
   * @param <Optional>dateThrough through date {@link <Optional>LocalDate} to verify
   */
  static void validatePeriodDates(Optional<LocalDate> dateFrom, Optional<LocalDate> dateThrough) {
    if (!dateFrom.isPresent()) return;
    if (!dateThrough.isPresent()) return;
    validatePeriodDates(dateFrom.get(), dateThrough.get());
  }

  /**
   * Retrieves the Diagnosis display value from a Diagnosis code look up file
   *
   * @param icdCode - Diagnosis code
   */
  public static String retrieveIcdCodeDisplay(String icdCode) {

    if (icdCode.isEmpty()) {
      return null;
    }

    /*
     * There's a race condition here: we may initialize this static field more than once if multiple
     * requests come in at the same time. However, the assignment is atomic, so the race and
     * reinitialization is harmless other than maybe wasting a bit of time.
     */
    // read the entire ICD file the first time and put in a Map
    if (icdMap == null) {
      icdMap = readIcdCodeFile();
    }

    if (icdMap.containsKey(icdCode.toUpperCase())) {
      String icdCodeDisplay = icdMap.get(icdCode);
      return icdCodeDisplay;
    }

    // log which NDC codes we couldn't find a match for in our downloaded NDC file
    if (!drugCodeLookupMissingFailures.contains(icdCode)) {
      drugCodeLookupMissingFailures.add(icdCode);
      LOGGER.info(
          "No ICD code display value match found for ICD code {} in resource {}.",
          icdCode,
          "DGNS_CD.txt");
    }

    return null;
  }

  /**
   * Reads ALL the ICD codes and display values from the DGNS_CD.txt file. Refer to the README file
   * in the src/main/resources directory
   */
  private static Map<String, String> readIcdCodeFile() {
    Map<String, String> icdDiagnosisMap = new HashMap<String, String>();

    try (final InputStream icdCodeDisplayStream =
            Thread.currentThread().getContextClassLoader().getResourceAsStream("DGNS_CD.txt");
        final BufferedReader icdCodesIn =
            new BufferedReader(new InputStreamReader(icdCodeDisplayStream))) {
      /*
       * We want to extract the ICD Diagnosis codes and display values and put in a map for easy
       * retrieval to get the display value icdColumns[1] is DGNS_DESC(i.e. 7840 code is HEADACHE
       * description)
       */
      String line = "";
      icdCodesIn.readLine();
      while ((line = icdCodesIn.readLine()) != null) {
        String icdColumns[] = line.split("\t");
        icdDiagnosisMap.put(icdColumns[0], icdColumns[1]);
      }
      icdCodesIn.close();
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to read ICD code data.", e);
    }

    return icdDiagnosisMap;
  }

  /**
   * Retrieves the NPI display value from an NPI code look up file
   *
   * @param npiCode - NPI code
   */
  public static String retrieveNpiCodeDisplay(String npiCode) {

    if (npiCode.isEmpty()) return null;

    /*
     * There's a race condition here: we may initialize this static field more than once if multiple
     * requests come in at the same time. However, the assignment is atomic, so the race and
     * reinitialization is harmless other than maybe wasting a bit of time.
     */
    // read the entire NPI file the first time and put in a Map
    if (npiMap == null) {
      npiMap = readNpiCodeFile();
    }

    if (npiMap.containsKey(npiCode.toUpperCase())) {
      String npiCodeDisplay = npiMap.get(npiCode);
      return npiCodeDisplay;
    }

    // log which NPI codes we couldn't find a match for in our downloaded NPI file
    if (!npiCodeLookupMissingFailures.contains(npiCode)) {
      npiCodeLookupMissingFailures.add(npiCode);
      LOGGER.info(
          "No NPI code display value match found for NPI code {} in resource {}.",
          npiCode,
          "NPI_Coded_Display_Values_Tab.txt");
    }

    return null;
  }

  /**
   * Reads ALL the NPI codes and display values from the NPI_Coded_Display_Values_Tab.txt file.
   * Refer to the README file in the src/main/resources directory
   */
  private static Map<String, String> readNpiCodeFile() {

    Map<String, String> npiCodeMap = new HashMap<String, String>();
    try (final InputStream npiCodeDisplayStream =
            Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("NPI_Coded_Display_Values_Tab.txt");
        final BufferedReader npiCodesIn =
            new BufferedReader(new InputStreamReader(npiCodeDisplayStream))) {
      /*
       * We want to extract the NPI codes and display values and put in a map for easy retrieval to
       * get the display value-- npiColumns[0] is the NPI Code, npiColumns[4] is the NPI
       * Organization Code, npiColumns[8] is the NPI provider name prefix, npiColumns[6] is the NPI
       * provider first name, npiColumns[7] is the NPI provider middle name, npiColumns[5] is the
       * NPI provider last name, npiColumns[9] is the NPI provider suffix name, npiColumns[10] is
       * the NPI provider credential.
       */
      String line = "";
      npiCodesIn.readLine();
      while ((line = npiCodesIn.readLine()) != null) {
        String npiColumns[] = line.split("\t");
        if (npiColumns[4].isEmpty()) {
          String npiDisplayName =
              npiColumns[8].trim()
                  + " "
                  + npiColumns[6].trim()
                  + " "
                  + npiColumns[7].trim()
                  + " "
                  + npiColumns[5].trim()
                  + " "
                  + npiColumns[9].trim()
                  + " "
                  + npiColumns[10].trim();
          npiCodeMap.put(npiColumns[0], npiDisplayName.replace("  ", " ").trim());
        } else {
          npiCodeMap.put(npiColumns[0], npiColumns[4].replace("\"", "").trim());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to read NPI code data.", e);
    }
    return npiCodeMap;
  }

  /**
   * Retrieves the Procedure code and display value from a Procedure code look up file
   *
   * @param procedureCode - Procedure code
   */
  public static String retrieveProcedureCodeDisplay(String procedureCode) {

    if (procedureCode.isEmpty()) return null;

    /*
     * There's a race condition here: we may initialize this static field more than once if multiple
     * requests come in at the same time. However, the assignment is atomic, so the race and
     * reinitialization is harmless other than maybe wasting a bit of time.
     */
    // read the entire Procedure code file the first time and put in a Map
    if (procedureMap == null) {
      procedureMap = readProcedureCodeFile();
    }

    if (procedureMap.containsKey(procedureCode.toUpperCase())) {
      String procedureCodeDisplay = procedureMap.get(procedureCode);
      return procedureCodeDisplay;
    }

    // log which Procedure codes we couldn't find a match for in our procedure codes
    // file
    if (!procedureLookupMissingFailures.contains(procedureCode)) {
      procedureLookupMissingFailures.add(procedureCode);
      LOGGER.info(
          "No procedure code display value match found for procedure code {} in resource {}.",
          procedureCode,
          "PRCDR_CD.txt");
    }

    return null;
  }

  /**
   * Reads all the procedure codes and display values from the PRCDR_CD.txt file Refer to the README
   * file in the src/main/resources directory
   */
  private static Map<String, String> readProcedureCodeFile() {

    Map<String, String> procedureCodeMap = new HashMap<String, String>();
    try (final InputStream procedureCodeDisplayStream =
            Thread.currentThread().getContextClassLoader().getResourceAsStream("PRCDR_CD.txt");
        final BufferedReader procedureCodesIn =
            new BufferedReader(new InputStreamReader(procedureCodeDisplayStream))) {
      /*
       * We want to extract the procedure codes and display values and put in a map for easy
       * retrieval to get the display value icdColumns[0] is PRCDR_CD; icdColumns[1] is
       * PRCDR_DESC(i.e. 8295 is INJECT TENDON OF HAND description)
       */
      String line = "";
      procedureCodesIn.readLine();
      while ((line = procedureCodesIn.readLine()) != null) {
        String icdColumns[] = line.split("\t");
        procedureCodeMap.put(icdColumns[0], icdColumns[1]);
      }
      procedureCodesIn.close();
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to read Procedure code data.", e);
    }

    return procedureCodeMap;
  }

  /**
   * Retrieves the PRODUCTNDC and SUBSTANCENAME from the FDA NDC Products file which was downloaded
   * during the build process
   *
   * @param claimDrugCode - NDC value in claim records
   */
  public static String retrieveFDADrugCodeDisplay(String claimDrugCode) {

    /*
     * Handle bad data (e.g. our random test data) if drug code is empty or length is less than 9
     * characters
     */
    if (claimDrugCode.isEmpty() || claimDrugCode.length() < 9) {
      return null;
    }

    /*
     * There's a race condition here: we may initialize this static field more than once if multiple
     * requests come in at the same time. However, the assignment is atomic, so the race and
     * reinitialization is harmless other than maybe wasting a bit of time.
     */
    // read the entire NDC file the first time and put in a Map
    if (ndcProductMap == null) {
      ndcProductMap = readFDADrugCodeFile();
    }

    String claimDrugCodeReformatted = null;

    claimDrugCodeReformatted = claimDrugCode.substring(0, 5) + "-" + claimDrugCode.substring(5, 9);

    if (ndcProductMap.containsKey(claimDrugCodeReformatted)) {
      String ndcSubstanceName = ndcProductMap.get(claimDrugCodeReformatted);
      return ndcSubstanceName;
    }

    // log which NDC codes we couldn't find a match for in our downloaded NDC file
    if (!drugCodeLookupMissingFailures.contains(claimDrugCode)) {
      drugCodeLookupMissingFailures.add(claimDrugCode);
      LOGGER.info(
          "No national drug code value (PRODUCTNDC column) match found for drug code {} in resource {}.",
          claimDrugCode,
          "fda_products_utf8.tsv");
    }

    return null;
  }

  /**
   * Reads all the <code>PRODUCTNDC</code> and <code>SUBSTANCENAME</code> fields from the FDA NDC
   * Products file which was downloaded during the build process.
   *
   * <p>See {@link FDADrugDataUtilityApp} for details.
   */
  public static Map<String, String> readFDADrugCodeFile() {
    Map<String, String> ndcProductHashMap = new HashMap<String, String>();
    try (final InputStream ndcProductStream =
            Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(FDADrugDataUtilityApp.FDA_PRODUCTS_RESOURCE);
        final BufferedReader ndcProductsIn =
            new BufferedReader(new InputStreamReader(ndcProductStream))) {
      /*
       * We want to extract the PRODUCTNDC and PROPRIETARYNAME/SUBSTANCENAME from the FDA Products
       * file (fda_products_utf8.tsv is in /target/classes directory) and put in a Map for easy
       * retrieval to get the display value which is a combination of PROPRIETARYNAME &
       * SUBSTANCENAME
       */
      String line = "";
      ndcProductsIn.readLine();
      while ((line = ndcProductsIn.readLine()) != null) {
        String ndcProductColumns[] = line.split("\t");
        String nationalDrugCodeManufacturer =
            StringUtils.leftPad(
                ndcProductColumns[1].substring(0, ndcProductColumns[1].indexOf("-")), 5, '0');
        String nationalDrugCodeIngredient =
            StringUtils.leftPad(
                ndcProductColumns[1].substring(
                    ndcProductColumns[1].indexOf("-") + 1, ndcProductColumns[1].length()),
                4,
                '0');
        // ndcProductColumns[3] - Proprietary Name
        // ndcProductColumns[13] - Substance Name
        ndcProductHashMap.put(
            String.format("%s-%s", nationalDrugCodeManufacturer, nationalDrugCodeIngredient),
            ndcProductColumns[3] + " - " + ndcProductColumns[13]);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to read NDC code data.", e);
    }
    return ndcProductHashMap;
  }

  /**
   * Create a bundle from the entire search result
   *
   * @param paging contains the {@link OffsetLinkBuilder} information
   * @param resources a list of {@link ExplanationOfBenefit}s, {@link Coverage}s, or {@link
   *     Patient}s, of which a portion or all will be added to the bundle based on the paging values
   * @param transactionTime date for the bundle
   * @return Returns a {@link Bundle} of either {@link ExplanationOfBenefit}s, {@link Coverage}s, or
   *     {@link Patient}s, which may contain multiple matching resources, or may also be empty.
   */
  public static Bundle createBundle(
      OffsetLinkBuilder paging, List<IBaseResource> resources, Date transactionTime) {
    Bundle bundle = new Bundle();
    if (paging.isPagingRequested()) {
      /*
       * FIXME: Due to a bug in HAPI-FHIR described here
       * https://github.com/jamesagnew/hapi-fhir/issues/1074 paging for count=0 is not working
       * correctly.
       */
      int endIndex = Math.min(paging.getStartIndex() + paging.getPageSize(), resources.size());
      List<IBaseResource> resourcesSubList = resources.subList(paging.getStartIndex(), endIndex);
      bundle = TransformerUtilsV2.addResourcesToBundle(bundle, resourcesSubList);
      paging.setTotal(resources.size()).addLinks(bundle);
    } else {
      bundle = TransformerUtilsV2.addResourcesToBundle(bundle, resources);
    }

    /*
     * Dev Note: the Bundle's lastUpdated timestamp is the known last update time for the whole
     * database. Because the filterManager's tracking of this timestamp is lazily updated for
     * performance reason, the resources of the bundle may be after the filter manager's version of
     * the timestamp.
     */
    Date maxBundleDate =
        resources.stream()
            .map(r -> r.getMeta().getLastUpdated())
            .filter(Objects::nonNull)
            .max(Date::compareTo)
            .orElse(transactionTime);
    bundle
        .getMeta()
        .setLastUpdated(transactionTime.after(maxBundleDate) ? transactionTime : maxBundleDate);
    bundle.setTotal(resources.size());
    return bundle;
  }

  /**
   * Create a bundle from the entire search result
   *
   * @param resources a list of {@link ExplanationOfBenefit}s, {@link Coverage}s, or {@link
   *     Patient}s, all of which will be added to the bundle
   * @param paging contains the {@link LinkBuilder} information to add to the bundle
   * @param transactionTime date for the bundle
   * @return Returns a {@link Bundle} of either {@link ExplanationOfBenefit}s, {@link Coverage}s, or
   *     {@link Patient}s, which may contain multiple matching resources, or may also be empty.
   */
  public static Bundle createBundle(
      List<IBaseResource> resources, LinkBuilder paging, Date transactionTime) {
    Bundle bundle = new Bundle();
    TransformerUtilsV2.addResourcesToBundle(bundle, resources);
    paging.addLinks(bundle);
    bundle.setTotalElement(
        paging.isPagingRequested() ? new UnsignedIntType() : new UnsignedIntType(resources.size()));

    /*
     * Dev Note: the Bundle's lastUpdated timestamp is the known last update time for the whole
     * database. Because the filterManager's tracking of this timestamp is lazily updated for
     * performance reason, the resources of the bundle may be after the filter manager's version of
     * the timestamp.
     */
    Date maxBundleDate =
        resources.stream()
            .map(r -> r.getMeta().getLastUpdated())
            .filter(Objects::nonNull)
            .max(Date::compareTo)
            .orElse(transactionTime);
    bundle
        .getMeta()
        .setLastUpdated(transactionTime.after(maxBundleDate) ? transactionTime : maxBundleDate);
    return bundle;
  }

  /**
   * @param bundle a {@link Bundle} to add the list of {@link ExplanationOfBenefit} resources to.
   * @param resources a list of either {@link ExplanationOfBenefit}s, {@link Coverage}s, or {@link
   *     Patient}s, of which a portion will be added to the bundle based on the paging values
   * @return Returns a {@link Bundle} of {@link ExplanationOfBenefit}s, {@link Coverage}s, or {@link
   *     Patient}s, which may contain multiple matching resources, or may also be empty.
   */
  public static Bundle addResourcesToBundle(Bundle bundle, List<IBaseResource> resources) {
    for (IBaseResource res : resources) {
      BundleEntryComponent entry = bundle.addEntry();
      entry.setResource((Resource) res);
    }
    return bundle;
  }

  /**
   * @param currencyIdentifier the {@link CurrencyIdentifier} indicating the currency of an {@link
   *     Identifier}.
   * @return Returns an {@link Extension} describing the currency of an {@link Identifier}.
   */
  public static Extension createIdentifierCurrencyExtension(CurrencyIdentifier currencyIdentifier) {
    String system = TransformerConstants.CODING_SYSTEM_IDENTIFIER_CURRENCY;
    String code = "historic";
    String display = "Historic";
    if (currencyIdentifier.equals(CurrencyIdentifier.CURRENT)) {
      code = "current";
      display = "Current";
    }

    Coding currentValueCoding = new Coding(system, code, display);
    Extension currencyIdentifierExtension =
        new Extension(TransformerConstants.CODING_SYSTEM_IDENTIFIER_CURRENCY, currentValueCoding);

    return currencyIdentifierExtension;
  }

  /**
   * Records the JPA query details in {@link MDC}.
   *
   * @param queryId an ID that identifies the type of JPA query being run, e.g. "bene_by_id"
   * @param queryDurationNanoseconds the JPA query's duration, in nanoseconds
   * @param recordCount the number of top-level records (e.g. JPA entities) returned by the query
   */
  public static void recordQueryInMdc(
      String queryId, long queryDurationNanoseconds, long recordCount) {
    String keyPrefix = String.format("jpa_query.%s", queryId);
    MDC.put(
        String.format("%s.duration_nanoseconds", keyPrefix),
        Long.toString(queryDurationNanoseconds));
    MDC.put(
        String.format("%s.duration_milliseconds", keyPrefix),
        Long.toString(queryDurationNanoseconds / 1000000));
    MDC.put(String.format("%s.record_count", keyPrefix), Long.toString(recordCount));
  }

  /**
   * Sets the lastUpdated value in the resource.
   *
   * @param resource is the FHIR resource to set lastUpdate
   * @param lastUpdated is the lastUpdated value set. If not present, set the fallback lastUpdated.
   */
  public static void setLastUpdated(IAnyResource resource, Optional<Date> lastUpdated) {
    resource
        .getMeta()
        .setLastUpdated(lastUpdated.orElse(TransformerConstants.FALLBACK_LAST_UPDATED));
  }

  /**
   * Sets the lastUpdated value in the resource if the passed in value is later than the current
   * value.
   *
   * @param resource is the FHIR resource to update
   * @param lastUpdated is the lastUpdated value from the entity
   */
  public static void updateMaxLastUpdated(IAnyResource resource, Optional<Date> lastUpdated) {
    lastUpdated.ifPresent(
        newDate -> {
          Date currentDate = resource.getMeta().getLastUpdated();
          if (currentDate != null && newDate.after(currentDate)) {
            resource.getMeta().setLastUpdated(newDate);
          }
        });
  }

  /**
   * Work around for https://github.com/jamesagnew/hapi-fhir/issues/1585. HAPI will fill in the
   * resource count as a total value when a Bundle has no total value.
   *
   * @param requestDetails of a resource provider
   */
  public static void workAroundHAPIIssue1585(RequestDetails requestDetails) {
    // The hack is to remove the _count parameter from theDetails so that total is not modified.
    Map<String, String[]> params = new HashMap<String, String[]>(requestDetails.getParameters());
    if (params.remove(Constants.PARAM_COUNT) != null) {
      // Remove _count parameter from the current request details
      requestDetails.setParameters(params);
    }
  }

  /**
   * @param beneficiaryPatientId the {@link #TransformerConstants.CODING_SYSTEM_CCW_BENE_ID} ID
   *     value for the {@link Coverage#getBeneficiary()} value to match
   * @param coverageType the {@link MedicareSegment} value to match
   * @return a {@link Reference} to the {@link Coverage} resource where {@link Coverage#getPlan()}
   *     matches {@link #COVERAGE_PLAN} and the other parameters specified also match
   */
  static Reference referenceCoverage(String beneficiaryPatientId, MedicareSegment coverageType) {
    return new Reference(buildCoverageId(coverageType, beneficiaryPatientId));
  }

  /**
   * @param medicareSegment the {@link MedicareSegment} to compute a {@link Coverage#getId()} for
   * @param beneficiary the {@link Beneficiary} to compute a {@link Coverage#getId()} for
   * @return the {@link Coverage#getId()} value to use for the specified values
   */
  public static IdDt buildCoverageId(MedicareSegment medicareSegment, Beneficiary beneficiary) {
    return buildCoverageId(medicareSegment, beneficiary.getBeneficiaryId());
  }

  /**
   * @param medicareSegment the {@link MedicareSegment} to compute a {@link Coverage#getId()} for
   * @param beneficiaryId the {@link Beneficiary#getBeneficiaryId()} value to compute a {@link
   *     Coverage#getId()} for
   * @return the {@link Coverage#getId()} value to use for the specified values
   */
  public static IdDt buildCoverageId(MedicareSegment medicareSegment, String beneficiaryId) {
    return new IdDt(
        Coverage.class.getSimpleName(),
        String.format("%s-%s", medicareSegment.getUrlPrefix(), beneficiaryId));
  }

  /**
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link Coding} will
   *     be contained in
   * @param ccwVariable the {@link CcwCodebookVariable} being coded
   * @param yearMonth the value to use for {@link String} for yearMonth
   * @param code the value to use for {@link Coding#getCode()}
   * @return the output {@link Coding} for the specified input values
   */
  private static Coding createCoding(
      IAnyResource rootResource, CcwCodebookInterface ccwVariable, String yearMonth, Object code) {
    /*
     * The code parameter is an Object to avoid needing multiple copies of this and related methods.
     * This if-else block is the price to be paid for that, though.
     */
    String codeString;
    if (code instanceof Character) codeString = ((Character) code).toString();
    else if (code instanceof String) codeString = code.toString().trim();
    else throw new BadCodeMonkeyException("Unsupported: " + code);

    String system = calculateVariableReferenceUrl(ccwVariable);

    String display;
    if (ccwVariable.getVariable().getValueGroups().isPresent())
      display = calculateCodingDisplay(rootResource, ccwVariable, codeString).orElse(null);
    else display = null;

    return new Coding(system, codeString, display);
  }
  /**
   * @param eob the {@link ExplanationOfBenefit} to extract the claim type from
   * @return the {@link ClaimType}
   */
  static ClaimType getClaimType(ExplanationOfBenefit eob) {
    String type =
        eob.getType().getCoding().stream()
            .filter(c -> c.getSystem().equals(TransformerConstants.CODING_SYSTEM_BBAPI_EOB_TYPE))
            .findFirst()
            .get()
            .getCode();
    return ClaimType.valueOf(type);
  }

  /**
   * Transforms the common group level header fields between all claim types
   *
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @param claimId CLM_ID
   * @param beneficiaryId BENE_ID
   * @param claimType {@link ClaimType} to process
   * @param claimGroupId CLM_GRP_ID
   * @param coverageType {@link MedicareSegment}
   * @param dateFrom CLM_FROM_DT
   * @param dateThrough CLM_THRU_DT
   * @param paymentAmount CLM_PMT_AMT
   * @param finalAction FINAL_ACTION
   */
  static void mapEobCommonClaimHeaderData(
      ExplanationOfBenefit eob,
      String claimId,
      String beneficiaryId,
      ClaimType claimType,
      String claimGroupId,
      MedicareSegment coverageType,
      Optional<LocalDate> dateFrom,
      Optional<LocalDate> dateThrough,
      Optional<BigDecimal> paymentAmount,
      char finalAction) {

    // Claim Type + Claim ID => ExplanationOfBenefit.id
    eob.setId(buildEobId(claimType, claimId));

    if (claimType.equals(ClaimType.PDE)) {
      // PDE_ID => ExplanationOfBenefit.identifier
      eob.addIdentifier(createClaimIdentifier(CcwCodebookVariable.PDE_ID, claimId));
    } else {
      // CLM_ID => ExplanationOfBenefit.identifier
      eob.addIdentifier(createClaimIdentifier(CcwCodebookVariable.CLM_ID, claimId));
    }

    // CLM_GRP_ID => ExplanationOfBenefit.identifier
    eob.addIdentifier()
        .setSystem(TransformerConstants.IDENTIFIER_SYSTEM_BBAPI_CLAIM_GROUP_ID)
        .setValue(claimGroupId)
        .setType(createC4BBClaimCodeableConcept());

    // BENE_ID + Coverage Type => ExplanationOfBenefit.insurance.coverage (ref)
    eob.addInsurance().setCoverage(referenceCoverage(beneficiaryId, coverageType));

    // BENE_ID => ExplanationOfBenefit.patient (reference)
    eob.setPatient(referencePatient(beneficiaryId));

    // FINAL_ACTION => ExplanationOfBenefit.status
    switch (finalAction) {
      case 'F':
        eob.setStatus(ExplanationOfBenefitStatus.ACTIVE);
        break;

      case 'N':
        eob.setStatus(ExplanationOfBenefitStatus.CANCELLED);
        break;

      default:
        // unknown final action value
        throw new BadCodeMonkeyException();
    }

    // CLM_FROM_DT => ExplanationOfBenefit.billablePeriod.start
    // CLM_THRU_DT => ExplanationOfBenefit.billablePeriod.end
    if (dateFrom.isPresent()) {
      validatePeriodDates(dateFrom, dateThrough);
      setPeriodStart(eob.getBillablePeriod(), dateFrom.get());
      setPeriodEnd(eob.getBillablePeriod(), dateThrough.get());
    }

    // CLM_PMT_AMT => ExplanationOfBenefit.payment.amount
    if (paymentAmount.isPresent()) {
      eob.getPayment().setAmount(createMoney(paymentAmount));
    }
  }

  /**
   * @param amountValue the value to use for {@link Money#getValue()}
   * @return a new {@link Money} instance, with the specified {@link Money#getValue()}
   */
  static Money createMoney(Optional<? extends Number> amountValue) {
    if (!amountValue.isPresent()) {
      throw new IllegalArgumentException();
    }

    Money money = new Money().setCurrency(TransformerConstants.CODED_MONEY_USD);

    if (amountValue.get() instanceof BigDecimal) {
      money.setValue((BigDecimal) amountValue.get());
    } else {
      throw new BadCodeMonkeyException();
    }

    return money;
  }

  /**
   * @param amountValue the value to use for {@link Money#getValue()}
   * @return a new {@link Money} instance, with the specified {@link Money#getValue()}
   */
  static Money createMoney(Number amountValue) {
    return createMoney(Optional.of(amountValue));
  }

  /**
   * Ensures that the specified {@link ExplanationOfBenefit} has the specified {@link
   * CareTeamComponent}, and links the specified {@link ItemComponent} to that {@link
   * CareTeamComponent} (via {@link ItemComponent#addCareTeamLinkId(int)}).
   *
   * @param eob the {@link ExplanationOfBenefit} that the {@link CareTeamComponent} should be part
   *     of
   * @param eobItem the {@link ItemComponent} that should be linked to the {@link CareTeamComponent}
   * @param practitionerIdSystem the {@link Identifier#getSystem()} of the practitioner to reference
   *     in {@link CareTeamComponent#getProvider()}
   * @param practitionerIdValue the {@link Identifier#getValue()} of the practitioner to reference
   *     in {@link CareTeamComponent#getProvider()}
   * @param careTeamRole the {@link ClaimCareteamrole} to use for the {@link
   *     CareTeamComponent#getRole()}
   * @return the {@link CareTeamComponent} that was created/linked
   */
  static CareTeamComponent addCareTeamPractitioner(
      ExplanationOfBenefit eob,
      ItemComponent eobItem,
      C4BBPractitionerIdentifierType type,
      String practitionerIdValue,
      C4BBClaimInstitutionalCareTeamRole careTeamRole) {
    // Try to find a matching pre-existing entry.
    CareTeamComponent careTeamEntry =
        eob.getCareTeam().stream()
            .filter(ctc -> ctc.getProvider().hasIdentifier())
            .filter(
                ctc ->
                    type.getSystem().equals(ctc.getProvider().getIdentifier().getSystem())
                        && practitionerIdValue.equals(ctc.getProvider().getIdentifier().getValue()))
            .filter(ctc -> ctc.hasRole())
            .filter(
                ctc ->
                    careTeamRole.toCode().equals(ctc.getRole().getCodingFirstRep().getCode())
                        && careTeamRole
                            .getSystem()
                            .equals(ctc.getRole().getCodingFirstRep().getSystem()))
            .findAny()
            .orElse(null);

    // If no match was found, add one to the EOB.
    // <ID Value> => ExplanationOfBenefit.careTeam.provider
    if (careTeamEntry == null) {
      careTeamEntry = eob.addCareTeam();
      // addItem adds and returns, so we want size() not size() + 1 here
      careTeamEntry.setSequence(eob.getCareTeam().size());
      careTeamEntry.setProvider(createPractitionerIdentifierReference(type, practitionerIdValue));

      CodeableConcept careTeamRoleConcept =
          createCodeableConcept(careTeamRole.getSystem(), careTeamRole.toCode());
      careTeamRoleConcept.getCodingFirstRep().setDisplay(careTeamRole.getDisplay());
      careTeamEntry.setRole(careTeamRoleConcept);
    }

    // care team entry is at eob level so no need to create item link id
    if (eobItem == null) {
      return careTeamEntry;
    }

    // ExplanationOfBenefit.careTeam.sequence => ExplanationOfBenefit.item.careTeamSequence
    if (!eobItem.getCareTeamSequence().contains(new PositiveIntType(careTeamEntry.getSequence()))) {
      eobItem.addCareTeamSequence(careTeamEntry.getSequence());
    }

    return careTeamEntry;
  }

  /**
   * Returns a new {@link SupportingInformationComponent} that has been added to the specified
   * {@link ExplanationOfBenefit}. Unlike {@link #addInformation(ExplanationOfBenefit,
   * CcwCodebookVariable)}, this also sets the {@link SupportingInformationComponent#getCode()}
   * based on the values provided.
   *
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @param categoryVariable {@link CcwCodebookVariable} to map to {@link
   *     SupportingInformationComponent#getCategory()}
   * @param codeSystemVariable the {@link CcwCodebookVariable} to map to the {@link
   *     Coding#getSystem()} used in the {@link SupportingInformationComponent#getCode()}
   * @param codeValue the value to map to the {@link Coding#getCode()} used in the {@link
   *     SupportingInformationComponent#getCode()}
   * @return the newly-added {@link SupportingInformationComponent} entry
   */
  static SupportingInformationComponent addInformationWithCode(
      ExplanationOfBenefit eob,
      CcwCodebookVariable categoryVariable,
      CcwCodebookVariable codeSystemVariable,
      Optional<?> codeValue) {
    SupportingInformationComponent infoComponent = addInformation(eob, categoryVariable);

    CodeableConcept infoCode =
        new CodeableConcept().addCoding(createCoding(eob, codeSystemVariable, codeValue));
    infoComponent.setCode(infoCode);

    return infoComponent;
  }

  static Optional<SupportingInformationComponent> addInformationSliceWithCode(
      ExplanationOfBenefit eob,
      C4BBSupportingInfoType slice,
      CcwCodebookVariable categoryVariable,
      CcwCodebookVariable codeSystemVariable,
      Optional<?> codeValue) {
    if (codeValue.isPresent()) {
      SupportingInformationComponent infoComponent =
          addInformationSlice(eob, slice, categoryVariable);

      CodeableConcept infoCode =
          new CodeableConcept().addCoding(createCoding(eob, codeSystemVariable, codeValue));
      infoComponent.setCode(infoCode);

      return Optional.of(infoComponent);
    } else {
      return Optional.empty();
    }
  }

  static SupportingInformationComponent addInformationSliceWithCode(
      ExplanationOfBenefit eob,
      C4BBSupportingInfoType slice,
      CcwCodebookVariable categoryVariable,
      CcwCodebookVariable codeSystemVariable,
      Object codeValue) {
    // Must get a valid value passed in
    if (codeValue == null) {
      throw new BadCodeMonkeyException();
    }

    return addInformationSliceWithCode(
            eob, slice, categoryVariable, codeSystemVariable, Optional.of(codeValue))
        // Since we are passing in a valid value, we will get a response
        .get();
  }

  /**
   * Returns a new {@link SupportingInformationComponent} that has been added to the specified
   * {@link ExplanationOfBenefit}. Unlike {@link #addInformation(ExplanationOfBenefit,
   * CcwCodebookVariable)}, this also sets the {@link SupportingInformationComponent#getCode()}
   * based on the values provided.
   *
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @param categoryVariable {@link CcwCodebookVariable} to map to {@link
   *     SupportingInformationComponent#getCategory()}
   * @param codeSystemVariable the {@link CcwCodebookVariable} to map to the {@link
   *     Coding#getSystem()} used in the {@link SupportingInformationComponent#getCode()}
   * @param date the value to map to the {@link SupportingInformationComponent#getTiming()}
   * @return the newly-added {@link SupportingInformationComponent} entry
   */
  static SupportingInformationComponent addInformationWithDate(
      ExplanationOfBenefit eob,
      CcwCodebookVariable categoryVariable,
      CcwCodebookVariable codeSystemVariable,
      Optional<LocalDate> date) {
    SupportingInformationComponent infoComponent = addInformation(eob, categoryVariable);

    if (!date.isPresent()) {
      throw new BadCodeMonkeyException();
    }

    return infoComponent.setTiming(new DateType(convertToDate(date.get())));
  }

  /**
   * Returns a new {@link SupportingInformationComponent} that has been added to the specified
   * {@link ExplanationOfBenefit}. Unlike {@link #addInformation(ExplanationOfBenefit,
   * CcwCodebookVariable)}, this also sets the {@link SupportingInformationComponent#getCode()}
   * based on the values provided.
   *
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @param categoryVariable {@link CcwCodebookVariable} to map to {@link
   *     SupportingInformationComponent#getCategory()}
   * @param codeSystemVariable the {@link CcwCodebookVariable} to map to the {@link
   *     Coding#getSystem()} used in the {@link SupportingInformationComponent#getCode()}
   * @param codeValue the value to map to the {@link Coding#getCode()} used in the {@link
   *     SupportingInformationComponent#getCode()}
   * @return the newly-added {@link SupportingInformationComponent} entry
   */
  static SupportingInformationComponent addInformationWithCode(
      ExplanationOfBenefit eob,
      CcwCodebookVariable categoryVariable,
      CcwCodebookVariable codeSystemVariable,
      Object codeValue) {
    return addInformationWithCode(
        eob, categoryVariable, codeSystemVariable, Optional.of(codeValue));
  }

  static SupportingInformationComponent addInformationSlice(
      ExplanationOfBenefit eob, C4BBSupportingInfoType slice, Object value) {
    return addInformation(eob)
        .setCategory(new CodeableConcept().addCoding(createC4BBSupportingInfoCoding(slice)));
  }

  /**
   * Returns a new {@link SupportingInformationComponent} that has been added to the specified
   * {@link ExplanationOfBenefit}.
   *
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @param categoryVariable {@link CcwCodebookVariable} to map to {@link
   *     SupportingInformationComponent#getCategory()}
   * @return the newly-added {@link SupportingInformationComponent} entry
   */
  static SupportingInformationComponent addInformation(
      ExplanationOfBenefit eob, CcwCodebookVariable categoryVariable) {
    return addInformation(eob)
        .setCategory(
            createCodeableConceptForFieldId(
                eob, TransformerConstants.CODING_BBAPI_INFORMATION_CATEGORY, categoryVariable));
  }

  /**
   * Returns a new {@link SupportingInformationComponent} that has been added to the specified
   * {@link ExplanationOfBenefit}.
   *
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @return the newly-added {@link SupportingInformationComponent} entry
   */
  static SupportingInformationComponent addInformation(ExplanationOfBenefit eob) {
    int maxSequence =
        eob.getSupportingInfo().stream().mapToInt(i -> i.getSequence()).max().orElse(0);

    SupportingInformationComponent infoComponent = new SupportingInformationComponent();
    infoComponent.setSequence(maxSequence + 1);
    eob.getSupportingInfo().add(infoComponent);

    return infoComponent;
  }

  /**
   * @param claimType the {@link ClaimType} to compute an {@link ExplanationOfBenefit#getId()} for
   * @param claimId the <code>claimId</code> field value (e.g. from {@link
   *     CarrierClaim#getClaimId()}) to compute an {@link ExplanationOfBenefit#getId()} for
   * @return the {@link ExplanationOfBenefit#getId()} value to use for the specified <code>claimId
   *     </code> value
   */
  public static String buildEobId(ClaimType claimType, String claimId) {
    return String.format("%s-%s", claimType.name().toLowerCase(), claimId);
  }

  /**
   * maps a blue button claim type to a FHIR claim type
   *
   * @param eobType the {@link CodeableConcept} that will get remapped
   * @param blueButtonClaimType the blue button {@link ClaimType} we are mapping from
   * @param ccwNearLineRecordIdCode if present, the blue button near line id code {@link
   *     Optional}&lt;{@link Character}&gt; gets remapped to a ccw record id code
   * @param ccwClaimTypeCode if present, the blue button claim type code {@link Optional}&lt;{@link
   *     String}&gt; gets remapped to a nch claim type code
   */
  static void mapEobType(
      ExplanationOfBenefit eob,
      ClaimType blueButtonClaimType,
      Optional<Character> ccwNearLineRecordIdCode,
      Optional<String> ccwClaimTypeCode) {

    // map blue button claim type code into a nch claim type
    // NCH_CLM_TYPE_CD => ExplanationOfBenefit.type.coding
    if (ccwClaimTypeCode.isPresent()) {
      eob.getType()
          .addCoding(createCoding(eob, CcwCodebookVariable.NCH_CLM_TYPE_CD, ccwClaimTypeCode));
    }

    // This Coding MUST always be present as it's the only one we can definitely map
    // for all 8 of our claim types.
    // EOB Type => ExplanationOfBenefit.type.coding
    eob.getType()
        .addCoding()
        .setSystem(TransformerConstants.CODING_SYSTEM_BBAPI_EOB_TYPE)
        .setCode(blueButtonClaimType.name());

    // Map a Coding for FHIR's ClaimType coding system, if we can.
    org.hl7.fhir.r4.model.codesystems.ClaimType fhirClaimType;
    switch (blueButtonClaimType) {
      case PDE:
        fhirClaimType = org.hl7.fhir.r4.model.codesystems.ClaimType.PHARMACY;
        break;

      case INPATIENT:
        fhirClaimType = org.hl7.fhir.r4.model.codesystems.ClaimType.INSTITUTIONAL;
        break;

      case OUTPATIENT:
        fhirClaimType = org.hl7.fhir.r4.model.codesystems.ClaimType.INSTITUTIONAL;
        break;

      default:
        // unknown claim type
        throw new BadCodeMonkeyException();
    }

    // Claim Type => ExplanationOfBenefit.type.coding
    if (fhirClaimType != null) {
      eob.getType()
          .addCoding(
              new Coding(
                  fhirClaimType.getSystem(), fhirClaimType.toCode(), fhirClaimType.getDisplay()));
    }

    // map blue button near line record id to a ccw record id code
    // NCH_NEAR_LINE_REC_IDENT_CD => ExplanationOfBenefit.extension
    if (ccwNearLineRecordIdCode.isPresent()) {
      eob.addExtension(
          createExtensionCoding(
              eob, CcwCodebookVariable.NCH_NEAR_LINE_REC_IDENT_CD, ccwNearLineRecordIdCode));
    }
  }

  /**
   * @param eob the {@link ExplanationOfBenefit} to extract the id from
   * @return the <code>claimId</code> field value (e.g. from {@link CarrierClaim#getClaimId()})
   */
  static String getUnprefixedClaimId(ExplanationOfBenefit eob) {
    for (Identifier i : eob.getIdentifier()) {
      if (i.getSystem().contains("clm_id") || i.getSystem().contains("pde_id")) {
        return i.getValue();
      }
    }

    throw new BadCodeMonkeyException("A claim ID was expected but none was found.");
  }

  /**
   * Adds EOB information to fields that are common between the Inpatient and SNF claim types.
   *
   * @param eob the {@link ExplanationOfBenefit} that fields will be added to by this method
   * @param admissionTypeCd CLM_IP_ADMSN_TYPE_CD: a {@link Character} shared field representing the
   *     admission type cd for the claim
   * @param sourceAdmissionCd CLM_SRC_IP_ADMSN_CD: an {@link Optional}&lt;{@link Character}&gt;
   *     shared field representing the source admission cd for the claim
   * @param noncoveredStayFromDate NCH_VRFD_NCVRD_STAY_FROM_DT: an {@link Optional}&lt;{@link
   *     LocalDate}&gt; shared field representing the non-covered stay from date for the claim
   * @param noncoveredStayThroughDate NCH_VRFD_NCVRD_STAY_THRU_DT: an {@link Optional}&lt;{@link
   *     LocalDate}&gt; shared field representing the non-covered stay through date for the claim
   * @param coveredCareThroughDate NCH_ACTV_OR_CVRD_LVL_CARE_THRU: an {@link Optional}&lt;{@link
   *     LocalDate}&gt; shared field representing the covered stay through date for the claim
   * @param medicareBenefitsExhaustedDate NCH_BENE_MDCR_BNFTS_EXHTD_DT_I: an {@link
   *     Optional}&lt;{@link LocalDate}&gt; shared field representing the medicare benefits
   *     exhausted date for the claim
   * @param diagnosisRelatedGroupCd CLM_DRG_CD: an {@link Optional}&lt;{@link String}&gt; shared
   *     field representing the non-covered stay from date for the claim
   */
  static void addCommonEobInformationInpatientSNF(
      ExplanationOfBenefit eob,
      Character admissionTypeCd,
      Optional<Character> sourceAdmissionCd,
      Optional<LocalDate> noncoveredStayFromDate,
      Optional<LocalDate> noncoveredStayThroughDate,
      Optional<LocalDate> coveredCareThroughDate,
      Optional<LocalDate> medicareBenefitsExhaustedDate,
      Optional<String> diagnosisRelatedGroupCd) {

    // CLM_IP_ADMSN_TYPE_CD => ExplanationOfBenefit.supportingInfo.code
    addInformationWithCode(
        eob,
        CcwCodebookVariable.CLM_IP_ADMSN_TYPE_CD,
        CcwCodebookVariable.CLM_IP_ADMSN_TYPE_CD,
        admissionTypeCd);

    // CLM_SRC_IP_ADMSN_CD => ExplanationOfBenefit.supportingInfo.code
    if (sourceAdmissionCd.isPresent()) {
      addInformationWithCode(
          eob,
          CcwCodebookVariable.CLM_SRC_IP_ADMSN_CD,
          CcwCodebookVariable.CLM_SRC_IP_ADMSN_CD,
          sourceAdmissionCd);
    }

    // noncoveredStayFromDate & noncoveredStayThroughDate
    // NCH_VRFD_NCVRD_STAY_FROM_DT =>
    // ExplanationOfBenefit.supportingInfo.timingPeriod
    // NCH_VRFD_NCVRD_STAY_THRU_DT =>
    // ExplanationOfBenefit.supportingInfo.timingPeriod
    if (noncoveredStayFromDate.isPresent() || noncoveredStayThroughDate.isPresent()) {
      TransformerUtilsV2.validatePeriodDates(noncoveredStayFromDate, noncoveredStayThroughDate);

      SupportingInformationComponent nchVrfdNcvrdStayInfo =
          TransformerUtilsV2.addInformation(eob, CcwCodebookVariable.NCH_VRFD_NCVRD_STAY_FROM_DT);

      Period nchVrfdNcvrdStayPeriod = new Period();

      if (noncoveredStayFromDate.isPresent()) {
        nchVrfdNcvrdStayPeriod.setStart(
            TransformerUtilsV2.convertToDate((noncoveredStayFromDate.get())),
            TemporalPrecisionEnum.DAY);
      }

      if (noncoveredStayThroughDate.isPresent()) {
        nchVrfdNcvrdStayPeriod.setEnd(
            TransformerUtilsV2.convertToDate((noncoveredStayThroughDate.get())),
            TemporalPrecisionEnum.DAY);
      }

      nchVrfdNcvrdStayInfo.setTiming(nchVrfdNcvrdStayPeriod);
    }

    // coveredCareThroughDate
    // NCH_ACTV_OR_CVRD_LVL_CARE_THRU =>
    // ExplanationOfBenefit.supportingInfo.timingDate
    if (coveredCareThroughDate.isPresent()) {
      SupportingInformationComponent nchActvOrCvrdLvlCareThruInfo =
          TransformerUtilsV2.addInformation(
              eob, CcwCodebookVariable.NCH_ACTV_OR_CVRD_LVL_CARE_THRU);
      nchActvOrCvrdLvlCareThruInfo.setTiming(
          new DateType(TransformerUtilsV2.convertToDate(coveredCareThroughDate.get())));
    }

    // medicareBenefitsExhaustedDate
    // NCH_BENE_MDCR_BNFTS_EXHTD_DT_I =>
    // ExplanationOfBenefit.supportingInfo.timingDate
    if (medicareBenefitsExhaustedDate.isPresent()) {
      SupportingInformationComponent nchBeneMdcrBnftsExhtdDtIInfo =
          TransformerUtilsV2.addInformation(
              eob, CcwCodebookVariable.NCH_BENE_MDCR_BNFTS_EXHTD_DT_I);
      nchBeneMdcrBnftsExhtdDtIInfo.setTiming(
          new DateType(TransformerUtilsV2.convertToDate(medicareBenefitsExhaustedDate.get())));
    }

    // diagnosisRelatedGroupCd
    // CLM_DRG_CD => ExplanationOfBenefit.diagnosis
    if (diagnosisRelatedGroupCd.isPresent()) {
      /*
       * FIXME This is an invalid DiagnosisComponent, since it's missing a (required)
       * ICD code. Instead, stick the DRG on the claim's primary/first diagnosis.
       * SamhsaMatcher uses this field so if this is updated you'll need to update
       * that as well.
       */
      eob.addDiagnosis()
          .setSequence(1)
          .setPackageCode(
              createCodeableConcept(eob, CcwCodebookVariable.CLM_DRG_CD, diagnosisRelatedGroupCd));
    }
  }

  /**
   * Adds field values to the benefit balance component that are common between the Inpatient and
   * SNF claim types.
   *
   * @param eob the {@link ExplanationOfBenefit} to map the fields into
   * @param coinsuranceDayCount BENE_TOT_COINSRNC_DAYS_CNT: a {@link BigDecimal} shared field
   *     representing the coinsurance day count for the claim
   * @param nonUtilizationDayCount CLM_NON_UTLZTN_DAYS_CNT: a {@link BigDecimal} shared field
   *     representing the non-utilization day count for the claim
   * @param deductibleAmount NCH_BENE_IP_DDCTBL_AMT: a {@link BigDecimal} shared field representing
   *     the deductible amount for the claim
   * @param partACoinsuranceLiabilityAmount NCH_BENE_PTA_COINSRNC_LBLTY_AM: a {@link BigDecimal}
   *     shared field representing the part A coinsurance amount for the claim
   * @param bloodPintsFurnishedQty NCH_BLOOD_PNTS_FRNSHD_QTY: a {@link BigDecimal} shared field
   *     representing the blood pints furnished quantity for the claim
   * @param noncoveredCharge NCH_IP_NCVRD_CHRG_AMT: a {@link BigDecimal} shared field representing
   *     the non-covered charge for the claim
   * @param totalDeductionAmount NCH_IP_TOT_DDCTN_AMT: a {@link BigDecimal} shared field
   *     representing the total deduction amount for the claim
   * @param claimPPSCapitalDisproportionateShareAmt CLM_PPS_CPTL_DSPRPRTNT_SHR_AMT: an {@link
   *     Optional}&lt;{@link BigDecimal}&gt; shared field representing the claim PPS capital
   *     disproportionate share amount for the claim
   * @param claimPPSCapitalExceptionAmount CLM_PPS_CPTL_EXCPTN_AMT: an {@link Optional}&lt;{@link
   *     BigDecimal}&gt; shared field representing the claim PPS capital exception amount for the
   *     claim
   * @param claimPPSCapitalFSPAmount CLM_PPS_CPTL_FSP_AMT: an {@link Optional}&lt;{@link
   *     BigDecimal}&gt; shared field representing the claim PPS capital FSP amount for the claim
   * @param claimPPSCapitalIMEAmount CLM_PPS_CPTL_IME_AMT: an {@link Optional}&lt;{@link
   *     BigDecimal}&gt; shared field representing the claim PPS capital IME amount for the claim
   * @param claimPPSCapitalOutlierAmount CLM_PPS_CPTL_OUTLIER_AMT: an {@link Optional}&lt;{@link
   *     BigDecimal}&gt; shared field representing the claim PPS capital outlier amount for the
   *     claim
   * @param claimPPSOldCapitalHoldHarmlessAmount CLM_PPS_OLD_CPTL_HLD_HRMLS_AMT: an {@link
   *     Optional}&lt;{@link BigDecimal}&gt; shared field representing the claim PPS old capital
   *     hold harmless amount for the claim
   */
  static void addCommonGroupInpatientSNF(
      ExplanationOfBenefit eob,
      BigDecimal coinsuranceDayCount,
      BigDecimal nonUtilizationDayCount,
      BigDecimal deductibleAmount,
      BigDecimal partACoinsuranceLiabilityAmount,
      BigDecimal bloodPintsFurnishedQty,
      BigDecimal noncoveredCharge,
      BigDecimal totalDeductionAmount,
      Optional<BigDecimal> claimPPSCapitalDisproportionateShareAmt,
      Optional<BigDecimal> claimPPSCapitalExceptionAmount,
      Optional<BigDecimal> claimPPSCapitalFSPAmount,
      Optional<BigDecimal> claimPPSCapitalIMEAmount,
      Optional<BigDecimal> claimPPSCapitalOutlierAmount,
      Optional<BigDecimal> claimPPSOldCapitalHoldHarmlessAmount) {

    // BENE_TOT_COINSRNC_DAYS_CNT => ExplanationOfBenefit.benefitBalance.financial
    addBenefitBalanceFinancialMedicalInt(
        eob, CcwCodebookVariable.BENE_TOT_COINSRNC_DAYS_CNT, coinsuranceDayCount);

    // CLM_NON_UTLZTN_DAYS_CNT => ExplanationOfBenefit.benefitBalance.financial
    addBenefitBalanceFinancialMedicalInt(
        eob, CcwCodebookVariable.CLM_NON_UTLZTN_DAYS_CNT, nonUtilizationDayCount);

    // NCH_BENE_IP_DDCTBL_AMT => ExplanationOfBenefit.benefitBalance.financial
    addBenefitBalanceFinancialMedicalAmt(
        eob, CcwCodebookVariable.NCH_BENE_IP_DDCTBL_AMT, deductibleAmount);

    // NCH_BENE_PTA_COINSRNC_LBLTY_AMT =>
    // ExplanationOfBenefit.benefitBalance.financial
    addBenefitBalanceFinancialMedicalAmt(
        eob, CcwCodebookVariable.NCH_BENE_PTA_COINSRNC_LBLTY_AMT, partACoinsuranceLiabilityAmount);

    // NCH_BLOOD_PNTS_FRNSHD_QTY =>
    // ExplanationOfBenefit.supportingInfo.valueQuantity
    SupportingInformationComponent nchBloodPntsFrnshdQtyInfo =
        addInformation(eob, CcwCodebookVariable.NCH_BLOOD_PNTS_FRNSHD_QTY);

    Quantity bloodPintsQuantity = new Quantity();
    bloodPintsQuantity.setValue(bloodPintsFurnishedQty);
    bloodPintsQuantity
        .setSystem(TransformerConstants.CODING_SYSTEM_UCUM)
        .setCode(TransformerConstants.CODING_SYSTEM_UCUM_PINT_CODE)
        .setUnit(TransformerConstants.CODING_SYSTEM_UCUM_PINT_DISPLAY);

    nchBloodPntsFrnshdQtyInfo.setValue(bloodPintsQuantity);

    // NCH_IP_NCVRD_CHRG_AMT => ExplanationOfBenefit.benefitBalance.financial
    addBenefitBalanceFinancialMedicalAmt(
        eob, CcwCodebookVariable.NCH_IP_NCVRD_CHRG_AMT, noncoveredCharge);

    // NCH_IP_TOT_DDCTN_AMT => ExplanationOfBenefit.benefitBalance.financial
    addBenefitBalanceFinancialMedicalAmt(
        eob, CcwCodebookVariable.NCH_IP_TOT_DDCTN_AMT, totalDeductionAmount);

    // CLM_PPS_CPTL_DSPRPRTNT_SHR_AMT => ExplanationOfBenefit.benefitBalance.financial
    addBenefitBalanceFinancialMedicalAmt(
        eob,
        CcwCodebookVariable.CLM_PPS_CPTL_DSPRPRTNT_SHR_AMT,
        claimPPSCapitalDisproportionateShareAmt);

    // CLM_PPS_CPTL_EXCPTN_AMT => ExplanationOfBenefit.benefitBalance.financial
    addBenefitBalanceFinancialMedicalAmt(
        eob, CcwCodebookVariable.CLM_PPS_CPTL_EXCPTN_AMT, claimPPSCapitalExceptionAmount);

    // CLM_PPS_CPTL_FSP_AMT => ExplanationOfBenefit.benefitBalance.financial
    addBenefitBalanceFinancialMedicalAmt(
        eob, CcwCodebookVariable.CLM_PPS_CPTL_FSP_AMT, claimPPSCapitalFSPAmount);

    // CLM_PPS_CPTL_IME_AMT => ExplanationOfBenefit.benefitBalance.financial
    addBenefitBalanceFinancialMedicalAmt(
        eob, CcwCodebookVariable.CLM_PPS_CPTL_IME_AMT, claimPPSCapitalIMEAmount);

    // CLM_PPS_CPTL_OUTLIER_AMT => ExplanationOfBenefit.benefitBalance.financial
    addBenefitBalanceFinancialMedicalAmt(
        eob, CcwCodebookVariable.CLM_PPS_CPTL_OUTLIER_AMT, claimPPSCapitalOutlierAmount);

    // CLM_PPS_OLD_CPTL_HLD_HRMLS_AMT => ExplanationOfBenefit.benefitBalance.financial
    addBenefitBalanceFinancialMedicalAmt(
        eob,
        CcwCodebookVariable.CLM_PPS_OLD_CPTL_HLD_HRMLS_AMT,
        claimPPSOldCapitalHoldHarmlessAmount);
  }

  /**
   * @param eob the {@link ExplanationOfBenefit} that the adjudication total should be part of
   * @param categoryVariable the {@link CcwCodebookVariable} to map to the adjudication's <code>
   *     category</code>
   * @param amountValue the {@link Money#getValue()} for the adjudication total
   * @return the new {@link BenefitBalanceComponent}, which will have already been added to the
   *     appropriate {@link ExplanationOfBenefit#getBenefitBalance()} entry
   */
  static void addAdjudicationTotal(
      ExplanationOfBenefit eob,
      CcwCodebookVariable categoryVariable,
      Optional<? extends Number> amountValue) {

    if (amountValue.isPresent()) {
      String extensionUrl = calculateVariableReferenceUrl(categoryVariable);
      Money adjudicationTotalAmount = createMoney(amountValue);
      Extension adjudicationTotalEextension = new Extension(extensionUrl, adjudicationTotalAmount);

      eob.addExtension(adjudicationTotalEextension);
    }
  }

  /**
   * @param eob the {@link ExplanationOfBenefit} that the adjudication total should be part of
   * @param categoryVariable the {@link CcwCodebookVariable} to map to the adjudication's <code>
   *     category</code>
   * @param totalAmountValue the {@link Money#getValue()} for the adjudication total
   * @return the new {@link BenefitBalanceComponent}, which will have already been added to the
   *     appropriate {@link ExplanationOfBenefit#getBenefitBalance()} entry
   */
  static void addAdjudicationTotal(
      ExplanationOfBenefit eob, CcwCodebookVariable categoryVariable, Number totalAmountValue) {
    addAdjudicationTotal(eob, categoryVariable, Optional.of(totalAmountValue));
  }

  /**
   * @param eob the {@link ExplanationOfBenefit} that the {@link ExBenefitcategory} should be part
   *     of
   * @param benefitCategoryCode the code representing an {@link ExBenefitcategory}
   * @param financialType the {@link CcwCodebookVariable} to map to {@link
   *     BenefitComponent#getType()}
   * @return the new {@link BenefitBalanceComponent}, which will have already been added to the
   *     appropriate {@link ExplanationOfBenefit#getBenefitBalance()} entry
   */
  static BenefitComponent addBenefitBalanceFinancial(
      ExplanationOfBenefit eob,
      ExBenefitcategory benefitCategoryCode,
      CcwCodebookVariable financialType) {
    BenefitBalanceComponent eobPrimaryBenefitBalance =
        findOrAddBenefitBalance(eob, benefitCategoryCode);

    CodeableConcept financialTypeConcept =
        TransformerUtilsV2.createCodeableConcept(
            TransformerConstants.CODING_BBAPI_BENEFIT_BALANCE_TYPE,
            calculateVariableReferenceUrl(financialType));

    financialTypeConcept.getCodingFirstRep().setDisplay(financialType.getVariable().getLabel());

    BenefitComponent financialEntry = new BenefitComponent(financialTypeConcept);
    eobPrimaryBenefitBalance.getFinancial().add(financialEntry);

    return financialEntry;
  }

  /**
   * Adds a {@link BenefitComponent} that has the passed in amount encoded in {@link
   * BenefitComponent#getUsedMoney()}
   *
   * @param eob the {@link ExplanationOfBenefit} that the {@link BenefitComponent} should be part of
   * @param financialType the {@link CcwCodebookVariable} to map to {@link
   *     BenefitComponent#getType()}
   * @param amt Money amount to map to {@link BenefitComponent#getUsedMoney()}
   * @return the new {@link BenefitComponent} which will have already been added to the appropriate
   *     {@link ExplanationOfBenefit#getBenefitBalance()} entry
   */
  static BenefitComponent addBenefitBalanceFinancialMedicalAmt(
      ExplanationOfBenefit eob, CcwCodebookVariable financialType, BigDecimal amt) {
    // "1" is the code for MEDICAL in ExBenefitcategory
    return addBenefitBalanceFinancial(eob, ExBenefitcategory._1, financialType)
        .setUsed(createMoney(amt));
  }

  /**
   * Optionally adds a {@link BenefitComponent} that has the passed in amount encoded in {@link
   * BenefitComponent#getUsedMoney()}
   *
   * @param eob the {@link ExplanationOfBenefit} that the {@link BenefitComponent} should be part of
   * @param financialType the {@link CcwCodebookVariable} to map to {@link
   *     BenefitComponent#getType()}
   * @param amt Money amount to map to {@link BenefitComponent#getUsedMoney()}
   * @return the new {@link BenefitComponent} which will have already been added to the appropriate
   *     {@link ExplanationOfBenefit#getBenefitBalance()} entry. Returns Empty if the amount wasn't
   *     set.
   */
  static Optional<BenefitComponent> addBenefitBalanceFinancialMedicalAmt(
      ExplanationOfBenefit eob, CcwCodebookVariable financialType, Optional<BigDecimal> amount) {
    return amount.map(
        amt -> addBenefitBalanceFinancialMedicalAmt(eob, financialType, amount.get()));
  }

  /**
   * Adds a {@link BenefitComponent} that has the passed in amount encoded in {@link
   * BenefitComponent#getUsedUnsignedIntType()}
   *
   * @param eob the {@link ExplanationOfBenefit} that the {@link BenefitComponent} should be part of
   * @param financialType the {@link CcwCodebookVariable} to map to {@link
   *     BenefitComponent#getType()}
   * @param value Integral amount to map to {@link BenefitComponent#getUsedUnsignedIntType()}
   * @return the new {@link BenefitComponent} which will have already been added to the appropriate
   *     {@link ExplanationOfBenefit#getBenefitBalance()} entry
   */
  static BenefitComponent addBenefitBalanceFinancialMedicalInt(
      ExplanationOfBenefit eob, CcwCodebookVariable financialType, BigDecimal amount) {
    // "1" is the code for MEDICAL in ExBenefitcategory
    return addBenefitBalanceFinancial(eob, ExBenefitcategory._1, financialType)
        // TODO: intValueExact() not working?
        .setUsed(new UnsignedIntType(amount.intValue()));
  }

  /**
   * Optionally adds a {@link BenefitComponent} that has the passed in amount encoded in {@link
   * BenefitComponent#getUsedUnsignedIntType()}
   *
   * @param eob the {@link ExplanationOfBenefit} that the {@link BenefitComponent} should be part of
   * @param financialType the {@link CcwCodebookVariable} to map to {@link
   *     BenefitComponent#getType()}
   * @param value Integral amount to map to {@link BenefitComponent#getUsedUnsignedIntType()}
   * @return the new {@link BenefitComponent} which will have already been added to the appropriate
   *     {@link ExplanationOfBenefit#getBenefitBalance()} entry. Returns Empty if the amount wasn't
   *     set.
   */
  static Optional<BenefitComponent> addBenefitBalanceFinancialMedicalInt(
      ExplanationOfBenefit eob, CcwCodebookVariable financialType, Optional<BigDecimal> value) {
    return value.map(val -> addBenefitBalanceFinancialMedicalInt(eob, financialType, value.get()));
  }

  /**
   * @param eob the {@link ExplanationOfBenefit} that the {@link BenefitComponent} should be part of
   * @param benefitCategory the {@link BenefitCategory} to map to {@link
   *     BenefitBalanceComponent#getCategory()}
   * @return the already-existing {@link BenefitBalanceComponent} that matches the specified
   *     parameters, or a new one
   */
  private static BenefitBalanceComponent findOrAddBenefitBalance(
      ExplanationOfBenefit eob, ExBenefitcategory benefitCategory) {

    Optional<BenefitBalanceComponent> matchingBenefitBalance =
        eob.getBenefitBalance().stream()
            .filter(
                bb ->
                    isCodeInConcept(
                        bb.getCategory(), benefitCategory.getSystem(), benefitCategory.toCode()))
            .findAny();

    // Found an existing BenefitBalance that matches the coding system
    if (matchingBenefitBalance.isPresent()) {
      return matchingBenefitBalance.get();
    }

    CodeableConcept benefitCategoryConcept = new CodeableConcept();
    benefitCategoryConcept
        .addCoding()
        .setSystem(benefitCategory.getSystem())
        .setCode(benefitCategory.toCode())
        .setDisplay(benefitCategory.getDisplay());

    BenefitBalanceComponent newBenefitBalance = new BenefitBalanceComponent(benefitCategoryConcept);
    eob.addBenefitBalance(newBenefitBalance);

    return newBenefitBalance;
  }

  /**
   * Optionally adds a member to @link ExplanationOfBenefit#getCareTeam()}
   *
   * @param eob the {@link ExplanationOfBenefit} that the {@link CareTeamComponent} should be part
   *     of
   * @param system Coding System to use, either NPI or UPIN
   * @param role The care team member's role
   * @param id The NPI or UPIN coded as a string
   */
  static void addCareTeamMember(
      ExplanationOfBenefit eob,
      C4BBPractitionerIdentifierType type,
      C4BBClaimInstitutionalCareTeamRole role,
      Optional<String> id) {
    if (id.isPresent()) {
      addCareTeamPractitioner(eob, null, type, id.get(), role);
    }
  }

  /**
   * Handles mapping the following values to the appropriate member of {@link
   * ExplanationOfBenefit#getCareTeam()}. This updates the passed in {@link ExplanationOfBenefit} in
   * place.
   *
   * @param eob the {@link ExplanationOfBenefit} that the {@link CareTeamComponent} should be part
   *     of
   * @param attendingPhysicianNpi AT_PHYSN_NPI
   * @param operatingPhysicianNpi OP_PHYSN_NPI
   * @param otherPhysicianNpi OT_PHYSN_NPI
   * @param attendingPhysicianUpin AT_PHYSN_UPIN
   * @param operatingPhysicianUpin OP_PHYSN_UPIN
   * @param otherPhysicianUpin OT_PHYSN_UPIN
   */
  static void mapCareTeam(
      ExplanationOfBenefit eob,
      Optional<String> attendingPhysicianNpi,
      Optional<String> operatingPhysicianNpi,
      Optional<String> otherPhysicianNpi,
      Optional<String> attendingPhysicianUpin,
      Optional<String> operatingPhysicianUpin,
      Optional<String> otherPhysicianUpin) {

    // AT_PHYSN_NPI => ExplanationOfBenefit.careTeam.provider
    addCareTeamMember(
        eob,
        C4BBPractitionerIdentifierType.NPI,
        C4BBClaimInstitutionalCareTeamRole.ATTENDING,
        attendingPhysicianNpi);

    // AT_PHYSN_UPIN => ExplanationOfBenefit.careTeam.provider
    addCareTeamMember(
        eob,
        C4BBPractitionerIdentifierType.UPIN,
        C4BBClaimInstitutionalCareTeamRole.ATTENDING,
        attendingPhysicianUpin);

    // OP_PHYSN_NPI => ExplanationOfBenefit.careTeam.provider
    addCareTeamMember(
        eob,
        C4BBPractitionerIdentifierType.NPI,
        C4BBClaimInstitutionalCareTeamRole.OPERATING,
        operatingPhysicianNpi);

    // OP_PHYSN_UPIN => ExplanationOfBenefit.careTeam.provider
    addCareTeamMember(
        eob,
        C4BBPractitionerIdentifierType.UPIN,
        C4BBClaimInstitutionalCareTeamRole.OPERATING,
        operatingPhysicianUpin);

    // OT_PHYSN_NPI => ExplanationOfBenefit.careTeam.provider
    addCareTeamMember(
        eob,
        C4BBPractitionerIdentifierType.NPI,
        C4BBClaimInstitutionalCareTeamRole.OTHER_OPERATING,
        otherPhysicianNpi);

    // OT_PHYSN_UPIN => ExplanationOfBenefit.careTeam.provider
    addCareTeamMember(
        eob,
        C4BBPractitionerIdentifierType.UPIN,
        C4BBClaimInstitutionalCareTeamRole.OTHER_OPERATING,
        otherPhysicianUpin);
  }

  /**
   * Transforms the common group level data elements between the {@link InpatientClaim} {@link
   * OutpatientClaim} and {@link SNFClaim} claim types to FHIR.
   *
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @param bloodDeductibleLiabilityAmount NCH_BENE_BLOOD_DDCTBL_LBLTY_AM
   * @param claimQueryCode CLAIM_QUERY_CODE
   * @param mcoPaidSw CLM_MCO_PD_SW
   */
  static void mapEobCommonGroupInpOutSNF(
      ExplanationOfBenefit eob,
      BigDecimal bloodDeductibleLiabilityAmount,
      char claimQueryCode,
      Optional<Character> mcoPaidSw) {

    // NCH_BENE_BLOOD_DDCTBL_LBLTY_AM => ExplanationOfBenefit.benefitBalance.financial
    addBenefitBalanceFinancialMedicalAmt(
        eob, CcwCodebookVariable.NCH_BENE_BLOOD_DDCTBL_LBLTY_AM, bloodDeductibleLiabilityAmount);

    // CLAIM_QUERY_CODE => ExplanationOfBenefit.billablePeriod.extension
    eob.getBillablePeriod()
        .addExtension(
            createExtensionCoding(eob, CcwCodebookVariable.CLAIM_QUERY_CD, claimQueryCode));

    // CLM_MCO_PD_SW => ExplanationOfBenefit.supportingInfo.code
    if (mcoPaidSw.isPresent()) {
      TransformerUtilsV2.addInformationWithCode(
          eob, CcwCodebookVariable.CLM_MCO_PD_SW, CcwCodebookVariable.CLM_MCO_PD_SW, mcoPaidSw);
    }
  }

  /**
   * Transforms the common group level data elements between the {@link InpatientClaimLine} {@link
   * OutpatientClaimLine} {@link HospiceClaimLine} {@link HHAClaimLine}and {@link SNFClaimLine}
   * claim types to FHIR. The method parameter fields from {@link InpatientClaimLine} {@link
   * OutpatientClaimLine} {@link HospiceClaimLine} {@link HHAClaimLine}and {@link SNFClaimLine} are
   * listed below and their corresponding RIF CCW fields (denoted in all CAPS below from {@link
   * InpatientClaimColumn} {@link OutpatientClaimColumn} {@link HopsiceClaimColumn} {@link
   * HHAClaimColumn} and {@link SNFClaimColumn}).
   *
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @param organizationNpi ORG_NPI_NUM,
   * @param claimFacilityTypeCode CLM_FAC_TYPE_CD,
   * @param claimFrequencyCode CLM_FREQ_CD,
   * @param claimNonPaymentReasonCode CLM_MDCR_NON_PMT_RSN_CD,
   * @param patientDischargeStatusCode PTNT_DSCHRG_STUS_CD,
   * @param claimServiceClassificationTypeCode CLM_SRVC_CLSFCTN_TYPE_CD,
   * @param claimPrimaryPayerCode NCH_PRMRY_PYR_CD,
   * @param attendingPhysicianNpi AT_PHYSN_NPI,
   * @param totalChargeAmount CLM_TOT_CHRG_AMT,
   * @param primaryPayerPaidAmount NCH_PRMRY_PYR_CLM_PD_AMT,
   * @param fiscalIntermediaryNumber FI_NUM
   */
  static void mapEobCommonGroupInpOutHHAHospiceSNF(
      ExplanationOfBenefit eob,
      Optional<String> organizationNpi,
      char claimFacilityTypeCode,
      char claimFrequencyCode,
      Optional<String> claimNonPaymentReasonCode,
      String patientDischargeStatusCode,
      char claimServiceClassificationTypeCode,
      Optional<Character> claimPrimaryPayerCode,
      BigDecimal totalChargeAmount,
      BigDecimal primaryPayerPaidAmount,
      Optional<String> fiscalIntermediaryNumber,
      Optional<Date> lastUpdated) {

    // ORG_NPI_NUM => ExplanationOfBenefit.provider
    addProviderSlice(eob, C4BBOrganizationIdentifierType.NPI, organizationNpi, lastUpdated);

    // CLM_FAC_TYPE_CD => ExplanationOfBenefit.facility.extension
    eob.getFacility()
        .addExtension(
            createExtensionCoding(eob, CcwCodebookVariable.CLM_FAC_TYPE_CD, claimFacilityTypeCode));

    // CLM_FREQ_CD => ExplanationOfBenefit.supportingInfo
    addInformationSliceWithCode(
        eob,
        C4BBSupportingInfoType.TYPE_OF_BILL,
        CcwCodebookVariable.CLM_FREQ_CD,
        CcwCodebookVariable.CLM_FREQ_CD,
        claimFrequencyCode);

    // CLM_MDCR_NON_PMT_RSN_CD => ExplanationOfBenefit.extension
    if (claimNonPaymentReasonCode.isPresent()) {
      eob.addExtension(
          createExtensionCoding(
              eob, CcwCodebookVariable.CLM_MDCR_NON_PMT_RSN_CD, claimNonPaymentReasonCode));
    }

    // PTNT_DSCHRG_STUS_CD => ExplanationOfBenefit.supportingInfo
    if (!patientDischargeStatusCode.isEmpty()) {
      addInformationSliceWithCode(
          eob,
          C4BBSupportingInfoType.DISCHARGE_STATUS,
          CcwCodebookVariable.PTNT_DSCHRG_STUS_CD,
          CcwCodebookVariable.PTNT_DSCHRG_STUS_CD,
          claimFrequencyCode);
    }

    // CLM_SRVC_CLSFCTN_TYPE_CD => ExplanationOfBenefit.extension
    eob.addExtension(
        createExtensionCoding(
            eob, CcwCodebookVariable.CLM_SRVC_CLSFCTN_TYPE_CD, claimServiceClassificationTypeCode));

    // NCH_PRMRY_PYR_CD => ExplainationOfBenefit.supportingInfo
    if (claimPrimaryPayerCode.isPresent()) {
      addInformationWithCode(
          eob,
          CcwCodebookVariable.NCH_PRMRY_PYR_CD,
          CcwCodebookVariable.NCH_PRMRY_PYR_CD,
          claimPrimaryPayerCode.get());
    }

    // CLM_TOT_CHRG_AMT => ExplainationOfBenefit.total
    addTotal(
        eob,
        createTotalAdjudicationAmountSlice(
            eob,
            CcwCodebookVariable.CLM_TOT_CHRG_AMT,
            C4BBAdjudication.SUBMITTED,
            totalChargeAmount));

    // NCH_PRMRY_PYR_CLM_PD_AMT => ExplanationOfBenefit.benefitBalance.financial
    addBenefitBalanceFinancialMedicalAmt(eob, CcwCodebookVariable.PRPAYAMT, primaryPayerPaidAmount);
  }

  /**
   * Helper function to look up method names and optionally attempt to execute
   *
   * @return an Optional result, cast to the generic type. If the method did not exist, or
   *     invocation failed, this returns {@link Optional#empty()}
   */
  @SuppressWarnings("unchecked")
  private static <T> Optional<T> tryMethod(Object obj, String methodName) {
    try {
      Method func = obj.getClass().getDeclaredMethod(methodName);

      return (Optional<T>) func.invoke(obj);
    }
    // Any reflection errors would be caused by the method not being available
    catch (Exception e) {
      return Optional.empty();
    }
  }

  /**
   * Generically attempts to retrieve a diagnosis from the current claim.
   *
   * @param substitution The methods to retrive diagnosis information all follow a similar pattern.
   *     This value is used to substitute into that pattern when looking up the specific method to
   *     retrive information with.
   * @param claim Passed as an Object because there is no top level `Claim` class that claims derive
   *     from
   * @param ccw CCW Codebook value that represents which "PresentOnAdmissionCode" is being used.
   *     Example: {@link CcwCodebookVariable#CLM_POA_IND_SW5}
   * @param labels One or more labels to use when mapping the diagnosis.
   * @return a {@link Diagnosis} or {@link Optional#empty()}
   */
  public static Optional<Diagnosis> extractDiagnosis(
      String substitution,
      Object claim,
      Optional<CcwCodebookInterface> ccw,
      DiagnosisLabel... labels) {

    Optional<String> code = tryMethod(claim, String.format("getDiagnosis%sCode", substitution));
    Optional<Character> codeVersion =
        tryMethod(claim, String.format("getDiagnosis%sCodeVersion", substitution));
    Optional<Character> presentOnAdm =
        tryMethod(claim, String.format("getDiagnosis%sPresentOnAdmissionCode", substitution));

    return Diagnosis.from(code, codeVersion, presentOnAdm, ccw, labels);
  }

  /**
   * @param eob the {@link ExplanationOfBenefit} to (possibly) modify
   * @param diagnosis the {@link Diagnosis} to add, if it's not already present
   * @return the {@link DiagnosisComponent#getSequence()} of the existing or newly-added entry
   */
  static int addDiagnosisCode(ExplanationOfBenefit eob, Diagnosis diagnosis) {
    // Filter out if the diagnosis is alrerady contained in the document
    Optional<DiagnosisComponent> existingDiagnosis =
        eob.getDiagnosis().stream()
            .filter(d -> d.getDiagnosis() instanceof CodeableConcept)
            .filter(d -> containedIn(diagnosis, (CodeableConcept) d.getDiagnosis()))
            .findAny();

    if (existingDiagnosis.isPresent()) {
      return existingDiagnosis.get().getSequenceElement().getValue();
    }

    DiagnosisComponent diagnosisComponent =
        new DiagnosisComponent().setSequence(eob.getDiagnosis().size() + 1);
    diagnosisComponent.setDiagnosis(toCodeableConcept(diagnosis));

    for (DiagnosisLabel diagnosisLabel : diagnosis.getLabels()) {
      CodeableConcept diagnosisTypeConcept =
          createCodeableConcept(diagnosisLabel.getSystem(), diagnosisLabel.toCode());
      diagnosisTypeConcept.getCodingFirstRep().setDisplay(diagnosisLabel.getDisplay());
      diagnosisComponent.addType(diagnosisTypeConcept);
    }

    if (diagnosis.getPresentOnAdmission().isPresent()
        && diagnosis.getPresentOnAdmissionCode().isPresent()) {
      diagnosisComponent.addExtension(
          createExtensionCoding(
              eob, diagnosis.getPresentOnAdmissionCode().get(), diagnosis.getPresentOnAdmission()));
    }

    eob.getDiagnosis().add(diagnosisComponent);

    return diagnosisComponent.getSequenceElement().getValue();
  }

  /**
   * @param eob the {@link ExplanationOfBenefit} to (possibly) modify
   * @param diagnosis the {@link Diagnosis} to add, if it's not already present
   * @return the {@link DiagnosisComponent#getSequence()} of the existing or newly-added entry
   */
  static int addDiagnosisCode(ExplanationOfBenefit eob, Optional<Diagnosis> diagnosis) {
    if (diagnosis.isPresent()) {
      return addDiagnosisCode(eob, diagnosis.get());
    } else {
      return -1;
    }
  }

  /**
   * Generically attempts to retrieve a procedure from the current claim.
   *
   * @param procedure Procedure accessors all follow the same pattern except for an integer
   *     difference. This value is used as a subtitution when looking up the method name.
   * @param claim Passed as an Object because there is no top level `Claim` class that claims derive
   *     from
   * @return a {@link CCWProcedure} or {@link Optional#empty()}
   */
  public static Optional<CCWProcedure> extractCCWProcedure(int procedure, Object claim) {
    Optional<String> code = tryMethod(claim, String.format("getProcedure%dCode", procedure));
    Optional<Character> codeVersion =
        tryMethod(claim, String.format("getProcedure%dCodeVersion", procedure));
    Optional<LocalDate> date = tryMethod(claim, String.format("getProcedure%dDate", procedure));

    return CCWProcedure.from(code, codeVersion, date);
  }

  static boolean containedIn(Diagnosis diag, CodeableConcept codeableConcept) {
    return codeableConcept.getCoding().stream()
            .filter(c -> diag.getCode().equals(c.getCode()))
            .filter(c -> diag.getFhirSystem().equals(c.getSystem()))
            .count()
        != 0;
  }

  static CodeableConcept toCodeableConcept(Diagnosis diag) {
    CodeableConcept codeableConcept = new CodeableConcept();

    codeableConcept
        .addCoding()
        .setSystem(diag.getFhirSystem())
        .setCode(diag.getCode())
        // TODO: This code should be pulled out to a common library
        .setDisplay(TransformerUtils.retrieveIcdCodeDisplay(diag.getCode()));

    return codeableConcept;
  }

  /**
   * @param eob the {@link ExplanationOfBenefit} to (possibly) modify
   * @param procedure the {@link CCWProcedure} to add, if it's not already present
   * @return the {@link ProcedureComponent#getSequence()} of the existing or newly-added entry
   */
  static int addProcedureCode(ExplanationOfBenefit eob, CCWProcedure procedure) {
    Optional<ProcedureComponent> existingProcedure =
        eob.getProcedure().stream()
            .filter(pc -> pc.getProcedure() instanceof CodeableConcept)
            .filter(
                pc ->
                    isCodeInConcept(
                        (CodeableConcept) pc.getProcedure(),
                        procedure.getFhirSystem(),
                        procedure.getCode()))
            .findAny();

    if (existingProcedure.isPresent()) {
      return existingProcedure.get().getSequenceElement().getValue();
    }

    ProcedureComponent procedureComponent =
        new ProcedureComponent()
            .setSequence(eob.getProcedure().size() + 1)
            .setProcedure(
                createCodeableConcept(
                    procedure.getFhirSystem(),
                    null,
                    retrieveProcedureCodeDisplay(procedure.getCode()),
                    procedure.getCode()));

    if (procedure.getProcedureDate().isPresent()) {
      procedureComponent.setDate(convertToDate(procedure.getProcedureDate().get()));
    }

    eob.getProcedure().add(procedureComponent);

    return procedureComponent.getSequenceElement().getValue();
  }

  /**
   * Adds an {@link ItemComponent} to the passed in {@link ExplanationOfBenefit}. It is added to the
   * end of the list and the Sequence is set appropriately.
   *
   * @param eob The {@link ExplanationOfBenefit} to add the {@link ItemComponent} to
   * @return The newly created {@link ItemComponent}
   */
  static ItemComponent addItem(ExplanationOfBenefit eob) {
    // addItem adds and returns, so we want size() not size() + 1 here
    return eob.addItem().setSequence(eob.getItem().size());
  }

  /**
   * Looks for an {@link Organization} with the given resource ID in {@link
   * ExplanationOfBenefit#getContained()} or adds one if it doesn't exist
   *
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @param id The resource ID
   * @return The found or new {@link Organization} resource
   */
  static Resource findOrCreateContainedOrg(ExplanationOfBenefit eob, String id) {
    Optional<Resource> org = eob.getContained().stream().filter(r -> r.getId() == id).findFirst();

    // If it isn't there, add one
    if (!org.isPresent()) {
      org = Optional.of(new Organization().setId(id));
      org.get().getMeta().addProfile(ProfileConstants.C4BB_ORGANIZATION_URL);
      eob.getContained().add(org.get());
    }

    return org.get();
  }

  // Used to look up and identify an internal `contained` Organization resource
  private static final String PROVIDER_ORG_ID = "provider-org";
  private static final String PROVIDER_ORG_REFERENCE = "#" + PROVIDER_ORG_ID;

  /**
   * Looks up or adds a contained {@link Organization} object to the current {@link
   * ExplanationOfBenefit}. This is used to store Identifier slices related to the Provider
   * organization.
   *
   * @param eob The {@link ExplanationOfBenefit} to provider org details to
   * @param type The {@link C4BBIdentifierType} of the identifier slice
   * @param value The value of the identifier. If empty, this call is a no-op
   */
  static void addProviderSlice(
      ExplanationOfBenefit eob,
      C4BBOrganizationIdentifierType type,
      Optional<String> value,
      Optional<Date> lastUpdated) {
    if (value.isPresent()) {
      Resource providerResource = findOrCreateContainedOrg(eob, PROVIDER_ORG_ID);

      // We are assuming that the contained resource with an id of "provider-org" is an Organization
      if (!Organization.class.isInstance(providerResource)) {
        throw new BadCodeMonkeyException();
      }

      Organization provider = (Organization) providerResource;

      // Add the new Identifier to the Organization
      Identifier id =
          new Identifier()
              .setType(createCodeableConcept(type.getSystem(), type.toCode()))
              .setValue(value.get());

      // Certain types have specific systems
      if (type == C4BBOrganizationIdentifierType.NPI) {
        id.setSystem(TransformerConstants.CODING_NPI_US);
      }

      provider.addIdentifier(id);

      setLastUpdated(provider, lastUpdated);

      // This gets updated for every call, but always set to the same value
      eob.getProvider().setReference(PROVIDER_ORG_REFERENCE);
    }
  }

  /** Convenience function when passing non-optional values */
  static void addProviderSlice(
      ExplanationOfBenefit eob,
      C4BBOrganizationIdentifierType type,
      String value,
      Optional<Date> lastupdated) {
    addProviderSlice(eob, type, Optional.of(value), lastupdated);
  }

  /**
   * Transforms the common group level data elements between the {@link InpatientClaim} {@link
   * HHAClaim} {@link HospiceClaim} and {@link SNFClaim} claim types to FHIR. The method parameter
   * fields from {@link InpatientClaim} {@link HHAClaim} {@link HospiceClaim} and {@link SNFClaim}
   * are listed below and their corresponding RIF CCW fields (denoted in all CAPS below from {@link
   * InpatientClaimColumn} {@link HHAClaimColumn} {@link HospiceColumn} and {@link SNFClaimColumn}).
   *
   * @param eob the root {@link ExplanationOfBenefit} that the {@link ItemComponent} is part of
   * @param item the {@link ItemComponent} to modify
   * @param deductibleCoinsruanceCd REV_CNTR_DDCTBL_COINSRNC_CD
   */
  static void mapEobCommonGroupInpHHAHospiceSNFCoinsurance(
      ExplanationOfBenefit eob, ItemComponent item, Optional<Character> deductibleCoinsuranceCd) {
    if (deductibleCoinsuranceCd.isPresent()) {
      item.getRevenue()
          .addExtension(
              createExtensionCoding(
                  eob, CcwCodebookVariable.REV_CNTR_DDCTBL_COINSRNC_CD, deductibleCoinsuranceCd));
    }
  }

  /*
   * @param claim the Claim to extract the {@link Diagnosis}es from
   * @return the {@link Diagnosis} that can be extracted from the specified {@link InpatientClaim}
   */
  static List<Diagnosis> extractDiagnoses(Object claim) {
    List<Optional<Diagnosis>> diagnosis = new ArrayList<>();

    // Handle the "special" diagnosis fields
    diagnosis.add(extractDiagnosis("Admitting", claim, Optional.empty(), DiagnosisLabel.ADMITTING));
    diagnosis.add(
        extractDiagnosis(
            "1",
            claim,
            Optional.of(CcwCodebookVariable.CLM_POA_IND_SW1),
            DiagnosisLabel.PRINCIPAL));
    diagnosis.add(extractDiagnosis("Principal", claim, Optional.empty(), DiagnosisLabel.PRINCIPAL));

    // Generically handle the rest (2-25)
    final int FIRST_DIAG = 2;
    final int LAST_DIAG = 25;

    IntStream.range(FIRST_DIAG, LAST_DIAG + 1)
        .mapToObj(
            i -> {
              return extractDiagnosis(
                  String.valueOf(i),
                  claim,
                  Optional.of(CcwCodebookVariable.valueOf("CLM_POA_IND_SW" + i)));
            })
        .forEach(diagnosis::add);

    // Handle external diagnosis
    diagnosis.add(
        extractDiagnosis(
            "External1",
            claim,
            Optional.of(CcwCodebookVariable.CLM_E_POA_IND_SW1),
            DiagnosisLabel.FIRSTEXTERNAL));
    diagnosis.add(
        extractDiagnosis("ExternalFirst", claim, Optional.empty(), DiagnosisLabel.FIRSTEXTERNAL));

    // Generically handle the rest (2-12)
    final int FIRST_EX_DIAG = 2;
    final int LAST_EX_DIAG = 12;

    IntStream.range(FIRST_EX_DIAG, LAST_EX_DIAG + 1)
        .mapToObj(
            i -> {
              return extractDiagnosis(
                  "External" + String.valueOf(i),
                  claim,
                  Optional.of(CcwCodebookVariable.valueOf("CLM_E_POA_IND_SW" + i)));
            })
        .forEach(diagnosis::add);

    // Some may be empty.  Convert from List<Optional<Diagnosis>> to List<Diagnosis>
    return diagnosis.stream()
        .filter(d -> d.isPresent())
        .map(d -> d.get())
        .collect(Collectors.toList());
  }

  /**
   * @param eob the {@link ExplanationOfBenefit} that the HCPCS code is being mapped into
   * @param item the {@link ItemComponent} that the HCPCS code is being mapped into
   * @param year the {@link CcwCodebookVariable#CARR_CLM_HCPCS_YR_CD} identifying the HCPCS code
   *     version in use
   * @param modifiers the {@link CcwCodebookVariable#HCPCS_1ST_MDFR_CD}, etc. values to be mapped
   *     (if any)
   */
  static void mapHcpcs(
      ExplanationOfBenefit eob,
      ItemComponent item,
      Optional<Character> year,
      List<Optional<String>> modifiers) {

    for (Optional<String> hcpcsModifier : modifiers) {
      if (hcpcsModifier.isPresent()) {
        CodeableConcept modifier =
            createCodeableConcept(TransformerConstants.CODING_SYSTEM_HCPCS, hcpcsModifier.get());

        // Set Coding.version for all of the mappings, if it's available.
        if (year.isPresent()) {
          // Note: Only CARRIER and DME claims have the year/version field.
          modifier.getCodingFirstRep().setVersion(year.get().toString());
        }

        item.addModifier(modifier);
      }
    }
  }

  /**
   * Transforms the common item level data elements between the {@link InpatientClaimLine} {@link
   * OutpatientClaimLine} {@link HospiceClaimLine} {@link HHAClaimLine}and {@link SNFClaimLine}
   * claim types to FHIR. The method parameter fields from {@link InpatientClaimLine} {@link
   * OutpatientClaimLine} {@link HospiceClaimLine} {@link HHAClaimLine}and {@link SNFClaimLine} are
   * listed below and their corresponding RIF CCW fields (denoted in all CAPS below from {@link
   * InpatientClaimColumn} {@link OutpatientClaimColumn} {@link HopsiceClaimColumn} {@link
   * HHAClaimColumn} and {@link SNFClaimColumn}).
   *
   * @param item the {@ ItemComponent} to modify
   * @param eob the {@ ExplanationOfBenefit} to modify
   * @param revenueCenterCode REV_CNTR,
   * @param rateAmount REV_CNTR_RATE_AMT,
   * @param totalChargeAmount REV_CNTR_TOT_CHRG_AMT,
   * @param nonCoveredChargeAmount REV_CNTR_NCVRD_CHRG_AMT,
   * @param unitCount REV_CNTR_UNIT_CNT,
   * @param nationalDrugCodeQuantity REV_CNTR_NDC_QTY,
   * @param nationalDrugCodeQualifierCode REV_CNTR_NDC_QTY_QLFR_CD,
   * @return the {@link ItemComponent}
   */
  static ItemComponent mapEobCommonItemRevenue(
      ItemComponent item,
      ExplanationOfBenefit eob,
      String revenueCenterCode,
      BigDecimal rateAmount,
      BigDecimal totalChargeAmount,
      BigDecimal nonCoveredChargeAmount,
      BigDecimal unitCount,
      Optional<BigDecimal> nationalDrugCodeQuantity,
      Optional<String> nationalDrugCodeQualifierCode) {

    // REV_CNTR => ExplanationOfBenefit.item.revenue
    item.setRevenue(createCodeableConcept(eob, CcwCodebookVariable.REV_CNTR, revenueCenterCode));

    // REV_CNTR_RATE_AMT => ExplanationOfBenefit.item.adjudication
    addAdjudication(
        item,
        createAdjudicationAmtSlice(
            CcwCodebookVariable.REV_CNTR_RATE_AMT, C4BBAdjudication.SUBMITTED, rateAmount));

    // REV_CNTR_TOT_CHRG_AMT => ExplanationOfBenefit.item.adjudication
    addAdjudication(
        item,
        createAdjudicationAmtSlice(
            CcwCodebookVariable.REV_CNTR_TOT_CHRG_AMT,
            C4BBAdjudication.SUBMITTED,
            totalChargeAmount));

    // REV_CNTR_NCVRD_CHRG_AMT => ExplanationOfBenefit.item.adjudication
    addAdjudication(
        item,
        createAdjudicationAmtSlice(
            CcwCodebookVariable.REV_CNTR_NCVRD_CHRG_AMT,
            C4BBAdjudication.NONCOVERED,
            nonCoveredChargeAmount));

    // REV_CNTR_UNIT_CNT => ExplanationOfBenefit.item.quantity
    item.setQuantity(new SimpleQuantity().setValue(unitCount));

    // REV_CNTR_NDC_QTY_QLFR_CD => ExplanationOfBenefit.item.modifier
    if (nationalDrugCodeQualifierCode.isPresent()) {
      item.getModifier()
          .add(
              TransformerUtilsV2.createCodeableConcept(
                  eob,
                  CcwCodebookVariable.REV_CNTR_NDC_QTY_QLFR_CD,
                  nationalDrugCodeQualifierCode));
    }

    // TODO: REV_CNTR_NDC_QTY needs to be mapped once mapping is updated

    return item;
  }

  /**
   * Transforms the common item level data elements between the {@link OutpatientClaimLine} {@link
   * HospiceClaimLine} and {@link HHAClaimLine} claim types to FHIR. The method parameter fields
   * from {@link OutpatientClaimLine} {@link HospiceClaimLine} and {@link HHAClaimLine} are listed
   * below and their corresponding RIF CCW fields (denoted in all CAPS below from {@link
   * OutpatientClaimColumn} {@link HopsiceClaimColumn} and {@link HHAClaimColumn}.
   *
   * @param item the {@ ItemComponent} to modify
   * @param revenueCenterDate REV_CNTR_DT,
   * @param paymentAmount REV_CNTR_PMT_AMT_AMT
   */
  static void mapEobCommonItemRevenueOutHHAHospice(
      ItemComponent item, Optional<LocalDate> revenueCenterDate, BigDecimal paymentAmount) {

    // Revenue Center Date
    // REV_CNTR_DT => ExplainationOfBenefit.item.serviced
    if (revenueCenterDate.isPresent()) {
      item.setServiced(new DateType().setValue(convertToDate(revenueCenterDate.get())));
    }

    // REV_CNTR_PMT_AMT_AMT => ExplainationOfBenefit.item.adjudication
    addAdjudication(
        item,
        createAdjudicationAmtSlice(
            CcwCodebookVariable.REV_CNTR_PMT_AMT_AMT, C4BBAdjudication.SUBMITTED, paymentAmount));
  }
}
