package app.nadel.documents.internal;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The issued invoice versions of the cooperative (RLS). */
interface InvoiceRepository extends JpaRepository<InvoiceRecord, UUID> {

    /** The latest version of the plot's invoice for the month; 0 when there is none. */
    @Query("select coalesce(max(i.version), 0) from InvoiceRecord i where i.plotId = :plotId and i.month = :month")
    int latestVersion(@Param("plotId") UUID plotId, @Param("month") LocalDate month);
}
