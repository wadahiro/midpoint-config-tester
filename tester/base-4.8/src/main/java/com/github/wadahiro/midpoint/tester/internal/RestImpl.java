package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.Filter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.RequestContextFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.evolveum.midpoint.rest.impl.MidpointAbstractHttpMessageConverter;
import com.evolveum.midpoint.rest.impl.RestConfig;

/**
 * midPoint's REST API in the tester's JVM (4.8+), without an HTTP server: Spring's MockMvc over a web context whose
 * parent is the tester's context. The web context has midPoint's REST controllers and message converters
 * ({@code RestConfig}) and its authentication ({@code com.evolveum.midpoint.authentication}: {@code SecurityConfigurer}
 * builds the security filter chain, whose {@code MidpointAuthFilter} authenticates a request by the security policy's
 * sequence of the REST channel). It starts on the first request of the JVM and stays: it holds no state of a test (the
 * policy, the users and their authorizations are read per request from the repository). Each request has a session
 * of its own (REST is session-less); the thread's security context, which the filter chain sets and clears, is put
 * back after it.
 */
final class RestImpl {

    private static MockMvc mvc;

    private final MidPointImpl mp;

    RestImpl(MidPointImpl mp) {
        this.mp = mp;
    }

    /** midPoint's REST layer, as its web application configures it. */
    @Configuration
    @EnableWebMvc
    @Import(RestConfig.class)
    static class WebConfig implements WebMvcConfigurer {

        @Autowired
        List<MidpointAbstractHttpMessageConverter<?>> midpointConverters;

        // a bean: it runs after the scans of the configuration classes (RestConfig's), which register the controllers
        @org.springframework.context.annotation.Bean
        static LeaveOut leaveOut() {
            return new LeaveOut();
        }

        // Spring Boot puts the converter beans first (HttpMessageConverters); plain @EnableWebMvc appends its own
        // first, whose JSON converter does not know midPoint's objects
        @Override
        public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.addAll(0, midpointConverters);
        }
    }

    /**
     * The controllers of midPoint's smart integration (4.11: AI-assisted connector development and schema mapping),
     * left out of the web context: their services are in modules the tester does not have.
     */
    private static final java.util.Set<String> LEFT_OUT = java.util.Set.of("connectorDevelopmentRestController",
            "smartIntegrationRestController");

    /** Leaves {@link #LEFT_OUT} out of the bean definitions the scans found. */
    static class LeaveOut implements org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor {

        @Override
        public void postProcessBeanDefinitionRegistry(
                org.springframework.beans.factory.support.BeanDefinitionRegistry registry) {
            for (String name : LEFT_OUT) {
                if (registry.containsBeanDefinition(name)) {
                    registry.removeBeanDefinition(name);
                }
            }
        }

        @Override
        public void postProcessBeanFactory(
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory) {
        }
    }

    /** Whether the line has REST calls in the tester: it has. */
    void available() {
    }

    RestExchange perform(String method, String path, Map<String, List<String>> headers, String contentType,
            byte[] body) throws Exception {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("A REST path starts with /: " + path);
        }
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path)
                // the whole path: midPoint's filter chain matches its channels on the servlet path, and takes an
                // empty one as a public URL
                .servletPath(pathOnly)
                .session(new MockHttpSession());
        headers.forEach((name, values) -> values.forEach(value -> request.header(name, value)));
        if (body != null) {
            request.content(body);
            request.contentType(contentType);
        }
        SecurityContext context = SecurityContextHolder.getContext();
        org.springframework.security.core.Authentication authentication = context.getAuthentication();
        MockHttpServletResponse response;
        try {
            response = mvc().perform(request).andReturn().getResponse();
        } finally {
            SecurityContextHolder.setContext(context);
            context.setAuthentication(authentication);
        }
        if (response.getStatus() == 500 && !hasGlobalSecurityPolicy()) {
            throw new IllegalStateException("midPoint answered " + method + " " + path + " with 500: its system"
                    + " configuration has no globalSecurityPolicyRef, so the REST channel has no authentication."
                    + " midPoint's initial system configuration references its default security policy"
                    + " (00000000-0000-0000-0000-000000000120); a deployment that replaces the system configuration"
                    + " keeps the reference, or names a security policy of its own");
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) {
            out.put(name, new ArrayList<>(response.getHeaders(name)));
        }
        return new RestExchange(response.getStatus(), out, response.getContentAsByteArray());
    }

    private boolean hasGlobalSecurityPolicy() {
        try {
            return mp.bean(com.evolveum.midpoint.repo.api.RepositoryService.class).getObject(
                    com.evolveum.midpoint.xml.ns._public.common.common_3.SystemConfigurationType.class,
                    com.evolveum.midpoint.xml.ns._public.common.common_3.SystemObjectsType.SYSTEM_CONFIGURATION.value(), null,
                    new com.evolveum.midpoint.schema.result.OperationResult("REST security policy"))
                    .asObjectable().getGlobalSecurityPolicyRef() != null;
        } catch (Exception e) {
            return true;
        }
    }

    private synchronized MockMvc mvc() {
        if (mvc == null) {
            long t = Timing.start();
            AnnotationConfigWebApplicationContext web = new AnnotationConfigWebApplicationContext();
            web.setParent(mp.bean(ApplicationContext.class));
            web.setServletContext(new MockServletContext());
            web.register(WebConfig.class);
            web.scan("com.evolveum.midpoint.authentication");
            web.refresh();
            // RequestContextFilter first: the session and request scoped beans of the security configuration need a
            // current request
            mvc = MockMvcBuilders.webAppContextSetup(web)
                    .addFilters(new RequestContextFilter(), web.getBean("springSecurityFilterChain", Filter.class))
                    .build();
            Timing.end("rest.webContext", t);
        }
        return mvc;
    }
}
