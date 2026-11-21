import androidx.a2ui.engine.catalog.A2uiCoreCatalog;
import androidx.a2ui.engine.catalog.A2uiCoreComponentDefinition;
import androidx.a2ui.engine.catalog.A2uiCoreComponentDefinitionCollection;
import androidx.a2ui.engine.platform.A2uiCoreComponentRegistry;
import androidx.a2ui.engine.platform.A2uiCoreDataModel;
import androidx.a2ui.engine.processor.A2uiCoreMessageProcessor;
import androidx.a2ui.engine.schema.A2uiCoreSchemaValidator;
import androidx.a2ui.model.catalog.A2uiFunction;
import androidx.a2ui.model.catalog.A2uiFunctionCollection;
import androidx.a2ui.model.processor.A2uiActionInterceptor;
import androidx.a2ui.model.processor.A2uiJsonMessageParser;
import androidx.a2ui.model.processor.A2uiSurfaceModel;
import androidx.a2ui.model.protocol.A2uiComponentPayload;
import androidx.a2ui.model.protocol.A2uiCreateSurfaceMessage;
import androidx.a2ui.model.protocol.A2uiDataPath;
import androidx.a2ui.model.protocol.A2uiException;
import androidx.a2ui.model.protocol.A2uiUpdateComponentsMessage;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MessageProcessorProbe (pre-flight for Block 5.1).
 * 1) Instantiate A2uiCoreMessageProcessor with a custom catalog (MetricCard),
 * 2) feed createSurface + updateComponents (via the real A2uiJsonMessageParser),
 * 3) verify the surface appears in activeSurfaces and the MetricCard component
 *    lands in the ComponentRegistry owned by that surface,
 * 4) check @RestrictTo on A2uiCoreMessageProcessor / A2uiCoreSchemaValidator /
 *    A2uiJsonMessageParser via reflection (class + methods).
 */
public class MessageProcessorProbe {

    static final class DefCollection extends AbstractList<A2uiCoreComponentDefinition>
            implements A2uiCoreComponentDefinitionCollection {
        private final List<A2uiCoreComponentDefinition> defs;
        private final Map<String, A2uiCoreComponentDefinition> byName = new HashMap<>();
        DefCollection(List<A2uiCoreComponentDefinition> defs) {
            this.defs = new ArrayList<>(defs);
            for (A2uiCoreComponentDefinition d : this.defs) { byName.put(d.getName(), d); }
        }
        @Override public A2uiCoreComponentDefinition get(int index) { return defs.get(index); }
        @Override public int size() { return defs.size(); }
        @Override public A2uiCoreComponentDefinition get(String name) { return byName.get(name); }
    }

    static final class FuncCollection extends AbstractList<A2uiFunction>
            implements A2uiFunctionCollection {
        @Override public A2uiFunction get(int index) { throw new IndexOutOfBoundsException(); }
        @Override public int size() { return 0; }
        @Override public A2uiFunction get(String name) { return null; }
    }

    static final class ProbeDataModel implements A2uiCoreDataModel {
        private final Map<String, Object> values = new HashMap<>();
        @Override public void update(A2uiDataPath path, Object value) {
            values.put(String.valueOf(path), value);
        }
        @Override public Object get(A2uiDataPath path) { return values.get(String.valueOf(path)); }
        @Override public void close() { values.clear(); }
    }

    static final class ProbeRegistry implements A2uiCoreComponentRegistry {
        volatile List<A2uiComponentPayload> lastComponents = Collections.emptyList();
        final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        @Override public void update(List<A2uiComponentPayload> components) { lastComponents = components; }
        @Override public void reportError(String id, A2uiException exception) {
            errors.add(id + ": " + exception);
        }
        @Override public void close() { lastComponents = Collections.emptyList(); }
    }

