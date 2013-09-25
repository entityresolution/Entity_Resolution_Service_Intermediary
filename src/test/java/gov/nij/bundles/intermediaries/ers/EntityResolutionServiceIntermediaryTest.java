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

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Resource;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.camel.EndpointInject;
import org.apache.camel.Exchange;
import org.apache.camel.Produce;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWithRouteBuilder;
import org.apache.camel.component.cxf.common.message.CxfConstants;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.impl.DefaultExchange;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.test.junit4.CamelSpringJUnit4ClassRunner;
import org.apache.camel.test.spring.UseAdviceWith;
import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.cxf.binding.soap.SoapHeader;
import org.apache.cxf.headers.Header;
import org.custommonkey.xmlunit.Diff;
import org.custommonkey.xmlunit.DifferenceListener;
import org.custommonkey.xmlunit.XMLUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * This is the test framework to test the Entity Resolution Service.
 * 
 */

@UseAdviceWith	// NOTE: this causes Camel contexts to not start up automatically
@RunWith(CamelSpringJUnit4ClassRunner.class)
@ContextConfiguration(locations={"classpath:META-INF/spring/camel-context.xml"}) 
public class EntityResolutionServiceIntermediaryTest {

	private static final Log log = LogFactory.getLog(EntityResolutionServiceIntermediaryTest.class);
	
	public static final String CXF_OPERATION_NAME = "Submit-Entity-Merge";
	public static final String CXF_OPERATION_NAMESPACE = "http://nij.gov/Services/WSDL/EntityResolutionService/1.0";

    @Resource
    private ModelCamelContext context;
    
    @Produce
    protected ProducerTemplate template;
    
    @EndpointInject(uri = "mock:EntityResolutionResponseEndpoint")
    protected MockEndpoint entityResolutionResponseMock;

	
	@Before
	public void setUp() throws Exception {
		//Tell XML Unit to ignore whitespace between elements and within elements
		XMLUnit.setIgnoreWhitespace(true);
		XMLUnit.setNormalizeWhitespace(true);
	}
	
    
    /**
     * Test the entity resolution service. 
     * 
     * @throws Exception
     */
    @Test
    @DirtiesContext
    public void testEntityResolution() throws Exception {
    
    	//Advise the person search results endpoint and replace it with a mock endpoint.
    	//We then will test this mock endpoint to see if it gets the proper payload.
    	context.getRouteDefinitions().get(0).adviceWith(context, new AdviceWithRouteBuilder() {
    	    @Override
    	    public void configure() throws Exception {
    	        // weave the vehicle search results in the route
    	        // and replace it with the following mock route path
    	        weaveByToString("To[EntityResolutionResponseEndpoint]").replace().to("mock:EntityResolutionResponseEndpoint");
    	        replaceFromWith("direct:entityResolutionRequestServiceEndpoint");
    	    }              
    	});

    	context.start();
    	
	    //We should get one message
    	entityResolutionResponseMock.expectedMessageCount(1);

    	//Create a new exchange
    	Exchange senderExchange = new DefaultExchange(context);
						
		Document doc = createDocument();
		List<SoapHeader> soapHeaders = new ArrayList<SoapHeader>();
		soapHeaders.add(makeSoapHeader(doc, "http://www.w3.org/2005/08/addressing", "MessageID", "12345"));
		soapHeaders.add(makeSoapHeader(doc, "http://www.w3.org/2005/08/addressing", "ReplyTo", "https://reply.to"));
		senderExchange.getIn().setHeader(Header.HEADER_LIST , soapHeaders);
		
 	    senderExchange.getIn().setHeader(CxfConstants.OPERATION_NAME, CXF_OPERATION_NAME);
	    senderExchange.getIn().setHeader(CxfConstants.OPERATION_NAMESPACE, CXF_OPERATION_NAMESPACE);
	        	
	    //Read the er search request file from the file system
	    File inputFile = new File("src/test/resources/xml/EntityMergeRequestMessageWithAttributeParameters.xml");
	    
	    //Set it as the message message body
	    senderExchange.getIn().setBody(inputFile);
	    
	    //Send the one-way exchange.  Using template.send will send an one way message
		Exchange returnExchange = template.send("direct:entityResolutionRequestServiceEndpoint", senderExchange);
		
		//Use getException to see if we received an exception
		if (returnExchange.getException() != null)
		{	
			throw new Exception(returnExchange.getException());
		}	
		
		//Sleep while a response is generated
		Thread.sleep(3000);
    			
		//Assert that the mock endpoint is satisfied
		entityResolutionResponseMock.assertIsSatisfied();
		
		//We should get one message
		entityResolutionResponseMock.expectedMessageCount(1);
		
		//Get the first exchange (the only one)
		Exchange ex = entityResolutionResponseMock.getExchanges().get(0);
		
		//Get the actual response
		String actualResponse = ex.getIn().getBody(String.class);
		log.info("Body recieved by Mock: " + actualResponse);
		
	    //Read the expected response into a string
		File expectedReponseFile = new File("src/test/resources/xml/EntityMergeResponseMessage.xml");
		String expectedResponseAsString = FileUtils.readFileToString(expectedReponseFile);
			
		//log.debug("Expected Response: " + expectedResponseAsString);
			
		//Use XML Unit to compare these files
		//We use a custom difference listener because the expected response will contain generated IDs
		//The generated IDs are random so we want to ignore them.
		DifferenceListener ignoreIdsDifferenceListener = new IgnoreIDsDifferenceListener();
		Diff myDiff = new Diff(expectedResponseAsString, actualResponse);
		myDiff.overrideDifferenceListener(ignoreIdsDifferenceListener);
		
		assertTrue("XML should be identical " + myDiff.toString(),
		               myDiff.identical());


    }
 
	private SoapHeader makeSoapHeader(Document doc, String namespace, String localName, String value) {
		Element messageId = doc.createElementNS(namespace, localName);
		messageId.setTextContent(value);
		SoapHeader soapHeader = new SoapHeader(new QName(namespace, localName), messageId);
		return soapHeader;
	}	
	
	private Document createDocument() throws Exception{

		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(true);
		Document doc = dbf.newDocumentBuilder().newDocument();

		return doc;
	}

}
