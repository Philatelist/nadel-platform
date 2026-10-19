package app.nadel.plots.internal;

/** A share that is not a fraction "n/d" with 1 &lt;= n &lt;= d &lt;= 1000. The message holds no personal data. */
class InvalidShareException extends RuntimeException {

    InvalidShareException(String message) {
        super(message);
    }
}
