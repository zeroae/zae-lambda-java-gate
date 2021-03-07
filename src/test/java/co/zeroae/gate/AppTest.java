package co.zeroae.gate;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.util.IOUtils;
import com.amazonaws.xray.AWSXRay;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import gate.Document;
import gate.DocumentFormat;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Random;

import static com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable;
import static org.junit.Assert.*;

public class AppTest {

    @BeforeClass
    public static void setUpClass() throws Exception {
        AWSXRay.beginSegment("setUpClass");
        app = withEnvironmentVariable("GATE_APP_NAME", "annie")
                .execute(App::new);
        AWSXRay.endSegment();
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
                .withHttpMethod("POST")
                .withHeaders(input_headers)
                .withBody("This is the default test message. I am an APIGatewayProxyRequestEvent and I love Wanda Vision.");
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
        assertEquals(result.getStatusCode().intValue(), 200);
    }

    @Test
    public void testGateXMLToDocument() throws Exception {
        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);

        assertEquals(result.getHeaders().get("Content-Type"), "application/gate+xml");
        final String resultBody = result.getBody();
        assertNotNull(resultBody);

        Document doc = Utils.xmlToDocument(new StringReader(resultBody));
        assertEquals(doc.getContent().toString(), input.getBody());
    }

    @Test
    public void testMissingContentType() {
        input_headers.remove("Content-Type", "text/plain");
        final TestContext context = new TestContext();
        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        assertEquals(result.getStatusCode().intValue(), 200);
    }

    @Test
    public void testGateJSONResponse() throws Exception {
        input_headers.put("Accept", "application/gate+json");

        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        assertEquals(result.getStatusCode().intValue(), 200);

        // Ensure we get back application/gate+json back
        assertEquals(result.getHeaders().get("Content-Type"), "application/gate+json");
        final JsonFactory factory = new JsonFactory();
        final JsonParser parser = factory.createParser(result.getBody());
        while (!parser.isClosed()) {
            parser.nextToken();
        }
    }

    @Test
    public void testCache() {
        input.withBody(input.getBody() + new Random().nextInt());
        // Invoke the App
        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        assertEquals(result.getStatusCode().intValue(), 200);
        assertEquals("MISS", result.getHeaders().get("x-zae-gate-cache"));

        final APIGatewayProxyResponseEvent cachedResult = app.handleRequest(input, context);
        assertEquals(cachedResult.getStatusCode().intValue(), 200);
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
        assertEquals(result.getStatusCode().intValue(), 200);
    }

    @Test
    public void tesTweetJsonInput() {
        input.withBody("{\"full_text\":\"" + input.getBody() + "\"}");
        input.getHeaders().put("Content-Type", "text/x-json-twitter");
        input.getHeaders().put("Accept", "application/gate+json");

        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        assertEquals(result.getStatusCode().intValue(), 200);
        assertFalse(result.getBody().contains("full_text"));
    }

    @Test
    public void testMediaWikiInput() throws IOException {
        final String body = IOUtils.toString(getClass().getResourceAsStream("mediawiki.txt"));
        input.withBody(body);
        input.getHeaders().put("Content-Type", "text/x-mediawiki");
        input.getHeaders().put("Accept", "application/gate+json");

        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        assertEquals(result.getStatusCode().intValue(), 200);
        assertFalse(result.getBody().contains("{{Short Description|"));
    }
}
