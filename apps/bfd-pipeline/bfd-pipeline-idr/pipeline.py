import logging
import sys
import os
from loader import PostgresLoader
import loader
from model import (
    IdrBeneficiary,
    IdrBeneficiaryHistory,
    IdrBeneficiaryMbi,
    IdrContractPbpNumber,
    IdrElectionPeriodUsage,
)
from extractor import Extractor, PostgresExtractor, SnowflakeExtractor, print_timers
import extractor

logger = logging.getLogger(__name__)


def init_logger():
    logger.setLevel(logging.INFO)
    console_handler = logging.StreamHandler()
    formatter = logging.Formatter("[%(levelname)s] %(asctime)s %(message)s")
    console_handler.setFormatter(formatter)
    logger.addHandler(console_handler)


def main():
    init_logger()
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    pg_connection = f"host={os.environ["BFD_DB_ENDPOINT"]} dbname=idr user={os.environ["BFD_DB_USERNAME"]} password={os.environ["BFD_DB_PASSWORD"]}"
    if mode == "local":
        pg_local = "host=localhost dbname=idr user=bfd password=InsecureLocalDev"
        run_pipeline(
            PostgresExtractor(
                connection_string=pg_local,
                batch_size=100_000,
            ),
            pg_local,
        )
    elif mode == "synthetic":
        run_pipeline(
            PostgresExtractor(
                connection_string=pg_connection,
                batch_size=100_000,
            ),
            pg_connection,
        )
    else:
        run_pipeline(
            SnowflakeExtractor(
                batch_size=100_000,
            ),
            pg_connection,
        )


def run_pipeline(data_extractor: Extractor, connection_string: str):
    logger.info("load start")

    history_fetch_query = """
    SELECT {COLUMNS}
    FROM cms_vdm_view_mdcr_prd.v2_mdcr_bene_hstry
    {WHERE_CLAUSE}
    ORDER BY idr_trans_efctv_ts, bene_sk
    """
    history_iter = data_extractor.extract_idr_data(
        IdrBeneficiaryHistory,
        connection_string=connection_string,
        fetch_query=history_fetch_query,
        progress_col="bene_sk",
        table="idr.beneficiary_history",
    )

    history_loader = PostgresLoader(
        connection_string=connection_string,
        table="idr.beneficiary_history",
        unique_key=["bene_sk", "idr_trans_efctv_ts"],
        sort_key="bene_sk",
        exclude_keys=["bene_xref_efctv_sk_computed"],
    )
    history_loader.load(history_iter, IdrBeneficiaryHistory)

    mbi_fetch_query = """
    SELECT {COLUMNS}
    FROM cms_vdm_view_mdcr_prd.v2_mdcr_bene_mbi_id
    {WHERE_CLAUSE}
    ORDER BY idr_trans_efctv_ts, bene_mbi_id
    """
    mbi_iter = data_extractor.extract_idr_data(
        IdrBeneficiaryMbi,
        connection_string=connection_string,
        fetch_query=mbi_fetch_query,
        progress_col="bene_mbi_id",
        table="idr.beneficiary_mbi_id",
    )

    mbi_loader = PostgresLoader(
        connection_string=connection_string,
        table="idr.beneficiary_mbi_id",
        unique_key=["bene_mbi_id", "idr_trans_efctv_ts"],
        sort_key="bene_mbi_id",
        exclude_keys=[],
    )
    mbi_loader.load(mbi_iter, IdrBeneficiaryMbi)

    bene_fetch_query = """
    SELECT {COLUMNS}
    FROM cms_vdm_view_mdcr_prd.v2_mdcr_bene
    {WHERE_CLAUSE}
    ORDER BY idr_trans_efctv_ts, bene_sk
    """
    bene_iter = data_extractor.extract_idr_data(
        IdrBeneficiary,
        connection_string=connection_string,
        fetch_query=bene_fetch_query,
        progress_col="bene_sk",
        table="idr.beneficiary",
    )

    bene_loader = PostgresLoader(
        connection_string=connection_string,
        table="idr.beneficiary",
        unique_key=["bene_sk"],
        sort_key="bene_sk",
        exclude_keys=["bene_xref_efctv_sk_computed"],
    )
    bene_loader.load(bene_iter, IdrBeneficiary)
    bene_loader.refresh_materialized_view("idr.overshare_mbis")

    # number of records in this table is relatively small (~300,000) and we don't have created/updated timestamps
    # so we can just sync all of the non-obsolete records each time
    pbp_fetch_query = data_extractor.get_query(
        IdrContractPbpNumber,
        """
        SELECT {COLUMNS}
        FROM cms_vdm_view_mdcr_prd.v2_mdcr_cntrct_pbp_num
        WHERE cntrct_pbp_sk_obslt_dt >= '9999-12-31'
        """,
    )
    pbp_iter = data_extractor.extract_many(IdrContractPbpNumber, pbp_fetch_query, {})
    pbp_loader = PostgresLoader(
        connection_string=connection_string,
        table="idr.contract_pbp_number",
        unique_key=["cntrct_pbp_sk"],
        sort_key="cntrct_pbp_sk",
        exclude_keys=[],
    )
    pbp_loader.load(pbp_iter, IdrContractPbpNumber)

    # equivalent to "select distinct on", but Snowflake has different syntax for that so it's unfortunately not portable
    prd_fetch_query = """
    WITH dupes as (
        SELECT {COLUMNS}, ROW_NUMBER() OVER (PARTITION BY bene_sk, cntrct_pbp_sk, bene_enrlmt_efctv_dt ORDER BY idr_trans_efctv_ts DESC) as row_order
        FROM cms_vdm_view_mdcr_prd.v2_mdcr_bene_elctn_prd_usg
        {WHERE_CLAUSE}
        ORDER BY idr_trans_efctv_ts, bene_sk
    )
    SELECT {COLUMNS} FROM dupes WHERE row_order = 1
    """
    contract_iter = data_extractor.extract_idr_data(
        IdrElectionPeriodUsage,
        connection_string=connection_string,
        fetch_query=prd_fetch_query,
        progress_col="bene_sk",
        table="idr.beneficiary_election_period_usage",
    )
    contract_loader = PostgresLoader(
        connection_string=connection_string,
        table="idr.beneficiary_election_period_usage",
        unique_key=["bene_sk", "cntrct_pbp_sk", "bene_enrlmt_efctv_dt"],
        sort_key="bene_sk",
        exclude_keys=[],
    )
    contract_loader.load(contract_iter, IdrElectionPeriodUsage)

    logger.info("done")
    extractor.print_timers()
    loader.print_timers()


if __name__ == "__main__":
    main()
