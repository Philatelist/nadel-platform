package app.nadel.ledger.internal;

import app.nadel.ledger.BookingMonths;
import app.nadel.tenancy.TenantContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link BookingMonths} for other modules: {@link BillingMonths#openForBooking} for the bound cooperative. */
@Component
class LedgerBookingMonths implements BookingMonths {

    private final BillingMonths billingMonths;

    LedgerBookingMonths(BillingMonths billingMonths) {
        this.billingMonths = billingMonths;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public YearMonth bookFor(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return billingMonths.openForBooking(TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("No cooperative is bound")), date).yearMonth();
    }
}
