package app.nadel.notifications.internal;

import app.nadel.documents.DocumentFormats;
import app.nadel.documents.DocumentsFacade;
import app.nadel.identity.AccessProfile;
import app.nadel.identity.OwnedPlot;
import app.nadel.identity.OwnedPlotsSource;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.PlotDebts;
import app.nadel.meters.MemberReadings;
import app.nadel.meters.MemberReadings.Accepted;
import app.nadel.meters.MemberReadings.MemberMeter;
import app.nadel.meters.MemberReadings.Problem;
import app.nadel.meters.MemberReadings.Rejected;
import app.nadel.meters.MemberReadings.SubmitResult;
import app.nadel.notifications.internal.BotDialogue.Purpose;
import app.nadel.notifications.internal.BotDialogue.State;
import app.nadel.notifications.internal.telegram.ReplyMarkup;
import app.nadel.problem.ApiProblemException;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The bot's menu actions for a linked member (feature 017 §2.5), answered in the member's chat ({@link BotChat}); the
 * caller has bound the cooperative and found the member of the chat. A member reaches only the plots they own now
 * ({@link OwnedPlotsSource}) and nothing else; every step checks it again.
 * <ul>
 *   <li>«Борг»: per plot, the debt per charge type, the total and the prepayment as of today in Kyiv
 *       ({@link LedgerReadModel#debtsAsOf}, every charge type, the types in list order with their names), as the plot
 *       account shows them.</li>
 *   <li>«Рахунок»: the plot (a question when there are several), then the invoice of the day
 *       ({@link DocumentsFacade#invoiceForDay}: one day, one invoice) as the PDF with its caption and «Оплатити» when
 *       something is due ({@link InvoiceMessages}).</li>
 *   <li>«Показання»: the plot (a question when there are several), the meter (buttons "resource icon + serial"), the
 *       value (digits with an optional decimal comma or point), submitted for the board's approval with today's date
 *       through {@link MemberReadings#submit}; a refusal is told in plain words per problem code and the member types
 *       again.</li>
 * </ul>
 * Database work runs in short transactions of its own, Telegram outside them. Never logs a text, a value or an id of a
 * member's plot.
 */
@Component
class BotCommands {

    /** Digits with an optional decimal comma or point; nothing else is a reading. */
    private static final Pattern NUMBER = Pattern.compile("\\d+([.,]\\d+)?");
    /** Refusals that typing another value cannot cure: the dialogue ends. */
    private static final Set<String> FINAL = Set.of("PLOT_NOT_FOUND", "NOT_FOUND", "METER_REMOVED",
            "PERMISSION_DENIED");

    private final OwnedPlotsSource plots;
    private final LedgerReadModel ledger;
    private final ChargeTypeCatalog chargeTypes;
    private final DocumentsFacade documents;
    private final InvoiceMessages invoices;
    private final MemberReadings readings;
    private final BotDialogue dialogue;
    private final Clock clock;
    private final TransactionTemplate readOnly;
    private final NotificationTexts texts = NotificationTexts.uk();

    BotCommands(OwnedPlotsSource plots, LedgerReadModel ledger, ChargeTypeCatalog chargeTypes,
            DocumentsFacade documents, InvoiceMessages invoices, MemberReadings readings, BotDialogue dialogue,
            Clock clock, PlatformTransactionManager transactionManager) {
        this.plots = plots;
        this.ledger = ledger;
        this.chargeTypes = chargeTypes;
        this.documents = documents;
        this.invoices = invoices;
        this.readings = readings;
        this.dialogue = dialogue;
        this.clock = clock;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /** The menu under the message box: «Борг», «Рахунок», «Показання», «Відключити». */
    ReplyMarkup menu() {
        return new ReplyMarkup.Keyboard(List.of(
                List.of(texts.text("bot.menu.debt"), texts.text("bot.menu.invoice")),
                List.of(texts.text("bot.menu.readings"), texts.text("bot.menu.unlink"))));
    }

    /** Whether the text is one of the menu's actions answered here. */
    boolean isAction(String text) {
        return text.equals(texts.text("bot.menu.debt")) || text.equals(texts.text("bot.menu.invoice"))
                || text.equals(texts.text("bot.menu.readings"));
    }

    /** Answers a menu action ({@link #isAction}); the dialogue starts afresh. */
    void action(BotChat chat, UUID memberId, String text) {
        dialogue.reset(chat.tenantId(), chat.chatId());
        if (text.equals(texts.text("bot.menu.debt"))) {
            debt(chat, memberId);
        } else if (text.equals(texts.text("bot.menu.invoice"))) {
            invoice(chat, memberId);
        } else {
            readings(chat, memberId);
        }
    }

    /**
     * Continues the plot choice or the reading dialogue with the text; false when the chat is at no step of them (the
     * caller answers with the help).
     */
    boolean step(BotChat chat, UUID memberId, State state, String text) {
        switch (state.step()) {
            case PICK_PLOT -> pickedPlot(chat, memberId, state, text);
            case PICK_METER -> pickedMeter(chat, memberId, state, text);
            case ENTER_VALUE -> enteredValue(chat, memberId, state, text);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---- «Борг»

    private void debt(BotChat chat, UUID memberId) {
        List<OwnedPlot> owned = ownedPlots(memberId);
        if (owned.isEmpty()) {
            chat.send(texts.text("bot.noPlots"), menu());
            return;
        }
        chat.send(Objects.requireNonNull(readOnly.execute(status -> debtText(owned))), menu());
    }

    /** The debts of the plots as the plot account states them: per type in list order, the total, the prepayment. */
    private String debtText(List<OwnedPlot> owned) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), PaymentMessages.KYIV);
        Map<UUID, PlotDebts> debts = ledger.debtsAsOf(today, Set.of(),
                owned.stream().map(OwnedPlot::plotId).toList());
        List<ChargeTypeFacts> types = chargeTypes.all();
        List<String> blocks = new ArrayList<>();
        for (OwnedPlot plot : owned) {
            PlotDebts plotDebts = debts.getOrDefault(plot.plotId(), PlotDebts.none(plot.plotId()));
            List<String> lines = new ArrayList<>();
            lines.add(texts.text("bot.debt.plot", Map.of("plot", plot.number())));
            List<ChargeTypeFacts> present = types.stream()
                    .filter(type -> plotDebts.byChargeType().containsKey(type.id())).toList();
            if (present.isEmpty()) {
                lines.add(texts.text("bot.debt.none"));
            } else {
                for (ChargeTypeFacts type : present) {
                    lines.add(texts.text("bot.debt.type", Map.of("type", typeName(type), "amount",
                            DocumentFormats.money(plotDebts.byChargeType().get(type.id())))));
                }
                lines.add(texts.text("bot.debt.total", Map.of("amount", DocumentFormats.money(plotDebts.total()))));
            }
            if (plotDebts.prepayment().signum() > 0) {
                lines.add(texts.text("bot.debt.prepayment", Map.of("amount",
                        DocumentFormats.money(plotDebts.prepayment()))));
            }
            blocks.add(String.join("\n", lines));
        }
        return String.join("\n\n", blocks);
    }

    /** A charge type's name: its own when renamed or custom, otherwise the Ukrainian default of its code. */
    private String typeName(ChargeTypeFacts type) {
        return type.code() != null && !type.nameCustomized()
                ? texts.text("chargeTypes.defaultNames." + type.code()) : type.name();
    }

    // ---- «Рахунок»

    private void invoice(BotChat chat, UUID memberId) {
        List<OwnedPlot> owned = ownedPlots(memberId);
        if (owned.isEmpty()) {
            chat.send(texts.text("bot.noPlots"), menu());
        } else if (owned.size() == 1) {
            sendInvoice(chat, owned.getFirst(), false);
        } else {
            dialogue.pickPlot(chat.tenantId(), chat.chatId(), Purpose.INVOICE);
            chat.send(texts.text("bot.invoice.pickPlot"), plotKeyboard(owned));
        }
    }

    /**
     * The plot's invoice of the day as the PDF with «Оплатити» when something is due. After a plot question the menu
     * comes back first with a short «Готую рахунок…».
     */
    private void sendInvoice(BotChat chat, OwnedPlot plot, boolean restoreMenu) {
        if (restoreMenu) {
            chat.send(texts.text("bot.invoice.preparing", Map.of("plot", plot.number())), menu());
        }
        Optional<DeliveryMessage> found;
        try {
            UUID invoiceId = documents.invoiceForDay(plot.plotId());
            found = invoices.of(invoiceId);
        } catch (ApiProblemException refused) {
            // no requisites, or the plot gets no invoice: the board's matter, the member is told so
            found = Optional.empty();
        }
        if (found.isEmpty() || !found.get().hasPdf()) {
            chat.send(texts.text("bot.invoice.unavailable", Map.of("plot", plot.number())), menu());
            return;
        }
        DeliveryMessage message = found.get();
        ReplyMarkup pay = message.payUrl() == null ? null
                : new ReplyMarkup.UrlButton(texts.text("invoice.pay"), message.payUrl());
        chat.sendDocument(Objects.requireNonNull(message.fileName()), Objects.requireNonNull(message.pdf()),
                message.text(), pay);
    }

    // ---- «Показання»

    private void readings(BotChat chat, UUID memberId) {
        List<OwnedPlot> owned = ownedPlots(memberId);
        if (owned.isEmpty()) {
            chat.send(texts.text("bot.noPlots"), menu());
        } else if (owned.size() == 1) {
            askMeter(chat, memberId, owned.getFirst());
        } else {
            dialogue.pickPlot(chat.tenantId(), chat.chatId(), Purpose.READINGS);
            chat.send(texts.text("bot.readings.pickPlot"), plotKeyboard(owned));
        }
    }

    private void askMeter(BotChat chat, UUID memberId, OwnedPlot plot) {
        List<MemberMeter> meters = readings.metersOf(plot.plotId(), member(chat, memberId));
        if (meters.isEmpty()) {
            dialogue.reset(chat.tenantId(), chat.chatId());
            chat.send(texts.text("bot.readings.noMeters", Map.of("plot", plot.number())), menu());
            return;
        }
        dialogue.pickMeter(chat.tenantId(), chat.chatId(), plot.plotId());
        chat.send(texts.text("bot.readings.pickMeter", Map.of("plot", plot.number())), meterKeyboard(meters));
    }

    private void pickedPlot(BotChat chat, UUID memberId, State state, String text) {
        if (cancelled(chat, text)) {
            return;
        }
        List<OwnedPlot> owned = ownedPlots(memberId);
        Optional<OwnedPlot> chosen = owned.stream().filter(plot -> plotLabel(plot).equals(text)).findFirst();
        if (chosen.isEmpty()) {
            if (owned.isEmpty()) {
                dialogue.reset(chat.tenantId(), chat.chatId());
                chat.send(texts.text("bot.noPlots"), menu());
                return;
            }
            dialogue.touch(chat.tenantId(), chat.chatId(), state);
            chat.send(texts.text("bot.pick.again"), plotKeyboard(owned));
            return;
        }
        if (state.purpose() == Purpose.INVOICE) {
            dialogue.reset(chat.tenantId(), chat.chatId());
            sendInvoice(chat, chosen.get(), true);
        } else {
            askMeter(chat, memberId, chosen.get());
        }
    }

    private void pickedMeter(BotChat chat, UUID memberId, State state, String text) {
        if (cancelled(chat, text)) {
            return;
        }
        Optional<OwnedPlot> plot = ownedPlot(memberId, state.plotId());
        if (plot.isEmpty()) {
            endWith(chat, texts.text("bot.readings.problem.PLOT_NOT_FOUND"));
            return;
        }
        List<MemberMeter> meters = readings.metersOf(plot.get().plotId(), member(chat, memberId));
        Optional<MemberMeter> chosen = meters.stream().filter(meter -> meterLabel(meter).equals(text)).findFirst();
        if (chosen.isPresent()) {
            dialogue.enterValue(chat.tenantId(), chat.chatId(), plot.get().plotId(), chosen.get().meterId());
            chat.send(texts.text("bot.readings.enterValue", Map.of("meter", chosen.get().serial())), menu());
        } else if (meters.isEmpty()) {
            endWith(chat, texts.text("bot.readings.noMeters", Map.of("plot", plot.get().number())));
        } else {
            dialogue.touch(chat.tenantId(), chat.chatId(), state);
            chat.send(texts.text("bot.pick.again"), meterKeyboard(meters));
        }
    }

    /**
     * The typed value of the chosen meter: a number is submitted for approval (the update was taken before it was
     * answered, so it is submitted at most once); anything else, or a refusal, is told and the member types again.
     */
    private void enteredValue(BotChat chat, UUID memberId, State state, String text) {
        UUID plotId = Objects.requireNonNull(state.plotId());
        AccessProfile member = member(chat, memberId);
        Optional<MemberMeter> meter = readings.metersOf(plotId, member).stream()
                .filter(found -> found.meterId().equals(state.meterId())).findFirst();
        if (meter.isEmpty()) {
            endWith(chat, texts.text("bot.readings.problem.NOT_FOUND"));
            return;
        }
        if (!NUMBER.matcher(text).matches()) {
            dialogue.touch(chat.tenantId(), chat.chatId(), state);
            chat.send(texts.text("bot.readings.notNumber"), menu());
            return;
        }
        SubmitResult result = readings.submit(plotId, meter.get().meterId(), text.replace(',', '.'), member);
        switch (result) {
            case Accepted accepted -> {
                dialogue.reset(chat.tenantId(), chat.chatId());
                chat.send(texts.text("bot.readings.submitted", Map.of("value",
                        DocumentFormats.number(accepted.value().value()), "meter", accepted.serial())), menu());
            }
            case Rejected rejected -> {
                String told = String.join("\n", rejected.problems().stream()
                        .map(problem -> problemText(problem, meter.get())).toList());
                if (rejected.problems().stream().anyMatch(problem -> FINAL.contains(problem.code()))) {
                    endWith(chat, told);
                } else {
                    dialogue.touch(chat.tenantId(), chat.chatId(), state);
                    chat.send(told + "\n" + texts.text("bot.readings.again"), menu());
                }
            }
        }
    }

    /**
     * A refusal in plain words, as the account words it: the code's sentence and, where the account adds them, the
     * neighbouring reading or the meter's working period.
     */
    String problemText(Problem problem, MemberMeter meter) {
        String key = "bot.readings.problem." + problem.code();
        if (!texts.keys().contains(key)) {
            return texts.text("bot.readings.problem.other");
        }
        StringBuilder text = new StringBuilder(texts.text(key));
        Map<String, String> params = problem.params();
        String unit = texts.text("bot.unit." + meter.resource().name());
        if (params.containsKey("previousValue") && params.containsKey("previousTakenOn")) {
            text.append(' ').append(texts.text("bot.readings.problem.previous", Map.of("value",
                    value(params.get("previousValue")), "unit", unit, "date", day(params.get("previousTakenOn")))));
        }
        if (params.containsKey("nextValue") && params.containsKey("nextTakenOn")) {
            text.append(' ').append(texts.text("bot.readings.problem.next", Map.of("value",
                    value(params.get("nextValue")), "unit", unit, "date", day(params.get("nextTakenOn")))));
        }
        if (params.containsKey("installedOn")) {
            text.append(' ').append(params.containsKey("removedOn")
                    ? texts.text("bot.readings.problem.periodFromTo", Map.of("installedOn",
                            day(params.get("installedOn")), "removedOn", day(params.get("removedOn"))))
                    : texts.text("bot.readings.problem.periodFrom", Map.of("installedOn",
                            day(params.get("installedOn")))));
        }
        if (params.containsKey("today")) {
            text.append(' ').append(texts.text("bot.readings.problem.today", Map.of("today",
                    day(params.get("today")))));
        }
        return text.toString();
    }

    // ---- helpers

    private boolean cancelled(BotChat chat, String text) {
        if (!text.equals(texts.text("bot.cancel"))) {
            return false;
        }
        endWith(chat, texts.text("bot.move.cancelled"));
        return true;
    }

    /** Ends the dialogue with the text and the menu. */
    private void endWith(BotChat chat, String text) {
        dialogue.reset(chat.tenantId(), chat.chatId());
        chat.send(text, menu());
    }

    /** The member's current plots, each once, in the natural order of their numbers. */
    private List<OwnedPlot> ownedPlots(UUID memberId) {
        Map<UUID, OwnedPlot> byId = new LinkedHashMap<>();
        plots.currentPlotsOf(memberId).forEach(plot -> byId.putIfAbsent(plot.plotId(), plot));
        return List.copyOf(byId.values());
    }

    private Optional<OwnedPlot> ownedPlot(UUID memberId, @Nullable UUID plotId) {
        return ownedPlots(memberId).stream().filter(plot -> plot.plotId().equals(plotId)).findFirst();
    }

    private static AccessProfile member(BotChat chat, UUID memberId) {
        return AccessProfile.ownerOnly(chat.tenantId(), memberId);
    }

    private String plotLabel(OwnedPlot plot) {
        return texts.text("bot.pick.plot", Map.of("plot", plot.number()));
    }

    private String meterLabel(MemberMeter meter) {
        return texts.text("bot.meter." + meter.resource().name(), Map.of("serial", meter.serial()));
    }

    private ReplyMarkup plotKeyboard(List<OwnedPlot> owned) {
        List<List<String>> rows = new ArrayList<>(owned.stream().map(plot -> List.of(plotLabel(plot))).toList());
        rows.add(List.of(texts.text("bot.cancel")));
        return new ReplyMarkup.Keyboard(rows);
    }

    private ReplyMarkup meterKeyboard(List<MemberMeter> meters) {
        List<List<String>> rows = new ArrayList<>(meters.stream().map(meter -> List.of(meterLabel(meter))).toList());
        rows.add(List.of(texts.text("bot.cancel")));
        return new ReplyMarkup.Keyboard(rows);
    }

    private static String value(String wire) {
        return DocumentFormats.number(new BigDecimal(wire));
    }

    private static String day(String iso) {
        return DocumentFormats.day(LocalDate.parse(iso));
    }
}
