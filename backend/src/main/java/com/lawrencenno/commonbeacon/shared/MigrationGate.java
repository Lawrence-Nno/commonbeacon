package com.lawrencenno.commonbeacon.shared;

import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Order is transaction -> shared gate -> method (and therefore all row locks).
 * Programmatic transfer transactions call shared explicitly. Read-only snapshots remain available. */
@Configuration(proxyBeanMethods=false)
@EnableTransactionManagement(order=0)
public class MigrationGate {
    public static final long KEY=736284910251L;
    public static void shared(JdbcTemplate jdbc) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock_shared(?)",Boolean.class,KEY)))
            throw new ApiFailure(409,"IMPORT_IN_PROGRESS","An import is activating. Retry after it completes.");
    }
    @Bean @Role(org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE)
    static DefaultPointcutAdvisor migrationWriteAdvisor(ObjectProvider<JdbcTemplate> jdbc) {
        var attributes=new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();
        var pointcut=new StaticMethodMatcherPointcut() {
            @Override public boolean matches(Method method,Class<?> type) {
                if(!type.getName().startsWith("com.lawrencenno.commonbeacon."))return false;
                var tx=attributes.getTransactionAttribute(method,type);return tx!=null && !tx.isReadOnly();
            }
        };
        var advisor=new DefaultPointcutAdvisor(pointcut,(MethodInterceptor) invocation->{
            if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("WRITE_TRANSACTION_REQUIRED");
            shared(jdbc.getObject());return invocation.proceed();
        });
        advisor.setOrder(1);return advisor;
    }
}
