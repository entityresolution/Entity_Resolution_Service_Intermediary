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

import gov.nij.bundles.intermediaries.ers.EntityResolutionMessageHandler;
import gov.nij.bundles.intermediaries.ers.EntityResolutionNamespaceContext;
import gov.nij.bundles.intermediaries.ers.osgi.AttributeParameters;
import gov.nij.bundles.intermediaries.ers.osgi.EntityResolutionConversionUtils;
import gov.nij.bundles.intermediaries.ers.osgi.ExternallyIdentifiableRecord;
import gov.nij.bundles.intermediaries.ers.osgi.RecordWrapper;
import gov.nij.processor.AttributeParametersXpathSupport;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import junit.framework.TestCase;

import org.apache.camel.converter.jaxp.XmlConverter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Before;
import org.junit.Test;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import serf.data.Attribute;

public class EntityResolutionMessageHandlerTest extends TestCase {

    private static final Log LOG = LogFactory.getLog(EntityResolutionMessageHandlerTest.class);

    private EntityResolutionMessageHandler entityResolutionMessageHandler;
    private InputStream testRequestMessageInputStream;
    private InputStream testAttributeParametersMessageInputStream;

    @Before
    public void setUp() throws Exception {
        entityResolutionMessageHandler = new EntityResolutionMessageHandler();
        testAttributeParametersMessageInputStream = getClass().getResourceAsStream("/xml/TestAttributeParameters.xml");
        assertNotNull(testAttributeParametersMessageInputStream);
        entityResolutionMessageHandler.setAttributeParametersStream(testAttributeParametersMessageInputStream);
        testRequestMessageInputStream = getClass().getResourceAsStream("/xml/EntityMergeRequestMessage.xml");
        assertNotNull(testRequestMessageInputStream);
    }

    @Test
    public void testPerformEntityResolutionWithDetermFactors() throws Exception {
        XmlConverter converter = new XmlConverter();
        converter.getDocumentBuilderFactory().setNamespaceAware(true);
        InputStream attributeParametersStream = getClass().getResourceAsStream("/xml/TestAttributeParametersWithDeterm.xml");
        entityResolutionMessageHandler.setAttributeParametersStream(attributeParametersStream);
        testRequestMessageInputStream = getClass().getResourceAsStream("/xml/EntityMergeRequestMessageForDeterm.xml");
        Document testRequestMessage = converter.toDOMDocument(testRequestMessageInputStream);

        Node entityContainerNode = testRequestMessage.getElementsByTagNameNS(EntityResolutionNamespaceContext.ER_EXT_NAMESPACE, "EntityContainer").item(0);
        assertNotNull(entityContainerNode);
        Document resultDocument = entityResolutionMessageHandler.performEntityResolution(entityContainerNode, null, null);

        resultDocument.normalizeDocument();
        // LOG.info(converter.toString(resultDocument));
        XPath xp = XPathFactory.newInstance().newXPath();
        xp.setNamespaceContext(new EntityResolutionNamespaceContext());
        NodeList entityNodes = (NodeList) xp.evaluate("//merge-result:EntityContainer/merge-result-ext:Entity", resultDocument, XPathConstants.NODESET);
        assertEquals(2, entityNodes.getLength());
        entityNodes = (NodeList) xp.evaluate("//merge-result-ext:MergedRecord", resultDocument, XPathConstants.NODESET);
        assertEquals(2, entityNodes.getLength());
        entityNodes = (NodeList) xp.evaluate("//merge-result-ext:OriginalRecordReference", resultDocument, XPathConstants.NODESET);
        assertEquals(2, entityNodes.getLength());
        for (int i = 0; i < entityNodes.getLength(); i++) {
            Element e = (Element) entityNodes.item(i);
            String entityIdRef = e.getAttributeNS(EntityResolutionNamespaceContext.STRUCTURES_NAMESPACE, "ref");
            assertNotNull(entityIdRef);
            assertNotNull(xp.evaluate("//merge-result-ext:Entity[@s:id='" + entityIdRef + "']", resultDocument, XPathConstants.NODE));
        }
    }

