import androidx.a2ui.model.processor.A2uiJsonMessageParser;
import androidx.a2ui.model.processor.A2uiJsonReader;
import androidx.a2ui.model.processor.A2uiJsonToken;
import androidx.a2ui.model.protocol.A2uiComponentPayload;
import androidx.a2ui.model.protocol.A2uiServerToClientMessage;
import androidx.a2ui.model.protocol.A2uiUpdateComponentsMessage;
import com.google.gson.JsonArray;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MVP CatalogValidator probe: property-level validation (required + enum) driven by
 * catalog/catalog.json schemas, applied to every messageExample (parsed through the real
 * A2uiJsonMessageParser). Complements the round-trip probe: round-trip proves the
 * envelope/structure is accepted by the engine; this probe proves property-level rules
 * are enforceable before rendering.
 */
public class CatalogValidationProbe {

    static final class GsonA2uiReader implements A2uiJsonReader {
        private final JsonReader delegate;
        GsonA2uiReader(JsonReader delegate) { this.delegate = delegate; delegate.setLenient(true); }
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

    /** MVP CatalogValidator: required + enum checks per catalog.json schema. */
    static List<String> validate(JsonObject crmComponents, String type, Map<String, Object> props) {
        List<String> errors = new ArrayList<>();
        JsonObject def = crmComponents.getAsJsonObject(type);
        if (def == null) { errors.add("UNKNOWN_COMPONENT: '" + type + "' is not in the catalog"); return errors; }
        JsonObject schema = def.getAsJsonObject("schema");
        for (Map.Entry<String, JsonElement> e : schema.entrySet()) {
            String prop = e.getKey();
            JsonObject spec = e.getValue().getAsJsonObject();
            boolean required = spec.has("required") && spec.get("required").getAsBoolean();
            if (required && !props.containsKey(prop)) {
                errors.add("MISSING_REQUIRED_PROPERTY: " + type + "." + prop
                        + " (path=/components/" + type + "/" + prop + ")");
                continue;
            }
            if (props.containsKey(prop) && spec.has("enum")) {
                JsonArray allowed = spec.getAsJsonArray("enum");
                String value = String.valueOf(props.get(prop));
                boolean ok = false;
                for (JsonElement a : allowed) { if (a.getAsString().equals(value)) { ok = true; break; } }
                if (!ok) {
                    errors.add("INVALID_PROPERTY_VALUE: " + type + "." + prop + "='" + value
                            + "' not in enum " + allowed + " (path=/components/" + type + "/" + prop + ")");
                }
            }
        }
        return errors;
    }

    static A2uiJsonMessageParser parser() {
        return new A2uiJsonMessageParser(s -> new GsonA2uiReader(new JsonReader(new StringReader(s))));
    }

    /** Nesting check: tree traversal from root following children ids, with ancestor stack. */
    static void walk(A2uiComponentPayload node, Map<String, A2uiComponentPayload> byId,
                     JsonArray nestingRules, java.util.Deque<String> ancestors, List<String> errors) {
        String type = node.getType();
        for (String parent : ancestors) {
            for (JsonElement r : nestingRules) {
                JsonObject rule = r.getAsJsonObject();
                if (!rule.get("parent").getAsString().equals(parent)) { continue; }
                for (JsonElement c : rule.getAsJsonArray("forbiddenChildren")) {
                    if (c.getAsString().equals(type)) {
                        errors.add("NESTING_NOT_ALLOWED: " + type + " inside " + parent
                                + " (path=/components/" + node.getId() + "/component, ancestors=" + ancestors + ")");
                    }
                }
            }
        }
        Object children = node.getProperties().get("children");
        Object child = node.getProperties().get("child");
        if (children instanceof List || child instanceof String) {
            ancestors.push(type);
            if (children instanceof List) {
                for (Object childId : (List<?>) children) {
                    A2uiComponentPayload c = byId.get(String.valueOf(childId));
                    if (c != null) { walk(c, byId, nestingRules, ancestors, errors); }
                }
            }
            if (child instanceof String) {
                A2uiComponentPayload c = byId.get(String.valueOf(child));
                if (c != null) { walk(c, byId, nestingRules, ancestors, errors); }
            }
            ancestors.pop();
        }
    }

