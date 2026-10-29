package app.nadel.members;

/**
 * How a member receives invoices, as other modules see it ({@link MemberDirectory#deliveryChannels}): the values of
 * {@code member.delivery_channel} (feature 002; the default is {@code PAPER}). Feature 016 prints the paper set from
 * it; feature 017 delivers by it.
 */
public enum DeliveryChannel {
    EMAIL, TELEGRAM, VIBER, PAPER
}