    @Test
    public void testRecordLimit() throws Exception {

        XmlConverter converter = new XmlConverter();
        converter.getDocumentBuilderFactory().setNamespaceAware(true);
        Document testRequestMessage = converter.toDOMDocument(testRequestMessageInputStream);

        Node entityContainerNode = testRequestMessage.getElementsByTagNameNS(EntityResolutionNamespaceContext.ER_EXT_NAMESPACE, "EntityContainer").item(0);
        assertNotNull(entityContainerNode);

        Node entityResolutionConfigurationNode = makeEntityResolutionConfigurationNode(String.valueOf(Integer.MAX_VALUE));

        Document resultDocument = entityResolutionMessageHandler.performEntityResolution(entityContainerNode, null, entityResolutionConfigurationNode);

        XPath xp = XPathFactory.newInstance().newXPath();
        xp.setNamespaceContext(new EntityResolutionNamespaceContext());
        NodeList entityNodes = (NodeList) xp.evaluate("//merge-result:EntityContainer/merge-result-ext:Entity", resultDocument, XPathConstants.NODESET);
        int inputEntityNodeCount = 3;
        assertEquals(inputEntityNodeCount, entityNodes.getLength());
        entityNodes = (NodeList) xp.evaluate("//merge-result-ext:MergedRecord", resultDocument, XPathConstants.NODESET);
        assertEquals(2, entityNodes.getLength());
        String recordLimitExceeded = xp.evaluate("/merge-result:EntityMergeResultMessage/merge-result:RecordLimitExceeded", resultDocument);
        assertEquals("false", recordLimitExceeded);
        
        entityResolutionConfigurationNode = makeEntityResolutionConfigurationNode(3+"");

        resultDocument = entityResolutionMessageHandler.performEntityResolution(entityContainerNode, null, entityResolutionConfigurationNode);

        xp.setNamespaceContext(new EntityResolutionNamespaceContext());
        entityNodes = (NodeList) xp.evaluate("//merge-result:EntityContainer/merge-result-ext:Entity", resultDocument, XPathConstants.NODESET);
        inputEntityNodeCount = 3;
        assertEquals(inputEntityNodeCount, entityNodes.getLength());
        entityNodes = (NodeList) xp.evaluate("//merge-result-ext:MergedRecord", resultDocument, XPathConstants.NODESET);
        assertEquals(2, entityNodes.getLength());
        recordLimitExceeded = xp.evaluate("/merge-result:EntityMergeResultMessage/merge-result:RecordLimitExceeded", resultDocument);
        assertEquals("false", recordLimitExceeded);
        
        entityResolutionConfigurationNode = makeEntityResolutionConfigurationNode(null);

        resultDocument = entityResolutionMessageHandler.performEntityResolution(entityContainerNode, null, entityResolutionConfigurationNode);

        xp.setNamespaceContext(new EntityResolutionNamespaceContext());
        entityNodes = (NodeList) xp.evaluate("//merge-result:EntityContainer/merge-result-ext:Entity", resultDocument, XPathConstants.NODESET);
        inputEntityNodeCount = 3;
        assertEquals(inputEntityNodeCount, entityNodes.getLength());
        entityNodes = (NodeList) xp.evaluate("//merge-result-ext:MergedRecord", resultDocument, XPathConstants.NODESET);
        assertEquals(2, entityNodes.getLength());
        recordLimitExceeded = xp.evaluate("/merge-result:EntityMergeResultMessage/merge-result:RecordLimitExceeded", resultDocument);
        assertEquals("false", recordLimitExceeded);
        
        entityResolutionConfigurationNode = makeEntityResolutionConfigurationNode("not an int");

        resultDocument = entityResolutionMessageHandler.performEntityResolution(entityContainerNode, null, entityResolutionConfigurationNode);

        xp.setNamespaceContext(new EntityResolutionNamespaceContext());
        entityNodes = (NodeList) xp.evaluate("//merge-result:EntityContainer/merge-result-ext:Entity", resultDocument, XPathConstants.NODESET);
        inputEntityNodeCount = 3;
        assertEquals(inputEntityNodeCount, entityNodes.getLength());
        entityNodes = (NodeList) xp.evaluate("//merge-result-ext:MergedRecord", resultDocument, XPathConstants.NODESET);
        assertEquals(2, entityNodes.getLength());
        recordLimitExceeded = xp.evaluate("/merge-result:EntityMergeResultMessage/merge-result:RecordLimitExceeded", resultDocument);
        assertEquals("false", recordLimitExceeded);
        
        entityResolutionConfigurationNode = makeEntityResolutionConfigurationNode(2+"");

        Document attributeParametersDocument = entityResolutionMessageHandler.getAttributeParametersDocument();
        resultDocument = entityResolutionMessageHandler.performEntityResolution(entityContainerNode, attributeParametersDocument.getDocumentElement(), entityResolutionConfigurationNode);

        xp.setNamespaceContext(new EntityResolutionNamespaceContext());
        entityNodes = (NodeList) xp.evaluate("//merge-result:EntityContainer/merge-result-ext:Entity", resultDocument, XPathConstants.NODESET);
        inputEntityNodeCount = 3;
        assertEquals(inputEntityNodeCount, entityNodes.getLength());
        entityNodes = (NodeList) xp.evaluate("//merge-result-ext:MergedRecord", resultDocument, XPathConstants.NODESET);
        assertEquals(3, entityNodes.getLength());
        recordLimitExceeded = xp.evaluate("/merge-result:EntityMergeResultMessage/merge-result:RecordLimitExceeded", resultDocument);
        assertEquals("true", recordLimitExceeded);
        
        //LOG.info(new XmlConverter().toString(resultDocument));
        NodeList statNodes = (NodeList) xp.evaluate("//merge-result-ext:MergedRecord/merge-result-ext:MergeQuality", resultDocument, XPathConstants.NODESET);
        assertEquals(3, statNodes.getLength());
        statNodes = (NodeList) xp.evaluate("//merge-result-ext:MergedRecord/merge-result-ext:MergeQuality/merge-result-ext:StringDistanceStatistics", resultDocument, XPathConstants.NODESET);
        assertEquals(6, statNodes.getLength());
        statNodes = (NodeList) xp.evaluate("//merge-result-ext:MergedRecord/merge-result-ext:MergeQuality/merge-result-ext:StringDistanceStatistics/merge-result-ext:AttributeXPath", resultDocument, XPathConstants.NODESET);
        assertEquals(6, statNodes.getLength());
        statNodes = (NodeList) xp.evaluate("//merge-result-ext:MergedRecord/merge-result-ext:MergeQuality/merge-result-ext:StringDistanceStatistics/merge-result-ext:StringDistanceMeanInRecord", resultDocument, XPathConstants.NODESET);
        assertEquals(6, statNodes.getLength());
        statNodes = (NodeList) xp.evaluate("//merge-result-ext:MergedRecord/merge-result-ext:MergeQuality/merge-result-ext:StringDistanceStatistics/merge-result-ext:StringDistanceStandardDeviationInRecord", resultDocument, XPathConstants.NODESET);
        assertEquals(6, statNodes.getLength());
    }

