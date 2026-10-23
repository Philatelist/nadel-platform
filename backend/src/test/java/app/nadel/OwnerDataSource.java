package app.nadel;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.TransactionState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Pooled superuser connections for {@link IntegrationTest#ownerJdbc()}. Opening a PostgreSQL connection starts a
 * server backend and runs SCRAM authentication; the tests issue hundreds of owner statements, so a fresh connection
 * per statement dominated their time.
 * <p>
 * A pooled connection must come back as clean as a fresh one: Hikari restores the JDBC state (auto-commit, isolation)
 * and rolls back what the JDBC API left open, and this class also rolls back a transaction block opened by the SQL
 * text itself ({@code "begin; ...; commit;"} that failed half-way), which the pool does not see.
 */
final class OwnerDataSource extends DelegatingDataSource {

    private final JdbcTemplate jdbc;

    OwnerDataSource(String url, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("test-owner");
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(1);
        setTargetDataSource(new HikariDataSource(config));
        jdbc = new JdbcTemplate(this);
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection pooled = super.getConnection();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close") && !pooled.isClosed()) {
                        endOpenTransactionBlock(pooled);
                    }
                    try {
                        return method.invoke(pooled, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static void endOpenTransactionBlock(Connection connection) throws SQLException {
        if (connection.getAutoCommit()
                && connection.unwrap(BaseConnection.class).getTransactionState() != TransactionState.IDLE) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("rollback");
            }
        }
    }
}
