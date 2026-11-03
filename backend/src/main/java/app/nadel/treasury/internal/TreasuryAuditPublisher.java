package app.nadel.treasury.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.money.Money;
import app.nadel.treasury.MoneyAccountKind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes the audit events of the treasury (feature 018 §2.14, technical considerations 2.9) inside the transaction
 * that stores the change, so the Spring Modulith outbox keeps them with it ({@code audit/README.md}). Entity type
 * {@code MONEY_ACCOUNT}: the entity id is the account, the label its kind code ({@code CASH} or {@code BANK}; the client
 * names it). {@code OPENING_BALANCE_SET} has, new values only, {@code kind}, {@code amount} (two decimals) and
 * {@code date} (ISO). Codes and amounts only, no names.
 */
@Component
class TreasuryAuditPublisher {

    static final String MONEY_ACCOUNT = "MONEY_ACCOUNT";

    /** Every action the treasury writes; each is a value of the contract's {@code AuditAction}. */
    enum Action {
        OPENING_BALANCE_SET
    }

    private final ApplicationEventPublisher events;

    TreasuryAuditPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    void openingBalanceSet(AccessProfile actor, UUID accountId, MoneyAccountKind kind, Money amount, LocalDate date,
            Instant at) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        events.publishEvent(new AuditEvent(UUID.randomUUID(), tenantId, at,
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), Action.OPENING_BALANCE_SET.name(),
                MONEY_ACCOUNT, accountId, kind.name(), List.of(new FieldChange("kind", null, kind.name()),
                        new FieldChange("amount", null, amount.toString()),
                        new FieldChange("date", null, date.toString()))));
    }
}