    private Node makeEntityResolutionConfigurationNode(String limit) throws Exception {
        try {
        if (Integer.parseInt(limit) == Integer.MAX_VALUE) {
            return null;
        }
        } catch (NumberFormatException nfe) {
            return null;
        }
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document d = db.newDocument();
        Element ret = d.createElementNS(EntityResolutionNamespaceContext.ER_EXT_NAMESPACE, "EntityResolutionConfiguration");
        d.appendChild(ret);
        Element e = d.createElementNS(EntityResolutionNamespaceContext.ER_EXT_NAMESPACE, "RecordLimit");
        ret.appendChild(e);
        e.setTextContent(limit);
        return ret;
    }

    @Test
    public void testPerformEntityResolution() throws Exception {
        XmlConverter converter = new XmlConverter();
        converter.getDocumentBuilderFactory().setNamespaceAware(true);
        Document testRequestMessage = converter.toDOMDocument(testRequestMessageInputStream);

        Node entityContainerNode = testRequestMessage.getElementsByTagNameNS(EntityResolutionNamespaceContext.ER_EXT_NAMESPACE, "EntityContainer").item(0);
        assertNotNull(entityContainerNode);

        Document resultDocument = entityResolutionMessageHandler.performEntityResolution(entityContainerNode, null, null);

        resultDocument.normalizeDocument();
        // LOG.info(converter.toString(resultDocument));
        XPath xp = XPathFactory.newInstance().newXPath();
        xp.setNamespaceContext(new EntityResolutionNamespaceContext());
        NodeList entityNodes = (NodeList) xp.evaluate("//merge-result:EntityContainer/merge-result-ext:Entity", resultDocument, XPathConstants.NODESET);
        int inputEntityNodeCount = 3;
        assertEquals(inputEntityNodeCount, entityNodes.getLength());
        entityNodes = (NodeList) xp.evaluate("//merge-result-ext:MergedRecord", resultDocument, XPathConstants.NODESET);
        assertEquals(2, entityNodes.getLength());
        entityNodes = (NodeList) xp.evaluate("//merge-result-ext:OriginalRecordReference", resultDocument, XPathConstants.NODESET);
        assertEquals(inputEntityNodeCount, entityNodes.getLength());
        for (int i = 0; i < entityNodes.getLength(); i++) {
            Element e = (Element) entityNodes.item(i);
            String entityIdRef = e.getAttributeNS(EntityResolutionNamespaceContext.STRUCTURES_NAMESPACE, "ref");
            assertNotNull(entityIdRef);
            assertNotNull(xp.evaluate("//merge-result-ext:Entity[@s:id='" + entityIdRef + "']", resultDocument, XPathConstants.NODE));
        }

        String recordLimitExceeded = xp.evaluate("/merge-result:EntityMergeResultMessage/merge-result:RecordLimitExceeded", resultDocument);
        assertEquals("false", recordLimitExceeded);

    }

