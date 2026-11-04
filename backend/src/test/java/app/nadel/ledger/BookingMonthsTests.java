package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.tenancy.TenantContext;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link BookingMonths} for other modules (feature 018 §2.2, technical considerations 2.3): an entry dated in an open
 * month belongs to that month, one dated in a closed month to the earliest open month after it (opened when missing),
 * in the caller's transaction only.
 */
class BookingMonthsTests extends FreshCooperativeSupport {

    @Autowired
    BookingMonths bookingMonths;

    @Autowired
    TransactionTemplate tx;

    @Test
    void anEntryBelongsToItsOwnOpenMonthOrToTheEarliestOpenMonthAfterAClosedOne() {
        YearMonth previous = current().minusMonths(1);

        assertThat(bookFor(previous)).isEqualTo(previous);
        closeMonth(previous.toString());

        assertThat(bookFor(previous)).isEqualTo(current());
        YearMonth today = TenantContext.runAs(tenant, () -> tx.execute(status ->
                bookingMonths.bookFor(current().atDay(1))));
        assertThat(today).isEqualTo(current());
        assertThat(owner().queryForObject("select count(*) from billing_month where tenant_id = ? and month = ?",
                Integer.class, tenant, current().atDay(1))).as("the open month was opened").isEqualTo(1);
    }

    @Test
    void itRunsOnlyInTheCallersTransaction() {
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> bookingMonths.bookFor(current().atDay(1))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private YearMonth bookFor(YearMonth month) {
        return TenantContext.runAs(tenant, () -> tx.execute(status -> bookingMonths.bookFor(month.atDay(15))));
    }
}
