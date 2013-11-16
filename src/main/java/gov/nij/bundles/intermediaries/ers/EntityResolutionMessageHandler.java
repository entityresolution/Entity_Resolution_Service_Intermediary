/*
 * Copyright 2013 SEARCH Group, Incorporated. 
 * 
 * See the NOTICE file distributed with  this work for additional information 
 * regarding copyright ownership.  SEARCH Group Inc. licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not use this 
 * file except in compliance with the License.  You may obtain a copy of the 
 * License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gov.nij.bundles.intermediaries.ers;

import gov.nij.bundles.intermediaries.ers.osgi.AttributeParameters;
import gov.nij.bundles.intermediaries.ers.osgi.AttributeStatistics;
import gov.nij.bundles.intermediaries.ers.osgi.AttributeWrapper;
import gov.nij.bundles.intermediaries.ers.osgi.EntityResolutionResults;
import gov.nij.bundles.intermediaries.ers.osgi.EntityResolutionService;
import gov.nij.bundles.intermediaries.ers.osgi.RecordWrapper;
import gov.nij.bundles.intermediaries.ers.osgi.SortOrderSpecification;
import gov.nij.camel.xpath.CamelXpathAnnotations;
import gov.nij.processor.AttributeParametersXpathSupport;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.camel.Exchange;
import org.apache.camel.converter.jaxp.XmlConverter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.cxf.common.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * This class bridges the Camel Route to the Entity Resolution OSGi bundle. It is responsible for processing attribute parameters either from a static configuration file or from the inbound message
 * payload. It also converts the XML payload into Java objects for the Entity Resolution algorithm to process and then converts Java objects back to XML for the merged response.
 * 
 */
public class EntityResolutionMessageHandler {

    private static final Log LOG = LogFactory.getLog(EntityResolutionMessageHandler.class);

    private XPath xpath;
    private EntityResolutionService entityResolutionService;
    private Document attributeParametersDocument;

    public EntityResolutionMessageHandler() {
        entityResolutionService = new EntityResolutionService();
        xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(new EntityResolutionNamespaceContext());

    }

    /**
     * This method is called from the Camel route and binds the parameters to the method arguments using an xpath expression. The result of the ER merge operation is set as the exchange body upon
     * completion.
     * 
     * @param exchange
     * @param entityContainerNodeList
     * @param attributeParametersNodeList
     * @throws Exception
     */
    public void process(Exchange exchange, @CamelXpathAnnotations("/merge:EntityMergeRequestMessage/merge:MergeParameters/er-ext:EntityContainer") NodeList entityContainerNodeList,
            @CamelXpathAnnotations("/merge:EntityMergeRequestMessage/merge:MergeParameters/er-ext:AttributeParameters") NodeList attributeParametersNodeList,
            @CamelXpathAnnotations("/merge:EntityMergeRequestMessage/merge:MergeParameters/er-ext:EntityResolutionConfiguration") NodeList entityResolutionConfigurationNodeList) throws Exception {

        // Parser returns a nodelist, Camel has provided typeconverter for Xpath processor but not for parameter binding:
        // https://issues.apache.org/jira/browse/CAMEL-5403
        Node entityContainerNode = entityContainerNodeList.item(0);
        Node attributeParametersNode = attributeParametersNodeList.item(0);
        Node entityResolutionConfigurationNode = entityResolutionConfigurationNodeList == null ? null : entityResolutionConfigurationNodeList.item(0);

        Document resultDocument = performEntityResolution(entityContainerNode, attributeParametersNode, entityResolutionConfigurationNode);
        exchange.getIn().setBody(resultDocument);

    }

