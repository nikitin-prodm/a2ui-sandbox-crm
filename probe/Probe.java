import androidx.a2ui.model.processor.A2uiJsonMessageParser;
import androidx.a2ui.model.processor.A2uiJsonReader;
import androidx.a2ui.model.processor.A2uiJsonToken;
import androidx.a2ui.model.protocol.A2uiException;
import androidx.a2ui.model.protocol.A2uiServerToClientMessage;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;

/**
 * Protocol Compatibility Probe for androidx.a2ui:a2ui-model:1.0.0-alpha02.
 * Feeds A2UI messages per spec v0.9.1 (server_to_client.json) and per spec v1.0
 * (agent_to_renderer.json) into A2uiJsonMessageParser and records what parses.
 * A2uiJsonReader is an INTERFACE in a2ui-model, so the host provides it;
 * here we adapt Gson's streaming JsonReader.
 */
public class Probe {

    static final class GsonA2uiReader implements A2uiJsonReader {
        private final JsonReader delegate;

        GsonA2uiReader(JsonReader delegate) {
            this.delegate = delegate;
            delegate.setLenient(true);
        }

        private static RuntimeException rethrow(IOException e) {
            return new RuntimeException(e);
        }

        private static A2uiJsonToken map(JsonToken t) {
            switch (t) {
                case BEGIN_ARRAY: return A2uiJsonToken.BEGIN_ARRAY;
                case END_ARRAY: return A2uiJsonToken.END_ARRAY;
                case BEGIN_OBJECT: return A2uiJsonToken.BEGIN_OBJECT;
                case END_OBJECT: return A2uiJsonToken.END_OBJECT;
                case NAME: return A2uiJsonToken.NAME;
                case STRING: return A2uiJsonToken.STRING;
                case NUMBER: return A2uiJsonToken.NUMBER;
                case BOOLEAN: return A2uiJsonToken.BOOLEAN;
                case NULL: return A2uiJsonToken.NULL;
                case END_DOCUMENT: return A2uiJsonToken.END_DOCUMENT;
                default: throw new IllegalStateException("Unexpected gson token: " + t);
            }
        }

        @Override public A2uiJsonToken peek() { try { return map(delegate.peek()); } catch (IOException e) { throw rethrow(e); } }
        @Override public void beginObject() { try { delegate.beginObject(); } catch (IOException e) { throw rethrow(e); } }
        @Override public void endObject() { try { delegate.endObject(); } catch (IOException e) { throw rethrow(e); } }
        @Override public void beginArray() { try { delegate.beginArray(); } catch (IOException e) { throw rethrow(e); } }
        @Override public void endArray() { try { delegate.endArray(); } catch (IOException e) { throw rethrow(e); } }
        @Override public boolean hasNext() { try { return delegate.hasNext(); } catch (IOException e) { throw rethrow(e); } }
        @Override public String nextName() { try { return delegate.nextName(); } catch (IOException e) { throw rethrow(e); } }
        @Override public String nextString() { try { return delegate.nextString(); } catch (IOException e) { throw rethrow(e); } }
        @Override public boolean nextBoolean() { try { return delegate.nextBoolean(); } catch (IOException e) { throw rethrow(e); } }
        @Override public double nextDouble() { try { return delegate.nextDouble(); } catch (IOException e) { throw rethrow(e); } }
        @Override public int nextInt() { try { return delegate.nextInt(); } catch (IOException e) { throw rethrow(e); } }
        @Override public long nextLong() { try { return delegate.nextLong(); } catch (IOException e) { throw rethrow(e); } }
        @Override public void nextNull() { try { delegate.nextNull(); } catch (IOException e) { throw rethrow(e); } }
        @Override public void skipValue() { try { delegate.skipValue(); } catch (IOException e) { throw rethrow(e); } }
        @Override public void close() { try { delegate.close(); } catch (IOException e) { throw rethrow(e); } }
    }

