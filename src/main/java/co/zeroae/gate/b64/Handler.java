package co.zeroae.gate.b64;

import com.amazonaws.util.StringInputStream;
import org.apache.commons.codec.binary.Base64InputStream;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

public class Handler extends URLStreamHandler {
    private class Connection extends URLConnection {
        /**
         * Constructs a URL connection to the specified URL. A connection to
         * the object referenced by the URL is not created.
         *
         * @param url the specified URL.
         */
        protected Connection(URL url) {
            super(url);
        }
        @Override
        public void connect() {
        }
        @Override
        public InputStream getInputStream() throws IOException {
            return new Base64InputStream(new StringInputStream(url.getPath()));
        }
    }
    @Override
    protected URLConnection openConnection(URL u) {
        return new Connection(u);
    }
}
