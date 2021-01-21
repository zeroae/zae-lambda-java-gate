package co.zeroae.gate;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import gate.Document;
import gate.Factory;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.HashMap;

import static com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
        input_headers = new HashMap<>();
        input = new APIGatewayProxyRequestEvent()
                .withHttpMethod("POST")
                .withHeaders(input_headers);
    }
    private APIGatewayProxyRequestEvent input = null;
    private HashMap<String, String> input_headers = null;

    @Test
    public void successfulResponse() {
        // Create the Input
        input_headers.put("Content-Type", "text/plain");
        input.withBody("This is a test. My name is LambdaTestFunction, and I just watched the T.V. show Wanda Vision.");

        // Invoke the App
        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);

        // Assert Results
        assertEquals(result.getStatusCode().intValue(), 200);
    }

    @Test
    public void testGateXMLToDocument() throws Exception {
        input_headers.put("Content-Type", "text/plain");
        final String content = "Today is Monday.";
        input.withBody(content);

        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);

        assertEquals(result.getHeaders().get("Content-Type"), "application/xml");
        final String resultBody = result.getBody();
        assertNotNull(resultBody);

        Document doc = Factory.newDocument("");
        XMLStreamReader reader = XMLInputFactory.newFactory().createXMLStreamReader(
                new StringReader(resultBody)
        );
        do {
            reader.next();
        } while(reader.getEventType() != XMLStreamReader.START_ELEMENT);
        gate.corpora.DocumentStaxUtils.readGateXmlDocument(reader, doc);
        assertEquals(doc.getContent().toString(), content);
    }

    @Test
    public void testMissingContentType() {
        input_headers.clear();
        input.withBody("I am still valid text...");
        final TestContext context = new TestContext();
        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        assertEquals(result.getStatusCode().intValue(), 200);
    }

    @Test
    public void testApplicationJsonResponse() throws Exception {
        input_headers.put("Accept", "application/json");
        input.withBody("Today is Monday.");

        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        assertEquals(result.getStatusCode().intValue(), 200);

        // Ensure we get back application/json back
        assertEquals(result.getHeaders().get("Content-Type"), "application/json");
        final JsonFactory factory = new JsonFactory();
        final JsonParser parser = factory.createParser(result.getBody());
        while (!parser.isClosed()) {
            parser.nextToken();
        }
    }
}
