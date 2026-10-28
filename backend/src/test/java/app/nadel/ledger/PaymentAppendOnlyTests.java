package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The payment tables are ledger tables: payment and allocation are append-only (privileges, trigger, maintenance
 * bypass), receipt_counter only moves forward by one, and the rules a CHECK cannot express are triggers (reversal and
 * allocation guards, receipt exclusion, deferred cap backstops).
 */
class PaymentAppendOnlyTests extends PaymentTestSupport {

    private static final String PAYMENT_INSERT = "insert into payment (id, tenant_id, plot_id, kind, source, amount, "
            + "received_on, billing_month_id, group_id, receipt_number, bank_reference, payer_note_enc, "
            + "reverses_payment_id, reason) values (?, ?, ?, ?, ?, ?::numeric, date '2031-01-15', ?, ?, ?, ?, ?, ?, ?)";
    private static final String ALLOCATION_INSERT = "insert into allocation (tenant_id, payment_id, charge_id, plot_id, "
            + "amount, releases_allocation_id, origin, release_cause) values (?, ?, ?, ?, ?::numeric, ?, ?, ?)";

    private Side demo;
    private UUID charge;
    private UUID payment;
    private UUID allocation;

    @BeforeEach
    void createFixtures() {
        demo = side(DEMO_TENANT);
        charge = insertCharge(demo, "PAYAPP:1");
        payment = cash(demo, "25.00", 9001);
        allocation = allocate(demo, payment, charge, "6.00");
    }

    // ---- the application role: privileges

    @Test
    void theApplicationRoleCannotUpdateDeleteOrTruncatePaymentsAndAllocations() {
        for (String table : new String[] {"payment", "allocation"}) {
            assertDenied(table, sql -> sql.update("update " + table + " set tenant_id = tenant_id"));
            assertDenied(table, sql -> sql.update("delete from " + table));
            assertDenied(table, sql -> {
                sql.execute("truncate " + table);
                return null;
            });
        }
        assertDenied("receipt_counter", sql -> sql.update("delete from receipt_counter"));
        assertDenied("receipt_counter", sql -> {
            sql.execute("truncate receipt_counter");
            return null;
        });
        assertRowsUnchanged();
    }

