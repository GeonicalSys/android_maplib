package com.nextgis.maplib.map;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static com.nextgis.maplib.util.GeoConstants.*;
import static org.junit.Assert.*;

public class LayerDisplayPriorityTest {
    @Test public void fieldLineRoleRequiresBothKnownNameAndLineGeometry() {
        assertTrue(LayerDisplayPriority.isFieldLines(GTLineString, "Полевые линейные"));
        assertTrue(LayerDisplayPriority.isFieldLines(GTMultiLineString, "  ПОЛЕВЫЕ ЛИНЕЙНЫЕ  "));
        assertTrue(LayerDisplayPriority.isFieldLines(GTLineString, "field_lines"));
        assertFalse(LayerDisplayPriority.isFieldLines(GTLineString, "Прочие линии"));
        assertFalse(LayerDisplayPriority.isFieldLines(GTLineString, "Полевые линейные архив"));
        assertFalse(LayerDisplayPriority.isFieldLines(GTPolygon, "Полевые линейные"));
        assertFalse(LayerDisplayPriority.isFieldLines(GTPoint, "field_lines"));
        assertFalse(LayerDisplayPriority.isFieldLines(GTLineString, null));
    }

    @Test public void everyClassicAndStreamedPointOrFieldLineMovesItsWholePassGroup() {
        for (int geometry : new int[]{GTPoint, GTMultiPoint, GTLineString, GTMultiLineString}) {
            for (boolean nativeTiles : new boolean[]{false, true}) {
                String renderer = nativeTiles ? "/field#sqlite-native-17" : "/field";
                List<LayerDisplayPriority.Pass> passes = Arrays.asList(
                        pass("body", renderer), pass("halo-or-dash", renderer),
                        pass("icon-or-rule-child", renderer), pass("label", "/field-text"),
                        pass("track-line-glow", "track-line-source"),
                        pass("track-line-layer", "track-line-source"),
                        pass("selected-dot-layer", "editor"), pass("gps", "location"));
                List<LayerDisplayPriority.Group> groups = Collections.singletonList(
                        group(geometry, "  ПОЛЕВЫЕ ЛИНЕЙНЫЕ  ", "/field", renderer));
                assertEquals("Actual geometry and current source identity choose the role",
                        Arrays.asList("track-line-glow", "track-line-layer", "body", "halo-or-dash", "icon-or-rule-child", "label"),
                        LayerDisplayPriority.movesBeforeAnchor(passes, groups, "selected-dot-layer"));
            }
        }
    }

    @Test public void fieldGroupsThenPointGroupsKeepLogicalAndInternalPassOrder() {
        List<LayerDisplayPriority.Pass> passes = Arrays.asList(
                pass("background", null),
                pass("upper-halo", "/upper#sqlite-native-4"),
                pass("single-line-body", "/single#sqlite-native-2"),
                pass("polygon-inline-anchor", "/polygon#sqlite-native-8"),
                pass("track-line-glow", "track-line-source"),
                pass("ordinary-line", "/ordinary#sqlite-native-9"),
                pass("multi-line-body", "/multi#sqlite-native-3"),
                pass("lower-body", "/lower#sqlite-native-1"),
                pass("track-inprogress-layer", "track-inprogress-source"),
                pass("single-line-label", "/single-text"),
                pass("upper-icon", "/upper#sqlite-native-4"),
                pass("lower-label", "/lower#sqlite-native-1"),
                pass("multi-line-label", "/multi-text"),
                pass("upper-label", "/upper#sqlite-native-4"),
                pass("selected-dot-layer", "editor"), pass("gps", "location"));
        // Logical order is supplied by the model, independently of SDK factory arrival order.
        List<LayerDisplayPriority.Group> groups = Arrays.asList(
                group(GTPoint, "Полевые точечные", "/lower", "/lower#sqlite-native-1"),
                group(GTMultiLineString, "field_lines", "/multi", "/multi#sqlite-native-3"),
                group(GTPolygon, "Полевые линейные", "/polygon", "/polygon#sqlite-native-8"),
                group(GTLineString, "Полевые линейные", "/single", "/single#sqlite-native-2"),
                group(GTMultiPoint, "Other points", "/upper", "/upper#sqlite-native-4"),
                group(GTLineString, "Обычная линия", "/ordinary", "/ordinary#sqlite-native-9"));
        List<String> moves = LayerDisplayPriority.movesBeforeAnchor(passes, groups, "selected-dot-layer");
        assertEquals(Arrays.asList("track-line-glow", "track-inprogress-layer", "multi-line-body", "multi-line-label",
                "single-line-body", "single-line-label", "lower-body", "lower-label", "upper-halo", "upper-icon", "upper-label"), moves);
        assertEquals(Arrays.asList("background", "polygon-inline-anchor", "ordinary-line", "track-line-glow",
                "track-inprogress-layer", "multi-line-body", "multi-line-label", "single-line-body", "single-line-label",
                "lower-body", "lower-label", "upper-halo", "upper-icon", "upper-label", "selected-dot-layer", "gps"),
                ids(apply(passes, moves)));
    }

