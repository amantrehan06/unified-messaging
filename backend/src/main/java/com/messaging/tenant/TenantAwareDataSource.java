package com.messaging.tenant;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection conn = super.getConnection();
        setTenantOnConnection(conn);
        return conn;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection conn = super.getConnection(username, password);
        setTenantOnConnection(conn);
        return conn;
    }

    private void setTenantOnConnection(Connection conn) throws SQLException {
        UUID tenantId = TenantContext.get().orElse(null);
        if (tenantId != null) {
            // Safe: tenantId is a UUID (already validated when parsed from JWT)
            try (var stmt = conn.createStatement()) {
                stmt.execute("SET app.current_tenant = '" + tenantId + "'");
            }
        } else {
            try (var stmt = conn.createStatement()) {
                stmt.execute("RESET app.current_tenant");
            }
        }
    }
}
