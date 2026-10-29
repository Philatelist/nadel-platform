package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.documents.DocumentFormats;
import app.nadel.money.Money;
import app.nadel.notifications.FakeBotApi.Sent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The bot's «Борг» (feature 017 §2.5): per plot of the member, the debt per charge type, the total and the prepayment,
 * the same figures as the plot account the member sees in the account; never a plot the member does not own now.
 */
class BotDebtTests extends BotCommandsTestSupport {

    private static final Map<String, String> DEFAULT_NAMES = Map.of("MEMBERSHIP", "Членський внесок",
            "ELECTRICITY", "Електроенергія");

    @Test
    void theDebtPerChargeTypeTheTotalAndThePrepaymentEqualTheAccount() throws Exception {
        startBot();
        Member owner = member("Шевченко");
        UUID nine = plot("9", owner.id());
        UUID ten = plot("10", owner.id());
        charge(nine, "MEMBERSHIP", "1200.00");
        charge(nine, "ELECTRICITY", "213.26");
        pay(nine, owner.id(), "200.00");
        // more than charged: the plot owes nothing and has a prepayment
        charge(ten, "MEMBERSHIP", "100.00");
        pay(ten, owner.id(), "150.00");
        long chat = link(owner.id());

        Sent reply = lastReply(chat, "Борг", 1);

        assertThat(reply.text()).isEqualTo(expected("9", account(owner, nine)) + "\n\n"
                + expected("10", account(owner, ten)));
        assertThat(reply.text()).contains("Ділянка 10\nЧленський внесок: 0,00" + NBSP + "₴\nУсього боргу: 0,00"
                + NBSP + "₴\nАванс: 50,00" + NBSP + "₴");
        assertThat(reply.text()).contains("Усього боргу: 1" + NBSP + "213,26" + NBSP + "₴");
        assertThat(reply.buttons()).containsExactly(MENU_ROW_1, MENU_ROW_2);
    }

    @Test
    void aPlotWithoutChargesHasNoDebtAndAnotherMembersPlotIsNeverShown() throws Exception {
        startBot();
        Member owner = member("Коваль");
        Member neighbour = member("Сусід");
        plot("21", owner.id());
        UUID theirs = plot("22", neighbour.id());
        charge(theirs, "MEMBERSHIP", "999.00");
        long chat = link(owner.id());

        Sent reply = lastReply(chat, "Борг", 1);

        assertThat(reply.text()).isEqualTo("Ділянка 21\nБоргу немає");
        assertThat(reply.text()).doesNotContain("22").doesNotContain("999");
    }

    @Test
    void aMemberWithoutPlotsIsToldSoByEveryAction() throws Exception {
        startBot();
        Member owner = member("Безземельний");
        long chat = link(owner.id());

        assertThat(lastReply(chat, "Борг", 1).text()).startsWith("Зараз за вами не записано жодної ділянки");
        // the other actions say the same and offer no plot to choose
        Sent invoice = lastReply(chat, "Рахунок", 2);
        assertThat(invoice.text()).startsWith("Зараз за вами не записано жодної ділянки");
        assertThat(invoice.buttons()).containsExactly(MENU_ROW_1, MENU_ROW_2);
        Sent readings = lastReply(chat, "Показання", 3);
        assertThat(readings.text()).startsWith("Зараз за вами не записано жодної ділянки");
        assertThat(readings.buttons()).containsExactly(MENU_ROW_1, MENU_ROW_2);
        assertThat(telegram.documents(chat)).isEmpty();
    }

    /** The bot's block of a plot built from the account's own figures. */
    private static String expected(String number, JsonNode account) {
        List<String> lines = new ArrayList<>();
        lines.add("Ділянка " + number);
        for (JsonNode debt : account.get("debts")) {
            String name = debt.get("nameCustomized").asBoolean() ? debt.get("name").asString()
                    : DEFAULT_NAMES.get(debt.get("chargeTypeCode").asString());
            lines.add(name + ": " + money(debt.get("debt").asString()));
        }
        lines.add("Усього боргу: " + money(account.get("total").asString()));
        if (new BigDecimal(account.get("prepayment").asString()).signum() > 0) {
            lines.add("Аванс: " + money(account.get("prepayment").asString()));
        }
        return String.join("\n", lines);
    }

    private static String money(String amount) {
        return DocumentFormats.money(Money.of(new BigDecimal(amount)));
    }
}
