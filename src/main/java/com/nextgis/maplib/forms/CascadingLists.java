package com.nextgis.maplib.forms;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Declarative, offline NGFP lists. Keys identify options; values are layer attributes. */
public final class CascadingLists {
    public static final String META_KEY = "lisa_form_dependencies";
    private static final int MAX_FIELDS = 512, MAX_ROWS = 100000;

    public static final class Option {
        public final String key, value, label;
        Option(String key, String value, String label) {
            this.key = key; this.value = value; this.label = label;
        }
    }

    private static final class Rule {
        final String field, table, key, value, label;
        final Map<String, String> filters = new LinkedHashMap<>();
        Rule(JSONObject json) throws JSONException {
            field = name(json, "field"); table = name(json, "table");
            key = name(json, "key"); value = name(json, "value"); label = name(json, "label");
            JSONArray conditions = json.getJSONArray("filters");
            for (int i = 0; i < conditions.length(); i++) {
                JSONObject condition = conditions.getJSONObject(i);
                if (filters.put(name(condition, "column"), name(condition, "field")) != null)
                    throw new JSONException("Repeated filter column");
            }
        }
    }

    private final Map<String, List<Map<String, String>>> tables = new LinkedHashMap<>();
    private final Map<String, Rule> rules = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();
    private final Map<String, String> keys = new LinkedHashMap<>(), values = new LinkedHashMap<>();
    private final Map<String, String> original = new LinkedHashMap<>();