    public static void main(String[] args) {
        A2uiJsonMessageParser parser = new A2uiJsonMessageParser(
                s -> new GsonA2uiReader(new JsonReader(new StringReader(s))));

        String[][] cases = {
            {"1) v0.9.1 createSurface (valid per v0.9.1 schema)",
             "{\"version\":\"v0.9.1\",\"createSurface\":{\"surfaceId\":\"s1\",\"catalogId\":\"dev.a2uicrm:catalog\"}}",
             "PARSE_OK"},
            {"2) v0.9 createSurface (valid per v0.9.1 schema enum)",
             "{\"version\":\"v0.9\",\"createSurface\":{\"surfaceId\":\"s2\",\"catalogId\":\"dev.a2uicrm:catalog\"}}",
             "PARSE_OK"},
            {"3) v1.0 createSurface minimal (version const 1.0)",
             "{\"version\":\"1.0\",\"createSurface\":{\"surfaceId\":\"s3\"}}",
             "VALIDATION_FAILED"},
            {"4) v1.0 createSurface with inline components + dataModel (v1.0-only)",
             "{\"version\":\"1.0\",\"createSurface\":{\"surfaceId\":\"s3\",\"components\":[{\"id\":\"root\",\"component\":\"Text\",\"text\":\"Hello\"}],\"dataModel\":{}}}",
             "VALIDATION_FAILED"},
            {"5) v0.9.1 updateComponents with component list",
             "{\"version\":\"v0.9.1\",\"updateComponents\":{\"surfaceId\":\"s1\",\"components\":[{\"id\":\"root\",\"component\":\"Text\",\"text\":\"Hi\"},{\"id\":\"c1\",\"component\":\"Button\",\"label\":\"OK\"}]}}",
             "PARSE_OK"},
            {"6) missing version field in envelope",
             "{\"createSurface\":{\"surfaceId\":\"s4\",\"catalogId\":\"dev.a2uicrm:catalog\"}}",
             "VALIDATION_FAILED"},
            {"7) v0.8 deleteSurface (deprecated version string)",
             "{\"version\":\"v0.8\",\"deleteSurface\":{\"surfaceId\":\"s1\"}}",
             "VALIDATION_FAILED"},
            {"8) v1.0 callRendererFunction (v1.0-only message type)",
             "{\"version\":\"1.0\",\"callRendererFunction\":{\"surfaceId\":\"s1\",\"functionCallId\":\"fc1\",\"callFunction\":{\"name\":\"formatString\",\"catalogId\":\"basic\"}}}",
             "VALIDATION_FAILED"},
        };

        int pass = 0;
        int fail = 0;
        System.out.println("=== A2UI Protocol Compatibility Probe ===");
        System.out.println("artifact: androidx.a2ui:a2ui-model:1.0.0-alpha02 (classes.jar from AAR)");
        System.out.println();

        for (String[] c : cases) {
            System.out.println("CASE " + c[0]);
            System.out.println("  input: " + c[1]);
            String actual;
            String detail;
            try {
                A2uiServerToClientMessage msg = parser.parse(c[1]);
                actual = "PARSE_OK";
                detail = "parsed -> " + msg.getClass().getSimpleName() + " " + msg;
            } catch (Throwable t) {
                // Kotlin does not declare checked exceptions, so dispatch manually.
                if (t instanceof A2uiException.A2uiValidationException) {
                    A2uiException.A2uiValidationException e = (A2uiException.A2uiValidationException) t;
                    actual = "VALIDATION_FAILED";
                    detail = "A2uiValidationException code=" + e.getCode()
                            + " message=\"" + e.getMessage() + "\" context=" + e.getContext();
                } else if (t instanceof A2uiException) {
                    A2uiException e = (A2uiException) t;
                    actual = "A2UI_EXCEPTION";
                    detail = e.getClass().getName() + " code=" + e.getCode()
                            + " message=\"" + e.getMessage() + "\" context=" + e.getContext();
                } else {
                    actual = "UNEXPECTED_THROWABLE";
                    detail = t.getClass().getName() + ": " + t.getMessage();
                }
            }
            System.out.println("  result: " + actual);
            System.out.println("  detail: " + detail);
            boolean ok = actual.equals(c[2]);
            System.out.println("  verdict: " + (ok ? "PASS (as expected)" : "FAIL (expected " + c[2] + ")"));
            if (ok) pass++; else fail++;
            System.out.println();
        }

        System.out.println("SUMMARY: " + pass + " passed, " + fail + " failed out of " + cases.length + " cases.");
        System.exit(fail == 0 ? 0 : 1);
    }
}
