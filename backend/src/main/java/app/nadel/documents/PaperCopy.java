package app.nadel.documents;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One printed copy of a plot's invoice for one member ({@link PaperRecipients}).
 *
 * @param memberId       who the copy is for
 * @param label          how the copy names its recipient («Примірник для {label}»), "Last First"; personal data
 * @param linkQrPayload  the content of the small QR code «Отримувати рахунки в Telegram» (the recipient's personal
 *                       deep link to the cooperative's bot); empty prints no such code. Never log it
 */
public record PaperCopy(UUID memberId, String label, Optional<String> linkQrPayload) {

    public PaperCopy {
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(linkQrPayload, "linkQrPayload");
    }

    /** Never prints the label or the link. */
    @Override
    public String toString() {
        return "PaperCopy[memberId=" + memberId + ", link=" + linkQrPayload.isPresent() + "]";
    }
}
