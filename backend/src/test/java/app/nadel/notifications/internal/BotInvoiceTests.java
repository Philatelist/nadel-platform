package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.nadel.documents.DocumentFormats;
import app.nadel.notifications.FakeBotApi.Reply;
import app.nadel.notifications.FakeBotApi.Sent;
import app.nadel.notifications.FakeBotApi.SentDocument;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The bot's «Рахунок» (feature 017 §2.5): the plot (asked only when the member has several), then the invoice of the
 * day as the PDF with its caption and «Оплатити» when something is due. The same day gives the same invoice whatever
 * changed meanwhile; the next day gives a new version only when the invoice would differ. When no invoice can be made
 * (no requisites) the member is told to ask the board; a plot gone before it is chosen is not served; a PDF Telegram
 * failed to take leaves the link as it is.
 */
class BotInvoiceTests extends BotCommandsTestSupport {

    @Test
    void theInvoiceOfTheDayComesAsThePdfWithThePayButtonAndTheSameDayGivesTheSameInvoice() throws Exception {
        startBot();
        Member owner = member("Мельник");
        UUID plot = plot("9", owner.id());
        charge(plot, "MEMBERSHIP", "100.00");
        long chat = link(owner.id());

        telegram.pushMessage(TOKEN, chat, "Рахунок");
        SentDocument first = awaitDocuments(chat, 1).getFirst();

        assertThat(first.caption()).isEqualTo("Рахунок за " + DocumentFormats.month(month()) + ", ділянка 9: до сплати "
                + "100,00" + NBSP + "₴");
        assertThat(new String(first.content(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(first.fileName()).startsWith("invoice_").endsWith(".pdf");
        assertThat(first.buttons()).singleElement().satisfies(button -> {
            assertThat(button.text()).isEqualTo("Оплатити");
            assertThat(button.url()).startsWith("https://qr.bank.gov.ua/");
        });
        assertThat(telegram.sent(chat)).isEmpty();
        assertThat(versions(plot)).isEqualTo(1);

        // pressed again, and again after a payment the same day: the same invoice
        telegram.pushMessage(TOKEN, chat, "Рахунок");
        SentDocument second = awaitDocuments(chat, 2).get(1);
        pay(plot, owner.id(), "30.00");
        telegram.pushMessage(TOKEN, chat, "Рахунок");
        SentDocument third = awaitDocuments(chat, 3).get(2);

        assertThat(List.of(second.fileName(), third.fileName())).containsOnly(first.fileName());
        assertThat(third.caption()).isEqualTo(first.caption());
        assertThat(versions(plot)).isEqualTo(1);
    }

    @Test
    void theNextDayGivesANewVersionOnlyWhenTheInvoiceChanged() throws Exception {
        startBot();
        Member owner = member("Ткаченко");
        UUID plot = plot("9", owner.id());
        charge(plot, "MEMBERSHIP", "100.00");
        long chat = link(owner.id());
        telegram.pushMessage(TOKEN, chat, "Рахунок");
        SentDocument today = awaitDocuments(chat, 1).getFirst();

        // the next day, nothing changed: the same invoice, no new version
        issuedADayEarlier();
        telegram.pushMessage(TOKEN, chat, "Рахунок");
        SentDocument unchanged = awaitDocuments(chat, 2).get(1);
        assertThat(unchanged.fileName()).isEqualTo(today.fileName());
        assertThat(versions(plot)).isEqualTo(1);

        // the next day after a payment: a new version with what is due now, the same one when asked again
        issuedADayEarlier();
        pay(plot, owner.id(), "30.00");
        telegram.pushMessage(TOKEN, chat, "Рахунок");
        SentDocument changed = awaitDocuments(chat, 3).get(2);
        telegram.pushMessage(TOKEN, chat, "Рахунок");
        SentDocument again = awaitDocuments(chat, 4).get(3);

        assertThat(versions(plot)).isEqualTo(2);
        assertThat(changed.caption()).endsWith("до сплати 70,00" + NBSP + "₴");
        assertThat(again.fileName()).isEqualTo(changed.fileName());
    }

    @Test
    void aMemberWithSeveralPlotsChoosesOneAndAnotherMembersPlotIsNotOffered() throws Exception {
        startBot();
        Member owner = member("Бойко");
        Member neighbour = member("Сусідка");
        plot("11", owner.id());
        UUID twelve = plot("12", owner.id());
        UUID theirs = plot("13", neighbour.id());
        charge(theirs, "MEMBERSHIP", "500.00");
        long chat = link(owner.id());

        Sent question = lastReply(chat, "Рахунок", 1);
        assertThat(question.text()).isEqualTo("Рахунок за яку ділянку?");
        assertThat(question.buttons()).containsExactly("Ділянка 11", "Ділянка 12", "Скасувати");

        Sent again = lastReply(chat, "Ділянка 13", 2);
        assertThat(again.text()).isEqualTo("Оберіть, будь ласка, кнопкою нижче.");
        assertThat(again.buttons()).containsExactly("Ділянка 11", "Ділянка 12", "Скасувати");
        assertThat(telegram.documents(chat)).isEmpty();

        Sent preparing = lastReply(chat, "Ділянка 12", 3);
        assertThat(preparing.text()).isEqualTo("Готую рахунок за ділянку 12…");
        assertThat(preparing.buttons()).containsExactly(MENU_ROW_1, MENU_ROW_2);
        SentDocument invoice = awaitDocuments(chat, 1).getFirst();
        // nothing is charged on plot 12: no debt, no pay button
        assertThat(invoice.caption()).isEqualTo("Рахунок за " + DocumentFormats.month(month())
                + ", ділянка 12. Боргу немає, дякуємо!");
        assertThat(invoice.buttons()).isEmpty();
        assertThat(versions(twelve)).isEqualTo(1);
        assertThat(versions(theirs)).isZero();

        Sent cancelled = lastReply(chat, "Рахунок", 4);
        assertThat(cancelled.text()).isEqualTo("Рахунок за яку ділянку?");
        Sent menu = lastReply(chat, "Скасувати", 5);
        assertThat(menu.buttons()).containsExactly(MENU_ROW_1, MENU_ROW_2);
    }

    @Test
    void withoutTheRequisitesTheMemberIsToldToAskTheBoard() throws Exception {
        startBot();
        Member owner = member("Без реквізитів");
        UUID plot = plot("9", owner.id());
        charge(plot, "MEMBERSHIP", "100.00");
        ownerJdbc().update("update cooperative_settings set iban = null where tenant_id = ?", tenant);
        long chat = link(owner.id());

        Sent reply = lastReply(chat, "Рахунок", 1);

        assertThat(reply.text()).isEqualTo("Рахунок за ділянку 9 зараз не вдається підготувати. Зверніться, будь "
                + "ласка, до правління.");
        assertThat(reply.buttons()).containsExactly(MENU_ROW_1, MENU_ROW_2);
        assertThat(telegram.documents(chat)).isEmpty();
        assertThat(versions(plot)).isZero();
    }

    @Test
    void plotsGoneBeforeOneIsChosenAreNotServed() throws Exception {
        startBot();
        Member owner = member("Продав");
        UUID eleven = plot("11", owner.id());
        UUID twelve = plot("12", owner.id());
        long chat = link(owner.id());
        assertThat(lastReply(chat, "Рахунок", 1).buttons()).containsExactly("Ділянка 11", "Ділянка 12", "Скасувати");

        // both plots are sold before the member answers
        ownerJdbc().update("delete from ownership where plot_id in (?, ?)", eleven, twelve);
        Sent reply = lastReply(chat, "Ділянка 11", 2);

        assertThat(reply.text()).startsWith("Зараз за вами не записано жодної ділянки");
        assertThat(reply.buttons()).containsExactly(MENU_ROW_1, MENU_ROW_2);
        assertThat(lastReply(chat, "Рахунок", 3).text()).startsWith("Зараз за вами не записано жодної ділянки");
        assertThat(telegram.documents(chat)).isEmpty();
        assertThat(versions(eleven) + versions(twelve)).isZero();
    }

    @Test
    void aPdfTelegramFailedToTakeLeavesTheLinkAndTheNextRequestGetsTheSameInvoice() throws Exception {
        startBot();
        Member owner = member("Невдача");
        UUID plot = plot("9", owner.id());
        charge(plot, "MEMBERSHIP", "100.00");
        long chat = link(owner.id());
        telegram.failNext("sendDocument", Reply.error(502, "Bad Gateway"));

        long update = telegram.pushMessage(TOKEN, chat, "Рахунок");
        awaitTaken(update);
        await().atMost(Duration.ofSeconds(10)).until(() -> telegram.calls("sendDocument").size() == 1);

        assertThat(telegram.documents(chat)).isEmpty();
        assertThat(linked(owner.id())).isTrue();
        telegram.pushMessage(TOKEN, chat, "Рахунок");
        SentDocument retried = awaitDocuments(chat, 1).getFirst();
        assertThat(retried.caption()).endsWith("до сплати 100,00" + NBSP + "₴");
        assertThat(versions(plot)).isEqualTo(1);
    }

    /** How many versions of this month's invoice the plot has. */
    private int versions(UUID plot) {
        return ownerJdbc().queryForObject("select count(*) from invoice where plot_id = ?", Integer.class, plot);
    }

    /** Moves the cooperative's invoices one day back, as if they had been issued yesterday (the next day). */
    private void issuedADayEarlier() {
        UUID id = tenant;
        asLedgerOwner(sql -> sql.update("update invoice set issued_at = issued_at - interval '1 day' "
                + "where tenant_id = ?", id));
    }
}
