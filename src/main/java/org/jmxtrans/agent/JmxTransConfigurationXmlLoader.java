/*
 * Copyright (c) 2010-2013 the original author or authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */
package org.jmxtrans.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Pattern;

import org.jmxtrans.agent.properties.NoPropertiesSourcePropertiesLoader;
import org.jmxtrans.agent.properties.PropertiesLoader;
import org.jmxtrans.agent.util.Preconditions2;
import org.jmxtrans.agent.util.PropertyPlaceholderResolver;
import org.jmxtrans.agent.util.io.IoUtils;
import org.jmxtrans.agent.util.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.annotation.Nonnull;

/**
 * XML configuration parser.
 *
 * @author <a href="mailto:cleclerc@cloudbees.com">Cyrille Le Clerc</a>
 */
public class JmxTransConfigurationXmlLoader implements JmxTransConfigurationLoader {

    private static final Pattern ATTRIBUTE_SPLIT_PATTERN = Pattern.compile("\\s*,\\s*");
    private Logger logger = Logger.getLogger(getClass().getName());
    private final PropertiesLoader propertiesLoader;
    
    @Nonnull
    private final String configurationFilePath;

    public JmxTransConfigurationXmlLoader(@Nonnull String configurationFilePath, PropertiesLoader propertiesLoader) {
        this.configurationFilePath = Preconditions2.checkNotNull(configurationFilePath, "configurationFilePath can not be null");
        this.propertiesLoader = propertiesLoader;
    }

    /**
     * Creates a JmxTransExporterBuilder with a PropertyLoader that does not use an
     * external properties source.
     */
    public JmxTransConfigurationXmlLoader(@Nonnull String configurationFilePath) {
        this(configurationFilePath, new NoPropertiesSourcePropertiesLoader());
    }

    @Override
    public JmxTransExporterConfiguration loadConfiguration() {
        return build(IoUtils.getFileAsDocument(configurationFilePath));
    }

    @Override
    public long lastModified() {
        return IoUtils.getFileLastModificationDate(configurationFilePath);
    }

    protected JmxTransExporterConfiguration build(Document document) {
        Element rootElement = document.getDocumentElement();

        Map<String, String> loadedProperties = loadPropertiesOrEmptyOnError();
        PropertyPlaceholderResolver resolver = new PropertyPlaceholderResolver(loadedProperties);
        JmxTransExporterConfiguration jmxTransExporterConfiguration = new JmxTransExporterConfiguration(document);

        Integer collectInterval = getIntegerElementValueOrNullIfNotSet(rootElement, "collectIntervalInSeconds", resolver);
        if (collectInterval != null) {
            jmxTransExporterConfiguration.withCollectInterval(collectInterval, TimeUnit.SECONDS);
        }
        Integer reloadConfigInterval = getIntegerElementValueOrNullIfNotSet(rootElement,
                "reloadConfigurationCheckIntervalInSeconds", resolver);
        if (reloadConfigInterval != null) {
            jmxTransExporterConfiguration.withConfigReloadInterval(reloadConfigInterval);
        }

        buildResultNameStrategy(rootElement, jmxTransExporterConfiguration, resolver);
        buildInvocations(rootElement, jmxTransExporterConfiguration);
        buildQueries(rootElement, jmxTransExporterConfiguration);

        buildOutputWriters(rootElement, jmxTransExporterConfiguration, resolver);

        return jmxTransExporterConfiguration;
    }

    private Map<String, String> loadPropertiesOrEmptyOnError() {
        try {
            return propertiesLoader.loadProperties();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error when loading properties from loader " + propertiesLoader + ", this source will be ignored", e);
            return new HashMap<String, String>();
        }
    }

    public JmxTransExporterConfiguration build(JmxTransConfigurationLoader configurationDocumentLoader)
            throws Exception {
        JmxTransExporterConfiguration configuration = configurationDocumentLoader.loadConfiguration();
        return build(configuration.getDocument());
    }

    private Integer getIntegerElementValueOrNullIfNotSet(Element rootElement, String elementName, PropertyPlaceholderResolver placeholderResolver) {
        NodeList nodeList = rootElement.getElementsByTagName(elementName);
        switch (nodeList.getLength()) {
            case 0:
                return null;
            case 1:
                Element element = (Element) nodeList.item(0);
                String stringValue = placeholderResolver.resolveString(element.getTextContent());
                try {
                    return Integer.parseInt(stringValue);
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("Invalid <" + elementName + "> value '" + stringValue + "', integer expected", e);
                }
            default:
                logger.warning("More than 1 <" + elementName + "> element found (" + nodeList.getLength() + "), use latest");
                Element lastElement = (Element) nodeList.item(nodeList.getLength() - 1);
                String lastStringValue = placeholderResolver.resolveString(lastElement.getTextContent());
                try {
                    return Integer.parseInt(lastStringValue);
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("Invalid <" + elementName +"> value '" + lastStringValue + "', integer expected", e);
                }
        }
    }

