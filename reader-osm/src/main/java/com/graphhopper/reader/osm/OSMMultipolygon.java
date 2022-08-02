package com.graphhopper.reader.osm;

import com.carrotsearch.hppc.LongArrayList;
import com.graphhopper.reader.ReaderRelation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * TODO
 * <p>
 *
 * @author Héctor Ochoa Ortiz
 */

public class OSMMultipolygon {
    private HashMap<Long, String> memberList = new HashMap<>();

    private HashMap<Integer, List<Long>> ringValues = new HashMap<>();

    private Status status;

    OSMMultipolygon(List<ReaderRelation.Member> memberList) {
        for (ReaderRelation.Member member : memberList) {
            if (member.getType() == ReaderRelation.Member.WAY)
                this.memberList.put(member.getRef(), member.getRole());
        }
        this.status = Status.INIT;
    }

    public void process(OSMReader osmReader) {
        // Derived from https://wiki.openstreetmap.org/wiki/Relation:multipolygon/Algorithm
        ringAssigment(osmReader);
        ringGrouping(osmReader);
        mpCreation(osmReader);
    }

    private void ringAssigment(OSMReader osmReader) {
        /// Ring assingment
        //// RA-1
        int currentRingCount = 0;
        ringValues.put(null, new ArrayList<>(memberList.keySet())); // null means unassigned way

        //// RA-2
        Long m = ringValues.get(null).get(0);
        ringValues.get(null).remove(0);
        ringValues.put(currentRingCount, new ArrayList<Long>());
        ringValues.get(currentRingCount).add(m);

        //// RA-3
        osmReader.getOsmWayIdSet(); // Get way nodes
        LongArrayList osmNodeIds = new LongArrayList();
        //LongArrayList allOsmNodes = ringValues.get(currentRingCount).get(0).getNodes();
        /*
        If the current ring is closed (first node id == last node id):

        If the current ring is not a valid geometry (i.e. self-intersecting):

            Use backtracking to try other options of building this ring. If no other options exist, ring assignment has failed.

        If the current ring is a valid geometry,

            If there are any unassigned ways left,

                increase ring counter and go to RA-2.

            If there are no unassigned ways left

                ring assignment has succeeded
         */

        //// RA-4
    }

    private void ringGrouping(OSMReader osmReader) {
        //TODO
    }

    private void mpCreation(OSMReader osmReader) {
        //TODO
    }


    static enum Status {
        INIT,
        RA, // Rings assigned
        RG, // Rings grouped
        MC; // (GIS) Multipolygon created
    }

    enum Type {
        UNSUPPORTED, HIGHWAY;

        public static Type getRestrictionType(ReaderRelation relation) {
            return (relation.hasTag("highway")) ? HIGHWAY : UNSUPPORTED;
        }
    }
}
