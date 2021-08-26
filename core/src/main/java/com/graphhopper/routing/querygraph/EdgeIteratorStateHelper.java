package com.graphhopper.routing.querygraph;

import com.graphhopper.routing.querygraph.VirtualEdgeIterator;
import com.graphhopper.routing.querygraph.VirtualEdgeIteratorState;
import com.graphhopper.util.EdgeIteratorState;

// ORS-GH MOD START - NEW CLASS
// TODO ORS: provide a reason for this change
public class EdgeIteratorStateHelper {

    public static int getOriginalEdge(EdgeIteratorState inst) {
        if (inst instanceof VirtualEdgeIteratorState) {
            return ((VirtualEdgeIteratorState) inst).getOriginalEdge();
        } else if (inst instanceof VirtualEdgeIterator) {
            // MARQ24 the 'detach' impl in the VirtualEdgeIterator will simply
            // return the EdgeState of the current active edge...
            // -> return edges.get(current)
            return getOriginalEdge(((VirtualEdgeIterator) inst).detach(false));
        } else {
            return inst.getEdge();
        }
    }
}
// ORS-GH MOD END
