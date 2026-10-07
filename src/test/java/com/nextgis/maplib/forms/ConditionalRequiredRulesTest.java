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
        for (String definition:new String[]{"{\"schema_version\":2,\"required\":[]}","""
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
}
