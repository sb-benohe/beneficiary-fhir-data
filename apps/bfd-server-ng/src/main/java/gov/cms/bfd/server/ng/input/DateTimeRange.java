package gov.cms.bfd.server.ng.input;

import ca.uhn.fhir.rest.param.DateRangeParam;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Represents a range of datetime bounds.
 *
 * @param lowerBound lower datetime bound
 * @param upperBound upper datetime bound
 */
public record DateTimeRange(
    Optional<DateTimeBound> lowerBound, Optional<DateTimeBound> upperBound) {

  /** Creates an empty {@link DateTimeRange}. */
  public DateTimeRange() {
    this(Optional.empty(), Optional.empty());
  }

  /**
   * Creates a {@link DateTimeRange} from the given FHIR parameter.
   *
   * @param dateRangeParam FHIR date range parameter
   */
  public DateTimeRange(DateRangeParam dateRangeParam) {
    this(
        Optional.ofNullable(dateRangeParam.getLowerBound()).map(DateTimeBound::new),
        Optional.ofNullable(dateRangeParam.getUpperBound()).map(DateTimeBound::new));
  }

  /**
   * Returns the lower datetime.
   *
   * @return datetime
   */
  public Optional<LocalDateTime> getLowerBoundDateTime() {
    return lowerBound.map(DateTimeBound::bound);
  }

  /**
   * Returns the upper datetime.
   *
   * @return datetime
   */
  public Optional<LocalDateTime> getUpperBoundDateTime() {
    return upperBound.map(DateTimeBound::bound);
  }

  /**
   * Returns the lower bound SQL operator used to filter the datetime.
   *
   * @return datetime
   */
  public String getLowerBoundSqlOperator() {
    return getSqlOperator(lowerBound, ">", ">=");
  }

  /**
   * Returns the upper bound SQL operator used to filter the datetime.
   *
   * @return datetime
   */
  public String getUpperBoundSqlOperator() {
    return getSqlOperator(upperBound, "<", "<=");
  }

  private String getSqlOperator(
      Optional<DateTimeBound> bound, String exclusiveOperator, String inclusiveOperator) {
    return bound
        .map(
            b ->
                switch (b.boundType()) {
                  case DateTimeBoundType.EXCLUSIVE -> exclusiveOperator;
                  case DateTimeBoundType.INCLUSIVE -> inclusiveOperator;
                })
        // If this case as hit, the boundary condition is null, so the value here has no effect
        // We need to return something to make the SQL query valid though.
        .orElse("!=");
  }
}
