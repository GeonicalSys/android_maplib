package com.nextgis.maplib.forms;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

public class CascadingListsTest {
    @Test public void styleValueResolvesAllParentsByKeysWithoutChangingSession() throws Exception {
        CascadingLists model = new CascadingLists(definition());
        List<CascadingLists.InitialSelection> choices = model.initialSelections("employee1", " bOb ", true);
        assertEquals(1, choices.size());
        CascadingLists.InitialSelection selected = choices.get(0);
        assertEquals(Map.of("contractor", "c2", "position1", "p1", "employee1", "e2"), selected.keys);
        assertEquals("Company B", selected.values.get("contractor"));
        assertEquals("Driver", selected.values.get("position1"));
        assertEquals("Bob", selected.values.get("employee1"));
        assertNull(model.value("employee1"));
        model.restore(selected.values, selected.keys, Collections.emptyMap());
        assertTrue(model.invalidFields().isEmpty());
        assertNull(model.value("employee2"));
        model.select("contractor", "c1");
        assertNull(model.value("employee1"));
    }

    @Test public void ambiguousStyleValueOffersBothParentTuplesInsteadOfPickingFirst() throws Exception {
        CascadingLists model = new CascadingLists(definition());
        List<CascadingLists.InitialSelection> choices = model.initialSelections("position1", "Driver", false);
        assertEquals(2, choices.size());
        assertEquals(List.of("Company A", "Company B"), choices.stream()
                .map(choice -> choice.values.get("contractor")).collect(Collectors.toList()));
        assertTrue(model.initialSelections("employee1", "deleted type", false).isEmpty());
    }