    /**
     * This method performs entity resolution and returns the Merge Response Document.
     * 
     * @param entityContainerNode
     * @param attributeParametersNode
     * @param entityResolutionConfigurationNode
     * @return
     * @throws Exception
     * @throws ParserConfigurationException
     * @throws XPathExpressionException
     * @throws TransformerException
     */
    Document performEntityResolution(Node entityContainerNode, Node attributeParametersNode, Node entityResolutionConfigurationNode) throws Exception, ParserConfigurationException,
            XPathExpressionException, TransformerException {
        Set<AttributeParametersXpathSupport> attributeParametersXpathSupport = getAttributeParameters(attributeParametersNode);
        List<RecordWrapper> records = createRecordsFromRequestMessage(entityContainerNode, attributeParametersNode);
        LOG.debug("before resolveEntities, records=" + records);

        // We can't call the ER OSGi service with AttributeParametersXpathSupport Set
        // so we create an attributeParameters Set to call it with.
        Set<AttributeParameters> attributeParameters = new HashSet<AttributeParameters>();

        for (AttributeParametersXpathSupport attributeParameterXpathSupport : attributeParametersXpathSupport) {
            attributeParameters.add(attributeParameterXpathSupport);
        }

        String recordLimitString = null;

        if (entityResolutionConfigurationNode != null) {
            recordLimitString = xpath.evaluate("er-ext:RecordLimit", entityResolutionConfigurationNode);
        }

        int recordLimit = Integer.MAX_VALUE;

        if (recordLimitString != null) {
            try {
                recordLimit = Integer.parseInt(recordLimitString);
            } catch (NumberFormatException nfe) {
                LOG.debug("Record limit value " + recordLimitString + " does not parse as an integer, will not set a record limit");
            }
        }

        EntityResolutionResults results = entityResolutionService.resolveEntities(records, attributeParameters, recordLimit);
        Document resultDocument = createResponseMessage(entityContainerNode, results);
        // without this next line, we get an exception about an unbound namespace URI (NIEM structures)
        resultDocument.normalizeDocument();
        return resultDocument;
    }

    Set<AttributeParametersXpathSupport> getAttributeParameters(Node attributeParametersNode) throws Exception {
        Set<AttributeParametersXpathSupport> ret = new HashSet<AttributeParametersXpathSupport>();

        NodeList parameterNodes = null;

        if (attributeParametersNode == null) {
            parameterNodes = (NodeList) xpath.evaluate("er-ext:AttributeParameter", attributeParametersDocument.getDocumentElement(), XPathConstants.NODESET);
        } else {
            parameterNodes = (NodeList) xpath.evaluate("er-ext:AttributeParameter", attributeParametersNode, XPathConstants.NODESET);
        }

        // XmlConverter converter = new XmlConverter();
        // converter.getDocumentBuilderFactory().setNamespaceAware(true);
        // LOG.info(converter.toString(attributeParametersDocument));

        for (int i = 0; i < parameterNodes.getLength(); i++) {
            Node node = parameterNodes.item(i);

            // From the attribute parameter element, extract the attribute xpath value
            // The namespace prefixes will need to be processed and added to the ER namespace context
            String attributeXpathValue = xpath.evaluate("er-ext:AttributeXPath", node);
            LOG.debug("Attribute parameter xpath value: " + attributeXpathValue);
            AttributeParametersXpathSupport parameter = new AttributeParametersXpathSupport(attributeXpathValue, node);

            String algorithmURI = xpath.evaluate("er-ext:AttributeMatchAlgorithmSimmetricsURICode", node);
            String botchedClassName = algorithmURI.replace("urn:org:search:ers:algorithms:", "");
            String[] splitClassName = botchedClassName.split("\\.");
            StringBuffer reversedClassName = new StringBuffer(64);
            for (int ii = splitClassName.length - 2; ii >= 0; ii--) {
                reversedClassName.append(splitClassName[ii]).append(".");
            }
            reversedClassName.append(splitClassName[splitClassName.length - 1]);
            parameter.setAlgorithmClassName(reversedClassName.toString());
            String isDeterm = xpath.evaluate("er-ext:AttributeIsDeterminativeIndicator", node);
            // LOG.info("$#$#$!!! isDeterm=" + isDeterm);
            parameter.setDeterminative("true".equals(isDeterm));
            parameter.setThreshold(Double.parseDouble(xpath.evaluate("er-ext:AttributeThresholdValue", node)));
            Node sortNode = (Node) xpath.evaluate("er-ext:AttributeSortSpecification", node, XPathConstants.NODE);
            if (sortNode != null) {
                SortOrderSpecification sos = new SortOrderSpecification();
                String sortOrder = xpath.evaluate("er-ext:AttributeSortOrder", sortNode);
                String sortOrderRankS = xpath.evaluate("er-ext:AttributeSortOrderRank", sortNode);
                if (sortOrder == null || sortOrderRankS == null) {
                    throw new IllegalArgumentException("If the AttributeSortSpecification element is specified, both sort order and rank must be specified.");
                }
                int sortOrderRank = Integer.parseInt(sortOrderRankS);
                sos.setSortOrder(sortOrder);
                sos.setSortOrderRank(sortOrderRank);
                parameter.setSortOrder(sos);
            }
            ret.add(parameter);
        }
        return ret;
    }

    /**
     * This method takes the entity and attribute parameter nodes as arguments and converts the XML to Java Objects so Entity Resolution can be performed.
     * 
     * @param entityContainerNode
     * @param attributeParametersNode
     * @return
     * @throws Exception
     */

