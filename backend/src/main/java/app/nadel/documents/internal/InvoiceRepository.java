package app.nadel.documents.internal;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The issued invoice versions of the cooperative (RLS). */
interface InvoiceRepository extends JpaRepository<InvoiceRecord, UUID> {

    /** The latest version of the plot's invoice for the month; 0 when there is none. */
    @Query("select coalesce(max(i.version), 0) from InvoiceRecord i where i.plotId = :plotId and i.month = :month")
    int latestVersion(@Param("plotId") UUID plotId, @Param("month") LocalDate month);

    /**
     * The latest version of each given plot's invoice for the month, as {@code [plotId, version, contentHash]}; a plot
     * without an invoice of the month has no row (the month build compares with it).
     */
    @Query("select i.plotId, i.version, i.contentHash from InvoiceRecord i where i.month = :month "
            + "and i.plotId in :plotIds and i.version = (select max(j.version) from InvoiceRecord j "
            + "where j.plotId = i.plotId and j.month = i.month)")
    List<Object[]> latestOfPlots(@Param("month") LocalDate month, @Param("plotIds") Collection<UUID> plotIds);

    /** The plots that have an invoice for the month. */
    @Query("select distinct i.plotId from InvoiceRecord i where i.month = :month")
    List<UUID> plotsInvoicedIn(@Param("month") LocalDate month);

    /**
     * The latest version of each plot's invoice for the month, as {@code [id, plotId, issuedAt]} (the print set).
     */
    @Query("select i.id, i.plotId, i.issuedAt from InvoiceRecord i where i.month = :month "
            + "and i.version = (select max(j.version) from InvoiceRecord j "
            + "where j.plotId = i.plotId and j.month = i.month)")
    List<Object[]> latestOfMonth(@Param("month") LocalDate month);

    /** The latest version of the plot's invoice for the month. */
    Optional<InvoiceRecord> findFirstByPlotIdAndMonthOrderByVersionDesc(UUID plotId, LocalDate month);
}
