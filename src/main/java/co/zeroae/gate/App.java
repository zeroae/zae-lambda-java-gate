package co.zeroae.gate;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import gate.*;
import gate.creole.ExecutionException;
import gate.creole.ResourceInstantiationException;
import gate.util.GateException;
import gate.util.persistence.PersistenceManager;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.File;
import java.net.URL;
import java.util.HashMap;
import java.util.Objects;

/**
 * This class implements a GATE application using AWS Lambda.
 * It loads the application from the .gapp file defined by the GATE_APP_FILE environment variable.
 * For every lambda invocation, it runs the application and outputs the result in GateXML format.
 */
public class App implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    static {
        try {
            Gate.init();
        } catch (GateException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Logger logger = LogManager.getLogger(App.class);
    private static final CorpusController application = loadApplication();

    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, final Context context) {
        final APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
                .withHeaders(new HashMap<>());
        response.getHeaders().put("Content-Type", "text/plain");

        if (!input.getHeaders().get("Content-Type").equalsIgnoreCase("text/plain")) {
            return response.withBody("We only support text/plain input.").withStatusCode(400);
        }

        try {
            final Document doc = Factory.newDocument(input.getBody());
            final Corpus corpus = application.getCorpus();
            corpus.add(doc);
            try {
                application.execute();
                response.getHeaders().put("Content-Type", "application/xml");
                return response.withBody(doc.toXml()).withStatusCode(200);
            } catch (ExecutionException e) {
                logger.error(e);
                return response.withBody(e.getMessage()).withStatusCode(500);
            } finally {
                corpus.clear();
                Factory.deleteResource(doc);
            }
        } catch (ResourceInstantiationException e) {
            logger.warn(e);
            return response.withBody(e.getMessage()).withStatusCode(400);
        }
    }

    private static CorpusController loadApplication() {
        try {
            final String gappResourcePah = System.getenv("GATE_APP_NAME") + "/application.xgapp";
            final URL gappUrl = App.class.getClassLoader().getResource(gappResourcePah);
            final File gappFile = new File(Objects.requireNonNull(gappUrl).getFile());
            final CorpusController rv =
                    (CorpusController) PersistenceManager.loadObjectFromFile(gappFile);
            final Corpus corpus = Factory.newCorpus("Lambda Corpus");
            rv.setCorpus(corpus);
            return rv;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
