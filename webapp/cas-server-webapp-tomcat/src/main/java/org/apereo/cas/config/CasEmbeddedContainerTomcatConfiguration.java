package org.apereo.cas.config;

import com.google.common.base.Throwables;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.ExtendedAccessLogValve;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.catalina.valves.rewrite.RewriteValve;
import org.apache.commons.lang3.StringUtils;
import org.apache.coyote.http2.Http2Protocol;
import org.apereo.cas.CasEmbeddedContainerUtils;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.configuration.model.core.CasServerProperties;
import org.apereo.cas.util.ResourceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.EmbeddedServletContainerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.embedded.EmbeddedServletContainerFactory;
import org.springframework.boot.context.embedded.tomcat.TomcatEmbeddedServletContainerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.util.SocketUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * This is {@link CasEmbeddedContainerTomcatConfiguration}.
 *
 * @author Misagh Moayyed
 * @since 5.0.0
 */
@Configuration("casEmbeddedContainerTomcatConfiguration")
@EnableConfigurationProperties(CasConfigurationProperties.class)
@ConditionalOnProperty(name = CasEmbeddedContainerUtils.EMBEDDED_CONTAINER_CONFIG_ACTIVE, havingValue = "true")
@AutoConfigureBefore(EmbeddedServletContainerAutoConfiguration.class)
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
public class CasEmbeddedContainerTomcatConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(CasEmbeddedContainerTomcatConfiguration.class);

    @Autowired
    private ServerProperties serverProperties;

    @Autowired
    private CasConfigurationProperties casProperties;


    @ConditionalOnClass(value = {Tomcat.class, Http2Protocol.class})
    @Bean
    public EmbeddedServletContainerFactory servletContainer() {
        final TomcatEmbeddedServletContainerFactory tomcat = new TomcatEmbeddedServletContainerFactory();

        configureAjp(tomcat);
        configureHttp(tomcat);
        configureHttpProxy(tomcat);
        configureExtendedAccessLogValve(tomcat);
        configureRewriteValve(tomcat);
        configureRemoteIpValve(tomcat);

        return tomcat;
    }

    private void configureRewriteValve(final TomcatEmbeddedServletContainerFactory tomcat) {
        final Resource res = casProperties.getServer().getRewriteValveConfigLocation();
        if (ResourceUtils.doesResourceExist(res)) {
            final RewriteValve valve = new RewriteValve() {
                @Override
                protected synchronized void startInternal() throws LifecycleException {
                    super.startInternal();
                    try (InputStream is = res.getInputStream();
                         InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
                         BufferedReader buffer = new BufferedReader(isr)) {
                        parse(buffer);
                    } catch (final Exception e) {
                        throw Throwables.propagate(e);
                    }
                }
            };
            valve.setAsyncSupported(true);
            valve.setEnabled(true);

            LOGGER.debug("Creating Rewrite valve configuration for the embedded tomcat container...");
            tomcat.addContextValves(valve);
        }
    }

    private void configureExtendedAccessLogValve(final TomcatEmbeddedServletContainerFactory tomcat) {
        final CasServerProperties.ExtendedAccessLog ext = casProperties.getServer().getExtAccessLog();

        if (ext.isEnabled() && StringUtils.isNotBlank(ext.getPattern())) {
            LOGGER.debug("Creating extended access log valve configuration for the embedded tomcat container...");
            final ExtendedAccessLogValve valve = new ExtendedAccessLogValve();
            valve.setPattern(ext.getPattern());

            if (StringUtils.isBlank(ext.getDirectory())) {
                valve.setDirectory(serverProperties.getTomcat().getAccesslog().getDirectory());
            } else {
                valve.setDirectory(ext.getDirectory());
            }
            valve.setPrefix(ext.getPrefix());
            valve.setSuffix(ext.getSuffix());
            valve.setAsyncSupported(true);
            valve.setEnabled(true);
            valve.setRotatable(true);
            valve.setBuffered(true);
            tomcat.addContextValves(valve);
            tomcat.addEngineValves(valve);
        }
    }

    private void configureRemoteIpValve(final TomcatEmbeddedServletContainerFactory tomcat) {
        final CasServerProperties.RemoteIp ip = casProperties.getServer().getRemoteIp();
        
        if (ip.isEnabled()) {
            final RemoteIpValve valve = new RemoteIpValve();
            valve.setAsyncSupported(true);
            valve.setRequestAttributesEnabled(true);
            valve.setChangeLocalPort(ip.isChangeLocalPort());
            
            if (StringUtils.isNotBlank(ip.getDomain())) {
                valve.setDomain(ip.getDomain());
            }
            if (ip.getHttpPort() > 0) {
                valve.setHttpServerPort(ip.getHttpPort());
            }
            if (ip.getHttpsPort() > 0) {
                valve.setHttpsServerPort(ip.getHttpsPort());
            }
            if (StringUtils.isNotBlank(ip.getPortHeader())) {
                valve.setPortHeader(ip.getPortHeader());
            }
            if (StringUtils.isNotBlank(ip.getProtocolHeader())) {
                valve.setProtocolHeader(ip.getProtocolHeader());
            }
            if (StringUtils.isNotBlank(ip.getRemoteIpHeader())) {
                valve.setRemoteIpHeader(ip.getRemoteIpHeader());
            }
            if (StringUtils.isNotBlank(ip.getInternalProxies())) {
                valve.setInternalProxies(ip.getInternalProxies());
            }
            if (StringUtils.isNotBlank(ip.getTrustedProxies())) {
                valve.setTrustedProxies(ip.getTrustedProxies());
            }
            if (StringUtils.isNotBlank(ip.getProxiesHeader())) {
                valve.setProxiesHeader(ip.getProxiesHeader());
            }

            tomcat.addContextValves(valve);
            tomcat.addEngineValves(valve);
        }
    }
    
    private void configureHttp(final TomcatEmbeddedServletContainerFactory tomcat) {
        final CasServerProperties.Http http = casProperties.getServer().getHttp();
        if (http.isEnabled()) {
            LOGGER.debug("Creating HTTP configuration for the embedded tomcat container...");
            final Connector connector = new Connector(http.getProtocol());
            int port = http.getPort();
            if (port <= 0) {
                LOGGER.warn("No explicit port configuration is provided to CAS. Scanning for available ports...");
                port = SocketUtils.findAvailableTcpPort();
            }
            LOGGER.debug("Set embedded tomcat container HTTP port to [{}]", port);
            connector.setPort(port);

            LOGGER.debug("Configuring embedded tomcat container for HTTP2 protocol support");
            connector.addUpgradeProtocol(new Http2Protocol());
            tomcat.addAdditionalTomcatConnectors(connector);
        }
    }

    private void configureHttpProxy(final TomcatEmbeddedServletContainerFactory tomcat) {
        final CasServerProperties.HttpProxy proxy = casProperties.getServer().getHttpProxy();
        if (proxy.isEnabled()) {
            LOGGER.debug("Customizing HTTP proxying for connector listening on port [{}]", tomcat.getPort());
            tomcat.getTomcatConnectorCustomizers().add(connector -> {
                connector.setSecure(proxy.isSecure());
                connector.setScheme(proxy.getScheme());

                if (StringUtils.isNotBlank(proxy.getProtocol())) {
                    LOGGER.debug("Setting HTTP proxying protocol to [{}]", proxy.getProtocol());
                    connector.setProtocol(proxy.getProtocol());
                }
                if (proxy.getRedirectPort() > 0) {
                    LOGGER.debug("Setting HTTP proxying redirect port to [{}]", proxy.getRedirectPort());
                    connector.setRedirectPort(proxy.getRedirectPort());
                }
                if (proxy.getProxyPort() > 0) {
                    LOGGER.debug("Setting HTTP proxying proxy port to [{}]", proxy.getProxyPort());
                    connector.setProxyPort(proxy.getProxyPort());
                }
                LOGGER.info("Configured connector listening on port [{}]", tomcat.getPort());
            });
        } else {
            LOGGER.debug("HTTP proxying is not enabled for CAS; Connector configuration for port [{}] is not modified.", tomcat.getPort());
        }
    }
    
    private void configureAjp(final TomcatEmbeddedServletContainerFactory tomcat) {
        final CasServerProperties.Ajp ajp = casProperties.getServer().getAjp();
        if (ajp.isEnabled() && ajp.getPort() > 0) {
            LOGGER.debug("Creating AJP configuration for the embedded tomcat container...");
            final Connector ajpConnector = new Connector(ajp.getProtocol());
            ajpConnector.setProtocol(ajp.getProtocol());
            ajpConnector.setPort(ajp.getPort());
            ajpConnector.setSecure(ajp.isSecure());
            ajpConnector.setAllowTrace(ajp.isAllowTrace());
            ajpConnector.setScheme(ajp.getScheme());
            if (ajp.getAsyncTimeout() > 0) {
                ajpConnector.setAsyncTimeout(ajp.getAsyncTimeout());
            }
            ajpConnector.setEnableLookups(ajp.isEnableLookups());
            if (ajp.getMaxPostSize() > 0) {
                ajpConnector.setMaxPostSize(ajp.getMaxPostSize());
            }
            ajpConnector.addUpgradeProtocol(new Http2Protocol());

            if (ajp.getProxyPort() > 0) {
                LOGGER.debug("Set AJP proxy port to [{}]", ajp.getProxyPort());
                ajpConnector.setProxyPort(ajp.getProxyPort());
            }

            if (ajp.getRedirectPort() > 0) {
                LOGGER.debug("Set AJP redirect port to [{}]", ajp.getRedirectPort());
                ajpConnector.setRedirectPort(ajp.getRedirectPort());
            }
            tomcat.addAdditionalTomcatConnectors(ajpConnector);
        }
    }

}