    private void buildQueries(Element rootElement, JmxTransExporterConfiguration configuration) {
        NodeList queries = rootElement.getElementsByTagName("query");
        for (int i = 0; i < queries.getLength(); i++) {
            Element queryElement = (Element) queries.item(i);
            String objectName = queryElement.getAttribute("objectName");
            List<String> attributes = getAttributes(queryElement, objectName);
            String key = queryElement.hasAttribute("key") ? queryElement.getAttribute("key") : null;
            String resultAlias = queryElement.getAttribute("resultAlias");
            String type = queryElement.getAttribute("type");
            Integer position;
            try {
                position = queryElement.hasAttribute("position") ? Integer.parseInt(queryElement.getAttribute("position")) : null;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid 'position' attribute for query objectName=" + objectName +
                        ", attributes=" + attributes + ", resultAlias=" + resultAlias);

            }

            configuration.withQuery(objectName, attributes, key, position, type, resultAlias);
        }
    }

    private List<String> getAttributes(Element queryElement, String objectName) {
        String attribute = queryElement.getAttribute("attribute");
        String attributes = queryElement.getAttribute("attributes");
        validateOnlyAttributeOrAttributesSpecified(attribute, attributes, objectName);
        if (attribute.isEmpty() && attributes.isEmpty()) {
            return Collections.emptyList();
        }
        if (!attribute.isEmpty()) {
            return Collections.singletonList(attribute);
        } else {
           String[] splitAttributes = ATTRIBUTE_SPLIT_PATTERN.split(attributes);
           return Arrays.asList(splitAttributes);
        }
    }


    private void validateOnlyAttributeOrAttributesSpecified(String attribute, String attributes, String objectName) {
        if (!attribute.isEmpty() && !attributes.isEmpty()) {
            throw new IllegalArgumentException("Only one of 'attribute' and 'attributes' is supported for a query - not both - objectName: " + objectName);
        }
    }

    private void buildInvocations(Element rootElement, JmxTransExporterConfiguration configuration) {
        NodeList invocations = rootElement.getElementsByTagName("invocation");
        for (int i = 0; i < invocations.getLength(); i++) {
            Element invocationElement = (Element) invocations.item(i);
            String objectName = invocationElement.getAttribute("objectName");
            String operation = invocationElement.getAttribute("operation");
            String resultAlias = invocationElement.getAttribute("resultAlias");

            configuration.withInvocation(objectName, operation, resultAlias);
        }
    }

    private void buildResultNameStrategy(Element rootElement, JmxTransExporterConfiguration configuration, PropertyPlaceholderResolver placeholderResolver) {
        NodeList resultNameStrategyNodeList = rootElement.getElementsByTagName("resultNameStrategy");

        ResultNameStrategy resultNameStrategy;
        switch (resultNameStrategyNodeList.getLength()) {
            case 0:
                // nothing to do, use default value
                resultNameStrategy = new ResultNameStrategyImpl();
                break;
            case 1:
                Element resultNameStrategyElement = (Element) resultNameStrategyNodeList.item(0);
                String outputWriterClass = resultNameStrategyElement.getAttribute("class");
                if (outputWriterClass.isEmpty())
                    throw new IllegalArgumentException("<resultNameStrategy> element must contain a 'class' attribute");

                try {
                    resultNameStrategy = (ResultNameStrategy) Class.forName(outputWriterClass).newInstance();
                    Map<String, String> settings = new HashMap<String, String>();
                    NodeList settingsNodeList = resultNameStrategyElement.getElementsByTagName("*");
                    for (int j = 0; j < settingsNodeList.getLength(); j++) {
                        Element settingElement = (Element) settingsNodeList.item(j);
                        settings.put(settingElement.getNodeName(), placeholderResolver.resolveString(settingElement.getTextContent()));
                    }
                    resultNameStrategy.postConstruct(settings);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Exception instantiating " + outputWriterClass, e);
                }

                break;
            default:
                throw new IllegalStateException("More than 1 <resultNameStrategy> element found (" + resultNameStrategyNodeList.getLength() + ")");
        }
        configuration.resultNameStrategy = resultNameStrategy;
    }

    private void buildOutputWriters(Element rootElement, JmxTransExporterConfiguration configuration, PropertyPlaceholderResolver placeholderResolver) {
        NodeList outputWriterNodeList = rootElement.getElementsByTagName("outputWriter");
        List<OutputWriter> outputWriters = new ArrayList<OutputWriter>();

        for (int i = 0; i < outputWriterNodeList.getLength(); i++) {
            Element outputWriterElement = (Element) outputWriterNodeList.item(i);
            String outputWriterClass = outputWriterElement.getAttribute("class");
            if (outputWriterClass.isEmpty()) {
                throw new IllegalArgumentException("<outputWriter> element must contain a 'class' attribute");
            }
            OutputWriter outputWriter;
            try {
                outputWriter = (OutputWriter) Class.forName(outputWriterClass).newInstance();
                Map<String, String> settings = new HashMap<String, String>();
                NodeList settingsNodeList = outputWriterElement.getElementsByTagName("*");
                for (int j = 0; j < settingsNodeList.getLength(); j++) {
                    Element settingElement = (Element) settingsNodeList.item(j);
                    settings.put(settingElement.getNodeName(), placeholderResolver.resolveString(settingElement.getTextContent()));
                }
                outputWriter = new OutputWriterCircuitBreakerDecorator(outputWriter);
                outputWriter.postConstruct(settings);
                outputWriters.add(outputWriter);
            } catch (Exception e) {
                throw new IllegalArgumentException("Exception instantiating " + outputWriterClass, e);
            }

        }

        switch (outputWriters.size()) {
            case 0:
                logger.warning("No outputwriter defined.");
                break;
            case 1:
                configuration.withOutputWriter(outputWriters.get(0));
                break;
            default:
                configuration.withOutputWriter(new OutputWritersChain(outputWriters));
        }
    }

    @Override
    public String toString() {
        return "JmxTransConfigurationXmlLoader{" +
                "configurationFilePath='" + configurationFilePath + '\'' +
                '}';
    }
}
