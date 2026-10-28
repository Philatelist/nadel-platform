package app.nadel.documents.internal;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** The debt certificates of the cooperative (RLS). */
interface CertificateRepository extends JpaRepository<CertificateRecord, UUID> {

    /** The valid certificates. */
    Page<CertificateRecord> findByAnnulledAtIsNull(Pageable pageable);

    /** The annulled certificates. */
    Page<CertificateRecord> findByAnnulledAtIsNotNull(Pageable pageable);

    /** Whether the plot has a certificate, valid or annulled (the demo documents issue one only once). */
    boolean existsByPlotId(UUID plotId);
}