    @Test public void exactAliasesDoNotAdoptAnotherPathOrRetiredGeneration() {
        List<LayerDisplayPriority.Pass> passes = Arrays.asList(
                pass("retired", "/points#sqlite-native-16"),
                pass("prefix-neighbor", "/points-other#sqlite-native-17"),
                pass("extended-generation", "/points#sqlite-native-17-extra"),
                pass("classic-body", "/points"), pass("native-body", "/points#sqlite-native-17"),
                pass("owned-label", "/points-text"),
                pass("track-line-layer", "track-line-source"), pass("selected-dot-layer", "editor"));
        assertEquals(Arrays.asList("track-line-layer", "classic-body", "native-body", "owned-label"),
                LayerDisplayPriority.movesBeforeAnchor(passes,
                        Collections.singletonList(group(GTPoint, "Points", "/points", "/points#sqlite-native-17")),
                        "selected-dot-layer"));
    }

    @Test public void aGenerationReplacementUsesOnlyTheNewAuthoritativeAlias() {
        List<LayerDisplayPriority.Pass> passes = Arrays.asList(
                pass("old-body", "/field#sqlite-native-1"), pass("new-body", "/field#sqlite-native-2"),
                pass("label", "/field-text"), pass("track-line-layer", "track-line-source"),
                pass("selected-dot-layer", "editor"));
        assertEquals(Arrays.asList("track-line-layer", "old-body", "label"),
                LayerDisplayPriority.movesBeforeAnchor(passes,
                        Collections.singletonList(group(GTLineString, "field_lines", "/field", "/field#sqlite-native-1")),
                        "selected-dot-layer"));
        assertEquals(Arrays.asList("track-line-layer", "new-body", "label"),
                LayerDisplayPriority.movesBeforeAnchor(passes,
                        Collections.singletonList(group(GTLineString, "field_lines", "/field", "/field#sqlite-native-2")),
                        "selected-dot-layer"));
    }

    @Test public void duplicateClassicAndRendererAliasesNeverDuplicateAPass() {
        List<LayerDisplayPriority.Pass> passes = Arrays.asList(pass("point", "/point"),
                pass("label", "/point-text"), pass("track-line-layer", "track-line-source"),
                pass("selected-dot-layer", "editor"));
        assertEquals(Arrays.asList("track-line-layer", "point", "label"),
                LayerDisplayPriority.movesBeforeAnchor(passes,
                        Collections.singletonList(group(GTPoint, "point", "/point", "/point")),
                        "selected-dot-layer"));
    }

    @Test public void polygonLabelPointsAndUnrelatedSymbolsAreNotPointGroups() {
        List<LayerDisplayPriority.Pass> passes = Arrays.asList(
                pass("polygon-body", "/polygon#sqlite-native-9"), pass("polygon-inline-label", "/polygon#sqlite-native-9"),
                pass("polygon-separate-label", "/polygon-text"), pass("decorative-symbol", "other"),
                pass("ordinary-line-label", "/ordinary-text"), pass("track-line-layer", "track-line-source"),
                pass("separator", null), pass("selected-dot-layer", "editor"));
        List<LayerDisplayPriority.Group> groups = Arrays.asList(
                group(GTMultiPolygon, "field_lines", "/polygon", "/polygon#sqlite-native-9"),
                group(GTLineString, "Other lines", "/ordinary", "/ordinary#sqlite-native-1"));
        assertEquals(Collections.singletonList("track-line-layer"),
                LayerDisplayPriority.movesBeforeAnchor(passes, groups, "selected-dot-layer"));
    }

