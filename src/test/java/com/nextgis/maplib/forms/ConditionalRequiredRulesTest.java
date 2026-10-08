package com.nextgis.maplib.forms;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

public class ConditionalRequiredRulesTest {
    private static ConditionalRequiredRules rules(String condition) throws Exception {
        return new ConditionalRequiredRules(new JSONObject("{\"schema_version\":1,\"required\":[{\"field\":\"comment\",\"when\":"+condition+"}]}"));
    }
    private static boolean matches(String condition,Object value) throws Exception {
        return rules(condition).required(field -> value).contains("comment");
    }
    @Test public void uncheckedBooleanOrNumericCheckboxRequiresComment() throws Exception {
        String condition="{\"field\":\"check\",\"op\":\"eq\",\"value\":false}";
        assertTrue(matches(condition,false));assertTrue(matches(condition,0));assertTrue(matches(condition,0L));
        assertFalse(matches(condition,true));assertFalse(matches(condition,1));assertFalse(matches(condition,null));
        assertFalse(matches(condition,"false"));
    }
    @Test public void nestedAllAnyAndNotUseSeveralFields() throws Exception {
        ConditionalRequiredRules rules=rules("""
            {"all":[{"field":"kind","op":"in","value":["audit","inspection"]},
              {"any":[{"field":"check","op":"eq","value":false},
                {"not":{"field":"reason","op":"is_empty"}}]}]}
            """);
        Map<String,Object> values=new HashMap<>();values.put("kind","audit");values.put("check",1);
        assertTrue(rules.required(values::get).isEmpty());values.put("reason","risk");
        assertEquals(Set.of("comment"),rules.required(values::get));
        assertEquals(Set.of("kind","check","reason"),rules.references());
        values.put("kind","other");assertTrue(rules.required(values::get).isEmpty());
    }
    @Test public void missingAndPlaceholderAreEmptyButNotApplicableZeroAndFalseAreValues() throws Exception {
        String condition="{\"field\":\"parent\",\"op\":\"is_empty\"}";
        for (Object value:new Object[]{null,JSONObject.NULL,"", " \u00a0 ", "НЕТ ЗНАЧЕНИЯ"}) assertTrue(matches(condition,value));
        for (Object value:new Object[]{false,0,"не применимо"}) assertFalse(matches(condition,value));
    }
    @Test public void negatedValueComparisonsDoNotMatchUnansweredParent() throws Exception {
        assertFalse(matches("{\"field\":\"p\",\"op\":\"ne\",\"value\":\"ok\"}",null));
        assertFalse(matches("{\"field\":\"p\",\"op\":\"not_in\",\"value\":[\"ok\"]}","Нет значения"));
        assertTrue(matches("{\"field\":\"p\",\"op\":\"ne\",\"value\":\"ok\"}","risk"));
    }
    @Test public void numbersCompareNumericallyWithoutConvertingText() throws Exception {
        String condition="{\"field\":\"p\",\"op\":\"eq\",\"value\":1.0}";
        assertTrue(matches(condition,1));assertTrue(matches(condition,1L));assertFalse(matches(condition,"1"));
        assertFalse(matches(condition,Double.NaN));
    }
    @Test public void invalidRulesAndUnboundedNestingAreRejected() throws Exception {
        for (String condition:new String[]{"{\"field\":\"p\",\"op\":\"eval\",\"value\":1}",
                "{\"field\":\"p\",\"op\":\"eq\",\"value\":null}","{\"all\":[]}",
                "{\"field\":\"p\",\"op\":\"is_empty\",\"value\":0}"}) {
            try { rules(condition);fail("Invalid condition accepted"); } catch (JSONException expected) { }
        }
        String deep="{\"field\":\"p\",\"op\":\"is_empty\"}";
        for (int i=0;i<18;i++) deep="{\"not\":"+deep+"}";
        try { rules(deep);fail("Deep condition accepted"); } catch (JSONException expected) { }
    }
    @Test public void unknownVersionAndDuplicateTargetsAreRejected() throws Exception {
        for (String definition:new String[]{"{\"schema_version\":3,\"required\":[]}","""
            {"schema_version":1,"required":[
              {"field":"c","when":{"field":"p","op":"is_empty"}},
              {"field":"c","when":{"field":"q","op":"is_empty"}}]}
            """}) {
            try { new ConditionalRequiredRules(new JSONObject(definition));fail("Invalid definition accepted"); }
            catch (JSONException expected) { }
        }
    }
    @Test public void readableLabelDoesNotChangeRequirementsOrStoredFields() throws Exception {
        JSONObject json=new JSONObject("{\"schema_version\":1,\"required\":[{\"field\":\"f_1_com\",\"label\":\"Safety comment\",\"when\":{\"field\":\"f_1\",\"op\":\"eq\",\"value\":false}}]}");
        ConditionalRequiredRules rules=new ConditionalRequiredRules(json);
        assertEquals("Safety comment",rules.label("f_1_com","f_1_com"));
        assertEquals("Other",rules.label("other","Other"));
        assertEquals(Set.of("f_1_com"),rules.required(field -> false));
        for (Object invalid:new Object[]{" ",true,JSONObject.NULL}) {
            json.getJSONArray("required").getJSONObject(0).put("label",invalid);
            try {new ConditionalRequiredRules(json);fail("Invalid label accepted");} catch (JSONException expected) { }
        }
    }
    @Test public void versionTwoSharesTypedConditionsForRequiredFieldsAndVisibility() throws Exception {
        ConditionalRequiredRules rules = new ConditionalRequiredRules(new JSONObject("""
            {"schema_version":2,
              "required":[{"field":"comment","when":{"field":"check","op":"eq","value":false}}],
              "visible":[
                {"field":"comment","when":{"field":"check","op":"eq","value":false}},
                {"element":"explanation","when":{"all":[
                  {"field":"status","op":"in","value":["risk","stopped"]},
                  {"field":"owner","op":"is_not_empty"}]}}]}
            """));
        Map<String,Object> values = new HashMap<>(); values.put("check", 1);
        assertEquals(Map.of("comment", false), rules.visibleFields(values::get));
        assertEquals(Map.of("explanation", false), rules.visibleElements(values::get));
        values.put("check", 0); values.put("status", "risk"); values.put("owner", "не применимо");
        assertEquals(Set.of("comment"), rules.required(values::get));
        assertEquals(Map.of("comment", true), rules.visibleFields(values::get));
        assertEquals(Map.of("explanation", true), rules.visibleElements(values::get));
        assertEquals(Set.of("check", "status", "owner"), rules.references());
        assertEquals(Set.of("comment"), rules.visibilityFields());
        assertEquals(Set.of("explanation"), rules.visibilityElements());
        assertEquals("не применимо", values.get("owner"));
    }
    @Test public void visibilityOnlyAndMutualValueReferencesHaveNoRecursiveState() throws Exception {
        ConditionalRequiredRules rules = new ConditionalRequiredRules(new JSONObject("""
            {"schema_version":2,"visible":[
              {"field":"a","when":{"field":"b","op":"eq","value":"yes"}},
              {"field":"b","when":{"field":"a","op":"is_not_empty"}}]}
            """));
        assertTrue(rules.required(field -> null).isEmpty());
        assertEquals(Map.of("a", false, "b", false), rules.visibleFields(field -> null));
        assertEquals(Map.of("a", true, "b", true), rules.visibleFields(field -> "yes"));
        assertTrue(new ConditionalRequiredRules(new JSONObject("{\"schema_version\":1,\"required\":[]}"))
                .visibleFields(field -> null).isEmpty());
    }
    @Test public void ambiguousUnknownAndDuplicateVisibilityRulesAreRejected() throws Exception {
        for (String definition : new String[]{
                "{\"schema_version\":1,\"required\":[],\"visible\":[]}",
                "{\"schema_version\":2,\"visible_if\":[]}",
                "{\"schema_version\":\"2\",\"visible\":[]}",
                "{\"schema_version\":2,\"visible\":null}",
                """
                {"schema_version":2,"visible":[{"field":"c","element":"x","when":{"field":"p","op":"is_empty"}}]}
                """,
                """
                {"schema_version":2,"visible":[{"field":123,"when":{"field":"p","op":"is_empty"}}]}
                """,
                """
                {"schema_version":2,"visible":[{"element":" ","when":{"field":"p","op":"is_empty"}}]}
                """,
                """
                {"schema_version":2,"visible":[{"element":"hint_933","when":{"field":"p","op":"is_empty"}}]}
                """,
                """
                {"schema_version":2,"visible":[
                  {"field":"c","when":{"field":"p","op":"is_empty"}},
                  {"field":"c","when":{"field":"q","op":"is_empty"}}]}
                """}) {
            try { new ConditionalRequiredRules(new JSONObject(definition)); fail("Invalid visibility accepted: " + definition); }
            catch (JSONException expected) { }
        }
    }
    @Test public void limitsApplyToBothRuleCollectionsAndUtf8Bytes() throws Exception {
        JSONObject definition = new JSONObject("{\"schema_version\":2,\"required\":[],\"visible\":[]}");
        for (int i=0; i<513; i++) definition.getJSONArray(i%2==0 ? "required" : "visible").put(new JSONObject()
                .put("field", "field"+i).put("when", new JSONObject().put("field","p").put("op","is_empty")));
        try { new ConditionalRequiredRules(definition); fail("Total target limit ignored"); } catch (JSONException expected) { }
        definition = new JSONObject("{\"schema_version\":2,\"required\":[]}");
        for (int i=0; i<280; i++) definition.getJSONArray("required").put(new JSONObject().put("field","field"+i)
                .put("label", "Я".repeat(500)).put("when", new JSONObject().put("field","p").put("op","is_empty")));
        assertTrue(definition.toString().length() < ConditionalRequiredRules.MAX_BYTES);
        try { new ConditionalRequiredRules(definition); fail("UTF-8 byte limit ignored"); } catch (JSONException expected) { }
    }
}
