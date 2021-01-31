package co.zeroae.gate;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import gate.*;
import gate.corpora.export.GATEJsonExporter;
import gate.creole.ExecutionException;
import gate.creole.ResourceInstantiationException;
import gate.util.GateException;
import gate.util.persistence.PersistenceManager;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final GATEJsonExporter gateJsonExporter = new GATEJsonExporter();

    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, final Context context) {
        final String responseType = input.getHeaders().getOrDefault("Accept", "application/xml");
        final APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
                .withHeaders(new HashMap<>());
        response.getHeaders().put("Content-Type", "text/plain");

        try {
            final Document doc = Factory.newDocument(input.getBody());
            final Corpus corpus = application.getCorpus();
            corpus.add(doc);
            try {
                application.execute();
                response.getHeaders().put("Content-Type", responseType);
                return response.withBody(encode(doc, responseType)).withStatusCode(200);
            } finally {
                corpus.clear();
                Factory.deleteResource(doc);
            }
        } catch (ExecutionException e) {
            logger.error(e);
            return response.withBody(e.getMessage()).withStatusCode(400);
        } catch (IOException e) {
            logger.error(e);
            return response.withBody(e.getMessage()).withStatusCode(406);
        } catch (ResourceInstantiationException e) {
            logger.warn(e);
            return response.withBody(e.getMessage()).withStatusCode(400);
        }
    }

    /**
     * @param doc an instance of gate.Document
     * @param responseType One of the supported response types
     */
    private String encode(Document doc, String responseType) throws IOException {
        final FeatureMap exportOptions = Factory.newFeatureMap();

        // Take *all* annotation types.
        final AnnotationSet defaultAnnots = doc.getAnnotations();
        final HashSet<String> annotationTypes = new HashSet<>();
        for (Annotation annotation: defaultAnnots.inDocumentOrder()) {
            annotationTypes.add(annotation.getType());
        }
        exportOptions.put("annotationTypes", annotationTypes);

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (responseType.equals("application/json")) {
            gateJsonExporter.setAnnotationTypes(doc.getAnnotationSetNames());
            gateJsonExporter.export(doc, baos, exportOptions);
            return baos.toString();
        }
        else {
            return doc.toXml();
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
