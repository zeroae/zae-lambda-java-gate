package co.zeroae.gate;

import com.sun.xml.fastinfoset.stax.StAXDocumentSerializer;
import gate.*;
import gate.corpora.*;
import gate.corpora.export.GATEJsonExporter;
import gate.corpora.export.GateXMLExporter;
import gate.creole.Plugin;
import gate.creole.ResourceInstantiationException;
import gate.util.GateException;
import org.codehaus.httpcache4j.util.Hex;

import javax.xml.stream.*;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static gate.corpora.DocumentStaxUtils.GATE_XML_VERSION;

public class Utils {

    interface TextProcessor {
        Document process() throws GateException;
    }

    /**
     *
     * @param gateXMLReader a Reader with GateXML content.
     * @return The parsed Document.
     * @throws ResourceInstantiationException if the Factory fails to create an empty Document.
     * @throws XMLStreamException if the reader has invalid XML content.
     */
    static Document xmlToDocument(Reader gateXMLReader) throws ResourceInstantiationException, XMLStreamException {
        final Document doc = Factory.newDocument("");
        XMLStreamReader reader;
        reader = XMLInputFactory.newFactory().createXMLStreamReader(gateXMLReader);
        do {
            reader.next();
        } while(reader.getEventType() != XMLStreamReader.START_ELEMENT);
        gate.corpora.DocumentStaxUtils.readGateXmlDocument(reader, doc);
        return doc;
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
            for (Class<? extends gate.Resource> clazz: classes) {
                Gate.getCreoleRegister().registerPlugin(new UniqueHashComponent(clazz));
            }
        } catch (GateException | MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    private static abstract class AnnotationSetExporter extends DocumentExporter {
        /**
         * Creates a new exporter instance for a given file type with default
         * extension.
         *
         * @param fileType         this is the human readable file type name that will
         *                         appear on the menu
         * @param defaultExtension the default file extension for this type
         * @param mimeType         the mimeType
         */
        public AnnotationSetExporter(String fileType, String defaultExtension, String mimeType) {
            super(fileType, defaultExtension, mimeType);
        }

        protected void export(Document doc, XMLStreamWriter xsw, FeatureMap options) throws XMLStreamException {
            final String namespaceURI = "";
            xsw.writeStartDocument("1.0");
            xsw.setDefaultNamespace(namespaceURI);
            xsw.writeStartElement(namespaceURI, "GateDocument");
            xsw.writeAttribute("version", GATE_XML_VERSION);
            DocumentStaxUtils.writeAnnotationSet(doc.getAnnotations(), xsw, namespaceURI);
            xsw.writeEndDocument();
            xsw.flush();
            xsw.close();
        }
    }

    private static class AnnotationSetFastInfosetExporter extends AnnotationSetExporter {
        /**
         * Creates a new exporter instance that excludes the original text.
         */
        public AnnotationSetFastInfosetExporter() {
            super("AnnotationSet FastInfoset", "finf", "application/fastinfoset; includeText=no");
        }
        @Override
        public void export(Document doc, OutputStream out, FeatureMap options) throws IOException {
            try {
                StAXDocumentSerializer xsw = new StAXDocumentSerializer(out);
                export(doc, xsw, options);
            } catch (XMLStreamException e) {
                throw new IOException(e);
            }
        }
    }

    private static class AnnotationSetXMLExporter extends AnnotationSetExporter {
        private static final XMLOutputFactory outputFactory = XMLOutputFactory.newInstance();
        /**
         * Creates a new exporter instance for a given file type with default
         * extension.
         */
        public AnnotationSetXMLExporter() {
            super("AnnotationSet GateXML", "xml", "application/gate+xml; includeText=no");
        }

        @Override
        public void export(Document doc, OutputStream out, FeatureMap options) throws IOException {
            try {
                XMLStreamWriter xsw = outputFactory.createXMLStreamWriter(out);
                super.export(doc, xsw, options);
            } catch (XMLStreamException e) {
                throw new IOException(e);
            }
        }
    }

    /**
     * Loads all exporters that we support
     * @return an UnmodifiableMap of the supported exporters.
     */
    static Map<String, DocumentExporter> loadExporters() {
        final GATEJsonExporter gateJsonExporter = new GATEJsonExporter();
        final GateXMLExporter gateXMLExporter = new GateXMLExporter();
        final AnnotationSetXMLExporter annotationSetXMLExporter = new AnnotationSetXMLExporter();
        final FastInfosetExporter fastInfosetExporter = new FastInfosetExporter();
        final AnnotationSetFastInfosetExporter annotationSetFastInfosetExporter = new AnnotationSetFastInfosetExporter();

        final Map<String, DocumentExporter> rv = new HashMap<>();
        rv.put("application/gate+xml", gateXMLExporter);
        rv.put("application/gate+xml; includeText=no", annotationSetXMLExporter);
        rv.put("application/gate+json", gateJsonExporter);
        rv.put(gateJsonExporter.getMimeType(), gateJsonExporter);
        rv.put(fastInfosetExporter.getMimeType(), fastInfosetExporter);
        rv.put("application/fastinfoset; includeText=no", annotationSetFastInfosetExporter);
        return Collections.unmodifiableMap(rv);
    }

    static String computeMessageDigest(String mimeType, String bodyContent) {
        try {
            final String rv;
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            if (mimeType != null)
                md.update(mimeType.getBytes());
            if (bodyContent != null)
                md.update(bodyContent.getBytes());
            rv = Hex.encode(md.digest());
            return rv;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
