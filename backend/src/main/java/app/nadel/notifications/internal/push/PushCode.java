package app.nadel.notifications.internal.push;

import java.util.List;

/**
 * What a push message says (feature 021 §2.2): a code that the service worker turns into the text of the device's
 * language from the bundled catalogs, the only parameters it may carry and the screen a tap opens (a path of the web
 * app). Never a name, an amount, a plot or an address: the text shows on a locked phone.
 */
enum PushCode {

    /** «Новий рахунок за {місяць}»: the member's invoices on the account page. */
    INVOICES_ISSUED(List.of("month"), "/account#my-invoices");

    private final List<String> params;
    private final String url;

    PushCode(List<String> params, String url) {
        this.params = params;
        this.url = url;
    }

    /** The names of the parameters the message carries, all of them required. */
    List<String> params() {
        return params;
    }

    /** The path a tap opens. */
    String url() {
        return url;
    }
}
