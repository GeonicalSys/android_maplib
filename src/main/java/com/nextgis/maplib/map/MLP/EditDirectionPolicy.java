package com.nextgis.maplib.map.MLP;

/** Resolves the vertex that bounds insertion after the currently selected vertex. */
final class EditDirectionPolicy {
    private EditDirectionPolicy() {
    }

    static int nextOpenVertex(int selected, int partStart, int partEndExclusive) {
        if (selected < partStart || selected >= partEndExclusive) {
            return -1;
        }
        int next = selected + 1;
        return next < partEndExclusive ? next : -1;
    }

    static int nextClosedVertex(int selected, int ringStart, int ringEndExclusive) {
        int vertexCount = ringEndExclusive - ringStart;
        if (vertexCount < 2 || selected < ringStart || selected >= ringEndExclusive) {
            return -1;
        }
        int next = selected + 1;
        return next < ringEndExclusive ? next : ringStart;
    }
}
