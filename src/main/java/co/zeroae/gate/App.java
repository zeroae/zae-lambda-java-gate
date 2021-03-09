package co.zeroae.gate;

import co.zeroae.gate.b64.Handler;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import com.amazonaws.util.Base64;
import com.amazonaws.xray.AWSXRay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import gate.*;
import gate.corpora.DocumentImpl;
import gate.util.GateException;
import gate.util.persistence.PersistenceManager;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLStreamHandler;
import java.util.*;
import java.util.function.Supplier;

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
    private static final String DIGEST_SALT = UUID.randomUUID().toString();

    private static final Logger logger = LogManager.getLogger(App.class);
    private static final CorpusController application = AWSXRay.createSegment(
            "Gate Load", App::loadApplication);
    private static final AppMetadata metadata = loadMetadata();

    private static final Map<String, DocumentExporter> exporters = AWSXRay.createSegment(
            "Gate Exporters", Utils::loadExporters
    );

    private static final DocumentLRUCache cache = AWSXRay.createSegment("Cache Init",
            () -> new DocumentLRUCache(App.CACHE_DIR, App.CACHE_DIR_USAGE));

    private static final URLStreamHandler b64Handler = new Handler();

    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, final Context context) {
        final String path = input.getPath();
        if (path.matches("^/([^/]*)/?$"))
            return handleExecute(input, context);
        else if (path.matches("^/([^/]*)/metadata/?$"))
            return handleMetadata(input, context);
        else
            throw new RuntimeException("How did you get here?");
    }

    public APIGatewayProxyResponseEvent handleMetadata(APIGatewayProxyRequestEvent input, final Context context) {
        final APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
                .withHeaders(new HashMap<>());
        response.getHeaders().put("Content-Type", "application/json");
        try {
            metadata.name = input.getPath().split("/")[1];
            response.withBody(new ObjectMapper().writeValueAsString(metadata)).withStatusCode(200);
        } catch (JsonProcessingException e) {
            // This is really bad... let it go through
            throw new RuntimeException(e);
        }
        return response;
    }

    public APIGatewayProxyResponseEvent handleExecute(APIGatewayProxyRequestEvent input, final Context context) {
        final APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
                    .withHeaders(new HashMap<>());
        final Map<String, String> queryStringParams = Optional.ofNullable(input.getQueryStringParameters()).orElse(new HashMap<>());
        final Map<String, List<String>> mQueryStringParams = Optional.ofNullable(input.getMultiValueQueryStringParameters()).orElse(new HashMap<>());
        try {
            final String acceptHeader = input.getHeaders().getOrDefault("Accept", "application/json");
            final String responseType = ((Supplier<String>) () -> {
                for (String mimeType : acceptHeader.split(",")) {
                    if (exporters.containsKey(mimeType.trim()))
                        return mimeType.trim();
                    else if (exporters.containsKey(mimeType.split(";")[0].trim()))
                        return mimeType.split(";")[0].trim();
                }
                return null;
            }).get();
            if (responseType != null)
                response.getHeaders().put("Content-Type", responseType.split(";")[0].trim());

            final DocumentExporter exporter = exporters.get(responseType);
            if (exporter == null)
                throw new IOException("Unsupported response content type.");


            final FeatureMap featureMap = Factory.newFeatureMap();
            final Integer nextAnnotationId = Integer.parseInt(queryStringParams.getOrDefault("nextAnnotationId", "0"));
            final String contentType = input.getHeaders().getOrDefault("Content-Type", "text/plain");
            final String contentDigest = AWSXRay.createSubsegment("Message Digest",() -> {
                String rv = Utils.computeMessageDigest(contentType + input.getBody() + nextAnnotationId + DIGEST_SALT);
                AWSXRay.getCurrentSubsegment().putMetadata("SHA256", rv);
                return rv;
            });
            featureMap.put("nextAnnotationId", nextAnnotationId);
            putRequestBody(featureMap, contentType, contentDigest, input.getBody(), input.getIsBase64Encoded());

            response.getHeaders().put("x-zae-gate-cache", "HIT");
            final Document doc = cache.computeIfNull(contentDigest, () -> {
                response.getHeaders().put("x-zae-gate-cache", "MISS");
                return execute(featureMap);
            });

            AWSXRay.beginSubsegment("Gate Export");
            AWSXRay.getCurrentSubsegment().putMetadata("Content-Type", response.getHeaders().get("Content-Type"));
            final List<String> annotationSelector = mQueryStringParams.get("annotations");

            try {
                return export(exporter, doc, annotationSelector, response).withStatusCode(200);
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

    private void putRequestBody(FeatureMap featureMap, String mimeType, String contentDigest, String content, boolean isBase64Encoded) throws MalformedURLException {
        featureMap.put(Document.DOCUMENT_MIME_TYPE_PARAMETER_NAME, mimeType);
        if (!isBase64Encoded)
            featureMap.put(Document.DOCUMENT_STRING_CONTENT_PARAMETER_NAME, content);
        else {
            // GATE FastInfosetFormat can not handle binary in the string content.
            Handler.paths.put(contentDigest, content);
            featureMap.put(
                    Document.DOCUMENT_URL_PARAMETER_NAME,
                    new URL("b64",
                            mimeType != null ? Base64.encodeAsString(mimeType.getBytes()) : null,
                            64,
                            contentDigest,
                            b64Handler));
        }
    }

    private Document execute(FeatureMap docFeatureMap) throws GateException {
        AWSXRay.beginSubsegment("Gate Execute");
        try {
            final DocumentImpl rvImpl;

            // Note: The DocumentImpl API does not conform to JavaBeans for the nextAnnotationId method.
            //       Paragraphs may be annotated right away, so we need to handle that issue.
            final int nextAnnotationId = (Integer)docFeatureMap.get("nextAnnotationId");
            docFeatureMap.remove("nextAnnotationId");
            rvImpl = (DocumentImpl) Factory.createResource("gate.corpora.DocumentImpl", docFeatureMap);
            rvImpl.setNextAnnotationId(Math.max(nextAnnotationId, rvImpl.getNextAnnotationId()));

            application.getCorpus().add(rvImpl);
            application.execute();
            return rvImpl;
        } catch (GateException e) {
            AWSXRay.getCurrentSubsegment().addException(e);
            throw e;
        } finally {
            application.getCorpus().clear();
            AWSXRay.endSubsegment();
        }
    }

    /**
     * @param exporter The document exporter
     * @param doc an instance of gate.Document
     * @param annotationSelector the List of AnnotationTypes to return
     * @param response The response where we put the exported Document as body
     * @return the modified response
     */
    private APIGatewayProxyResponseEvent export(
            DocumentExporter exporter,
            Document doc,
            List<String> annotationSelector,
            APIGatewayProxyResponseEvent response
    ) throws IOException {
        final FeatureMap exportOptions = Factory.newFeatureMap();

        // Include the selected annotations in the doc features.
        if (annotationSelector != null)
            doc.getFeatures().put("gate.cloud.annotationSelectors", annotationSelector);

        // Take *all* annotation types and filter based on AnnotationSelector
        final AnnotationSet defaultAnnots = doc.getAnnotations();

        // This if for GateJSONExporter (but should be for all exporters...)
        final Set<String> includeTypes = new HashSet<>(defaultAnnots.getAllTypes());
        if (annotationSelector != null)
            includeTypes.removeIf((type) -> !annotationSelector.contains(":" + type));
        exportOptions.put("annotationTypes", includeTypes);

        // This is a hack for FastInfoSet and XML *only*, but we are going to do for all modes...
        defaultAnnots.removeIf((annotation) -> !includeTypes.contains(annotation.getType()));

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            exporter.export(doc, baos, exportOptions);
        } catch (IOException e) {
            AWSXRay.getCurrentSubsegment().addException(e);
            throw e;
        }

        // If we add a second type, then we should create a "Set" at the Utils level and test against it.
        if (exporter.getMimeType().startsWith("application/fastinfoset")) {
            response.withIsBase64Encoded(true).setBody(Base64.encodeAsString(baos.toByteArray()));
        } else {
            response.setBody(baos.toString());
        }
        return response;
    }

    private static AppMetadata loadMetadata() {
        final AppMetadata rv = new AppMetadata();
        // TODO: Load metadata/metadata.xml if it exists, and set as default values
        rv.name = null;
        rv.costPerRequest = Integer.parseInt(System.getenv().getOrDefault("GATE_APP_COST_PER_REQUEST", "0"));
        rv.dailyQuota = Integer.parseUnsignedInt(System.getenv().getOrDefault("GATE_APP_DAILY_QUOTA", "0"));
        rv.defaultAnnotations = System.getenv("GATE_APP_DEFAULT_ANNOTATIONS");
        rv.additionalAnnotations = System.getenv("GATE_APP_ADDITIONAL_ANNOTATIONS");
        return rv;
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
}
