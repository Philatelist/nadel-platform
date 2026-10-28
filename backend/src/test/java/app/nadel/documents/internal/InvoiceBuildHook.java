package app.nadel.documents.internal;

import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.AopTestUtils;

/**
 * Lets a test of another package run code between two chunks of a month build ({@link InvoiceBuilder#afterChunk}):
 * inside the build's {@code REPEATABLE READ} transaction, after the chunk was written and before the next one is
 * read. Test code only; a test removes its hook ({@code null}) when it ends.
 */
public final class InvoiceBuildHook {

    private InvoiceBuildHook() {
    }

    /** Sets the hook of the application's {@code InvoiceBuilder}; {@code null} removes it. */
    public static void afterChunk(ApplicationContext context, @Nullable IntConsumer hook) {
        InvoiceBuilder builder = AopTestUtils.getUltimateTargetObject(context.getBean(InvoiceBuilder.class));
        builder.afterChunk(hook);
    }
}
