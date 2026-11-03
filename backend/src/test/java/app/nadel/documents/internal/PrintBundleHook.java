package app.nadel.documents.internal;

import app.nadel.documents.PaperRecipients;
import app.nadel.documents.PrintScope;
import app.nadel.members.MemberDirectory;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotOwners;
import app.nadel.tenancy.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lets a test of another package print a month's paper set as a deployment without a {@link PaperRecipients}
 * implementation would (feature 016's filter): a {@link PrintBundle} built from the application's beans with an empty
 * provider, run in a transaction bound to the cooperative. Test code only.
 */
public final class PrintBundleHook {

    private PrintBundleHook() {
    }

    /** The PAPER print set of the month without paper recipients. */
    public static byte[] printWithoutRecipients(ApplicationContext context, UUID tenant, YearMonth month) {
        PrintBundle bundle = new PrintBundle(
                SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class)),
                context.getBean(InvoiceRepository.class), context.getBean(InvoiceSnapshots.class),
                context.getBean(PlotCatalog.class), context.getBean(PlotOwners.class),
                context.getBean(MemberDirectory.class), context.getBean(ListedMonths.class),
                new StaticListableBeanFactory().getBeanProvider(PaperRecipients.class));
        return TenantContext.runAs(tenant, () -> new TransactionTemplate(
                context.getBean(PlatformTransactionManager.class))
                .execute(status -> bundle.print(tenant, month, PrintScope.PAPER).content()));
    }
}
