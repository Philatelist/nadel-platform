package app.nadel.notifications.internal;

/**
 * The channel one delivery goes through (column {@code delivery.channel}): the member's preferred channel after the
 * rule of feature 017 §2.3 ({@link ChannelResolver}). Viber is not one: a Viber member gets paper.
 */
enum Channel {
    EMAIL,
    TELEGRAM,
    PAPER
}