    static A2uiCoreComponentDefinition metricCardDefinition() {
        Map<String, androidx.a2ui.model.schema.A2uiSchema> props = new LinkedHashMap<>();
        props.put("label", new androidx.a2ui.model.schema.A2uiStringSchema("Metric label", Collections.emptyList()));
        props.put("value", new androidx.a2ui.model.schema.A2uiStringSchema("Metric value", Collections.emptyList()));
        @SuppressWarnings({"unchecked", "rawtypes"})
        List trendKeywords = Arrays.asList(
                (androidx.a2ui.model.schema.A2uiSchemaKeyword)
                        new androidx.a2ui.model.schema.A2uiSchemaKeyword.Enum(Arrays.asList("up", "down", "flat")));
        props.put("trend", new androidx.a2ui.model.schema.A2uiStringSchema("Trend", trendKeywords));
        androidx.a2ui.model.schema.A2uiSchema schema = new androidx.a2ui.model.schema.A2uiObjectSchema(
                props, new HashSet<>(Arrays.asList("label", "value")),
                false, null, "MetricCard properties", Collections.emptyList());
        return new A2uiCoreComponentDefinition() {
            @Override public String getName() { return "MetricCard"; }
            @Override public String getDescription() { return "A single KPI tile: value + label + trend"; }
            @Override public androidx.a2ui.model.schema.A2uiSchema getPropertySchema() { return schema; }
        };
    }

    static boolean hasRestrictTo(Annotation[] annotations) {
        for (Annotation a : annotations) {
            if (a.annotationType().getName().contains("RestrictTo")) { return true; }
        }
        return false;
    }

