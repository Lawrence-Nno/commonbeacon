package com.lawrencenno.commonbeacon.identity;

import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transaction -> maintenance gate -> identity gate/current account -> cached-role checks -> domain locks. */
@Configuration(proxyBeanMethods=false)
public class AccountAuthorization {
    private static PreAuthorize policy(Method method,Class<?> type){
        var annotation=AnnotatedElementUtils.findMergedAnnotation(method,PreAuthorize.class);
        return annotation!=null?annotation:AnnotatedElementUtils.findMergedAnnotation(type,PreAuthorize.class);
    }
    @Bean @Role(org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE)
    static DefaultPointcutAdvisor authoritativeAccountAdvisor(ObjectProvider<AccountPolicy> policies,ObjectProvider<JdbcTemplate> jdbc){
        var pointcut=new StaticMethodMatcherPointcut(){
            @Override public boolean matches(Method method,Class<?> type){return method.getDeclaringClass().getName().startsWith("com.lawrencenno.commonbeacon.") && policy(method,type)!=null;}
        };
        var advisor=new DefaultPointcutAdvisor(pointcut,(MethodInterceptor) call->{
            if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("AUTHORIZATION_TRANSACTION_REQUIRED");
            AccountPolicy.shared(jdbc.getObject());
            var account=policies.getObject().current(SecurityContextHolder.getContext().getAuthentication(),true);
            String rule=policy(call.getMethod(),call.getThis().getClass()).value();
            switch(rule){
                case "isAuthenticated()" -> policies.getObject().requireFull(account,false,false);
                case "hasRole('ADMINISTRATOR')" -> policies.getObject().requireFull(account,true,false);
                case "hasAnyRole('MODERATOR', 'ADMINISTRATOR')" -> policies.getObject().requireFull(account,false,true);
                default -> throw new IllegalStateException("UNSUPPORTED_ACCOUNT_AUTHORIZATION_RULE");
            }
            return call.proceed();
        });
        advisor.setOrder(2);return advisor;
    }
}
