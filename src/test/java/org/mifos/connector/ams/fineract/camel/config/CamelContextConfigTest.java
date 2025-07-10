package org.mifos.connector.ams.fineract.camel.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.camel.CamelContext;
import org.apache.camel.component.http.HttpComponent;
import org.apache.camel.spi.RestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.boot.info.BuildProperties;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class CamelContextConfigTest {

    @Mock
    private CamelContext camelContext;

    @Mock
    private BuildProperties buildProperties;

    @Mock
    private HttpComponent httpComponent;

    @Test
    void contextConfiguration_ShouldConfigureCamelContext() {
        // Arrange
        CamelContextConfig config = new CamelContextConfig(buildProperties);
        ReflectionTestUtils.setField(config, "serverPort", 8080);
        ReflectionTestUtils.setField(config, "applicationName", "test-app");

        when(camelContext.getComponent("http", HttpComponent.class)).thenReturn(httpComponent);
        when(camelContext.getComponent("https", HttpComponent.class)).thenReturn(httpComponent);

        // Act
        config.contextConfiguration().beforeApplicationStart(camelContext);

        // Assert
        verify(camelContext).setTracing(false);
        verify(camelContext).setMessageHistory(false);
        verify(camelContext).setStreamCaching(true);
        verify(camelContext).disableJMX();
        verify(camelContext).setRestConfiguration(any(RestConfiguration.class));
        verify(camelContext).getComponent("http", HttpComponent.class);
        verify(camelContext).getComponent("https", HttpComponent.class);
        verify(httpComponent, times(2)).setHttpClientConfigurer(any());
    }

    @Test
    void contextConfiguration_WhenBuildPropertiesNull_ShouldConfigureCamelContext() {
        // Arrange
        CamelContextConfig config = new CamelContextConfig(null);
        ReflectionTestUtils.setField(config, "serverPort", 8080);
        ReflectionTestUtils.setField(config, "applicationName", "test-app");

        when(camelContext.getComponent("http", HttpComponent.class)).thenReturn(httpComponent);
        when(camelContext.getComponent("https", HttpComponent.class)).thenReturn(httpComponent);

        // Act
        config.contextConfiguration().beforeApplicationStart(camelContext);

        // Assert
        verify(camelContext).setTracing(false);
        verify(camelContext).setMessageHistory(false);
        verify(camelContext).setStreamCaching(true);
        verify(camelContext).disableJMX();
        verify(camelContext).setRestConfiguration(any(RestConfiguration.class));
        verify(camelContext).getComponent("http", HttpComponent.class);
        verify(camelContext).getComponent("https", HttpComponent.class);
        verify(httpComponent, times(2)).setHttpClientConfigurer(any());
    }

}
