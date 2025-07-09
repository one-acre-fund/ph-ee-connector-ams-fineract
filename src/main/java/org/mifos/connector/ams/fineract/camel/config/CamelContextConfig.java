package org.mifos.connector.ams.fineract.camel.config;

import java.util.HashMap;
import lombok.RequiredArgsConstructor;
import org.apache.camel.CamelContext;
import org.apache.camel.component.http.HttpComponent;
import org.apache.camel.spi.RestConfiguration;
import org.apache.camel.spring.boot.CamelContextConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configuration class for camel context. */
@Configuration
@RequiredArgsConstructor
public class CamelContextConfig {

    @Value("${camel.server-port}")
    private int serverPort;

    private final BuildProperties buildProperties;

    @Value("${spring.application.name:ph-ee-connector-ams-fineract}")
    private String applicationName;

    /**
     * Configures the camel context to be used.
     *
     * @return {@link CamelContextConfiguration}
     */
    @Bean
    CamelContextConfiguration contextConfiguration() {
        return new CamelContextConfiguration() {

            @Override
            public void beforeApplicationStart(CamelContext camelContext) {
                camelContext.setTracing(false);
                camelContext.setMessageHistory(false);
                camelContext.setStreamCaching(true);
                camelContext.disableJMX();

                RestConfiguration rest = new RestConfiguration();
                camelContext.setRestConfiguration(rest);
                rest.setComponent("undertow");
                rest.setProducerComponent("undertow");
                rest.setPort(serverPort);
                rest.setBindingMode(RestConfiguration.RestBindingMode.json);
                rest.setDataFormatProperties(new HashMap<>());
                rest.getDataFormatProperties().put("prettyPrint", "true");
                rest.setScheme("http");
                configureHttpComponent(camelContext, "http");
                configureHttpComponent(camelContext, "https");
            }

            @Override
            public void afterApplicationStart(CamelContext camelContext) {
                // empty
            }
        };
    }

    /**
     * Configures the HTTP component with a user agent.
     *
     * @param camelContext
     *            the Camel context to configure
     * @param scheme
     *            the scheme to configure (http or https)
     */
    private void configureHttpComponent(CamelContext camelContext, String scheme) {
        HttpComponent httpComponent = camelContext.getComponent(scheme, HttpComponent.class);
        String userAgent = String.format("%s/%s", applicationName, buildProperties.getVersion());
        httpComponent.setHttpClientConfigurer(clientBuilder -> clientBuilder.setUserAgent(userAgent));
    }
}
