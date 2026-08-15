/*
 * Project: NextGIS Mobile
 * Purpose: Stream KML/GPX coordinates as independent point records.
 */
package com.nextgis.maplib.util;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/**
 * A small, streaming parser for the deliberately simple KML/GPX import mode.
 * It preserves coordinate order and emits every valid coordinate as one point.
 */
public final class CoordinatePointParser {
    public enum Format {
        KML,
        GPX
    }

    public interface PointConsumer {
        void accept(PointRecord point) throws SAXException;
    }

    public static final class PointRecord {
        private final double mLongitude;
        private final double mLatitude;
        private final Double mElevation;
        private final String mSourceType;
        private final String mName;
        private final String mTime;

        PointRecord(double longitude, double latitude, Double elevation,
                    String sourceType, String name, String time) {
            mLongitude = longitude;
            mLatitude = latitude;
            mElevation = elevation;
            mSourceType = sourceType;
            mName = emptyToNull(name);
            mTime = emptyToNull(time);
        }

        public double getLongitude() {
            return mLongitude;
        }

        public double getLatitude() {
            return mLatitude;
        }

        public Double getElevation() {
            return mElevation;
        }

        public String getSourceType() {
            return mSourceType;
        }

        public String getName() {
            return mName;
        }

        public String getTime() {
            return mTime;
        }
    }

    public static final class ParseResult {
        private final long mPointCount;
        private final long mSkippedCoordinateCount;

        ParseResult(long pointCount, long skippedCoordinateCount) {
            mPointCount = pointCount;
            mSkippedCoordinateCount = skippedCoordinateCount;
        }

        public long getPointCount() {
            return mPointCount;
        }

        public long getSkippedCoordinateCount() {
            return mSkippedCoordinateCount;
        }
    }

    private CoordinatePointParser() {
    }