    @Test
    public void testAttributeParametersSetup() throws Exception {
        Set<AttributeParametersXpathSupport> attributeParameters = entityResolutionMessageHandler.getAttributeParameters(null);
        assertEquals(2, attributeParameters.size());
        boolean givenNameFound = false;
        boolean surNameFound = false;
        for (AttributeParameters ap : attributeParameters) {
            if ("ext:PersonSearchResult/ext:Person/nc:PersonName/nc:PersonGivenName".equals(ap.getAttributeName())) {
                givenNameFound = true;
                assertEquals(0.8, ap.getThreshold());
            } else if ("ext:PersonSearchResult/ext:Person/nc:PersonName/nc:PersonSurName".equals(ap.getAttributeName())) {
                surNameFound = true;
                assertEquals(0.5, ap.getThreshold());
                assertEquals(1, ap.getSortOrder().getSortOrderRank());
                assertEquals("ascending", ap.getSortOrder().getSortOrder());
            }
            assertEquals("com.wcohen.ss.Jaro", ap.getAlgorithmClassName());
            assertFalse(ap.isDeterminative());
        }
        assertTrue(givenNameFound && surNameFound);
    }

    @Test
    public void testCreateRecords() throws Exception {
        XmlConverter converter = new XmlConverter();
        converter.getDocumentBuilderFactory().setNamespaceAware(true);
        Document testRequestMessage = converter.toDOMDocument(testRequestMessageInputStream);
        assertNotNull(testRequestMessage);

        Node entityContainerNode = testRequestMessage.getElementsByTagNameNS(EntityResolutionNamespaceContext.ER_EXT_NAMESPACE, "EntityContainer").item(0);
        assertNotNull(entityContainerNode);

        List<ExternallyIdentifiableRecord> records = EntityResolutionConversionUtils.convertRecordWrappers(entityResolutionMessageHandler.createRecordsFromRequestMessage(entityContainerNode, null));

        assertNotNull(records);
        assertEquals(3, records.size());
        boolean mickeyFound = false;
        boolean minnieFound = false;
        boolean minnyFound = false;
        for (ExternallyIdentifiableRecord record : records) {
            Attribute a = record.getAttribute("ext:PersonSearchResult/ext:Person/nc:PersonName/nc:PersonGivenName");
            assertNotNull(a);
            assertEquals(1, a.getValuesCount());
            String value = a.iterator().next();
            if ("Mickey".equals(value)) {
                mickeyFound = true;
            } else if ("Minnie".equals(value)) {
                minnieFound = true;
            } else if ("Minny".equals(value)) {
                minnyFound = true;
            }
        }
        assertTrue(mickeyFound);
        assertTrue(minnieFound);
        assertTrue(minnyFound);
    }

    @Test
    public void testCreateLargeRecordset() throws Exception {
        XPath xp = XPathFactory.newInstance().newXPath();
        xp.setNamespaceContext(new EntityResolutionNamespaceContext());
        XmlConverter converter = new XmlConverter();
        converter.getDocumentBuilderFactory().setNamespaceAware(true);
        Document testRequestMessage = converter.toDOMDocument(testRequestMessageInputStream);
        Element entityContainerElement = (Element) xp.evaluate("/merge:EntityMergeRequestMessage/merge:MergeParameters/er-ext:EntityContainer", testRequestMessage, XPathConstants.NODE);
        assertNotNull(entityContainerElement);
        Element entityElement = (Element) xp.evaluate("er-ext:Entity[1]", entityContainerElement, XPathConstants.NODE);
        assertNotNull(entityElement);
        int entityCount = ((NodeList) xp.evaluate("er-ext:Entity", entityContainerElement, XPathConstants.NODESET)).getLength();
        int expectedInitialEntityCount = 3;
        assertEquals(expectedInitialEntityCount, entityCount);
        int recordIncrement = 500;
        for (int i = 0; i < recordIncrement; i++) {
            Element newEntityElement = (Element) entityElement.cloneNode(true);
            entityContainerElement.appendChild(newEntityElement);
        }
        entityCount = ((NodeList) xp.evaluate("er-ext:Entity", entityContainerElement, XPathConstants.NODESET)).getLength();
        assertEquals(expectedInitialEntityCount + recordIncrement, entityCount);

        Node entityContainerNode = testRequestMessage.getElementsByTagNameNS(EntityResolutionNamespaceContext.ER_EXT_NAMESPACE, "EntityContainer").item(0);
        assertNotNull(entityContainerNode);

        List<RecordWrapper> records = entityResolutionMessageHandler.createRecordsFromRequestMessage(entityContainerNode, null);
        assertEquals(expectedInitialEntityCount + recordIncrement, records.size());
    }
}