    @Test public void alreadyCorrectOrderDoesNotRequestNativeMutations() {
        List<LayerDisplayPriority.Pass> passes = Arrays.asList(pass("ordinary", "/ordinary"),
                pass("track-line-glow", "track-line-source"), pass("track-line-layer", "track-line-source"),
                pass("field", "/field#sqlite-native-1"), pass("field-label", "/field-text"),
                pass("point", "/point#sqlite-native-2"), pass("point-label", "/point#sqlite-native-2"),
                pass("selected-dot-layer", "editor"), pass("tool-line", "editor-line"), pass("gps", "location"));
        List<LayerDisplayPriority.Group> groups = Arrays.asList(
                group(GTLineString, "field_lines", "/field", "/field#sqlite-native-1"),
                group(GTPoint, "Point", "/point", "/point#sqlite-native-2"));
        assertTrue(LayerDisplayPriority.movesBeforeAnchor(passes, groups, "selected-dot-layer").isEmpty());
    }

    @Test public void oneMovePlanIsIdempotentAndLeavesToolsAndGpsAboveAllData() {
        List<LayerDisplayPriority.Pass> passes = Arrays.asList(pass("point", "/point#sqlite-native-2"),
                pass("point-label", "/point#sqlite-native-2"), pass("track-inprogress-glow", "track-inprogress-source"),
                pass("track-inprogress-layer", "track-inprogress-source"), pass("selected-dot-layer", "editor"),
                pass("tool-line", "editor-line"), pass("gps", "location"));
        List<LayerDisplayPriority.Group> groups = Collections.singletonList(
                group(GTMultiPoint, "Point", "/point", "/point#sqlite-native-2"));
        List<LayerDisplayPriority.Pass> moved = apply(passes,
                LayerDisplayPriority.movesBeforeAnchor(passes, groups, "selected-dot-layer"));
        assertEquals(Arrays.asList("track-inprogress-glow", "track-inprogress-layer", "point", "point-label",
                "selected-dot-layer", "tool-line", "gps"), ids(moved));
        assertTrue(LayerDisplayPriority.movesBeforeAnchor(moved, groups, "selected-dot-layer").isEmpty());
    }

    @Test public void modelReorderChangesPointGroupOrderWithoutChangingPassOrder() {
        LayerDisplayPriority.Group lower = group(GTPoint, "Lower", "/lower", "/lower#sqlite-native-1");
        LayerDisplayPriority.Group upper = group(GTMultiPoint, "Upper", "/upper", "/upper#sqlite-native-2");
        List<LayerDisplayPriority.Pass> passes = Arrays.asList(pass("track-line-layer", "track-line-source"),
                pass("lower-body", "/lower#sqlite-native-1"), pass("lower-label", "/lower#sqlite-native-1"),
                pass("upper-halo", "/upper#sqlite-native-2"), pass("upper-body", "/upper#sqlite-native-2"),
                pass("upper-label", "/upper#sqlite-native-2"), pass("selected-dot-layer", "editor"));
        assertTrue(LayerDisplayPriority.movesBeforeAnchor(passes, Arrays.asList(lower, upper), "selected-dot-layer").isEmpty());
        assertEquals(Arrays.asList("track-line-layer", "upper-halo", "upper-body", "upper-label", "lower-body", "lower-label"),
                LayerDisplayPriority.movesBeforeAnchor(passes, Arrays.asList(upper, lower), "selected-dot-layer"));
    }

    @Test public void absentToolAnchorOrEmptyPrioritySetDoesNotMutatePartialStyles() {
        List<LayerDisplayPriority.Group> point = Collections.singletonList(group(GTPoint, "Point", "/p", "/p#sqlite-native-1"));
        assertTrue(LayerDisplayPriority.movesBeforeAnchor(
                Arrays.asList(pass("point", "/p#sqlite-native-1"), pass("track-line-layer", "track-line-source")),
                point, "selected-dot-layer").isEmpty());
        assertTrue(LayerDisplayPriority.movesBeforeAnchor(
                Arrays.asList(pass("ordinary", "/ordinary"), pass("selected-dot-layer", "editor")),
                Collections.emptyList(), "selected-dot-layer").isEmpty());
    }

