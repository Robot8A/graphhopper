package com.graphhopper.util;

import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.NodeAccess;
import us.dustinj.timezonemap.TimeZoneMap;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * @author Andrzej Oles
 */
public class DateTimeHelper {
    private final NodeAccess nodeAccess;
    private final TimeZoneMap timeZoneMap;

    public DateTimeHelper(GraphHopperStorage graph) {
        this.nodeAccess = graph.getNodeAccess();
        this.timeZoneMap = graph.getTimeZoneMap();
    }

    public ZonedDateTime getZonedDateTime(EdgeIteratorState iter, long time) {
        int node = iter.getBaseNode();
        double lat = nodeAccess.getLat(node);
        double lon = nodeAccess.getLon(node);
        String timeZoneId = timeZoneMap.getOverlappingTimeZone(lat, lon).getZoneId();
        ZoneId edgeZoneId = ZoneId.of(timeZoneId);
        Instant edgeEnterTime = Instant.ofEpochMilli(time);
        return ZonedDateTime.ofInstant(edgeEnterTime, edgeZoneId);
    }

    public ZonedDateTime getZonedDateTime(double lat, double lon, String time) {
        // if the query has no departure or arrival, time will be empty, so we default to now.
        // TODO: move this to the "outside", i.e. resolve in query parsing
        if (time == "") {
            time = LocalDateTime.now().toString();
        }
        LocalDateTime localDateTime = LocalDateTime.parse(time);
        String timeZoneId = timeZoneMap.getOverlappingTimeZone(lat, lon).getZoneId();
        return localDateTime.atZone(ZoneId.of(timeZoneId));
    }

    public String getZoneId(double lat, double lon) {
        return timeZoneMap.getOverlappingTimeZone(lat, lon).getZoneId();
    }
}
