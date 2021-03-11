package co.zeroae.gate;

import com.sun.xml.fastinfoset.stax.StAXDocumentSerializer;
import gate.AnnotationSet;
import gate.Document;
import gate.DocumentExporter;
import gate.FeatureMap;
import gate.corpora.DocumentStaxUtils;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.function.Supplier;

import static gate.corpora.DocumentStaxUtils.GATE_XML_VERSION;

abstract class AnnotationSetExporter extends DocumentExporter {
    /**
     * Creates an exporter just for the AnnotationSets
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
        final AnnotationSet annotations = filterAnnotationSet(doc.getAnnotations(), options);
        final String namespaceURI = "";
        xsw.writeStartDocument("1.0");
        xsw.setDefaultNamespace(namespaceURI);
        xsw.writeStartElement(namespaceURI, "GateDocument");
        xsw.writeAttribute("version", GATE_XML_VERSION);
        DocumentStaxUtils.writeAnnotationSet(annotations, xsw, namespaceURI);
        xsw.writeEndDocument();
        xsw.flush();
        xsw.close();
    }

    @SuppressWarnings("unchecked")
    private AnnotationSet filterAnnotationSet(AnnotationSet annotations, FeatureMap options) {
        final Set<String> annotationTypes = ((Supplier<Set<String>>) () -> {
            Object types = options.get("annotationTypes");
            if (types instanceof Set) {
                return (Set<String>) types;
            } else
                return null;
        }).get();
        return annotationTypes != null ? annotations.get(annotationTypes) : annotations;
    }

    static class GATEFastInfoset extends AnnotationSetExporter {
        /**
         * Creates a new exporter instance that excludes the original text.
         */
        public GATEFastInfoset() {
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

    static class GateXML extends AnnotationSetExporter {
        private static final XMLOutputFactory outputFactory = XMLOutputFactory.newInstance();

        /**
         * Creates a new exporter instance for a given file type with default
         * extension.
         */
        public GateXML() {
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
}