    @Test public void canonicalNestedModelOrderWinsOverStandaloneBindArrival() {
        Model line = new Model(group(GTLineString, "field_lines", "/line", "/line#sqlite-native-1"));
        Model multiLine = new Model(group(GTMultiLineString, "field_lines", "/multi-line", "/multi-line#sqlite-native-2"));
        Model point = new Model(group(GTPoint, "Point", "/point", "/point#sqlite-native-3"));
        Model multiPoint = new Model(group(GTMultiPoint, "MultiPoint", "/multi-point", "/multi-point#sqlite-native-4"));
        List<Model> roots = Arrays.asList(line, new Model(Collections.emptyList()),
                new Model(Arrays.asList(multiLine, new Model(Collections.singletonList(point)))), multiPoint);
        List<LayerDisplayPriority.Group> groups = modelGroups(roots);
        assertEquals(Arrays.asList(line.group, multiLine.group, point.group, multiPoint.group), groups);
        // This is the failed native witness: complete point/line groups were reversed after
        // initial standalone binds, even though none of the groups was internally interleaved.
        List<LayerDisplayPriority.Pass> reversedArrival = Arrays.asList(
                pass("multi-point-body", "/multi-point#sqlite-native-4"), pass("multi-point-icon", "/multi-point#sqlite-native-4"),
                pass("point-body", "/point#sqlite-native-3"), pass("point-label", "/point#sqlite-native-3"),
                pass("multi-line-body", "/multi-line#sqlite-native-2"), pass("multi-line-label", "/multi-line-text"),
                pass("line-body", "/line#sqlite-native-1"), pass("line-label", "/line-text"),
                pass("track-line-layer", "track-line-source"), pass("selected-dot-layer", "editor"));
        assertEquals(Arrays.asList("track-line-layer", "line-body", "line-label", "multi-line-body", "multi-line-label",
                        "point-body", "point-label", "multi-point-body", "multi-point-icon"),
                LayerDisplayPriority.movesBeforeAnchor(reversedArrival, groups, "selected-dot-layer"));
    }

    @Test public void nestedModelReorderChangesPriorityWithoutChangingSourceBindings() {
        Model point = new Model(group(GTPoint, "Point", "/point", "/point#sqlite-native-3"));
        Model multiPoint = new Model(group(GTMultiPoint, "MultiPoint", "/multi-point", "/multi-point#sqlite-native-4"));
        List<Model> children = new ArrayList<>(Arrays.asList(point, multiPoint));
        List<Model> roots = Collections.singletonList(new Model(children));
        List<LayerDisplayPriority.Pass> passes = Arrays.asList(pass("track-line-layer", "track-line-source"),
                pass("point-body", "/point#sqlite-native-3"), pass("point-label", "/point#sqlite-native-3"),
                pass("multi-point-body", "/multi-point#sqlite-native-4"), pass("multi-point-label", "/multi-point#sqlite-native-4"),
                pass("selected-dot-layer", "editor"));
        assertTrue(LayerDisplayPriority.movesBeforeAnchor(passes, modelGroups(roots), "selected-dot-layer").isEmpty());
        Collections.reverse(children);
        assertSame(multiPoint.group, modelGroups(roots).get(0));
        assertEquals(Arrays.asList("track-line-layer", "multi-point-body", "multi-point-label", "point-body", "point-label"),
                LayerDisplayPriority.movesBeforeAnchor(passes, modelGroups(roots), "selected-dot-layer"));
    }

    private static final class Model {
        final LayerDisplayPriority.Group group;
        final List<Model> children;
        Model(LayerDisplayPriority.Group group) { this.group = group; children = null; }
        Model(List<Model> children) { group = null; this.children = children; }
    }
    private static List<LayerDisplayPriority.Group> modelGroups(List<Model> roots) {
        List<LayerDisplayPriority.Group> groups = new ArrayList<>();
        for (Model leaf : LayerDisplayPriority.bottomToTop(roots, model -> model.children)) groups.add(leaf.group);
        return groups;
    }

    private static LayerDisplayPriority.Pass pass(String id, String source) { return new LayerDisplayPriority.Pass(id, source); }
    private static LayerDisplayPriority.Group group(int geometry, String name, String logical, String renderer) {
        return new LayerDisplayPriority.Group(geometry, name, logical, renderer);
    }
    private static List<String> ids(List<LayerDisplayPriority.Pass> passes) {
        List<String> result = new ArrayList<>();
        for (LayerDisplayPriority.Pass pass : passes) result.add(pass.id);
        return result;
    }
    /** SDK remove/add-below semantics, with independent literal expected orders in each test. */
    private static List<LayerDisplayPriority.Pass> apply(List<LayerDisplayPriority.Pass> original, List<String> moves) {
        List<LayerDisplayPriority.Pass> result = new ArrayList<>(original);
        for (String id : moves) {
            LayerDisplayPriority.Pass selected = null, anchor = null;
            for (LayerDisplayPriority.Pass pass : result) {
                if (pass.id.equals(id)) selected = pass;
                if (pass.id.equals("selected-dot-layer")) anchor = pass;
            }
            assertNotNull(selected); assertNotNull(anchor);
            result.remove(selected); result.add(result.indexOf(anchor), selected);
        }
        return result;
    }
}
