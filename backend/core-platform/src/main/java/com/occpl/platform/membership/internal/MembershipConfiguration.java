package com.occpl.platform.membership.internal;

import com.occpl.platform.company.api.CompanyDirectory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.handler.MappedInterceptor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InvitationProperties.class)
class MembershipConfiguration {

    /** Detected by Spring MVC as an interceptor for company paths (see {@link CompanyPathGuard}). */
    @Bean
    MappedInterceptor companyPathGuard(CompanyMembershipRepository memberships, CompanyDirectory companies) {
        return new MappedInterceptor(new String[] {"/api/v1/companies/*", "/api/v1/companies/*/**"},
                new CompanyPathGuard(memberships, companies));
    }
}
