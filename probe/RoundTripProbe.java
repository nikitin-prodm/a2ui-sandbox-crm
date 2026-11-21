import androidx.a2ui.model.processor.A2uiJsonMessageParser;
import androidx.a2ui.model.processor.A2uiJsonReader;
import androidx.a2ui.model.processor.A2uiJsonToken;
import androidx.a2ui.model.protocol.A2uiServerToClientMessage;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Round-trip test: every messageExample in catalog/catalog.json must be a VALID A2UI 0.9.1
 * envelope accepted by androidx.a2ui:a2ui-model:1.0.0-alpha02 (A2uiJsonMessageParser).
 * This proves the catalog is a contract, not paper examples.
 */
public class RoundTripProbe {

    static final class GsonA2uiReader implements A2uiJsonReader {
        private final JsonReader delegate;

        GsonA2uiReader(JsonReader delegate) {
            this.delegate = delegate;
            delegate.setLenient(true);
        }

        private static RuntimeException rethrow(IOException e) { return new RuntimeException(e); }

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

    public static void main(String[] args) throws Exception {
        String catalogPath = args.length > 0 ? args[0] : "catalog/catalog.json";
        String raw = new String(Files.readAllBytes(Paths.get(catalogPath)), StandardCharsets.UTF_8);
        JsonObject catalog = JsonParser.parseString(raw).getAsJsonObject();
        A2uiJsonMessageParser parser = new A2uiJsonMessageParser(
                s -> new GsonA2uiReader(new JsonReader(new StringReader(s))));

        System.out.println("=== A2UI Catalog Round-Trip Probe ===");
        System.out.println("catalog: " + catalogPath);
        System.out.println("parser:  androidx.a2ui:a2ui-model:1.0.0-alpha02 (A2uiJsonMessageParser)");
        System.out.println();

        int pass = 0;
        int fail = 0;
        for (Map.Entry<String, JsonElement> e : catalog.getAsJsonObject("components").entrySet()) {
            String name = e.getKey();
            JsonElement ex = e.getValue().getAsJsonObject().get("messageExample");
            if (ex == null || !ex.isJsonObject()) {
                System.out.println("ROUNDTRIP " + name + ": SKIP (no messageExample)");
                continue;
            }
            String message = ex.toString();
            System.out.println("ROUNDTRIP " + name);
            System.out.println("  input: " + message);
            try {
                A2uiServerToClientMessage msg = parser.parse(message);
                System.out.println("  result: PARSE_OK -> " + msg.getClass().getSimpleName() + " " + msg);
                pass++;
            } catch (Throwable t) {
                System.out.println("  result: FAIL -> " + t.getClass().getSimpleName() + ": " + t.getMessage());
                fail++;
            }
            System.out.println();
        }

        System.out.println("SUMMARY: " + pass + " passed, " + fail + " failed.");
        System.exit(fail == 0 ? 0 : 1);
    }
}
