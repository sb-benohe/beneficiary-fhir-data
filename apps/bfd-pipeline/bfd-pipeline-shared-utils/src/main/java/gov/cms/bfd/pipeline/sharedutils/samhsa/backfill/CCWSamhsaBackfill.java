package gov.cms.bfd.pipeline.sharedutils.samhsa.backfill;

import static gov.cms.bfd.pipeline.sharedutils.samhsa.backfill.QueryConstants.*;

import gov.cms.bfd.pipeline.sharedutils.TransactionManager;
import gov.cms.bfd.pipeline.sharedutils.model.TableEntry;
import java.util.List;
import org.apache.logging.log4j.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** CCW implementation for AbstractSamhsaBackfill. */
public class CCWSamhsaBackfill extends AbstractSamhsaBackfill {
  /** The claim id field. */
  private static final String CLAIM_FIELD = "clm_id";

  private static final String CARRIER_DME_LINE_NUM_FIELD = "line_num";
  private static final String LINE_NUM_FIELD = "clm_line_num";

  /** prncpal_dgns_cd column. */
  private static final String PRINCIPAL_DIAGNOSIS_CODE_COLUMN = "prncpal_dgns_cd";

  /** admtg_dgns_cd column. */
  private static final String ADMITTING_DIAGNOSIS_CODE_COLUMN = "admtg_dgns_cd";

  /** template for icd_dgns_cd enumerated column. */
  private static final String ICD_DIAGNOSIS_CODE_ENUMERATED_COLUMN = "icd_dgns_cd%d";

  /** line_icd_dgns_cd column. */
  private static final String LINE_ICD_DIAGNOSIS_CODE_COLUMN = "line_icd_dgns_cd";

  /** hcpcs_cd column. */
  private static final String HCPCS_CODE_COLUMN = "hcpcs_cd";

  /** template for icd_dgns_e_cd enumerated columns. */
  private static final String ICD_DIAGNOSIS_E_CODE_ENUMERATED_COLUMN = "icd_dgns_e_cd%d";

  /** rev_cntr_apc_hipps_cd column. */
  private static final String REV_CENTER_APC_HIPPS_CODE_COLUMN = "rev_cntr_apc_hipps_cd";

  /** template for ics_prcdr_cd enumerated columns. */
  private static final String ICD_PROCEDURE_CODE_ENUMERATED_COLUMN = "icd_prcdr_cd%d";

  /** template for rns_visit_cd enumerated column. */
  private static final String REASON_VISIT_CODE_ENUMERATED_COLUMN = "rsn_visit_cd%d";

  /** fst_dgns_e_cd column. */
  private static final String FST_DIAGNOSIS_E_CODE_COLUMN = "fst_dgns_e_cd";

  /** clm_drg_cd column. */
  private static final String DRG_CODE_COLUMN = "clm_drg_cd";

  /** From date of the claim. */
  private static final String CLAIM_FROM_DATE = "clm_from_dt";

  /** Thru date of the claim. */
  private static final String CLAIM_THRU_DATE = "clm_thru_dt";

  /**
   * Creates a concatenated string of an enumerated column (i.e. "icd_prcdr_cd1, icd_prcdr_cd2, ...
   * icd_prcdr_cd25"). This will allow it to be inserted into the query without modification.
   *
   * @param column The String for the column.
   * @param count The number of iterations of the column.
   * @return a concatenated string with the column names.
   */
  private static String enumerateColumns(String column, int count) {
    String[] enumeratedColumns = new String[count];
    for (int i = 0; i < count; i++) {
      enumeratedColumns[i] = String.format(column, i + 1); // column numbering starts at 1
    }
    return String.join(", ", enumeratedColumns);
  }

  /** The Logger. */
  static final Logger LOGGER = LoggerFactory.getLogger(CCWSamhsaBackfill.class);

  /**
   * The list of table entries for CCW claims. This is mostly used in building the queries for the
   * tables.
   */
  public enum CCW_TABLES {
    /** Carrier Claim. */
    CARRIER_CLAIMS(
        new TableEntry(
            GET_CLAIM_DATES,
            "ccw.carrier_tags",
            CLAIM_FIELD,
            CLAIM_FROM_DATE,
            CLAIM_THRU_DATE,
            Strings.EMPTY,
            "ccw.carrier_claims",
            "ccw.carrier_claims", // Should not be used, since is already a parent table. Included
            // out of an abundance of caution.
            false)),
    /** Carrier Claim Lines. */
    CARRIER_CLAIM_LINES(
        new TableEntry(
            GET_CLAIM_DATES,
            "ccw.carrier_tags",
            CLAIM_FIELD,
            Strings.EMPTY,
            Strings.EMPTY,
            CARRIER_DME_LINE_NUM_FIELD,
            "ccw.carrier_claim_lines",
            "ccw.carrier_claims",
            true)),