    public static ParseResult parse(InputStream inputStream, Format format,
                                    PointConsumer consumer)
            throws IOException, SAXException, ParserConfigurationException {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setXIncludeAware(false);
        } catch (UnsupportedOperationException ignored) {
            // Android's SAX implementation does not expose XInclude on every supported API.
        }
        setFeatureIfSupported(factory,
                "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeatureIfSupported(factory,
                "http://xml.org/sax/features/external-general-entities", false);
        setFeatureIfSupported(factory,
                "http://xml.org/sax/features/external-parameter-entities", false);
        setFeatureIfSupported(factory,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        XMLReader reader = factory.newSAXParser().getXMLReader();
        Handler handler = new Handler(format, consumer);
        reader.setContentHandler(handler);
        reader.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("External XML entities are not allowed");
        });
        try {
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        } catch (SAXException ignored) {
            // The input stream below rejects an ASCII DOCTYPE even without this callback.
        }
        reader.parse(new InputSource(new DoctypeRejectingInputStream(inputStream)));
        return new ParseResult(handler.mPointCount, handler.mSkippedCoordinateCount);
    }

    private static void setFeatureIfSupported(SAXParserFactory factory, String feature,
                                              boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException | SAXException ignored) {
            // EntityResolver, LexicalHandler and the guarded stream provide the fallback.
        }
    }

    private static final class Handler extends DefaultHandler implements LexicalHandler {
        private final Format mFormat;
        private final PointConsumer mConsumer;
        private final Deque<String> mKmlGeometryStack = new ArrayDeque<>();
        private final StringBuilder mText = new StringBuilder();
        private final StringBuilder mKmlCoordinateToken = new StringBuilder();

        private long mPointCount;
        private long mSkippedCoordinateCount;
        private int mPlacemarkDepth;
        private String mPlacemarkName;
        private String mCapturedElement;
        private boolean mReadingKmlCoordinates;
        private String mKmlCoordinateSource;
        private String mKmlCoordinateName;
        private GpxPoint mGpxPoint;

        Handler(Format format, PointConsumer consumer) {
            mFormat = format;
            mConsumer = consumer;
        }

        @Override
        public void startElement(String uri, String localName, String qName,
                                 Attributes attributes) throws SAXException {
            String name = elementName(localName, qName);
            if (mFormat == Format.KML) {
                startKmlElement(name);
            } else {
                startGpxElement(name, attributes);
            }
        }

        private void startKmlElement(String name) {
            if ("placemark".equals(name)) {
                if (mPlacemarkDepth == 0) {
                    mPlacemarkName = null;
                }
                mPlacemarkDepth++;
            } else if ("point".equals(name) || "linestring".equals(name)
                    || "polygon".equals(name)) {
                mKmlGeometryStack.push(name);
            } else if ("name".equals(name) && mPlacemarkDepth > 0) {
                beginCapture(name);
            } else if ("coordinates".equals(name)) {
                mReadingKmlCoordinates = true;
                mKmlCoordinateToken.setLength(0);
                mKmlCoordinateSource = kmlSourceType();
                mKmlCoordinateName = mPlacemarkName;
            }
        }

        private void startGpxElement(String name, Attributes attributes) {
            if (isGpxPoint(name)) {
                mGpxPoint = new GpxPoint(name,
                        attributes.getValue("lon"), attributes.getValue("lat"));
            } else if (mGpxPoint != null
                    && ("name".equals(name) || "time".equals(name) || "ele".equals(name))) {
                beginCapture(name);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (mReadingKmlCoordinates) {
                appendKmlCoordinates(ch, start, length);
            } else if (mCapturedElement != null) {
                mText.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            String name = elementName(localName, qName);
            if (mFormat == Format.KML) {
                endKmlElement(name);
            } else {
                endGpxElement(name);
            }
        }

        private void endKmlElement(String name) throws SAXException {
            if ("coordinates".equals(name) && mReadingKmlCoordinates) {
                emitKmlToken();
                mReadingKmlCoordinates = false;
            } else if ("name".equals(name) && "name".equals(mCapturedElement)) {
                mPlacemarkName = normalizedText();
                endCapture();
            } else if ("point".equals(name) || "linestring".equals(name)
                    || "polygon".equals(name)) {
                if (!mKmlGeometryStack.isEmpty()) {
                    mKmlGeometryStack.pop();
                }
            } else if ("placemark".equals(name)) {
                mPlacemarkDepth = Math.max(0, mPlacemarkDepth - 1);
                if (mPlacemarkDepth == 0) {
                    mPlacemarkName = null;
                }
            }
        }

        private void endGpxElement(String name) throws SAXException {
            if (mGpxPoint == null) {
                return;
            }
            if (name.equals(mCapturedElement)) {
                String value = normalizedText();
                if ("name".equals(name)) {
                    mGpxPoint.name = value;
                } else if ("time".equals(name)) {
                    mGpxPoint.time = value;
                } else if ("ele".equals(name)) {
                    mGpxPoint.elevation = parseFiniteDouble(value);
                }
                endCapture();
            }
            if (mGpxPoint.element.equals(name)) {
                Double longitude = parseFiniteDouble(mGpxPoint.longitude);
                Double latitude = parseFiniteDouble(mGpxPoint.latitude);
                if (isValidWgs84(longitude, latitude)) {
                    emit(new PointRecord(longitude, latitude, mGpxPoint.elevation,
                            "gpx_" + mGpxPoint.element, mGpxPoint.name, mGpxPoint.time));
                } else {
                    mSkippedCoordinateCount++;
                }
                mGpxPoint = null;
                endCapture();
            }
        }

        private void appendKmlCoordinates(char[] chars, int start, int length)
                throws SAXException {
            int end = start + length;
            for (int i = start; i < end; i++) {
                char value = chars[i];
                if (Character.isWhitespace(value)) {
                    emitKmlToken();
                } else {
                    mKmlCoordinateToken.append(value);
                }
            }
        }

        private void emitKmlToken() throws SAXException {
            if (mKmlCoordinateToken.length() == 0) {
                return;
            }
            String tuple = mKmlCoordinateToken.toString();
            mKmlCoordinateToken.setLength(0);
            String[] values = tuple.split(",", -1);
            Double longitude = values.length > 0 ? parseFiniteDouble(values[0]) : null;
            Double latitude = values.length > 1 ? parseFiniteDouble(values[1]) : null;
            Double elevation = values.length > 2 ? parseFiniteDouble(values[2]) : null;
            if (isValidWgs84(longitude, latitude)) {
                emit(new PointRecord(longitude, latitude, elevation,
                        mKmlCoordinateSource, mKmlCoordinateName, null));
            } else {
                mSkippedCoordinateCount++;
            }
        }

        private void emit(PointRecord point) throws SAXException {
            mConsumer.accept(point);
            mPointCount++;
        }

        private String kmlSourceType() {
            String geometry = mKmlGeometryStack.peek();
            return geometry == null ? "kml" : "kml_" + geometry;
        }

        private void beginCapture(String element) {
            mCapturedElement = element;
            mText.setLength(0);
        }

        private void endCapture() {
            mCapturedElement = null;
            mText.setLength(0);
        }

        private String normalizedText() {
            return emptyToNull(mText.toString());
        }

        @Override
        public void startDTD(String name, String publicId, String systemId) throws SAXException {
            throw new SAXException("DOCTYPE is not allowed");
        }

        @Override public void endDTD() { }
        @Override public void startEntity(String name) { }
        @Override public void endEntity(String name) { }
        @Override public void startCDATA() { }
        @Override public void endCDATA() { }
        @Override public void comment(char[] ch, int start, int length) { }
    }

    private static final class GpxPoint {
        final String element;
        final String longitude;
        final String latitude;
        Double elevation;
        String name;
        String time;

        GpxPoint(String element, String longitude, String latitude) {
            this.element = element;
            this.longitude = longitude;
            this.latitude = latitude;
        }
    }

    private static boolean isGpxPoint(String element) {
        return "wpt".equals(element) || "rtept".equals(element) || "trkpt".equals(element);
    }

    private static String elementName(String localName, String qName) {
        String name = localName == null || localName.isEmpty() ? qName : localName;
        int colon = name == null ? -1 : name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static Double parseFiniteDouble(String value) {
        try {
            if (value == null) {
                return null;
            }
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isValidWgs84(Double longitude, Double latitude) {
        return longitude != null && latitude != null
                && longitude >= -180.0 && longitude <= 180.0
                && latitude >= -90.0 && latitude <= 90.0;
    }

    /** Rejects a DOCTYPE before the XML parser can expand internal entities. */
    private static final class DoctypeRejectingInputStream extends FilterInputStream {
        private static final byte[] FORBIDDEN = "<!DOCTYPE".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        private int mMatchIndex;

        DoctypeRejectingInputStream(InputStream inputStream) {
            super(inputStream);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                inspect((byte) value);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            for (int i = 0; i < read; i++) {
                inspect(buffer[offset + i]);
            }
            return read;
        }

        private void inspect(byte value) throws IOException {
            byte upper = value >= 'a' && value <= 'z' ? (byte) (value - ('a' - 'A')) : value;
            if (upper == FORBIDDEN[mMatchIndex]) {
                mMatchIndex++;
                if (mMatchIndex == FORBIDDEN.length) {
                    throw new IOException("DOCTYPE is not allowed");
                }
            } else {
                mMatchIndex = upper == FORBIDDEN[0] ? 1 : 0;
            }
        }
    }
}
