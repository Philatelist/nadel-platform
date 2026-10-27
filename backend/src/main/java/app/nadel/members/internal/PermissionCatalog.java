package app.nadel.members.internal;

import app.nadel.identity.Permission;
import java.util.Arrays;
import java.util.List;
import nadel.contract.model.PermissionInfo;

/** English name and one-line description of every permission; the web client localises by code. */
final class PermissionCatalog {

    private PermissionCatalog() {
    }

    static List<PermissionInfo> all() {
        return Arrays.stream(Permission.values()).map(PermissionCatalog::info).toList();
    }

    private static PermissionInfo info(Permission permission) {
        String[] text = switch (permission) {
            case MEMBERS_VIEW -> new String[] {"View members",
                    "See all members and their contact details and notes"};
            case MEMBERS_EDIT -> new String[] {"Edit members", "Add, edit, archive and restore members"};
            case ROLES_MANAGE -> new String[] {"Manage roles",
                    "Create, edit and delete custom roles; change permissions of base roles; assign roles to members"};
            case AUDIT_VIEW -> new String[] {"View audit log", "Read the audit log"};
            case PLOTS_VIEW -> new String[] {"View plots", "See all plots with details"};
            case PLOTS_EDIT -> new String[] {"Edit plots", "Add, edit and archive plots"};
            case OWNERSHIP_EDIT -> new String[] {"Edit ownership", "Add, change and correct plot ownership"};
            case IMPORT_RUN -> new String[] {"Run import", "Run the Excel import"};
            case SETTINGS_MANAGE -> new String[] {"Manage cooperative settings",
                    "Change the settings of the cooperative, such as who sees the colour of plots"};
            case TARIFFS_VIEW -> new String[] {"View tariffs",
                    "See charge types, tariffs and what each plot would be charged"};
            case TARIFFS_MANAGE -> new String[] {"Manage tariffs",
                    "Add, rename, reorder and archive charge types; manage tariffs and their scope"};
            case METERS_VIEW -> new String[] {"View meters",
                    "See the meter register, the meter tree and the readings of every meter"};
            case METERS_MANAGE -> new String[] {"Manage meters",
                    "Register, change, replace, remove and delete meters; build the meter tree"};
            case READINGS_ENTER -> new String[] {"Enter readings",
                    "Enter, correct and delete meter readings, for example on a round"};
            case READINGS_APPROVE -> new String[] {"Approve readings",
                    "Approve or reject the readings that members submit"};
            case CHARGES_VIEW -> new String[] {"View charges",
                    "See the account of every plot, the billing months, the runs and what could not be charged"};
            case CHARGES_RUN -> new String[] {"Run charges",
                    "Run a month, losses and common consumers; retry what could not be charged; enter opening balances"};
            case CHARGES_REVERSE -> new String[] {"Reverse charges", "Reverse a charge or a whole run with a reason"};
            case PERIODS_CLOSE -> new String[] {"Close months", "Close a billing month so that it never changes again"};
            case RECALCULATION_RUN -> new String[] {"Recalculate charges",
                    "Preview and book recalculations of past charges; correct used tariff versions and readings"};
            case REPORTS_VIEW -> new String[] {"View reports",
                    "Build and download reports and fill in report templates"};
            case REPORT_TEMPLATES_MANAGE -> new String[] {"Manage report templates",
                    "Upload, replace, download and archive report templates"};
        };
        return new PermissionInfo(nadel.contract.model.Permission.fromValue(permission.name()), text[0], text[1]);
    }
}
