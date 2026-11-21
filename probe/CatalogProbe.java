import androidx.a2ui.engine.catalog.A2uiCoreCatalog;
import androidx.a2ui.engine.catalog.A2uiCoreComponentDefinition;
import androidx.a2ui.engine.catalog.A2uiCoreComponentDefinitionCollection;
import androidx.a2ui.engine.schema.A2uiCoreSchemaValidator;
import androidx.a2ui.model.catalog.A2uiFunction;
import androidx.a2ui.model.catalog.A2uiFunctionCollection;
import androidx.a2ui.model.protocol.A2uiException;
import androidx.a2ui.model.schema.A2uiObjectSchema;
import androidx.a2ui.model.schema.A2uiRefSchema;
import androidx.a2ui.model.schema.A2uiSchema;
import androidx.a2ui.model.schema.A2uiSchemaKeyword;
import androidx.a2ui.model.schema.A2uiStringSchema;

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
 * Custom Catalog Registration Probe (gate for Block 5).
 * Verifies that androidx.a2ui 1.0.0-alpha02 allows registering a custom catalog
 * (id = "dev.a2uicrm:crm-catalog") with an A2uiCoreComponentDefinition("MetricCard", ...),
 * and that A2uiCoreSchemaValidator accepts a MetricCard payload through this catalog
 * and rejects it through a catalog where MetricCard is not registered.
 * Verdict recorded in analysis.md: Custom Catalog API - available / partial / not available.
 */
public class CatalogProbe {

    static A2uiSchema metricCardPropertySchema() {
        Map<String, A2uiSchema> props = new LinkedHashMap<>();
        props.put("label", new A2uiStringSchema("Metric label", Collections.emptyList()));
        props.put("value", new A2uiStringSchema("Metric value", Collections.emptyList()));
        @SuppressWarnings({"unchecked", "rawtypes"})
        List<A2uiSchemaKeyword<String>> trendKeywords = Arrays.asList(
                (A2uiSchemaKeyword) new A2uiSchemaKeyword.Enum<>(Arrays.asList("up", "down", "flat")));
        props.put("trend", new A2uiStringSchema("Trend", trendKeywords));
        return new A2uiObjectSchema(
                props,
                new HashSet<>(Arrays.asList("label", "value")),
                false, null, "MetricCard properties", Collections.emptyList());
    }

    static A2uiCoreComponentDefinition metricCardDefinition(A2uiSchema schema) {
        return new A2uiCoreComponentDefinition() {
            @Override public String getName() { return "MetricCard"; }
            @Override public String getDescription() { return "A single KPI tile: value + label + trend"; }
            @Override public A2uiSchema getPropertySchema() { return schema; }
        };
    }

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

    static A2uiCoreCatalog catalog(String id, List<A2uiCoreComponentDefinition> definitions) {
        return new A2uiCoreCatalog() {
            @Override public String getId() { return id; }
            @Override public String getTitle() { return "Probe catalog"; }
            @Override public String getDescription() { return "Catalog registration probe"; }
            @Override public A2uiCoreComponentDefinitionCollection getComponentDefinitions() { return new DefCollection(definitions); }
            @Override public A2uiFunctionCollection getFunctions() { return new FuncCollection(); }
            @Override public A2uiSchema getThemeSchema() { return null; }
            @Override public boolean isInline() { return false; }
        };
    }

    static Map<String, Object> validMetricProps() {
        Map<String, Object> m = new HashMap<>();
        m.put("label", "Overdue");
        m.put("value", "2");
        m.put("trend", "up");
        return m;
    }

    interface Check { void run(); }

    static String outcome(Check c) {
        try { c.run(); return "PASS (no exception)"; }
        catch (Throwable t) {
            // Kotlin does not declare checked exceptions, so dispatch manually.
            if (t instanceof A2uiException.A2uiValidationException) {
                A2uiException.A2uiValidationException e = (A2uiException.A2uiValidationException) t;
                return "VALIDATION_FAILED: " + e.getMessage() + " (context=" + e.getContext() + ")";
            }
            return "UNEXPECTED " + t.getClass().getName() + ": " + t.getMessage();
        }
    }

    public static void main(String[] args) {
        A2uiSchema schema = metricCardPropertySchema();
        A2uiCoreComponentDefinition metricCard = metricCardDefinition(schema);
        A2uiCoreCatalog crmCatalog =
                catalog("dev.a2uicrm:crm-catalog", Collections.singletonList(metricCard));
        A2uiCoreCatalog defaultCatalog =
                catalog("a2ui.org/specification/v0_9/catalogs/basic", Collections.emptyList());

        A2uiCoreSchemaValidator crmValidator = new A2uiCoreSchemaValidator(crmCatalog);
        A2uiCoreSchemaValidator defaultValidator = new A2uiCoreSchemaValidator(defaultCatalog);
        A2uiRefSchema metricCardRef = new A2uiRefSchema("catalog.json#/components/MetricCard");

        System.out.println("=== Custom Catalog Registration Probe ===");
        System.out.println("artifact: androidx.a2ui:a2ui-engine:1.0.0-alpha02 (A2uiCoreCatalog + A2uiCoreSchemaValidator)");
        System.out.println();

        System.out.println("[1] Registry lookup: crmCatalog.componentDefinitions.get(\"MetricCard\") != null");
        System.out.println("    -> " + (crmCatalog.getComponentDefinitions().get("MetricCard") != null ? "PASS" : "FAIL"));

        System.out.println("[2] Direct property-schema validation (valid props, crm catalog):");
        System.out.println("    " + outcome(() -> crmValidator.validateSchema(validMetricProps(), schema, "/")));

        System.out.println("[3] Direct property-schema validation (trend=spiral) - expect VALIDATION_FAILED:");
        Map<String, Object> bad = validMetricProps(); bad.put("trend", "spiral");
        System.out.println("    " + outcome(() -> crmValidator.validateSchema(bad, schema, "/")));

        System.out.println("[4] Direct property-schema validation (missing required value) - expect VALIDATION_FAILED:");
        Map<String, Object> missing = new HashMap<>(); missing.put("label", "Overdue");
        System.out.println("    " + outcome(() -> crmValidator.validateSchema(missing, schema, "/")));

        System.out.println("[5] Ref-schema via CRM catalog (catalog.json#/components/MetricCard) - expect PASS:");
        System.out.println("    " + outcome(() -> crmValidator.validateSchema(validMetricProps(), metricCardRef, "/")));

        System.out.println("[6] Ref-schema via DEFAULT catalog (MetricCard not registered) - expect VALIDATION_FAILED:");
        System.out.println("    " + outcome(() -> defaultValidator.validateSchema(validMetricProps(), metricCardRef, "/")));

        System.out.println();
        System.out.println("Note: A2uiCoreSchemaValidator is @RestrictTo(LIBRARY_GROUP_PREFIX) in a2ui-engine:");
        System.out.println("runtime works (this probe), but it is not intended as public API; production");
        System.out.println("CatalogValidator should wrap or re-implement it (see analysis.md, Block 4 plan).");
    }
}
