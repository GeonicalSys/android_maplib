package com.nextgis.maplib.map;

import com.nextgis.maplib.util.GeoConstants;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Metadata-only persistent display order; never a geometry or hit-test filter. */
final class LayerDisplayPriority {
    private LayerDisplayPriority() { }

    static boolean isFieldLines(int geometryType, String name) {
        if (geometryType != GeoConstants.GTLineString && geometryType != GeoConstants.GTMultiLineString) return false;
        if (name == null) return false;
        String value = name.trim();
        return "Полевые линейные".equalsIgnoreCase(value) || "field_lines".equalsIgnoreCase(value);
    }

    static boolean hasPriority(int geometryType, String name) {
        return isPoint(geometryType) || isFieldLines(geometryType, name);
    }

    private static boolean isPoint(int geometryType) {
        return geometryType == GeoConstants.GTPoint || geometryType == GeoConstants.GTMultiPoint;
    }

    /** Canonical LayerGroup children are already bottom-to-top; null children identify a leaf. */
    static <T> List<T> bottomToTop(List<T> roots, Function<T, List<T>> children) {
        List<T> ordered = new ArrayList<>();
        appendLeaves(roots, children, ordered);
        return ordered;
    }

    private static <T> void appendLeaves(List<T> models, Function<T, List<T>> children, List<T> ordered) {
        for (T model : models) {
            List<T> nested = children.apply(model);
            if (nested == null) ordered.add(model);
            else appendLeaves(nested, children, ordered);
        }
    }

    /** One actual SDK pass, in its current bottom-to-top order. */
    static final class Pass {
        final String id, source;
        Pass(String id, String source) { this.id = id; this.source = source; }
    }

    /** The renderer identity is supplied by the current style's source owner, never parsed. */
    static final class Group {
        final int geometry;
        final String name, logicalSource, rendererSource;
        Group(int geometry, String name, String logicalSource, String rendererSource) {
            this.geometry = geometry;
            this.name = name;
            this.logicalSource = logicalSource;
            this.rendererSource = rendererSource;
        }
    }

    /**
     * Returns the IDs to move below the tool anchor, or no work when already correct.
     * Logical groups arrive bottom-to-top. Each group's body/style/label passes retain their
     * existing relative order. Aliases reference one group list, so a native alias never duplicates
     * its children. Polygon-label points have no point role of their own.
     */
    static List<String> movesBeforeAnchor(List<Pass> passes, List<Group> logicalOrder, String anchorId) {
        int anchor = -1;
        for (int i = 0; i < passes.size(); i++) if (anchorId.equals(passes.get(i).id)) anchor = i;
        if (anchor < 0) return Collections.emptyList();

        List<List<String>> fieldLines = new ArrayList<>(), points = new ArrayList<>();
        Map<String, List<String>> aliases = new HashMap<>();
        for (Group group : logicalOrder) {
            if (!hasPriority(group.geometry, group.name)) continue;
            List<String> children = new ArrayList<>();
            if (isPoint(group.geometry)) points.add(children); else fieldLines.add(children);
            if (group.logicalSource != null) {
                aliases.put(group.logicalSource, children);
                aliases.put(group.logicalSource + MPLFeaturesUtils.source_polygon_text, children);
            }
            if (group.rendererSource != null) aliases.put(group.rendererSource, children);
        }

        List<String> ordered = new ArrayList<>();
        for (Pass pass : passes) {
            if (pass.id == null) continue;
            if (pass.id.startsWith("track-line-") || pass.id.startsWith("track-inprogress-")) {
                ordered.add(pass.id);
            } else {
                List<String> group = aliases.get(pass.source);
                if (group != null) group.add(pass.id);
            }
        }
        for (List<String> group : fieldLines) ordered.addAll(group);
        for (List<String> group : points) ordered.addAll(group);

        boolean correct = anchor >= ordered.size();
        for (int i = 0; correct && i < ordered.size(); i++)
            correct = ordered.get(i).equals(passes.get(anchor - ordered.size() + i).id);
        return correct ? Collections.emptyList() : Collections.unmodifiableList(ordered);
    }
}
