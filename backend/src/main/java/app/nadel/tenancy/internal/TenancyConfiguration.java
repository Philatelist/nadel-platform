package app.nadel.tenancy.internal;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
class TenancyConfiguration {

    /** Replaces the default JpaTransactionManager from Spring Boot auto-configuration. */
    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new TenantAwareTransactionManager(emf);
    }
}