    List<RecordWrapper> createRecordsFromRequestMessage(Node entityContainerNode, Node attributeParametersNode) throws Exception {

        NodeList entityNodeList = (NodeList) xpath.evaluate("er-ext:Entity", entityContainerNode, XPathConstants.NODESET);

        List<RecordWrapper> records = new ArrayList<RecordWrapper>();

        Set<AttributeParametersXpathSupport> attributeParametersXpathSupport = getAttributeParameters(attributeParametersNode);

        for (int i = 0; i < entityNodeList.getLength(); i++) {
            Element entityElement = (Element) entityNodeList.item(i);

            // The following lines will first check for an ID, if none is found, one is generated
            String entityId = entityElement.getAttributeNS(EntityResolutionNamespaceContext.STRUCTURES_NAMESPACE, "id");

            if (StringUtils.isEmpty(entityId)) {
                entityId = "E" + UUID.randomUUID().toString();
                entityElement.setAttributeNS(EntityResolutionNamespaceContext.STRUCTURES_NAMESPACE, "s:id", entityId);
            }

            entityElement = createOrphanElement(entityElement);

            Map<String, AttributeWrapper> attributeMap = new HashMap<String, AttributeWrapper>();

            for (AttributeParametersXpathSupport parameter : attributeParametersXpathSupport) {
                String attributeName = parameter.getAttributeName();
                AttributeWrapper attribute = new AttributeWrapper(attributeName);

                XPath attributeParameterXpath = parameter.getXpath();
                String value = attributeParameterXpath.evaluate(attributeName, entityElement);

                attribute.addValue(value);
                LOG.debug("Adding attribute to record with entityId=" + entityId + ", type=" + attributeName + ", value=" + value);
                attributeMap.put(attribute.getType(), attribute);
            }

            RecordWrapper record = new RecordWrapper(attributeMap, entityId);
            records.add(record);
        }

        return records;

    }

    /**
     * Xpath performance degrades on large documents so this workaround was needed to improve performance. See link inline in the code.
     * 
     * @param entityElement
     * @return
     * @throws ParserConfigurationException
     */
    private Element createOrphanElement(Element entityElement) throws ParserConfigurationException {

        // this is necessary to avoid a performance bottleneck in the Xalan xpath engine
        // see http://stackoverflow.com/questions/6340802/java-xpath-apache-jaxp-implementation-performance

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document dummyDocument = db.newDocument();
        dummyDocument.appendChild(dummyDocument.importNode(entityElement, true));
        entityElement = dummyDocument.getDocumentElement();

        return entityElement;

    }

    /**
     * This method takes the ER response and converts the Java objects to the Merge Response XML.
     * 
     * @param entityContainerNode
     * @param results
     * @return
     * @throws ParserConfigurationException
     * @throws XPathExpressionException
     * @throws TransformerException
     */
    private Document createResponseMessage(Node entityContainerNode, EntityResolutionResults results) throws ParserConfigurationException, XPathExpressionException, TransformerException {

        List<RecordWrapper> records = results.getRecords();

        // Create new DOM Document
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);

        Document resultDocument = dbf.newDocumentBuilder().newDocument();

        // Create and append root element
        Element entityMergeResultMessageElement = resultDocument.createElementNS(EntityResolutionNamespaceContext.MERGE_RESULT_NAMESPACE, "EntityMergeResultMessage");
        resultDocument.appendChild(entityMergeResultMessageElement);

        // Adopt node into new document that is being created, it will be renamed and then appended
        Node resultElement = resultDocument.adoptNode(entityContainerNode.cloneNode(true));

        // Rename to merge results
        resultDocument.renameNode(resultElement, EntityResolutionNamespaceContext.MERGE_RESULT_NAMESPACE, resultElement.getLocalName());
        entityMergeResultMessageElement.appendChild(resultElement);

        // Grab the existing entities that were appended to the new document
        NodeList entityNodeList = (NodeList) xpath.evaluate("er-ext:Entity", resultElement, XPathConstants.NODESET);

        // Loop through and rename the new nodes, preserving the local name and changing the namespace
        for (int i = 0; i < entityNodeList.getLength(); i++) {
            Node entityNode = entityNodeList.item(i);
            resultDocument.renameNode(entityNode, EntityResolutionNamespaceContext.MERGE_RESULT_EXT_NAMESPACE, entityNode.getLocalName());
        }

        Element mergedRecordsElement = resultDocument.createElementNS(EntityResolutionNamespaceContext.MERGE_RESULT_NAMESPACE, "MergedRecords");
        entityMergeResultMessageElement.appendChild(mergedRecordsElement);

