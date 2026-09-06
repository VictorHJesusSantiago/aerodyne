package io.aerodyne.api.persistence;

import io.aerodyne.geo.GeoPoint;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

import java.util.List;

/**
 * Converts between the engine's {@link GeoPoint} and the JTS geometries the database stores.
 *
 * <p>The axis order is the trap. A {@code GeoPoint} is latitude then longitude, because that is
 * how a position is written and spoken in aviation; a JTS coordinate is x then y, which for
 * geographic data means <em>longitude then latitude</em>. Every conversion between the two
 * happens here, so that the swap is written down once instead of being remembered everywhere.
 */
public final class Positions {

    /** WGS-84, the datum ARINC 424 publishes on and the one PostGIS knows as 4326. */
    public static final int SRID = 4326;

    private static final GeometryFactory FACTORY =
            new GeometryFactory(new PrecisionModel(), SRID);

    private Positions() {
    }

    /** A point for the database: x is longitude, y is latitude. */
    public static Point toPoint(GeoPoint position) {
        return FACTORY.createPoint(new Coordinate(position.longitude(), position.latitude()));
    }

    /** A point from the database, read back with the axes put the right way round. */
    public static GeoPoint toGeoPoint(Point point) {
        return GeoPoint.of(point.getY(), point.getX());
    }

    /** A route as a linestring, for map display and for spatial queries against it. */
    public static LineString toLineString(List<GeoPoint> path) {
        Coordinate[] coordinates = path.stream()
                .map(p -> new Coordinate(p.longitude(), p.latitude()))
                .toArray(Coordinate[]::new);
        return FACTORY.createLineString(coordinates);
    }

    public static List<GeoPoint> toPath(LineString line) {
        return java.util.Arrays.stream(line.getCoordinates())
                .map(c -> GeoPoint.of(c.y, c.x))
                .toList();
    }
}
