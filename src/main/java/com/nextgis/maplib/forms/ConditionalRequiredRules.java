package com.nextgis.maplib.forms;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Bounded, offline, declarative requirements. Conditions never execute code or change values. */
public final class ConditionalRequiredRules {
    public static final String META_KEY = "lisa_form_rules";
    public static final int MAX_BYTES = 256 * 1024;
    private final Map<String, Condition> rules = new LinkedHashMap<>();
    private final Map<String, String> labels = new LinkedHashMap<>();
    private final Set<String> references = new LinkedHashSet<>();
    private int nodes;
    private interface Condition { boolean matches(Function<String, Object> values); }

    public ConditionalRequiredRules(JSONObject definition) throws JSONException {
        if (definition.getInt("schema_version") != 1) throw new JSONException("Unknown form rules version");
        JSONArray required = definition.getJSONArray("required");
        if (required.length() > 512) throw new JSONException("Too many required rules");
        for (int i = 0; i < required.length(); i++) {
            JSONObject rule = required.getJSONObject(i);
            String field = name(rule, "field");
            if (rule.has("label")) {
                Object label = rule.get("label");
                if (!(label instanceof String) || ((String) label).trim().isEmpty() || ((String) label).length() > 512)
                    throw new JSONException("Invalid required field label");
                labels.put(field, (String) label);
            }
            if (rules.put(field, parse(rule.getJSONObject("when"), 0)) != null)
                throw new JSONException("Repeated required target: " + field);
        }
    }

    public Set<String> targets() { return Collections.unmodifiableSet(rules.keySet()); }
    public Set<String> references() { return Collections.unmodifiableSet(references); }
    public String label(String field, String fallback) { return labels.getOrDefault(field, fallback); }
    public Set<String> required(Function<String, Object> values) {
        Set<String> required = new LinkedHashSet<>();
        for (Map.Entry<String, Condition> rule : rules.entrySet())
            if (rule.getValue().matches(values)) required.add(rule.getKey());
        return required;
    }

    private Condition parse(JSONObject expression, int depth) throws JSONException {
        if (depth > 16 || ++nodes > 4096) throw new JSONException("Form condition exceeds limits");
        for (String group : new String[]{"all", "any", "not"}) {
            if (!expression.has(group)) continue;
            if (expression.length() != 1) throw new JSONException("Ambiguous condition");
            if (group.equals("not")) {
                Condition child = parse(expression.getJSONObject(group), depth + 1);
                return values -> !child.matches(values);
            }
            JSONArray array = expression.getJSONArray(group);
            if (array.length() == 0 || array.length() > 512) throw new JSONException("Invalid condition group");
            Condition[] children = new Condition[array.length()];
            for (int i = 0; i < children.length; i++) children[i] = parse(array.getJSONObject(i), depth + 1);
            return values -> {
                for (Condition child : children) {
                    boolean match = child.matches(values);
                    if (group.equals("all") && !match) return false;
                    if (group.equals("any") && match) return true;
                }
                return group.equals("all");
            };
        }
        String field = name(expression, "field"), op = expression.getString("op");
        references.add(field);
        if (op.equals("is_empty") || op.equals("is_not_empty")) {
            if (expression.length() != 2) throw new JSONException("Unexpected condition value");
            return values -> op.equals("is_empty") == missing(values.apply(field));
        }
        if (expression.length() != 3 || !expression.has("value")) throw new JSONException("Invalid comparison");
        Object expected = expression.get("value");
        if (op.equals("in") || op.equals("not_in")) {
            if (!(expected instanceof JSONArray)) throw new JSONException("Expected a list of values");
            JSONArray array = (JSONArray) expected;
            if (array.length() == 0 || array.length() > 512) throw new JSONException("Invalid comparison list");
            Object[] choices = new Object[array.length()];
            for (int i = 0; i < choices.length; i++) choices[i] = scalar(array.get(i));
            return values -> {
                Object actual = values.apply(field);
                if (missing(actual)) return false;
                boolean found = false;
                for (Object choice : choices) if (equal(actual, choice)) { found = true; break; }
                return op.equals("in") == found;
            };
        }
        expected = scalar(expected);
        if (!op.equals("eq") && !op.equals("ne")) throw new JSONException("Unknown condition operator: " + op);
        Object comparison = expected;
        // An unanswered parent never silently triggers a value comparison, including ne/not_in.
        return values -> !missing(values.apply(field))
                && (op.equals("eq") == equal(values.apply(field), comparison));
    }

    private static String name(JSONObject json, String key) throws JSONException {
        String name = json.getString(key);
        if (name.isEmpty() || name.length() > 256) throw new JSONException("Invalid field name");
        return name;
    }
    private static Object scalar(Object value) throws JSONException {
        if (!(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean))
            throw new JSONException("Condition needs a non-null scalar");
        return value;
    }
    public static boolean missing(Object value) {
        return value == null || value == JSONObject.NULL
                || value instanceof CharSequence && CascadingLists.isMissing(value.toString());
    }
    private static boolean equal(Object actual, Object expected) {
        if (expected instanceof Boolean) {
            if (actual instanceof Boolean) return actual.equals(expected);
            if (actual instanceof Number) return ((Number) actual).doubleValue() == ((Boolean) expected ? 1 : 0);
            return false;
        }
        if (expected instanceof Number && actual instanceof Number) {
            try { return new BigDecimal(actual.toString()).compareTo(new BigDecimal(expected.toString())) == 0; }
            catch (NumberFormatException invalid) { return false; }
        }
        return expected instanceof String && actual instanceof CharSequence && expected.equals(actual.toString());
    }
}
