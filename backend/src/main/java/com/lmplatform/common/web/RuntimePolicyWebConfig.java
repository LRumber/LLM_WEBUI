package com.lmplatform.common.web;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.api.service.ApiGatewayService;
import com.lmplatform.common.service.RuntimeAdmissionService;
import com.lmplatform.common.service.RuntimePolicyService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.session.web.http.SessionRepositoryFilter;

@Configuration
public class RuntimePolicyWebConfig {
    @Bean
    public FilterRegistrationBean<RuntimePolicyFilter> runtimePolicyFilter(RuntimePolicyService policies,
            RuntimeAdmissionService admission, ApiGatewayService api, ObjectMapper mapper) {
        var registration = new FilterRegistrationBean<>(new RuntimePolicyFilter(policies, admission, api, mapper));
        // Read the Spring Session wrapper, including Redis-backed user sessions.
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER + 10);
        registration.setAsyncSupported(true);
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}