    /** DME Claim. */
    DME_CLAIMS(
        new TableEntry(
            GET_CLAIM_DATES,
            "ccw.dme_tags",
            CLAIM_FIELD,
            CLAIM_FROM_DATE,
            CLAIM_THRU_DATE,
            Strings.EMPTY,
            "ccw.dme_claims",
            "ccw.dme_claims", // Should not be used, since is already a parent table. Included out
            // of an abundance of caution.
            false)),
    /** DME Claim Lines. */
    DME_CLAIM_LINES(
        new TableEntry(
            GET_CLAIM_DATES,
            "ccw.dme_tags",
            CLAIM_FIELD,
            Strings.EMPTY,
            Strings.EMPTY,
            CARRIER_DME_LINE_NUM_FIELD,
            "ccw.dme_claim_lines",
            "ccw.dme_claims",
            true)),
    /** HHA Claim. */
    HHA_CLAIMS(
        new TableEntry(
            GET_CLAIM_DATES,
            "ccw.hha_tags",
            CLAIM_FIELD,
            CLAIM_FROM_DATE,
            CLAIM_THRU_DATE,
            Strings.EMPTY,
            "ccw.hha_claims",
            "ccw.hha_claims", // Should not be used, since is already a parent table. Included out
            // of an abundance of caution.
            false)),
    /** HHA Claim Lines. */
    HHA_CLAIM_LINES(
        new TableEntry(
            GET_CLAIM_DATES,
            "ccw.hha_tags",
            CLAIM_FIELD,
            Strings.EMPTY,
            Strings.EMPTY,
            LINE_NUM_FIELD,
            "ccw.hha_claim_lines",
            "ccw.hha_claims",
            true)),
    /** Hospice Claim. */
    HOSPICE_CLAIMS(
        new TableEntry(
            GET_CLAIM_DATES,
            "ccw.hospice_tags",
            CLAIM_FIELD,
            CLAIM_FROM_DATE,
            CLAIM_THRU_DATE,
            Strings.EMPTY,
            "ccw.hospice_claims",
            "ccw.hospice_claims", // Should not be used, since is already a parent table. Included
            // out of an abundance of caution.
            false)),
    /** Hospice Claim Lines. */
    HOSPICE_CLAIM_LINES(
        new TableEntry(
            GET_CLAIM_DATES,
            "ccw.hospice_tags",
            CLAIM_FIELD,
            Strings.EMPTY,
            Strings.EMPTY,
            LINE_NUM_FIELD,
            "ccw.hospice_claim_lines",
            "ccw.hospice_claims",
            true)),
    /** Inpatient Claim. */
    INPATIENT_CLAIMS(
        new TableEntry(
            GET_CLAIM_DATES,
            "ccw.inpatient_tags",
            CLAIM_FIELD,
            CLAIM_FROM_DATE,
            CLAIM_THRU_DATE,
            Strings.EMPTY,
            "ccw.inpatient_claims",
            "ccw.inpatient_claims", // Should not be used, since is already a parent table. Included
            // out of an abundance of caution.
            false)),
    /** Inpatient Claim Lines. */
    INPATIENT_CLAIM_LINES(
        new TableEntry(
            GET_CLAIM_DATES,
            "ccw.inpatient_tags",
            CLAIM_FIELD,
            Strings.EMPTY,
            Strings.EMPTY,
            LINE_NUM_FIELD,
            "ccw.inpatient_claim_lines",
            "ccw.inpatient_claims",
            true)),
    /** Outpatient Claim. */
    OUTPATIENT_CLAIMS(
        new TableEntry(
            GET_CLAIM_DATES,
            "ccw.outpatient_tags",
            CLAIM_FIELD,
            CLAIM_FROM_DATE,
            CLAIM_THRU_DATE,
            Strings.EMPTY,
            "ccw.outpatient_claims",
            "ccw.outpatient_claims", // Should not be used, since is already a parent table.
            // Included out of an abundance of caution.
            false)),
    /** Outpatient Claim Lines. */
    OUTPATIENT_CLAIM_LINES(
        new TableEntry(
            GET_CLAIM_DATES,
            "ccw.outpatient_tags",
            CLAIM_FIELD,
            Strings.EMPTY,
            Strings.EMPTY,
            LINE_NUM_FIELD,
            "ccw.outpatient_claim_lines",
            "ccw.outpatient_claims",
            true)),
    /** SNF Claim. */
    SNF_CLAIMS(
        new TableEntry(
            GET_CLAIM_DATES,
            "ccw.snf_tags",
            CLAIM_FIELD,
            CLAIM_FROM_DATE,
            CLAIM_THRU_DATE,
            Strings.EMPTY,
            "ccw.snf_claims",
            "ccw.snf_claims", // Should not be used, since is already a parent table. Included out
            // of an abundance of caution.
            false)),
    /** SNF Claim Lines. */
    SNF_CLAIM_LINES(
        new TableEntry(
            GET_CLAIM_DATES,
            "ccw.snf_tags",
            CLAIM_FIELD,
            Strings.EMPTY,
            Strings.EMPTY,
            LINE_NUM_FIELD,
            "ccw.snf_claim_lines",
            "ccw.snf_claims",
            true));

