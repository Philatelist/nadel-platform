package app.nadel.members.internal;

/** How a member receives invoices. EMAIL, TELEGRAM and VIBER need the matching contact (enforced from slice 3). */
enum DeliveryChannel {
    EMAIL, TELEGRAM, VIBER, PAPER
}