    public CascadingLists(JSONObject json) throws JSONException {
        if (json.getInt("schema_version") != 1) throw new JSONException("Unsupported list schema");
        JSONObject data = json.getJSONObject("tables");
        int totalRows = 0;
        for (String table : iterable(data.keys())) {
            JSONArray rows = data.getJSONArray(table);
            totalRows += rows.length();
            if (totalRows > MAX_ROWS) throw new JSONException("Too many list rows");
            List<Map<String, String>> parsed = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                Map<String, String> cells = new LinkedHashMap<>();
                for (String column : iterable(row.keys())) {
                    Object cell = row.get(column);
                    if (!(cell instanceof String)) throw new JSONException("List cells must be strings");
                    cells.put(column, (String) cell);
                }
                parsed.add(Collections.unmodifiableMap(cells));
            }
            tables.put(table, parsed);
        }
        JSONArray fields = json.getJSONArray("fields");
        if (fields.length() == 0 || fields.length() > MAX_FIELDS)
            throw new JSONException("Invalid field count");
        for (int i = 0; i < fields.length(); i++) {
            Rule rule = new Rule(fields.getJSONObject(i));
            if (rules.put(rule.field, rule) != null || !tables.containsKey(rule.table))
                throw new JSONException("Repeated field or unknown table");
        }
        // Kahn's algorithm: no recursion or fixed cascade depth; reject cycles before rendering.
        Set<String> remaining = new LinkedHashSet<>(rules.keySet());
        while (!remaining.isEmpty()) {
            boolean progress = false;
            for (String field : new ArrayList<>(remaining)) {
                Rule rule = rules.get(field);
                if (!rules.keySet().containsAll(rule.filters.values()))
                    throw new JSONException("Unknown parent field");
                if (order.containsAll(rule.filters.values())) {
                    order.add(field); remaining.remove(field); progress = true;
                }
            }
            if (!progress) throw new JSONException("Cyclic list dependencies");
        }
        for (Rule rule : rules.values()) {
            // The same key may occur in several relation rows, but always denotes one value.
            Map<String, Option> identity = new LinkedHashMap<>();
            for (Map<String, String> row : tables.get(rule.table)) {
                String key = cell(row, rule.key), value = cell(row, rule.value), label = cell(row, rule.label);
                if (key.trim().isEmpty() || isMissing(value) || label.trim().isEmpty())
                    throw new JSONException("Empty option or reserved placeholder");
                for (String column : rule.filters.keySet()) cell(row, column);
                Option previous = identity.put(key, new Option(key, value, label));
                if (previous != null && (!previous.value.equals(value) || !previous.label.equals(label)))
                    throw new JSONException("Conflicting option identity");
            }
        }
    }

    public List<String> fields() { return Collections.unmodifiableList(order); }
    public boolean manages(String field) { return rules.containsKey(field); }
    public String value(String field) { return values.get(field); }
    public String key(String field) { return keys.get(field); }
    public Map<String, String> selectionKeys() { return new LinkedHashMap<>(keys); }
    public Map<String, String> originalValues() { return new LinkedHashMap<>(original); }

    public static final class InitialSelection {
        public final Map<String, String> keys, values, labels;
        private InitialSelection(Map<String, String> keys, Map<String, String> values,
                                 Map<String, String> labels) {
            this.keys = Collections.unmodifiableMap(keys);
            this.values = Collections.unmodifiableMap(values);
            this.labels = Collections.unmodifiableMap(labels);
        }
    }

    /** Resolve a style value backwards through ALL parent filters, without changing this session.
     * Distinct valid parent tuples remain distinct choices; never guess an ambiguous parent.
     */
    public List<InitialSelection> initialSelections(String field, String value, boolean ignoreCase) {
        if (!manages(field)) throw new IllegalArgumentException("Unknown list field");
        Set<String> ancestors = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(); pending.add(field);
        while (!pending.isEmpty()) {
            String item = pending.removeFirst();
            if (ancestors.add(item)) pending.addAll(rules.get(item).filters.values());
        }
        List<Map<String, String>> states = new ArrayList<>();
        states.add(new LinkedHashMap<>());
        for (int i = order.size() - 1; i >= 0; i--) {
            String item = order.get(i);
            if (!ancestors.contains(item)) continue;
            Rule rule = rules.get(item);
            Set<Map<String, String>> next = new LinkedHashSet<>();
            for (Map<String, String> state : states) {
                for (Map<String, String> row : tables.get(rule.table)) {
                    String rowValue = row.get(rule.value);
                    if (item.equals(field) && !sameStyleValue(rowValue, value, ignoreCase)) continue;
                    if (state.containsKey(item) && !state.get(item).equals(row.get(rule.key))) continue;
                    Map<String, String> candidate = new LinkedHashMap<>(state);
                    candidate.put(item, row.get(rule.key));
                    boolean matches = true;
                    for (Map.Entry<String, String> filter : rule.filters.entrySet()) {
                        String required = row.get(filter.getKey());
                        String previous = candidate.put(filter.getValue(), required);
                        if (previous != null && !previous.equals(required)) { matches = false; break; }
                    }
                    if (matches) next.add(candidate);
                    if (next.size() > 256) throw new IllegalArgumentException("Too many initial list choices");
                }
            }
            states = new ArrayList<>(next);
        }
        List<InitialSelection> result = new ArrayList<>();
        for (Map<String, String> state : states) {
            Map<String, String> selectedKeys = new LinkedHashMap<>(), selectedValues = new LinkedHashMap<>(),
                    labels = new LinkedHashMap<>();
            for (String item : order) {
                if (!state.containsKey(item)) continue;
                Rule rule = rules.get(item);
                for (Map<String, String> row : tables.get(rule.table)) {
                    if (!state.get(item).equals(row.get(rule.key))) continue;
                    selectedKeys.put(item, state.get(item));
                    selectedValues.put(item, row.get(rule.value));
                    labels.put(item, row.get(rule.label));
                    break;
                }
            }
            result.add(new InitialSelection(selectedKeys, selectedValues, labels));
        }
        return result;
    }

    private static boolean sameStyleValue(String left, String right, boolean ignoreCase) {
        if (left == null || right == null) return Objects.equals(left, right);
        return ignoreCase ? left.trim().equalsIgnoreCase(right.trim()) : left.trim().equals(right.trim());
    }

    public boolean ready(String field) {
        for (String parent : rules.get(field).filters.values()) if (keys.get(parent) == null) return false;
        return true;
    }

    public List<Option> options(String field) {
        Rule rule = rules.get(field);
        if (rule == null || !ready(field)) return Collections.emptyList();
        Map<String, Option> options = new LinkedHashMap<>();
        for (Map<String, String> row : tables.get(rule.table)) {
            boolean matches = true;
            for (Map.Entry<String, String> filter : rule.filters.entrySet()) {
                if (!Objects.equals(row.get(filter.getKey()), keys.get(filter.getValue()))) {
                    matches = false; break;
                }
            }
            if (matches) options.putIfAbsent(row.get(rule.key),
                    new Option(row.get(rule.key), row.get(rule.value), row.get(rule.label)));
        }
        return new ArrayList<>(options.values());
    }

    /** Restore all parents first. Ambiguous text is retained, never guessed or replaced. */
    public void restore(Map<String, String> stored, Map<String, String> savedKeys,
                        Map<String, String> originalValues) {
        keys.clear(); values.clear(); original.clear(); original.putAll(originalValues);
        for (String field : order) {
            String value = stored.get(field);
            if (isMissing(value)) { values.put(field, null); continue; }
            Option match = null;
            int matches = 0;
            for (Option option : options(field)) {
                if (!option.value.equals(value)) continue;
                if (option.key.equals(savedKeys.get(field))) { match = option; matches = 1; break; }
                match = option; matches++;
            }
            values.put(field, value);
            if (matches == 1) keys.put(field, match.key);
        }
    }

    /** An explicit parent selection clears every descendant, including both audit branches. */
    public void select(String field, String key) {
        if (!rules.containsKey(field)) throw new IllegalArgumentException("Unknown list field");
        Option selected = null;
        if (key != null) {
            for (Option option : options(field)) if (option.key.equals(key)) { selected = option; break; }
            if (selected == null) throw new IllegalArgumentException("Option outside parent filters");
        }
        if (selected == null) { keys.remove(field); values.put(field, null); }
        else { keys.put(field, selected.key); values.put(field, selected.value); }
        for (String child : descendants(field)) { keys.remove(child); values.put(child, null); }
    }

    public List<String> invalidFields() {
        List<String> invalid = new ArrayList<>();
        for (String field : order) {
            if (isMissing(values.get(field))) continue; // required is an independent common Save gate.
            boolean current = false;
            for (Option option : options(field))
                if (option.key.equals(keys.get(field)) && option.value.equals(values.get(field))) {
                    current = true; break;
                }
            if (!current && !unchangedHistorical(field)) invalid.add(field);
        }
        return invalid;
    }

    private boolean unchangedHistorical(String field) {
        Set<String> ancestors = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(); pending.add(field);
        while (!pending.isEmpty()) {
            String item = pending.removeFirst();
            if (ancestors.add(item)) pending.addAll(rules.get(item).filters.values());
        }
        for (String ancestor : ancestors)
            if (!original.containsKey(ancestor) || !Objects.equals(original.get(ancestor), values.get(ancestor)))
                return false;
        return true;
    }

    private Set<String> descendants(String field) {
        Set<String> result = new LinkedHashSet<>(); result.add(field);
        for (String item : order)
            for (String parent : rules.get(item).filters.values())
                if (result.contains(parent)) { result.add(item); break; }
        result.remove(field); return result;
    }

    public static boolean isMissing(String value) {
        if (value == null) return true;
        int start = 0, end = value.length();
        while (start < end && isSpace(value.charAt(start))) start++;
        while (end > start && isSpace(value.charAt(end - 1))) end--;
        return start == end || "Нет значения".equalsIgnoreCase(value.substring(start, end));
    }
    private static boolean isSpace(char value) { return Character.isWhitespace(value) || Character.isSpaceChar(value); }
    private static String name(JSONObject json, String key) throws JSONException {
        Object value = json.get(key);
        if (!(value instanceof String) || ((String)value).trim().isEmpty())
            throw new JSONException("Expected nonempty identifier: " + key);
        return (String)value;
    }
    private static String cell(Map<String, String> row, String column) throws JSONException {
        if (!row.containsKey(column)) throw new JSONException("Missing list column: " + column);
        return row.get(column);
    }
    private static <T> Iterable<T> iterable(java.util.Iterator<T> iterator) { return () -> iterator; }
}