    static List<String> validateMessage(String message, JsonObject crmComponents,
                                        Map<String, JsonObject> nativeByName, JsonArray nestingRules) {
        List<String> errors = new ArrayList<>();
        A2uiServerToClientMessage msg = parser().parse(message);
        if (!(msg instanceof A2uiUpdateComponentsMessage)) { return errors; }
        List<A2uiComponentPayload> payloads = ((A2uiUpdateComponentsMessage) msg).getComponents();
        for (A2uiComponentPayload p : payloads) {
            String type = p.getType();
            JsonObject nativeDef = nativeByName.get(type);
            if (nativeDef != null) {
                // Native type: only rendererSupport is checked here (spec-level validation is
                // A2uiCoreSchemaValidator's job, verified separately in CatalogProbe).
                if ("not_implemented_v1".equals(
                        nativeDef.has("rendererSupport") ? nativeDef.get("rendererSupport").getAsString() : "native")) {
                    errors.add("UNSUPPORTED_RENDERER_TYPE: '" + type
                            + "' is valid per spec 0.9.1 but deferred in this renderer"
                            + " (path=/components/" + type
                            + "/component, hint: use the budgeted catalog types (see rendererSupport)"
                            + " or a dedicated flow instead)");
                }
                continue;
            }
            errors.addAll(validate(crmComponents, type, p.getProperties()));
        }
        // Nesting constraints (machine-readable: top-level nestingRules in catalog.json).
        Map<String, A2uiComponentPayload> byId = new java.util.HashMap<>();
        for (A2uiComponentPayload p : payloads) { byId.put(p.getId(), p); }
        A2uiComponentPayload root = byId.get("root");
        if (root != null) {
            walk(root, byId, nestingRules, new java.util.ArrayDeque<>(), errors);
        }
        return errors;
    }

