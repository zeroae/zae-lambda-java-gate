package co.zeroae.gate;

import co.zeroae.gate.b64.Handler;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import com.amazonaws.util.Base64;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Subsegment;

import gate.*;
import gate.creole.ResourceInstantiationException;
import gate.util.GateException;
import gate.util.persistence.PersistenceManager;

import com.jakewharton.disklrucache.DiskLruCache;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.codehaus.httpcache4j.util.Hex;

import javax.xml.stream.XMLStreamException;
import java.io.*;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * This class implements a GATE application using AWS Lambda.
 * It loads the application from the .gapp file defined by the GATE_APP_FILE environment variable.
 * For every lambda invocation, it runs the application and outputs the result in GateXML format.
 */
public class App implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    static {
        AWSXRay.createSegment("Gate Init", () -> {
            try {
                Gate.init();
                Utils.loadDocumentFormats();
            } catch (GateException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static final String GATE_APP_NAME = System.getenv("GATE_APP_NAME");
    private static final String CACHE_DIR = System.getenv().getOrDefault(
            "CACHE_DIR_PREFIX", "/tmp/lru/" + GATE_APP_NAME );
    private static final double CACHE_DIR_USAGE = .9;

    private static final Logger logger = LogManager.getLogger(App.class);
    private static final CorpusController application = AWSXRay.createSegment(
            "Gate Load", App::loadApplication);
    private static final Map<String, DocumentExporter> exporters = AWSXRay.createSegment(
            "Gate Exporters", Utils::loadExporters
    );

    private static final DiskLruCache cache = AWSXRay.createSegment(
            "Cache Init", App::initializeCache);

    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, final Context context) {
        final APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
                .withHeaders(new HashMap<>());
        try {
            final String responseType = input.getHeaders().get("Accept");
            final DocumentExporter exporter = exporters.get(responseType);
            if (exporter == null) {
                throw new IOException("Unsupported response content type.");
            } else {
                response.getHeaders().put("Content-Type", responseType);
            }

            final FeatureMap featureMap = Factory.newFeatureMap();
            final String bodyType = input.getHeaders().get("Content-Type");
            if (bodyType != null)
                featureMap.put(Document.DOCUMENT_MIME_TYPE_PARAMETER_NAME, bodyType);
            if (input.getIsBase64Encoded() != null && input.getIsBase64Encoded())
                featureMap.put(
                        Document.DOCUMENT_URL_PARAMETER_NAME,
                        new URL("b64", "localhost", 64, input.getBody(),
                                new Handler()));
            else
                featureMap.put(Document.DOCUMENT_STRING_CONTENT_PARAMETER_NAME, input.getBody());

            final String inputDigest = AWSXRay.createSubsegment("Message Digest",
                    (subsegment) -> {
                        String rv = computeMessageDigest(featureMap);
                        subsegment.putMetadata("SHA256", rv);
                        return rv;
                    });
            response.getHeaders().put("x-zae-gate-cache", "HIT");
            final Document doc = cacheComputeIfNull(
                    inputDigest,
                    () -> {
                        final Subsegment subsegment = AWSXRay.beginSubsegment("Gate Execute");
                        final Corpus corpus = application.getCorpus();
                        final Document rv = (Document)Factory.createResource(
                                "gate.corpora.DocumentImpl",
                                featureMap
                        );
                        response.getHeaders().put("x-zae-gate-cache", "MISS");
                        try {
                            corpus.add(rv);
                            application.execute();
                        } catch (GateException e) {
                            subsegment.addException(e);
                            throw e;
                        } finally {
                            corpus.clear();
                            AWSXRay.endSubsegment();
                        }
                        return rv;
                    }
            );
            AWSXRay.beginSubsegment("Gate Export");
            AWSXRay.getCurrentSubsegment().putMetadata("Content-Type", responseType);
            try {
                return export(exporter, doc, response).withStatusCode(200);
            } finally {
                Factory.deleteResource(doc);
                AWSXRay.endSubsegment();
            }
        } catch (GateException e) {
            logger.error(e);
            AWSXRay.getCurrentSubsegmentOptional().ifPresent((segment -> segment.addException(e)));
            response.getHeaders().put("Content-Type", "application/json");
            return response.withStatusCode(400).withBody(String.format(
                    "{\"message\":\"%s\"}", e.getMessage()));
        } catch (IOException e) {
            logger.error(e);
            AWSXRay.getCurrentSubsegmentOptional().ifPresent((segment -> segment.addException(e)));
            response.getHeaders().put("Content-Type", "application/json");
            return response.withStatusCode(406).withBody(String.format(
                    "{\"message\":\"%s\"}", e.getMessage()));
        }
    }

    private void cachePutDocument(String key, Document doc) {
        try {
            DiskLruCache.Editor editor = cache.edit(key);
            editor.set(0, doc.toXml());
            editor.commit();
        } catch (IOException e) {
            logger.warn(e);
            AWSXRay.getCurrentSubsegment().addException(e);
        }
    }

    private Document cacheComputeIfNull(String key, Utils.TextProcessor processor) throws GateException {
        try {
            final DiskLruCache.Snapshot snapshot = cache.get(key);
            if (snapshot == null) {
                final Document doc = processor.process();
                AWSXRay.createSubsegment("Cache Edit", () -> cachePutDocument(key, doc));
                return doc;
            } else {
                AWSXRay.beginSubsegment("Cache Read");
                try {
                    return Utils.xmlToDocument(new InputStreamReader(snapshot.getInputStream(0)));
                } catch (ResourceInstantiationException | XMLStreamException e) {
                    logger.warn(e);
                    AWSXRay.getCurrentSubsegment().addException(e);
                    cache.remove(key);
                    return processor.process();
                } finally {
                    AWSXRay.endSubsegment();
                }
            }
        } catch (IOException e) {
            logger.warn(e);
            AWSXRay.getCurrentSubsegment().addException(e);
            return processor.process();
        }
    }

    private String computeMessageDigest(FeatureMap featureMap) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final String mimeType = (String)featureMap.get(Document.DOCUMENT_MIME_TYPE_PARAMETER_NAME);
            final String bodyContent = (String)featureMap.get(Document.DOCUMENT_STRING_CONTENT_PARAMETER_NAME);
            final URL sourceUrl = (URL)featureMap.get(Document.DOCUMENT_URL_PARAMETER_NAME);
            if (mimeType != null)
                md.update(mimeType.getBytes());
            if (bodyContent != null)
                md.update(bodyContent.getBytes());
            if (sourceUrl != null)
                md.update(sourceUrl.toString().getBytes());
            return Hex.encode(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @param exporter The document exporter
     * @param doc an instance of gate.Document
     * @param response The response where we put the exported Document as body
     * @return the modified response
     */
    private APIGatewayProxyResponseEvent export(
            DocumentExporter exporter,
            Document doc,
            APIGatewayProxyResponseEvent response
    ) throws IOException {
        final FeatureMap exportOptions = Factory.newFeatureMap();

        // Take *all* annotation types.
        final AnnotationSet defaultAnnots = doc.getAnnotations();
        final HashSet<String> annotationTypes = new HashSet<>();
        for (Annotation annotation : defaultAnnots.inDocumentOrder()) {
            annotationTypes.add(annotation.getType());
        }
        exportOptions.put("annotationTypes", annotationTypes);

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            exporter.export(doc, baos, exportOptions);
        } catch (IOException e) {
            AWSXRay.getCurrentSubsegment().addException(e);
            throw e;
        }
        // If we add a second type, then we should create a "Set" at the Utils level and test against it.
        if (exporter.getMimeType().equals("application/fastinfoset")) {
            response.withIsBase64Encoded(true).setBody(Base64.encodeAsString(baos.toByteArray()));
        } else {
            response.setBody(baos.toString());
        }
        return response;
    }

    private static CorpusController loadApplication() {
        try {
            final String gappResourcePah = GATE_APP_NAME + "/application.xgapp";
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

    private static DiskLruCache initializeCache() {
        File cacheDir = new File(CACHE_DIR);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new RuntimeException("Unable to create cache directory '" + cacheDir.getName() + "'.");
        }
        for (File file: Objects.requireNonNull(cacheDir.listFiles())) file.delete();
        try {
            long usableSpace = (long) (cacheDir.getUsableSpace()*CACHE_DIR_USAGE);
            return DiskLruCache.open(cacheDir,
                    1,
                    1,
                    usableSpace);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
