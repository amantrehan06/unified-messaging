package com.messaging.tenant;

import java.sql.SQLException;

import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.persistence.EntityManagerFactory;

public class TenantAwareTransactionManager extends JpaTransactionManager {

    public TenantAwareTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);

        TenantContext.get().ifPresent(tenantId -> {
            EntityManagerHolder emHolder = (EntityManagerHolder)
                    TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
            if (emHolder == null) {
                return;
            }
            emHolder.getEntityManager().unwrap(Session.class).doWork(connection -> {
                try (var stmt = connection.createStatement()) {
                    // Safe: tenantId is a UUID (validated when parsed from JWT)
                    stmt.execute("SET LOCAL app.current_tenant = '" + tenantId + "'");
                }
            });
        });
    }
}
