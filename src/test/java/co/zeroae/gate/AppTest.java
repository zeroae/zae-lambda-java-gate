package co.zeroae.gate;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.util.Base64;
import com.amazonaws.util.IOUtils;
import com.amazonaws.util.StringInputStream;
import com.amazonaws.xray.AWSXRay;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.sun.xml.fastinfoset.stax.StAXDocumentParser;
import gate.Document;
import gate.DocumentFormat;
import org.apache.commons.codec.binary.Base64InputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Random;
import java.util.UUID;

import static com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable;
import static org.junit.Assert.*;

public class AppTest {

    @BeforeClass
    public static void setUpClass() throws Exception {
        app = withEnvironmentVariable("GATE_APP_NAME", "annie")
                .execute(App::new);
    }

    private static App app = null;
    private static TestContext context = new TestContext();

    @Before
    public void setUp() {
        AWSXRay.beginSegment("Test");
        input_headers = new HashMap<>();
        input_headers.put("Content-Type", "text/plain");
        input_headers.put("Accept", "application/gate+xml");
        input = new APIGatewayProxyRequestEvent()
                .withRequestContext(new APIGatewayProxyRequestEvent.ProxyRequestContext().withRequestId(UUID.randomUUID().toString()))
                .withHttpMethod("POST")
                .withHeaders(input_headers)
                .withBody("This is the default test message. I am an APIGatewayProxyRequestEvent and I love Wanda Vision.")
                .withIsBase64Encoded(false);

    }

    @After
    public void tearDown() {
        AWSXRay.endSegment();
    }
    private APIGatewayProxyRequestEvent input = null;
    private HashMap<String, String> input_headers = null;

    @Test
    public void successfulResponse() {
        // Invoke the App
        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        // Assert Results
        assertEquals(200, result.getStatusCode().intValue());
    }

    @Test
    public void testGateXMLToDocument() throws Exception {
        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);

        assertEquals("application/gate+xml", result.getHeaders().get("Content-Type"));
        final String resultBody = result.getBody();
        assertNotNull(resultBody);

