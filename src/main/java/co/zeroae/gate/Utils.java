package co.zeroae.gate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import gate.*;
import gate.corpora.*;
import gate.corpora.export.GATEJsonExporter;
import gate.corpora.export.GateXMLExporter;
import gate.creole.Plugin;
import gate.creole.ResourceInstantiationException;
import gate.util.GateException;
import org.codehaus.httpcache4j.util.Hex;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class Utils {

    static final ObjectMapper objectMapper = new ObjectMapper();

    static final Map<String, DocumentExporter> exporters = loadExporters();

    static String asJson(Map<String, Object> obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException jsonProcessingException) {
            throw new RuntimeException(jsonProcessingException);
        }
    }

    static String ensureValidRequestContentType(String contentType) throws GateException {
        final MimeType mimeType = DocumentFormat.getMimeTypeForString(
                contentType.equals("application/json") ? "text/json" : contentType);
        if (mimeType == null) {
            throw new GateException(
                    "Unsupported MIME type " + contentType + " valid options are "
                            + Arrays.toString(DocumentFormat.getSupportedMimeTypes()
                            .stream()
                            .map((type) -> type.equals("text/json") ? "application/json" : type)
                            .sorted()
                            .toArray())
            );
        }
        return mimeType.toString();
    }

    static String ensureValidResponseType(String acceptHeader) throws GateException {
        for (String mimeType : acceptHeader.split(",")) {
            if (exporters.containsKey(mimeType.trim()))
                return mimeType.trim();
            else if (exporters.containsKey(mimeType.split(";")[0].trim()))
                return mimeType.split(";")[0].trim();
        }
        throw new GateException("Unsupported MIME response type " + acceptHeader + ", valid options are " +
                Arrays.toString(exporters.keySet().stream().sorted().toArray()));
    }

    /**
     * @param gateXMLReader a Reader with GateXML content.
     * @return The parsed Document.
     * @throws ResourceInstantiationException if the Factory fails to create an empty Document.
     * @throws XMLStreamException             if the reader has invalid XML content.
     */
    static Document xmlToDocument(Reader gateXMLReader) throws ResourceInstantiationException, XMLStreamException {
        final Document doc = Factory.newDocument("");
        XMLStreamReader reader;
        reader = XMLInputFactory.newFactory().createXMLStreamReader(gateXMLReader);
        do {
            reader.next();
        } while (reader.getEventType() != XMLStreamReader.START_ELEMENT);
        gate.corpora.DocumentStaxUtils.readGateXmlDocument(reader, doc);
        return doc;
    }

    static void loadDocumentFormats() {
        try {
            final Set<Class<? extends gate.Resource>> classes = new HashSet<>();
            classes.add(CochraneTextDocumentFormat.class);
            classes.add(DataSiftFormat.class);
            classes.add(FastInfosetDocumentFormat.class);
            classes.add(GATEJSONFormat.class);
            classes.add(JSONTweetFormat.class);
            classes.add(MediaWikiDocumentFormat.class);
            classes.add(PubmedTextDocumentFormat.class);
            for (Class<? extends gate.Resource> clazz : classes) {
                Gate.getCreoleRegister().registerPlugin(new UniqueHashComponent(clazz));
            }
        } catch (GateException | MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Loads all exporters that we support
     *
     * @return an UnmodifiableMap of the supported exporters.
     */
    static Map<String, DocumentExporter> loadExporters() {
        final GATEJsonExporter gateJsonExporter = new GATEJsonExporter();
        final GateXMLExporter gateXMLExporter = new GateXMLExporter();
        final FastInfosetExporter fastInfosetExporter = new FastInfosetExporter();

        final AnnotationSetExporter.GateXML annotationSetXMLExporter = new AnnotationSetExporter.GateXML();
        final AnnotationSetExporter.GATEFastInfoset annotationSetFastInfosetExporter = new AnnotationSetExporter.GATEFastInfoset();

        final Map<String, DocumentExporter> rv = new HashMap<>();
        rv.put("application/gate+xml", gateXMLExporter);
        rv.put("application/gate+json", gateJsonExporter);
        rv.put(gateJsonExporter.getMimeType(), gateJsonExporter);
        rv.put(fastInfosetExporter.getMimeType(), fastInfosetExporter);

        rv.put("application/gate+xml; includeText=no", annotationSetXMLExporter);
        rv.put("application/fastinfoset; includeText=no", annotationSetFastInfosetExporter);
        return Collections.unmodifiableMap(rv);
    }

    static String computeMessageDigest(String message) {
        try {
            final String rv;
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(message.getBytes());
            rv = Hex.encode(md.digest());
            return rv;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    interface GATESupplier<T> {
        T get() throws GateException;
    }

    /**
     * The Plugin.Component class has a bug when the baseUrl fails to resolve uniquely.
     * This fixes the bug by assigning the Class' hashCode instead of the Plugin level one.
     */
    private static class UniqueHashComponent extends Plugin.Component {
        final private int hashCode;

        public UniqueHashComponent(Class<? extends Resource> resourceClass) throws MalformedURLException {
            super(resourceClass);
            hashCode = resourceClass.hashCode();
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
