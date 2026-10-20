package app.nadel.members;

import java.util.List;

/** The plan for all member rows of an import. */
public record MemberImportPlan(List<MemberImportItem> items) {

    public MemberImportPlan {
        items = List.copyOf(items);
    }
}