    /** The table entry. */
    private TableEntry entry;

    /**
     * Constructor.
     *
     * @param entry The tableEntry.
     */
    CCW_TABLES(TableEntry entry) {
      this.entry = entry;
    }

    /**
     * Returns the table entry.
     *
     * @return The tableEntry.
     */
    TableEntry getEntry() {
      return entry;
    }
  }

  /**
   * Constructor.
   *
   * @param transactionManager Transaction manager.
   * @param batchSize the query batch size.
   * @param logInterval The log reporting interval, in seconds.
   * @param tableEntry The table to use in this thread.
   */
  public CCWSamhsaBackfill(
      TransactionManager transactionManager,
      int batchSize,
      Long logInterval,
      CCW_TABLES tableEntry) {
    super(transactionManager, batchSize, LOGGER, logInterval, tableEntry.getEntry());
    query = getQueryByTableEntry(tableEntry);
    nonCodeFields =
        List.of(
            LINE_NUM_FIELD,
            CARRIER_DME_LINE_NUM_FIELD,
            CLAIM_FIELD,
            CLAIM_FROM_DATE,
            CLAIM_THRU_DATE);
  }

  /**
   * Returns the query for a table.
   *
   * @param tableEntry The tableEntry pojo for the table.
   * @return the query for the table.
   */
  public String getQueryByTableEntry(CCW_TABLES tableEntry) {
    switch (tableEntry) {
      case CARRIER_CLAIMS:
        return buildQueryStringTemplate(
            "ccw.carrier_claims",
            CLAIM_FIELD,
            CLAIM_FROM_DATE,
            CLAIM_THRU_DATE,
            PRINCIPAL_DIAGNOSIS_CODE_COLUMN,
            enumerateColumns(ICD_DIAGNOSIS_CODE_ENUMERATED_COLUMN, 12));
      case CARRIER_CLAIM_LINES:
        return buildQueryStringTemplate(
            "ccw.carrier_claim_lines",
            CLAIM_FIELD,
            CARRIER_DME_LINE_NUM_FIELD,
            LINE_ICD_DIAGNOSIS_CODE_COLUMN,
            HCPCS_CODE_COLUMN);

      case DME_CLAIM_LINES:
        return buildQueryStringTemplate(
            "ccw.dme_claim_lines",
            CLAIM_FIELD,
            CARRIER_DME_LINE_NUM_FIELD,
            LINE_ICD_DIAGNOSIS_CODE_COLUMN,
            HCPCS_CODE_COLUMN);

      case DME_CLAIMS:
        return buildQueryStringTemplate(
            "ccw.dme_claims",
            CLAIM_FIELD,
            CLAIM_FROM_DATE,
            CLAIM_THRU_DATE,
            PRINCIPAL_DIAGNOSIS_CODE_COLUMN,
            enumerateColumns(ICD_DIAGNOSIS_CODE_ENUMERATED_COLUMN, 12));

      case HHA_CLAIM_LINES:
        return buildQueryStringTemplate(
            "ccw.hha_claim_lines",
            CLAIM_FIELD,
            HCPCS_CODE_COLUMN,
            LINE_NUM_FIELD,
            REV_CENTER_APC_HIPPS_CODE_COLUMN);
      case HHA_CLAIMS:
        return buildQueryStringTemplate(
            "ccw.hha_claims",
            CLAIM_FIELD,
            CLAIM_FROM_DATE,
            CLAIM_THRU_DATE,
            PRINCIPAL_DIAGNOSIS_CODE_COLUMN,
            enumerateColumns(ICD_DIAGNOSIS_CODE_ENUMERATED_COLUMN, 25),
            enumerateColumns(ICD_DIAGNOSIS_E_CODE_ENUMERATED_COLUMN, 12),
            FST_DIAGNOSIS_E_CODE_COLUMN);
      case HOSPICE_CLAIM_LINES:
        return buildQueryStringTemplate(
            "ccw.hospice_claim_lines", CLAIM_FIELD, LINE_NUM_FIELD, HCPCS_CODE_COLUMN);
      case HOSPICE_CLAIMS:
        return buildQueryStringTemplate(
            "ccw.hospice_claims",
            CLAIM_FIELD,
            CLAIM_FROM_DATE,
            CLAIM_THRU_DATE,
            PRINCIPAL_DIAGNOSIS_CODE_COLUMN,
            enumerateColumns(ICD_DIAGNOSIS_CODE_ENUMERATED_COLUMN, 25),
            enumerateColumns(ICD_DIAGNOSIS_E_CODE_ENUMERATED_COLUMN, 12),
            FST_DIAGNOSIS_E_CODE_COLUMN);

      case SNF_CLAIM_LINES:
        return buildQueryStringTemplate(
            "ccw.snf_claim_lines", CLAIM_FIELD, LINE_NUM_FIELD, HCPCS_CODE_COLUMN);
      case SNF_CLAIMS:
        return buildQueryStringTemplate(
            "ccw.snf_claims",
            CLAIM_FIELD,
            CLAIM_FROM_DATE,
            CLAIM_THRU_DATE,
            DRG_CODE_COLUMN,
            enumerateColumns(ICD_DIAGNOSIS_CODE_ENUMERATED_COLUMN, 25),
            enumerateColumns(ICD_DIAGNOSIS_E_CODE_ENUMERATED_COLUMN, 12),
            enumerateColumns(ICD_PROCEDURE_CODE_ENUMERATED_COLUMN, 25),
            FST_DIAGNOSIS_E_CODE_COLUMN,
            ADMITTING_DIAGNOSIS_CODE_COLUMN,
            PRINCIPAL_DIAGNOSIS_CODE_COLUMN);
      case INPATIENT_CLAIM_LINES:
        return buildQueryStringTemplate(
            "ccw.inpatient_claim_lines", CLAIM_FIELD, LINE_NUM_FIELD, HCPCS_CODE_COLUMN);
      case INPATIENT_CLAIMS:
        return buildQueryStringTemplate(
            "ccw.inpatient_claims",
            CLAIM_FIELD,
            CLAIM_FROM_DATE,
            CLAIM_THRU_DATE,
            DRG_CODE_COLUMN,
            enumerateColumns(ICD_DIAGNOSIS_CODE_ENUMERATED_COLUMN, 25),
            enumerateColumns(ICD_DIAGNOSIS_E_CODE_ENUMERATED_COLUMN, 12),
            ADMITTING_DIAGNOSIS_CODE_COLUMN,
            FST_DIAGNOSIS_E_CODE_COLUMN,
            PRINCIPAL_DIAGNOSIS_CODE_COLUMN,
            enumerateColumns(ICD_PROCEDURE_CODE_ENUMERATED_COLUMN, 25));
      case OUTPATIENT_CLAIM_LINES:
        return buildQueryStringTemplate(
            "ccw.outpatient_claim_lines",
            CLAIM_FIELD,
            LINE_NUM_FIELD,
            HCPCS_CODE_COLUMN,
            REV_CENTER_APC_HIPPS_CODE_COLUMN);
      case OUTPATIENT_CLAIMS:
        return buildQueryStringTemplate(
            "ccw.outpatient_claims",
            CLAIM_FIELD,
            CLAIM_FROM_DATE,
            CLAIM_THRU_DATE,
            enumerateColumns(ICD_DIAGNOSIS_CODE_ENUMERATED_COLUMN, 25),
            enumerateColumns(ICD_DIAGNOSIS_E_CODE_ENUMERATED_COLUMN, 12),
            PRINCIPAL_DIAGNOSIS_CODE_COLUMN,
            FST_DIAGNOSIS_E_CODE_COLUMN,
            enumerateColumns(REASON_VISIT_CODE_ENUMERATED_COLUMN, 3),
            enumerateColumns(ICD_PROCEDURE_CODE_ENUMERATED_COLUMN, 25));
      default:
        throw new IllegalArgumentException("Unknown tableEntry enum.");
    }
  }

  /** {@inheritDoc} */
  @Override
  protected Long convertClaimId(String claim) {
    return Long.valueOf(claim);
  }
}
