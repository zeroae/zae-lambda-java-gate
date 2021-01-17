package co.zeroae.gate;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import gate.*;
import gate.creole.ExecutionException;
import gate.creole.ResourceInstantiationException;
import gate.util.GateException;
import gate.util.persistence.PersistenceManager;

import java.io.File;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * This class implements a GATE application using AWS Lambda.
 * It loads the application from the .gapp file defined by the GATE_APP_FILE environment variable.
 * For every lambda invocation, it runs the application and outputs the result in GateXML format.
 */
public class App implements RequestHandler< Map<String, ?>, GatewayResponse> {
    static {
        try {
            Gate.init();
        } catch (GateException e) {
            throw new RuntimeException(e);
        }
    }

    private static final CorpusController application = loadApplication();

    public GatewayResponse handleRequest(final Map<String, ?> input, final Context context) {
        final Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/plain");

        @SuppressWarnings("unchecked")
        final Map<String, String> inputHeaders = (Map<String, String>) input.get("headers");
        if (!inputHeaders.get("Content-Type").equalsIgnoreCase("text/plain")) {
            return new GatewayResponse("We only support text/plain input.", headers, 400);
        }

        try {
            final Document doc = Factory.newDocument(input.get("body").toString());
            final Corpus corpus = application.getCorpus();
            corpus.add(doc);
            try {
                application.execute();
                headers.put("Content-Type", "application/xml");
                return new GatewayResponse(doc.toXml(), headers, 200);
            } catch (ExecutionException e) {
                context.getLogger().log(e.getMessage());
                return new GatewayResponse(e.getMessage(), headers, 500);
            } finally {
                corpus.clear();
                Factory.deleteResource(doc);
            }
        } catch (ResourceInstantiationException e) {
            context.getLogger().log(e.getMessage());
            return new GatewayResponse(e.getMessage(), headers, 400);
        }
    }

    private static CorpusController loadApplication() {
        try {
            final URL gappUrl = App.class.getResource("application.xgapp");
            final File gappFile = new File(gappUrl.getFile());
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