        Document doc = Utils.xmlToDocument(new StringReader(resultBody));
        assertEquals(input.getBody(), doc.getContent().toString());
    }

    @Test
    public void testNextAnnotationId() {
        final int nextAnnotationId = 1000 + new Random().nextInt(1000);
        input.withQueryStringParameters(new HashMap<>())
                .getQueryStringParameters()
                .put("nextAnnotationId", String.valueOf(nextAnnotationId));

        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);

        assertEquals("application/gate+xml", result.getHeaders().get("Content-Type"));
        final String resultBody = result.getBody();
        assertNotNull(resultBody);
        assertTrue(resultBody.contains("<Annotation Id=\"" + (nextAnnotationId+1) + "\""));
    }

    @Test
    public void testMissingContentType() {
        input_headers.remove("Content-Type", "text/plain");
        final TestContext context = new TestContext();
        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        assertEquals(200, result.getStatusCode().intValue());
    }

    @Test
    public void testGateJSONResponse() throws Exception {
        for (String responseType: new String[]{"application/gate+json", "application/json"}) {
            input_headers.put("Accept", responseType);

            final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
            assertEquals(200, result.getStatusCode().intValue());

            // Ensure we get back application/gate+json back
            assertEquals(responseType, result.getHeaders().get("Content-Type"));
            final JsonFactory factory = new JsonFactory();
            final JsonParser parser = factory.createParser(result.getBody());
            while (!parser.isClosed()) {
                parser.nextToken();
            }
        }
    }

    @Test
    public void testFastInfosetResponse() {
        input_headers.put("Accept", "application/fastinfoset");

        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        assertEquals(200, result.getStatusCode().intValue());

        // Ensure we get back application/fastinfoset back
        assertEquals("application/fastinfoset", result.getHeaders().get("Content-Type"));
        assertTrue(result.getIsBase64Encoded());
    }

    @Test
    public void testFastInfosetResponseCloudClientParsing() throws UnsupportedEncodingException, XMLStreamException {
        input_headers.put("Accept", "application/fastinfoset; includeText=no");

        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        assertEquals(200, result.getStatusCode().intValue());

        // Ensure we get back application/fastinfoset back
        assertEquals("application/fastinfoset", result.getHeaders().get("Content-Type"));
        assertTrue(result.getIsBase64Encoded());

        // parse the response

        XMLStreamReader xsr = new StAXDocumentParser(new Base64InputStream(new StringInputStream(result.getBody())));
        xsr.nextTag(); // should be GateDocument
        xsr.require(XMLStreamConstants.START_ELEMENT, null, "GateDocument");
        xsr.nextTag(); // should be AnnotationSet ?
        xsr.require(XMLStreamConstants.START_ELEMENT, null, "AnnotationSet");
    }

    @Test
    public void testCache() {
        input.withBody(input.getBody() + new Random().nextInt());
        // Invoke the App
        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        assertEquals(200, result.getStatusCode().intValue());
        assertEquals("MISS", result.getHeaders().get("x-zae-gate-cache"));

        final APIGatewayProxyResponseEvent cachedResult = app.handleRequest(input, context);
        assertEquals(200, cachedResult.getStatusCode().intValue());
        assertEquals("HIT", cachedResult.getHeaders().get("x-zae-gate-cache"));
    }

    @Test
    public void testInputTypes() {
        String[] types = {
                "application/fastinfoset",
                "text/html",
                "text/json",
                "text/plain",
                "text/xml",
                "text/x-cochrane",
                "text/x-mediawiki",
                "text/x-json-datasift",
                "text/x-json-twitter",
        };
        for (String type: types) assertNotNull(DocumentFormat.getMimeTypeForString(type));
    }

    @Test
    public void testGateJsonInput() {
        input.withBody("{\"text\":\"" + input.getBody() + "\"}");
        input.getHeaders().put("Content-Type", "text/json");

        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        assertEquals(200,result.getStatusCode().intValue());
    }

    @Test
    public void tesTweetJsonInput() {
        input.withBody("{\"full_text\":\"" + input.getBody() + "\"}");
        input.getHeaders().put("Content-Type", "text/x-json-twitter");
        input.getHeaders().put("Accept", "application/gate+json");

        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        assertEquals(200, result.getStatusCode().intValue());
        assertFalse(result.getBody().contains("full_text"));
    }

    @Test
    public void testMediaWikiInput() throws IOException {
        final String body = IOUtils.toString(getClass().getResourceAsStream("mediawiki.txt"));
        input.withBody(body);
        input.getHeaders().put("Content-Type", "text/x-mediawiki");
        input.getHeaders().put("Accept", "application/gate+json");

        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        assertEquals(200, result.getStatusCode().intValue());
        assertFalse(result.getBody().contains("{{Short Description|"));
    }

    @Test
    public void testFastInfosetInput() throws IOException {
        final byte[] body = IOUtils.toByteArray(getClass().getResourceAsStream("example.finf"));
        input.withIsBase64Encoded(true)
                .withBody(Base64.encodeAsString(body))
                .getHeaders().put("Content-Type", "application/fastinfoset");

        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        assertEquals(200, result.getStatusCode().intValue());
        assertEquals("MISS", result.getHeaders().get("x-zae-gate-cache"));

        final APIGatewayProxyResponseEvent cachedResult = app.handleRequest(input, context);
        assertEquals(200, cachedResult.getStatusCode().intValue());
        assertEquals("HIT", cachedResult.getHeaders().get("x-zae-gate-cache"));
    }

    @Test
    public void testCacheCoherenceWithContentType() {
        final int cacheBust= new Random().nextInt();
        final String gateJsonBody =  "{\"text\":\"" + input.getBody() + cacheBust + "\"}";

        // We are going to make a "mistake" and send gateJsonBody as text/x-json-twitter.
        input.getHeaders().put("Accept", "application/gate+json");
        input.getHeaders().put("Content-Type", "text/x-json-twitter");
        input.withBody(gateJsonBody);

        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        assertEquals("MISS", result.getHeaders().get("x-zae-gate-cache"));

        // Now we fix the mistake, we must *miss* o.w. we are getting the *wrong* entities!
        input.getHeaders().put("Content-Type", "text/json");

        final APIGatewayProxyResponseEvent wikiResult = app.handleRequest(input, context);
        assertEquals("MISS", wikiResult.getHeaders().get("x-zae-gate-cache"));
    }
}