    @Test
    void theApplicationRoleMayInsertAndReadPaymentsAllocationsAndCounters() {
        inTenant(DEMO_TENANT, sql -> {
            sql.update("insert into receipt_counter (tenant_id, last_number) values (?, 0)", DEMO_TENANT);
            sql.update("update receipt_counter set last_number = last_number + 1");
            sql.update(PAYMENT_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(), "PAYMENT", "BANK", "5.00",
                    demo.month(), UUID.randomUUID(), null, "BANK-1", null, null, null);
            return sql.update(ALLOCATION_INSERT, DEMO_TENANT, payment, charge, demo.plot(), "1.00", null, "ENTRY",
                    null);
        });
        try {
            Integer payments = inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                    "select count(*) from payment where plot_id = ?", Integer.class, demo.plot()));
            Long last = inTenant(DEMO_TENANT, sql -> sql.queryForObject("select last_number from receipt_counter",
                    Long.class));
            assertThat(payments).isEqualTo(2);
            assertThat(last).isEqualTo(1L);
        } finally {
            ownerJdbc().update("delete from receipt_counter where tenant_id = ?", DEMO_TENANT);
        }
    }

    // ---- the owner: trigger

    @Test
    void theOwnerIsRefusedByTheTrigger() {
        for (String table : new String[] {"payment", "allocation"}) {
            assertTrigger(table, "UPDATE", () -> ownerJdbc().update("update " + table + " set tenant_id = tenant_id"));
            assertTrigger(table, "DELETE", () -> ownerJdbc().update("delete from " + table));
        }
        assertTrigger("allocation", "TRUNCATE", () -> ownerJdbc().execute("truncate allocation"));
        // payment is referenced by allocation, so it is truncated together with it
        assertThatThrownBy(() -> ownerJdbc().execute("truncate payment, allocation"))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("is append-only: TRUNCATE");
        assertRowsUnchanged();
    }

    @Test
    void nadelOwnerWithoutTheSettingIsRefused() {
        assertThatThrownBy(() -> asNadelOwner(false, sql -> sql.update("delete from allocation where id = ?",
                allocation))).isInstanceOf(DataAccessException.class).hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> asNadelOwner(false, sql -> sql.update("update payment set amount = 1 where id = ?",
                payment))).hasStackTraceContaining("append-only");
        assertRowsUnchanged();
    }

    @Test
    void nadelOwnerWithTheSettingMayChangeAndDeleteLedgerRows() {
        asLedgerOwner(sql -> {
            assertThat(sql.update("update payment set bank_reference = null where id = ?", payment)).isEqualTo(1);
            assertThat(sql.update("delete from allocation where id = ?", allocation)).isEqualTo(1);
            assertThat(sql.update("delete from payment where id = ?", payment)).isEqualTo(1);
        });

        assertThat(ownerJdbc().queryForObject("select count(*) from payment where id = ?", Integer.class, payment))
                .isZero();
        assertThat(ownerJdbc().queryForObject("select count(*) from allocation where id = ?", Integer.class,
                allocation)).isZero();
    }

    @Test
    void theMaintenancePathMayDeleteAndReinsertConsistentlyDespiteTheBackstops() {
        asLedgerOwner(sql -> {
            sql.update("delete from allocation where id = ?", allocation);
            sql.update("delete from payment where id = ?", payment);
            sql.update("delete from charge where id = ?", charge);
            // the demo data path: a charge, a payment and an allocation that fit together are written again
            sql.update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                    + "amount, service_from, service_to, accrued_on) values (?, ?, ?, ?, ?, 'REGULAR', 'TARIFF', "
                    + "10.00, date '2031-01-01', date '2031-01-31', date '2031-01-31')", charge, DEMO_TENANT,
                    demo.plot(), demo.chargeType(), demo.month());
            sql.update(PAYMENT_INSERT, payment, DEMO_TENANT, demo.plot(), "PAYMENT", "CASH", "25.00", demo.month(),
                    UUID.randomUUID(), 9001L, null, null, null, null);
            sql.update(ALLOCATION_INSERT, DEMO_TENANT, payment, charge, demo.plot(), "10.00", null, "ENTRY", null);
        });

        assertThat(ownerJdbc().queryForObject("select sum(amount)::text from allocation where charge_id = ?",
                String.class, charge)).isEqualTo("10.00");
    }

    @Test
    void theApplicationRoleIsRefusedEvenWithTheSetting() {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> {
            sql.execute("set local app.ledger_maintenance = 'on'");
            return sql.update("delete from payment where id = ?", payment);
        })).isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied for table payment");
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> {
            sql.execute("set local app.ledger_maintenance = 'on'");
            return sql.update("update allocation set origin = 'ENTRY' where id = ?", allocation);
        })).isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied for table allocation");
        assertRowsUnchanged();
    }

    // ---- payment: CHECKs

    @Test
    void kindAndSourceAreChecked() {
        // the amount CHECK is evaluated first (alphabetical order) and no amount fits an unknown kind
        assertCheck("payment_amount_ck", () -> insertPayment("REFUND", "CASH", "5.00", 11L, null, null, null, null));
        assertThat(ownerJdbc().queryForObject("select pg_get_constraintdef(oid) from pg_constraint "
                + "where conname = 'payment_kind_ck'", String.class)).contains("PAYMENT_REVERSAL");
        assertCheck("payment_source_ck", () -> insertPayment("PAYMENT", "CARD", "5.00", null, null, null, null, null));
    }

    @Test
    void thePaymentSignFollowsItsKind() {
        assertCheck("payment_amount_ck", () -> insertPayment("PAYMENT", "BANK", "-5.00", null, null, null, null, null));
        assertCheck("payment_amount_ck", () -> insertPayment("PAYMENT", "BANK", "0.00", null, null, null, null, null));
        // the original is unknown to the guard, so the CHECK is reached
        assertCheck("payment_amount_ck", () -> insertPayment("PAYMENT_REVERSAL", "BANK", "5.00", null, null, null,
                UUID.randomUUID(), "Wrong"));
    }

    @Test
    void aReversalPointsAtItsOriginalAndOnlyAReversalDoes() {
        assertCheck("payment_reversal_ck", () -> insertPayment("PAYMENT_REVERSAL", "BANK", "-5.00", null, null, null,
                null, "Wrong"));
        assertCheck("payment_reversal_ck", () -> insertPayment("PAYMENT", "BANK", "5.00", null, null, null,
                UUID.randomUUID(), null));
        assertCheck("payment_not_self_reversal_ck", () -> {
            UUID id = UUID.randomUUID();
            return ownerJdbc().update(PAYMENT_INSERT, id, DEMO_TENANT, demo.plot(), "PAYMENT_REVERSAL", "BANK", "-5.00",
                    demo.month(), UUID.randomUUID(), null, null, null, id, "Wrong");
        });
    }

    @Test
    void aReversalNeedsAReasonOfOneToFiveHundredCharactersAndAPaymentHasNone() {
        UUID original = UUID.randomUUID();
        assertCheck("payment_reason_ck", () -> insertPayment("PAYMENT_REVERSAL", "BANK", "-5.00", null, null, null,
                original, null));
        assertCheck("payment_reason_ck", () -> insertPayment("PAYMENT_REVERSAL", "BANK", "-5.00", null, null, null,
                original, "   "));
        assertCheck("payment_reason_ck", () -> insertPayment("PAYMENT_REVERSAL", "BANK", "-5.00", null, null, null,
                original, "x".repeat(501)));
        assertCheck("payment_reason_ck", () -> insertPayment("PAYMENT", "BANK", "5.00", null, null, null, null,
                "A reason"));
    }

    @Test
    void exactlyTheCashPaymentsCarryAPositiveReceiptNumber() {
        assertCheck("payment_receipt_ck", () -> insertPayment("PAYMENT", "CASH", "5.00", null, null, null, null, null));
        assertCheck("payment_receipt_ck", () -> insertPayment("PAYMENT", "BANK", "5.00", 12L, null, null, null, null));
        assertCheck("payment_receipt_ck", () -> insertPayment("PAYMENT", "CASH", "5.00", 0L, null, null, null, null));
        assertCheck("payment_receipt_ck", () -> insertPayment("PAYMENT_REVERSAL", "CASH", "-5.00", 13L, null, null,
                UUID.randomUUID(), "Wrong"));
    }

    @Test
    void aBankReferenceBelongsToABankPaymentAndHasOneToSixtyFourCharacters() {
        assertCheck("payment_bank_reference_ck", () -> insertPayment("PAYMENT", "CASH", "5.00", 14L, "REF", null, null,
                null));
        assertCheck("payment_bank_reference_ck", () -> insertPayment("PAYMENT", "BANK", "5.00", null, "x".repeat(65),
                null, null, null));
        assertCheck("payment_bank_reference_ck", () -> insertPayment("PAYMENT", "BANK", "5.00", null, " ", null, null,
                null));
        assertCheck("payment_bank_reference_ck", () -> insertPayment("PAYMENT_REVERSAL", "BANK", "-5.00", null, "REF",
                null, UUID.randomUUID(), "Wrong"));
        insertPayment("PAYMENT", "BANK", "5.00", null, "x".repeat(64), null, null, null);
    }

    @Test
    void aPayerNoteIsSizeBoundedAndBelongsToAPayment() {
        assertCheck("payment_payer_note_ck", () -> insertPayment("PAYMENT", "BANK", "5.00", null, null, new byte[0],
                null, null));
        assertCheck("payment_payer_note_ck", () -> insertPayment("PAYMENT", "BANK", "5.00", null, null,
                new byte[2049], null, null));
        assertCheck("payment_payer_note_ck", () -> insertPayment("PAYMENT_REVERSAL", "BANK", "-5.00", null, null,
                new byte[16], UUID.randomUUID(), "Wrong"));
        insertPayment("PAYMENT", "BANK", "5.00", null, null, new byte[2048], null, null);
    }

    // ---- payment: reversal guard and uniqueness

    @Test
    void aReversalTargetsAPaymentOfTheSamePlotWithTheNegatedAmount() {
        UUID second = extraPlot(DEMO_TENANT);
        // a payment without allocations, so only the guard is under test
        UUID free = cash(demo, "4.00", 9003);
        // wrong plot
        assertGuard("payment_target_guard", () -> ownerJdbc().update(PAYMENT_INSERT, UUID.randomUUID(), DEMO_TENANT,
                second, "PAYMENT_REVERSAL", "CASH", "-4.00", demo.month(), UUID.randomUUID(), null, null, null,
                free, "Wrong"));
        // wrong amount, both a part and too much
        assertGuard("payment_target_guard", () -> insertPayment("PAYMENT_REVERSAL", "CASH", "-3.99", null, null, null,
                free, "Wrong"));
        assertGuard("payment_target_guard", () -> insertPayment("PAYMENT_REVERSAL", "CASH", "-4.01", null, null, null,
                free, "Wrong"));
        // the reversal of a reversal
        UUID reversal = reversePayment(demo, free);
        assertGuard("payment_target_guard", () -> insertPayment("PAYMENT_REVERSAL", "CASH", "4.00", null, null, null,
                reversal, "Wrong"));
        assertGuard("payment_target_guard", () -> insertPayment("PAYMENT_REVERSAL", "CASH", "-4.00", null, null, null,
                reversal, "Wrong"));
    }

    @Test
    void aPaymentIsReversedAtMostOnce() {
        // the allocation is released first, so only the uniqueness is under test
        asNadelOwner(false, sql -> {
            sql.update("insert into payment (tenant_id, plot_id, kind, source, amount, received_on, billing_month_id, "
                    + "group_id, receipt_number, reverses_payment_id, reason) values (?, ?, 'PAYMENT_REVERSAL', 'CASH', "
                    + "-25.00, date '2031-01-15', ?, ?, null, ?, 'First')", DEMO_TENANT, demo.plot(), demo.month(),
                    UUID.randomUUID(), payment);
            release(sql, demo, allocation, payment, charge, "-6.00");
        });

        assertThatThrownBy(() -> reversePayment(demo, payment))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                .hasMessageContaining("payment_reversed_once_uq");
    }

    // ---- payment: receipt exclusion

    @Test
    void aReceiptNumberBelongsToOneGroup() {
        // 9001 is held by the group of the fixture payment
        assertThatThrownBy(() -> cash(demo, "5.00", 9001))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23P01"))
                .hasMessageContaining("payment_receipt_group_excl");
    }

    @Test
    void thePartsOfOneCashGroupShareItsReceiptNumber() {
        UUID second = extraPlot(DEMO_TENANT);
        UUID group = UUID.randomUUID();

        payment(demo, demo.plot(), "CASH", "5.00", 9002L, group);
        payment(demo, second, "CASH", "7.00", 9002L, group);

        assertThat(ownerJdbc().queryForObject("select count(*) from payment where receipt_number = 9002",
                Integer.class)).isEqualTo(2);
    }

    // ---- receipt_counter

    @Test
    void theCounterMovesForwardByOneOnly() {
        counter(DEMO_TENANT, 5);

        assertCheck("receipt_counter_step", () -> ownerJdbc().update(
                "update receipt_counter set last_number = 7 where tenant_id = ?", DEMO_TENANT));
        assertCheck("receipt_counter_step", () -> ownerJdbc().update(
                "update receipt_counter set last_number = 5 where tenant_id = ?", DEMO_TENANT));
        assertCheck("receipt_counter_step", () -> ownerJdbc().update(
                "update receipt_counter set last_number = 4 where tenant_id = ?", DEMO_TENANT));
        assertCheck("receipt_counter_step", () -> ownerJdbc().update(
                "update receipt_counter set last_number = 0 where tenant_id = ?", DEMO_TENANT));
        assertThat(ownerJdbc().update("update receipt_counter set last_number = last_number + 1 where tenant_id = ?",
                DEMO_TENANT)).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select last_number from receipt_counter where tenant_id = ?",
                Long.class, DEMO_TENANT)).isEqualTo(6L);
        assertCheck("receipt_counter_last_number_ck", () -> ownerJdbc().update(
                "insert into receipt_counter (tenant_id, last_number) values (?, -1)", OTHER_TENANT));
    }

    // ---- allocation: CHECKs

    @Test
    void anAllocationHasAnOriginAndAReleaseHasACause() {
        UUID other = payment;
        assertCheck("allocation_amount_ck", () -> insertAllocation(other, "0.00", null, null, null));
        // positive: origin required, no release target, no cause
        assertCheck("allocation_origin_ck", () -> insertAllocation(other, "1.00", null, null, null));
        assertCheck("allocation_origin_ck", () -> insertAllocation(other, "1.00", null, "MAGIC", null));
        assertCheck("allocation_release_ck", () -> insertAllocation(other, "1.00", allocation, "ENTRY", null));
        assertCheck("allocation_release_cause_ck", () -> insertAllocation(other, "1.00", null, "ENTRY", "REALLOCATED"));
        // negative: cause required, no origin, a target
        assertCheck("allocation_release_ck", () -> insertAllocation(other, "-1.00", null, null, "REALLOCATED"));
        assertCheck("allocation_release_cause_ck", () -> insertAllocation(other, "-1.00", allocation, null, null));
        assertCheck("allocation_release_cause_ck", () -> insertAllocation(other, "-1.00", allocation, null, "MAGIC"));
        assertCheck("allocation_origin_ck", () -> insertAllocation(other, "-1.00", allocation, "ENTRY", "REALLOCATED"));
        for (String origin : new String[] {"ENTRY", "REALLOCATION", "PREPAYMENT_MANUAL", "PREPAYMENT_AUTO"}) {
            insertAllocation(other, "0.50", null, origin, null);
        }
        for (String cause : new String[] {"CHARGE_REVERSED", "CHARGE_ADJUSTED", "PAYMENT_REVERSED", "REALLOCATED"}) {
            insertAllocation(other, "-0.50", allocation, null, cause);
        }
    }

    // ---- allocation: guard

    @Test
    void anAllocationNeverTargetsAReversedChargeOrAReversedPayment() {
        UUID reversedCharge = insertCharge(demo, "PAYAPP:rev");
        reverseCharge(ownerJdbc(), demo, reversedCharge);
        assertGuard("allocation_guard", () -> allocate(demo, payment, reversedCharge, "1.00"));

        UUID reversedPayment = bank(demo, "9.00");
        reversePayment(demo, reversedPayment);
        assertGuard("allocation_guard", () -> allocate(demo, reversedPayment, charge, "1.00"));
    }

    @Test
    void anAllocationTargetsARegularOrOpeningChargeAndAPaymentNotItsReversal() {
        UUID reversal = reverseCharge(ownerJdbc(), demo, insertCharge(demo, "PAYAPP:orig"));
        assertGuard("allocation_guard", () -> allocate(demo, payment, reversal, "1.00"));

        UUID adjustment = adjustCharge(ownerJdbc(), demo, charge, "-1.00");
        assertGuard("allocation_guard", () -> allocate(demo, payment, adjustment, "1.00"));

        UUID paymentReversal = reversePayment(demo, bank(demo, "9.00"));
        assertGuard("allocation_guard", () -> allocate(demo, paymentReversal, charge, "1.00"));

        // an opening balance is allocatable
        UUID opening = UUID.randomUUID();
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on) values (?, ?, ?, ?, ?, 'OPENING', 'OPENING', 3.00, "
                + "date '2031-01-01', date '2031-01-31', date '2031-01-31')", opening, DEMO_TENANT, demo.plot(),
                demo.chargeType(), demo.month());
        allocate(demo, payment, opening, "3.00");
    }

    @Test
    void aReleaseNeverExceedsWhatIsLeftOfItsAllocation() {
        // 6.00 allocated: 2.00 + 3.00 released, 1.00 left
        release(demo, allocation, payment, charge, "-2.00");
        release(demo, allocation, payment, charge, "-3.00");

        assertGuard("allocation_guard", () -> release(demo, allocation, payment, charge, "-1.01"));
        assertGuard("allocation_guard", () -> release(demo, allocation, payment, charge, "-6.00"));
        release(demo, allocation, payment, charge, "-1.00");
        assertGuard("allocation_guard", () -> release(demo, allocation, payment, charge, "-0.01"));
        assertThat(ownerJdbc().queryForObject("select sum(amount)::text from allocation where payment_id = ?",
                String.class, payment)).isEqualTo("0.00");
    }

    @Test
    void aReleaseTargetsAPositiveAllocation() {
        UUID release = release(demo, allocation, payment, charge, "-1.00");

        assertGuard("allocation_guard", () -> release(demo, release, payment, charge, "-1.00"));
    }

    // ---- deferred backstops

    @Test
    void theNetAllocationsOfAPaymentNeverExceedItsAmountAtCommit() {
        UUID small = bank(demo, "15.00");
        UUID second = insertCharge(demo, "PAYAPP:2");
        UUID third = insertCharge(demo, "PAYAPP:3");
        UUID onSecond = allocate(demo, small, second, "10.00");
        allocate(demo, small, third, "4.00");

        // 14.00 + 1.01 > 15.00, and both charges have room
        assertBackstop("allocation_payment_cap", () -> allocate(demo, small, third, "1.01"));
        // a release makes room again, in the same transaction as the allocation it makes room for
        asNadelOwner(false, sql -> {
            release(sql, demo, onSecond, small, second, "-1.00");
            sql.update(ALLOCATION_INSERT, DEMO_TENANT, small, third, demo.plot(), "2.00", null, "REALLOCATION", null);
        });
        assertThat(ownerJdbc().queryForObject("select sum(amount)::text from allocation where payment_id = ?",
                String.class, small)).isEqualTo("15.00");
    }

    @Test
    void theNetAllocationsToAChargeNeverExceedItsEffectiveAmountAtCommit() {
        UUID second = bank(demo, "10.00");
        // the charge is 10.00 and holds 6.00 already
        assertBackstop("allocation_charge_cap", () -> allocate(demo, second, charge, "4.01"));
        allocate(demo, second, charge, "4.00");

        assertThat(ownerJdbc().queryForObject("select sum(amount)::text from allocation where charge_id = ?",
                String.class, charge)).isEqualTo("10.00");
    }

    @Test
    void aChargeReversalNeedsItsAllocationsReleasedInTheSameTransaction() {
        assertBackstop("allocation_charge_cap", () -> reverseCharge(ownerJdbc(), demo, charge));

        asNadelOwner(false, sql -> {
            reverseCharge(sql, demo, charge);
            release(sql, demo, allocation, payment, charge, "-6.00");
        });

        assertThat(count("charge", "reverses_charge_id = '" + charge + "'")).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select sum(amount)::text from allocation where charge_id = ?",
                String.class, charge)).isEqualTo("0.00");
    }

    @Test
    void aChargeAdjustmentMustLeaveRoomForItsAllocationsAtCommit() {
        // effective amount 10.00 - 5.00 = 5.00 < 6.00 allocated
        assertBackstop("allocation_charge_cap", () -> adjustCharge(ownerJdbc(), demo, charge, "-5.00"));
        // 10.00 - 4.00 = 6.00 holds exactly; a positive adjustment never lowers anything
        adjustCharge(ownerJdbc(), demo, charge, "-4.00");
        adjustCharge(ownerJdbc(), demo, charge, "2.00");
        // effective amount is now 8.00; with the release in the same transaction a further -4.00 holds: 4.00 = 6.00 - 2.00
        asNadelOwner(false, sql -> {
            adjustCharge(sql, demo, charge, "-4.00");
            release(sql, demo, allocation, payment, charge, "-2.00");
        });
        assertThat(ownerJdbc().queryForObject("select sum(amount)::text from allocation where charge_id = ?",
                String.class, charge)).isEqualTo("4.00");
    }

    @Test
    void aPaymentReversalNeedsItsAllocationsReleasedInTheSameTransaction() {
        assertBackstop("allocation_payment_cap", () -> reversePayment(demo, payment));

        asNadelOwner(false, sql -> {
            sql.update("insert into payment (tenant_id, plot_id, kind, source, amount, received_on, billing_month_id, "
                    + "group_id, reverses_payment_id, reason) values (?, ?, 'PAYMENT_REVERSAL', 'CASH', -25.00, "
                    + "date '2031-01-15', ?, ?, ?, 'Wrong')", DEMO_TENANT, demo.plot(), demo.month(),
                    UUID.randomUUID(), payment);
            release(sql, demo, allocation, payment, charge, "-6.00");
        });

        assertThat(count("payment", "reverses_payment_id = '" + payment + "'")).isEqualTo(1);
    }

    @Test
    void theBackstopsHoldForTheApplicationRoleUnderRowLevelSecurity() {
        UUID second = insertCharge(demo, "PAYAPP:3");
        String newPayment = "insert into payment (id, tenant_id, plot_id, kind, source, amount, received_on, "
                + "billing_month_id, group_id) values (?, ?, ?, 'PAYMENT', 'BANK', 15.00, date '2031-01-15', ?, ?)";
        UUID fits = UUID.randomUUID();
        inTenant(DEMO_TENANT, sql -> {
            sql.update(newPayment, fits, DEMO_TENANT, demo.plot(), demo.month(), UUID.randomUUID());
            sql.update(ALLOCATION_INSERT, DEMO_TENANT, fits, second, demo.plot(), "10.00", null, "ENTRY", null);
            return sql.update(ALLOCATION_INSERT, DEMO_TENANT, fits, charge, demo.plot(), "4.00", null, "ENTRY", null);
        });
        assertThat(count("allocation", "payment_id = '" + fits + "'")).isEqualTo(2);

        UUID exceeds = UUID.randomUUID();
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> {
            sql.update(newPayment, exceeds, DEMO_TENANT, demo.plot(), demo.month(), UUID.randomUUID());
            sql.update(ALLOCATION_INSERT, DEMO_TENANT, exceeds, second, demo.plot(), "7.00", null, "ENTRY", null);
            // 7.00 + 9.00 > 15.00: the payment is over-allocated (the charge is over-allocated too: 6.00 + 9.00 > 10.00)
            return sql.update(ALLOCATION_INSERT, DEMO_TENANT, exceeds, charge, demo.plot(), "9.00", null, "ENTRY",
                    null);
        })).hasStackTraceContaining("allocation_");
        assertThat(count("payment", "id = '" + exceeds + "'")).isZero();
    }

    // ---- helpers

    private int insertPayment(String kind, String source, String amount, Long receipt, String reference,
            byte[] note, UUID reverses, String reason) {
        return ownerJdbc().update(PAYMENT_INSERT, UUID.randomUUID(), DEMO_TENANT, demo.plot(), kind, source, amount,
                demo.month(), UUID.randomUUID(), receipt, reference, note, reverses, reason);
    }

    private int insertAllocation(UUID forPayment, String amount, UUID releases, String origin, String cause) {
        return ownerJdbc().update(ALLOCATION_INSERT, DEMO_TENANT, forPayment, charge, demo.plot(), amount, releases,
                origin, cause);
    }

    private UUID ownerAllocation(UUID forPayment, UUID forCharge) {
        return ownerJdbc().queryForObject("select id from allocation where payment_id = ? and charge_id = ?",
                UUID.class, forPayment, forCharge);
    }

    private int count(String table, String where) {
        return ownerJdbc().queryForObject("select count(*) from " + table + " where " + where, Integer.class);
    }

    private void assertRowsUnchanged() {
        assertThat(ownerJdbc().queryForObject("select count(*) from payment where id = ? and amount = 25.00",
                Integer.class, payment)).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select count(*) from allocation where id = ? and amount = 6.00",
                Integer.class, allocation)).isEqualTo(1);
    }

    private void assertTrigger(String table, String operation, Runnable statement) {
        assertThatThrownBy(statement::run)
                .as("%s on %s", operation, table)
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ledger table " + table + " is append-only: " + operation);
    }

    private void assertDenied(String table, Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied for table " + table);
    }

    private void assertCheck(String constraint, Supplier<?> statement) {
        assertThatThrownBy(statement::get)
                .as(constraint)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"))
                .hasMessageContaining(constraint);
    }

    private void assertGuard(String guard, Supplier<?> statement) {
        assertCheck(guard, statement);
    }

    /** A deferred backstop refuses at commit; a single statement runs in its own transaction. */
    private void assertBackstop(String constraint, Supplier<?> statement) {
        assertThatThrownBy(statement::get)
                .as(constraint)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"))
                .hasStackTraceContaining(constraint);
    }
}
