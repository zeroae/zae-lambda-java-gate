package co.zeroae.gate;

import gate.*;
import gate.corpora.*;
import gate.corpora.export.GATEJsonExporter;
import gate.corpora.export.GateXMLExporter;
import gate.creole.Plugin;
import gate.creole.ResourceInstantiationException;
import gate.util.GateException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.util.*;

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

    /**
     * Loads all exporters that we support
     * @return an UnmodifiableMap of the supported exporters.
     */
    static Map<String, DocumentExporter> loadExporters() {
        final GateXMLExporter gateXMLExporter = new GateXMLExporter();
        final GATEJsonExporter gateJsonExporter = new GATEJsonExporter();
        final FastInfosetExporter fastInfosetExporter = new FastInfosetExporter();

        final Map<String, DocumentExporter> rv = new HashMap<>();
        rv.put("application/gate+xml", gateXMLExporter);
        rv.put("application/gate+json", gateJsonExporter);
        rv.put("application/fastinfoset", fastInfosetExporter);
        return Collections.unmodifiableMap(rv);
    }
}