        // Loop through RecordWrappers to extract info to create merged records
        for (RecordWrapper record : records) {
            LOG.debug("  !#!#!#!# Record 1, id=" + record.getExternalId() + ", externals=" + record.getRelatedIds());

            // Create Merged Record Container
            Element mergedRecordElement = resultDocument.createElementNS(EntityResolutionNamespaceContext.MERGE_RESULT_EXT_NAMESPACE, "MergedRecord");
            mergedRecordsElement.appendChild(mergedRecordElement);

            // Create Original Record Reference for 'first record'
            Element originalRecordRefElement = resultDocument.createElementNS(EntityResolutionNamespaceContext.MERGE_RESULT_EXT_NAMESPACE, "OriginalRecordReference");
            originalRecordRefElement.setAttributeNS(EntityResolutionNamespaceContext.STRUCTURES_NAMESPACE, "ref", record.getExternalId());
            mergedRecordElement.appendChild(originalRecordRefElement);

            // Loop through and add any related records
            for (String relatedRecordId : record.getRelatedIds()) {
                originalRecordRefElement = resultDocument.createElementNS(EntityResolutionNamespaceContext.MERGE_RESULT_EXT_NAMESPACE, "OriginalRecordReference");
                originalRecordRefElement.setAttributeNS(EntityResolutionNamespaceContext.STRUCTURES_NAMESPACE, "ref", relatedRecordId);
                mergedRecordElement.appendChild(originalRecordRefElement);
            }

            // Create Merge Quality Element
            Element mergeQualityElement = resultDocument.createElementNS(EntityResolutionNamespaceContext.MERGE_RESULT_EXT_NAMESPACE, "MergeQuality");
            mergedRecordElement.appendChild(mergeQualityElement);
            Set<AttributeStatistics> stats = results.getStatisticsForRecord(record.getExternalId());
            for (AttributeStatistics stat : stats) {
                Element stringDistanceStatsElement = resultDocument.createElementNS(EntityResolutionNamespaceContext.MERGE_RESULT_EXT_NAMESPACE, "StringDistanceStatistics");
                mergeQualityElement.appendChild(stringDistanceStatsElement);
                Element xpathElement = resultDocument.createElementNS(EntityResolutionNamespaceContext.MERGE_RESULT_EXT_NAMESPACE, "AttributeXPath");
                stringDistanceStatsElement.appendChild(xpathElement);
                Node contentNode = resultDocument.createTextNode(stat.getAttributeName());
                xpathElement.appendChild(contentNode);
                Element meanElement = resultDocument.createElementNS(EntityResolutionNamespaceContext.MERGE_RESULT_EXT_NAMESPACE, "StringDistanceMeanInRecord");
                stringDistanceStatsElement.appendChild(meanElement);
                contentNode = resultDocument.createTextNode(String.valueOf(stat.getAverageStringDistance()));
                meanElement.appendChild(contentNode);
                Element sdElement = resultDocument.createElementNS(EntityResolutionNamespaceContext.MERGE_RESULT_EXT_NAMESPACE, "StringDistanceStandardDeviationInRecord");
                stringDistanceStatsElement.appendChild(sdElement);
                contentNode = resultDocument.createTextNode(String.valueOf(stat.getStandardDeviationStringDistance()));
                sdElement.appendChild(contentNode);

            }
        }

        Element recordLimitExceededElement = resultDocument.createElementNS(EntityResolutionNamespaceContext.MERGE_RESULT_NAMESPACE, "RecordLimitExceeded");
        recordLimitExceededElement.setTextContent(new Boolean(results.isRecordLimitExceeded()).toString());
        entityMergeResultMessageElement.appendChild(recordLimitExceededElement);

        return resultDocument;

    }

    /**
     * This is used in testing to allow the attribute parameters to be read from a static file
     */
    void setAttributeParametersStream(InputStream attributeParametersStream) throws Exception {
        XmlConverter xmlConverter = new XmlConverter();
        xmlConverter.getDocumentBuilderFactory().setNamespaceAware(true);
        attributeParametersDocument = xmlConverter.toDOMDocument(attributeParametersStream);
    }

    /**
     * This setter allows the attribute parameters document to be read from a static file. It can still be overridden if the attribute parameters are in the message.
     * 
     * @param attributeParametersURL
     * @throws Exception
     */
    public void setAttributeParametersURL(String attributeParametersURL) throws Exception {
        try {
            URL staticFileURL = new URL(attributeParametersURL);
            setAttributeParametersStream(staticFileURL.openStream());
        } catch (MalformedURLException mfu) {
            attributeParametersURL = attributeParametersURL.replace("classpath:", "");
            InputStream is = this.getClass().getResourceAsStream(attributeParametersURL);
            setAttributeParametersStream(is);
        }
    }

}