    public static void main(String[] args) throws Exception {
        String catalogPath = args.length > 0 ? args[0] : "catalog/catalog.json";
        JsonObject catalog = JsonParser.parseString(
                new String(Files.readAllBytes(Paths.get(catalogPath)), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject crmComponents = catalog.getAsJsonObject("components");
        Map<String, JsonObject> nativeByName = new java.util.HashMap<>();
        for (JsonElement e : catalog.getAsJsonObject("nativeComponents").getAsJsonArray("components")) {
            JsonObject d = e.getAsJsonObject();
            nativeByName.put(d.get("name").getAsString(), d);
        }
        JsonArray nestingRules = catalog.getAsJsonArray("nestingRules");

        System.out.println("=== MVP CatalogValidator Probe (required + enum + rendererSupport + nesting) ===");
        System.out.println("catalog: " + catalogPath);
        System.out.println();

        int pass = 0; int fail = 0;
        for (Map.Entry<String, JsonElement> e : crmComponents.entrySet()) {
            String name = e.getKey();
            String example = e.getValue().getAsJsonObject().get("messageExample").toString();
            List<String> errors = validateMessage(example, crmComponents, nativeByName, nestingRules);
            System.out.println("VALIDATE " + name + ": " + (errors.isEmpty()
                    ? "PASS (0 errors)" : "FAIL " + errors));
            if (errors.isEmpty()) pass++; else fail++;
        }

        System.out.println();
        System.out.println("--- negative controls (must FAIL with structured errors) ---");
        String metricExample = crmComponents.getAsJsonObject("MetricCard")
                .get("messageExample").toString();
        String badTrend = metricExample.replace("\"trend\":\"up\"", "\"trend\":\"spiral\"");
        List<String> r1 = validateMessage(badTrend, crmComponents, nativeByName, nestingRules);
        System.out.println("NEGATIVE 1 (MetricCard.trend='spiral'): " + (r1.isEmpty() ? "NOT DETECTED (bad)" : r1));
        String noValue = metricExample.replace(",\"value\":{\"path\":\"/stats/overdueCount\"}", "");
        List<String> r2 = validateMessage(noValue, crmComponents, nativeByName, nestingRules);
        System.out.println("NEGATIVE 2 (MetricCard without required 'value'): " + (r2.isEmpty() ? "NOT DETECTED (bad)" : r2));
        String textFieldMsg = "{\"version\":\"v0.9.1\",\"updateComponents\":{\"surfaceId\":\"demo\",\"components\":["
                + "{\"id\":\"root\",\"component\":\"Column\",\"children\":[\"tf1\"]},"
                + "{\"id\":\"tf1\",\"component\":\"TextField\",\"label\":\"Edit me\"}]}}";
        List<String> r3 = validateMessage(textFieldMsg, crmComponents, nativeByName, nestingRules);
        System.out.println("NEGATIVE 3 (TextField, deferred to v1.1): " + (r3.isEmpty() ? "NOT DETECTED (bad)" : r3));
        String mediaMsg = "{\"version\":\"v0.9.1\",\"updateComponents\":{\"surfaceId\":\"demo\",\"components\":["
                + "{\"id\":\"root\",\"component\":\"Column\",\"children\":[\"v1\",\"a1\"]},"
                + "{\"id\":\"v1\",\"component\":\"Video\",\"url\":\"https://example.com/x.mp4\"},"
                + "{\"id\":\"a1\",\"component\":\"AudioPlayer\",\"url\":\"https://example.com/x.mp3\"}]}}";
        List<String> r4 = validateMessage(mediaMsg, crmComponents, nativeByName, nestingRules);
        System.out.println("NEGATIVE 4 (Video + AudioPlayer, not budgeted): " + (r4.isEmpty() ? "NOT DETECTED (bad)" : r4));
        String imageMsg = "{\"version\":\"v0.9.1\",\"updateComponents\":{\"surfaceId\":\"demo\",\"components\":["
                + "{\"id\":\"root\",\"component\":\"Column\",\"children\":[\"img1\"]},"
                + "{\"id\":\"img1\",\"component\":\"Image\",\"url\":\"https://example.com/logo.png\"}]}}";
        List<String> r5 = validateMessage(imageMsg, crmComponents, nativeByName, nestingRules);
        System.out.println("NEGATIVE 5 (Image, not budgeted): " + (r5.isEmpty() ? "NOT DETECTED (bad)" : r5));
        String nestingMsg = "{\"version\":\"v0.9.1\",\"updateComponents\":{\"surfaceId\":\"demo\",\"components\":["
                + "{\"id\":\"root\",\"component\":\"Column\",\"children\":[\"ec1\"]},"
                + "{\"id\":\"ec1\",\"component\":\"EntityCard\",\"entityType\":\"opportunity\",\"entityId\":\"x1\","
                + "\"title\":\"Acme renewal\",\"children\":[\"tc1\"]},"
                + "{\"id\":\"tc1\",\"component\":\"TrendChart\",\"kind\":\"bar\",\"points\":[1,2,3],\"labels\":[\"a\",\"b\",\"c\"]}]}}";
        List<String> r6 = validateMessage(nestingMsg, crmComponents, nativeByName, nestingRules);
        System.out.println("NEGATIVE 6 (TrendChart inside EntityCard): " + (r6.isEmpty() ? "NOT DETECTED (bad)" : r6));

        System.out.println();
        System.out.println("SUMMARY: " + pass + " passed, " + fail + " failed (positive); "
                + ((r1.isEmpty() ? 0 : 1) + (r2.isEmpty() ? 0 : 1) + (r3.isEmpty() ? 0 : 1)
                        + (r4.isEmpty() ? 0 : 1) + (r5.isEmpty() ? 0 : 1) + (r6.isEmpty() ? 0 : 1))
                + "/6 negative controls detected (enum, missing required, unsupported renderer type x3, nesting).");
        System.exit(fail == 0 && !r1.isEmpty() && !r2.isEmpty() && !r3.isEmpty()
                && !r4.isEmpty() && !r5.isEmpty() && !r6.isEmpty() ? 0 : 1);
    }
}