    @Test public void backwardsResolutionRejectsInconsistentSharedAncestors() throws Exception {
        JSONObject json = definition();
        json.getJSONObject("tables").getJSONArray("employees").put(new JSONObject()
                .put("id", "e4").put("contractor_id", "missing").put("position_id", "p1")
                .put("position", "Driver").put("name", "Invalid"));
        assertTrue(new CascadingLists(json).initialSelections("employee1", "Invalid", false).isEmpty());
    }
    private static JSONObject rule(String field, String table, String key, String value,
                                    String... parents) throws Exception {
        JSONArray filters = new JSONArray();
        for (int i = 0; i < parents.length; i += 2)
            filters.put(new JSONObject().put("column", parents[i]).put("field", parents[i+1]));
        return new JSONObject().put("field", field).put("table", table).put("key", key)
                .put("value", value).put("label", value).put("filters", filters);
    }
    private static JSONObject definition() throws Exception {
        JSONObject json = new JSONObject("""
                {"schema_version":1,"tables":{
                  "contractors":[{"id":"c1","name":"Company A"},{"id":"c2","name":"Company B"}],
                  "employees":[
                    {"id":"e1","contractor_id":"c1","position_id":"p1","position":"Driver","name":"Alex"},
                    {"id":"e2","contractor_id":"c2","position_id":"p1","position":"Driver","name":"Bob"},
                    {"id":"e3","contractor_id":"c1","position_id":"p2","position":"Master","name":"Chris"}
                  ]},"fields":[]}
                """);
        JSONArray fields = json.getJSONArray("fields");
        // Deliberately out of dependency order: bindings are independent of layout and tab order.
        for (int i = 1; i <= 2; i++) {
            fields.put(rule("employee"+i, "employees", "id", "name",
                    "contractor_id", "contractor", "position_id", "position"+i));
            fields.put(rule("position"+i, "employees", "position_id", "position", "contractor_id", "contractor"));
        }
        fields.put(rule("contractor", "contractors", "id", "name"));
        return json;
    }
    private static List<String> keys(CascadingLists model, String field) {
        return model.options(field).stream().map(x -> x.key).collect(Collectors.toList());
    }
    @Test public void newFormHasNoImplicitFirstSelection() throws Exception {
        CascadingLists model = new CascadingLists(definition());
        model.restore(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        assertNull(model.value("contractor"));
        assertTrue(model.options("position1").isEmpty());
        assertFalse(model.ready("employee1"));
        assertTrue(model.invalidFields().isEmpty());
    }
    @Test public void bothParentsFilterEmployeeAndPositionsAreDistinct() throws Exception {
        CascadingLists model = new CascadingLists(definition());
        model.select("contractor", "c1"); model.select("position1", "p1");
        assertEquals(List.of("p1", "p2"), keys(model, "position1"));
        assertEquals(List.of("e1"), keys(model, "employee1"));
        model.select("contractor", "c2"); model.select("position1", "p1");
        assertEquals(List.of("e2"), keys(model, "employee1"));
        assertThrows(IllegalArgumentException.class, () -> model.select("employee1", "e1"));
    }
    @Test public void parentChangeClearsBothBranchesEvenForSamePosition() throws Exception {
        CascadingLists model = new CascadingLists(definition());
        model.select("contractor", "c1");
        for (int i = 1; i <= 2; i++) { model.select("position"+i, "p1"); model.select("employee"+i, "e1"); }
        model.select("contractor", "c2");
        for (int i = 1; i <= 2; i++) { assertNull(model.value("position"+i)); assertNull(model.value("employee"+i)); }
    }
    @Test public void positionChangeOnlyClearsOwnEmployee() throws Exception {
        CascadingLists model = new CascadingLists(definition()); model.select("contractor", "c1");
        for (int i = 1; i <= 2; i++) { model.select("position"+i, "p1"); model.select("employee"+i, "e1"); }
        model.select("position1", "p2");
        assertNull(model.value("employee1")); assertEquals("Alex", model.value("employee2"));
    }
    @Test public void chainHasNoTwoOrThreeLevelLimit() throws Exception {
        JSONObject json = definition(); JSONArray fields = json.getJSONArray("fields");
        for (int i = 0; i < 80; i++) fields.put(rule("level"+i, "employees", "id", "name",
                "id", i == 0 ? "employee1" : "level"+(i-1)));
        CascadingLists model = new CascadingLists(json);
        model.select("contractor", "c1"); model.select("position1", "p1"); model.select("employee1", "e1");
        for (int i = 0; i < 80; i++) model.select("level"+i, "e1");
        assertEquals("Alex", model.value("level79"));
        model.select("contractor", "c2"); assertNull(model.value("level79"));
    }
    @Test public void restoreResolvesParentsBeforeChildren() throws Exception {
        CascadingLists model = new CascadingLists(definition());
        model.restore(Map.of("contractor","Company B", "position1","Driver", "employee1","Bob"),
                Collections.emptyMap(), Collections.emptyMap());
        assertEquals("e2", model.key("employee1")); assertTrue(model.invalidFields().isEmpty());
    }
    @Test public void wrongNewEmployeeCannotBeSaved() throws Exception {
        CascadingLists model = new CascadingLists(definition());
        model.restore(Map.of("contractor","Company A", "position1","Driver", "employee1","Bob"),
                Collections.emptyMap(), Collections.emptyMap());
        assertEquals(List.of("employee1"), model.invalidFields());
        assertEquals("Bob", model.value("employee1")); // retain user input until explicit correction.
    }
    @Test public void historicalTupleSurvivesDictionaryRemovalButCannotMoveToAnotherParent() throws Exception {
        CascadingLists model = new CascadingLists(definition());
        Map<String,String> old = Map.of("contractor","Old company", "position1","Retired role", "employee1","Old employee");
        model.restore(old, Collections.emptyMap(), old);
        assertTrue(model.invalidFields().isEmpty()); assertEquals("Old employee", model.value("employee1"));
        model.select("contractor", "c1"); assertNull(model.value("employee1"));
        model.restore(Map.of("contractor","Company A", "position1","Driver", "employee1","Old employee"),
                Collections.emptyMap(), old);
        assertEquals(List.of("employee1"), model.invalidFields());
    }
    @Test public void duplicateTextNeedsSavedIdentityForNewDraft() throws Exception {
        JSONObject json = definition();
        json.getJSONObject("tables").getJSONArray("employees").put(new JSONObject(
                "{\"id\":\"e4\",\"contractor_id\":\"c1\",\"position_id\":\"p1\",\"position\":\"Driver\",\"name\":\"Alex\"}"));
        CascadingLists model = new CascadingLists(json);
        Map<String,String> input = Map.of("contractor","Company A", "position1","Driver", "employee1","Alex");
        model.restore(input, Collections.emptyMap(), Collections.emptyMap());
        assertNull(model.key("employee1")); assertEquals(List.of("employee1"), model.invalidFields());
        model.restore(input, Map.of("employee1","e4"), Collections.emptyMap());
        assertEquals("e4", model.key("employee1")); assertTrue(model.invalidFields().isEmpty());
    }
    @Test public void cycleAndUnknownParentsAreRejected() throws Exception {
        JSONObject json = definition();
        json.getJSONArray("fields").getJSONObject(4).getJSONArray("filters")
                .put(new JSONObject().put("column","id").put("field","employee1"));
        assertThrows(JSONException.class, () -> new CascadingLists(json));
        json.getJSONArray("fields").getJSONObject(4).getJSONArray("filters").getJSONObject(0).put("field","missing");
        assertThrows(JSONException.class, () -> new CascadingLists(json));
    }
    @Test public void missingColumnsConflictingIdsAndUnknownSchemasAreRejected() throws Exception {
        JSONObject json = definition(); json.getJSONObject("tables").getJSONArray("employees").getJSONObject(0).remove("contractor_id");
        assertThrows(JSONException.class, () -> new CascadingLists(json));
        JSONObject conflict = definition(); conflict.getJSONObject("tables").getJSONArray("contractors")
                .put(new JSONObject().put("id","c1").put("name","Different company"));
        assertThrows(JSONException.class, () -> new CascadingLists(conflict));
        JSONObject future = definition().put("schema_version",2);
        assertThrows(JSONException.class, () -> new CascadingLists(future));
    }
    @Test public void placeholderIsMissingButNotApplicableIsARealChoice() throws Exception {
        assertTrue(CascadingLists.isMissing("  НЕТ ЗНАЧЕНИЯ  "));
        assertTrue(CascadingLists.isMissing("\u00a0НЕТ ЗНАЧЕНИЯ\u2003"));
        assertFalse(CascadingLists.isMissing("не применимо"));
        JSONObject json = definition(); json.getJSONObject("tables").getJSONArray("contractors")
                .put(new JSONObject().put("id","na").put("name","не применимо"));
        CascadingLists model = new CascadingLists(json); model.select("contractor", "na");
        assertEquals("не применимо", model.value("contractor"));
    }
}
