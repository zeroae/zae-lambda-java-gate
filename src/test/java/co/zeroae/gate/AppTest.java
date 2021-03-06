package co.zeroae.gate;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.xray.AWSXRay;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import gate.Document;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;
import java.util.HashMap;

import static com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
        // Invoke the App
        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        assertEquals(result.getStatusCode().intValue(), 200);
        assertEquals(result.getHeaders().get("x-zae-gate-cache"), "MISS");

        final APIGatewayProxyResponseEvent cachedResult = app.handleRequest(input, context);
        assertEquals(cachedResult.getStatusCode().intValue(), 200);
        assertEquals(cachedResult.getHeaders().get("x-zae-gate-cache"), "HIT");
    }
}
