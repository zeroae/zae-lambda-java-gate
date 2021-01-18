package co.zeroae.gate;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;

import static org.junit.Assert.*;

import static com.github.stefanbirkner.systemlambda.SystemLambda.*;

public class AppTest {

    @BeforeClass
    public static void setUpClass() throws Exception {
        app = withEnvironmentVariable("GATE_APP_NAME", "annie")
                .execute(App::new);
    }

    private static App app = null;

    @Test
    public void successfulResponse() throws Exception {
        // Create the Input
        final HashMap<String, String> input_headers = new HashMap<>();
        input_headers.put("Content-Type", "text/plain");
        APIGatewayProxyRequestEvent input = new APIGatewayProxyRequestEvent()
                .withHttpMethod("POST")
                .withHeaders(Collections.unmodifiableMap(input_headers))
                .withBody("This is a test. My name is LambdaTestFunction, and I just watched the T.V. show Wanda Vision.");

        // Context
        final TestContext context = new TestContext();

        // Invoke the App
        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);

        // Assert Results
        assertEquals(result.getStatusCode().intValue(), 200);
        assertEquals(result.getHeaders().get("Content-Type"), "application/xml");
        final String content = result.getBody();
        assertNotNull(content);
        assertTrue(content.contains("GateDocument version=\"3\""));
    }

    @Test
    public void testMissingContentType() throws Exception {
        APIGatewayProxyRequestEvent input = new APIGatewayProxyRequestEvent()
                .withHttpMethod("POST")
                .withHeaders(Collections.unmodifiableMap(new HashMap<>()))
                .withBody("I am still valid text...");
        final TestContext context = new TestContext();
        final APIGatewayProxyResponseEvent result = app.handleRequest(input, context);
        assertEquals(result.getStatusCode().intValue(), 200);
    }
}