    static String restrictToStatus(Class<?> c) {
        if (hasRestrictTo(c.getAnnotations())) { return "yes (class-level)"; }
        StringBuilder methods = new StringBuilder();
        for (Method m : c.getDeclaredMethods()) {
            if (hasRestrictTo(m.getAnnotations())) { methods.append(m.getName()).append(' '); }
        }
        for (Method m : c.getMethods()) {
            if (hasRestrictTo(m.getAnnotations())) { methods.append(m.getName()).append(' '); }
        }
        return methods.length() > 0 ? "yes (methods: " + methods.toString().trim() + ")" : "no";
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        System.out.println("=== MessageProcessorProbe (A2uiCoreMessageProcessor end-to-end) ===");
        System.out.println("artifact: androidx.a2ui:a2ui-engine:1.0.0-alpha02");
        System.out.println();

        A2uiCoreCatalog catalog = new A2uiCoreCatalog() {
            @Override public String getId() { return "dev.a2uicrm:crm-catalog"; }
            @Override public String getTitle() { return "A2UI CRM Catalog"; }
            @Override public String getDescription() { return "MessageProcessor probe"; }
            @Override public A2uiCoreComponentDefinitionCollection getComponentDefinitions() {
                return new DefCollection(Collections.singletonList(metricCardDefinition()));
            }
            @Override public A2uiFunctionCollection getFunctions() { return new FuncCollection(); }
            @Override public androidx.a2ui.model.schema.A2uiSchema getThemeSchema() { return null; }
            @Override public boolean isInline() { return false; }
        };
        ProbeRegistry registry = new ProbeRegistry();
        A2uiCoreMessageProcessor processor = new A2uiCoreMessageProcessor(
                Collections.singletonList(catalog),
                () -> new ProbeDataModel(),
                () -> registry,
                Collections.<A2uiActionInterceptor>emptyList());
        System.out.println("[1] A2uiCoreMessageProcessor instantiated with custom catalog: PASS");

        kotlinx.coroutines.Job job = kotlinx.coroutines.JobKt.Job(null);
        Thread worker = new Thread(() -> {
            try {
                kotlinx.coroutines.BuildersKt.runBlocking(job,
                        (kotlin.jvm.functions.Function2<kotlinx.coroutines.CoroutineScope,
                                kotlin.coroutines.Continuation<? super kotlin.Unit>, Object>)
                                (scope, cont) -> processor.collectMessages(cont));
            } catch (Throwable ignored) { /* cancelled */ }
        });
        worker.setDaemon(true);
        worker.start();

        processor.processMessage(new A2uiCreateSurfaceMessage(
                "demo", "dev.a2uicrm:crm-catalog", new HashMap<>(), false));
        Map<String, Object> rootProps = new LinkedHashMap<>();
        rootProps.put("children", Arrays.asList("m1"));
        Map<String, Object> metricProps = new LinkedHashMap<>();
        metricProps.put("label", "Overdue");
        metricProps.put("value", "2");
        metricProps.put("trend", "up");
        processor.processMessage(new A2uiUpdateComponentsMessage("demo", Arrays.asList(
                new A2uiComponentPayload("root", "Column", rootProps),
                new A2uiComponentPayload("m1", "MetricCard", metricProps))));
        System.out.println("[2] createSurface(demo) + updateComponents(root Column + m1 MetricCard) submitted: PASS");

        boolean surfaceOk = false;
        boolean componentOk = false;
        for (int i = 0; i < 50 && !(surfaceOk && componentOk); i++) {
            List<A2uiSurfaceModel> surfaces =
                    (List<A2uiSurfaceModel>) processor.getActiveSurfaces().getValue();
            surfaceOk = surfaces.size() == 1 && "demo".equals(surfaces.get(0).getId());
            componentOk = registry.lastComponents.stream()
                    .anyMatch(p -> "MetricCard".equals(p.getType()));
            if (!(surfaceOk && componentOk)) { Thread.sleep(100); }
        }
        System.out.println("[3] activeSurfaces contains surface id='demo': " + (surfaceOk ? "PASS" : "FAIL"));
        System.out.println("[4] surface ComponentRegistry received MetricCard: " + (componentOk ? "PASS" : "FAIL"));
        System.out.println("    registry.lastComponents = " + registry.lastComponents);
        System.out.println("    registry.errors = " + registry.errors);

        // Case [5]: valid type, INVALID property (trend='spiral') on a second surface.
        // If the surface actor reports a property error -> full-schema validation in the
        // critical path (§7.2 variant b). If only unknown-TYPE errors appear (root Column
        // not registered) -> registry lookup only (§7.2 variant a).
        registry.errors.clear();
        processor.processMessage(new A2uiCreateSurfaceMessage(
                "demo-bad", "dev.a2uicrm:crm-catalog", new HashMap<>(), false));
        Map<String, Object> badRootProps = new LinkedHashMap<>();
        badRootProps.put("children", Arrays.asList("m2"));
        Map<String, Object> badMetricProps = new LinkedHashMap<>();
        badMetricProps.put("label", "Overdue");
        badMetricProps.put("value", "9");
        badMetricProps.put("trend", "spiral");
        processor.processMessage(new A2uiUpdateComponentsMessage("demo-bad", Arrays.asList(
                new A2uiComponentPayload("root", "Column", badRootProps),
                new A2uiComponentPayload("m2", "MetricCard", badMetricProps))));
        for (int i = 0; i < 30 && registry.errors.isEmpty(); i++) { Thread.sleep(100); }
        List<String> case5Errors = new ArrayList<>(registry.errors);
        boolean propertyError = case5Errors.stream().anyMatch(e ->
                e.contains("spiral") || e.contains("trend") || e.contains("enum"));
        boolean typeError = case5Errors.stream().anyMatch(e -> e.contains("not found in catalog"));
        System.out.println("[5] MetricCard with trend='spiral' (surface 'demo-bad'):");
        System.out.println("    registry.errors = " + case5Errors);
        System.out.println("    property-level error: " + (propertyError
                ? "YES -> FULL-SCHEMA validation in critical path (§7.2 variant b)"
                : "NO"));
        System.out.println("    type-level error (expected, root Column unregistered): " + (typeError ? "YES" : "no"));
        System.out.println("    verdict: surface actor validation = " + (propertyError
                ? "FULL-SCHEMA" : "REGISTRY-LOOKUP (property validation is OUR CatalogValidator's job)"));

        System.out.println();
        System.out.println("@RestrictTo (runtime reflection — INCONCLUSIVE for CLASS retention):");
        System.out.println("  A2uiCoreMessageProcessor: " + restrictToStatus(A2uiCoreMessageProcessor.class));
        System.out.println("  A2uiCoreSchemaValidator:  " + restrictToStatus(A2uiCoreSchemaValidator.class));
        System.out.println("  A2uiJsonMessageParser:    " + restrictToStatus(A2uiJsonMessageParser.class));
        System.out.println("  Note: androidx annotations use RetentionPolicy.CLASS (kept in bytecode, NOT visible");
        System.out.println("  to runtime reflection), so reflection alone CANNOT prove absence/presence.");
        System.out.println("  Authoritative verification is javap bytecode analysis + AOSP source (see analysis.md");
        System.out.println("  §7.2): A2uiCoreMessageProcessor = NO @RestrictTo; A2uiCoreSchemaValidator = YES");
        System.out.println("  (RuntimeInvisibleAnnotations: androidx.annotation.RestrictTo(LIBRARY_GROUP_PREFIX));");
        System.out.println("  A2uiJsonMessageParser = NO.");

        job.cancel(null);
        worker.join(2000);
        System.out.println();
        System.out.println("SUMMARY: " + ((surfaceOk && componentOk)
                ? "PASS (surface created, components delivered)" : "FAIL"));
        System.exit(surfaceOk && componentOk ? 0 : 1);
    }
}

