package app.nadel.treasury;

import java.util.Optional;
import java.util.UUID;

/**
 * The cash counts as their acts show them (feature 018 §2.6), for the documents module («Акт перерахунку каси»). Runs
 * in the caller's transaction ({@code Propagation.MANDATORY}), which the tenant-aware transaction manager has bound to
 * the cooperative; RLS hides other cooperatives. No permission check of its own: the caller decides who may see a
 * count ({@code CASHBOOK_VIEW}).
 */
public interface CashCountActs {

    /** The act of the count; empty for an unknown count or one of another cooperative. */
    Optional<CashCountAct> act(UUID countId);
}
