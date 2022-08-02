/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.storage;

/**
 * TODO
 * <p>
 *
 * @author Héctor Ochoa Ortiz
 */
public class OSMMultipolygonExtension implements GraphExtension {
    @Override
    public boolean isRequireNodeField() {
        return false; // no additional field at the graphs node storage is required
    }

    @Override
    public boolean isRequireEdgeField() {
        return false; // no additional field at the graphs edge storage is required
    }

    /**
     * @return the default field value which will be set for default when creating nodes
     */
    @Override
    public int getDefaultNodeFieldValue() {
        return 0; // TODO
    }

    /**
     * @return the default field value which will be set for default when creating edges
     */
    @Override
    public int getDefaultEdgeFieldValue() {
        return 0; // TODO
    }

    /**
     * initializes the extended storage by giving the base graph
     */
    @Override
    public void init(Graph graph, Directory dir) {
        // TODO
    }

    @Override
    public void setSegmentSize(int bytes) {
        // TODO
    }

    @Override
    public GraphExtension copyTo(GraphExtension extStorage) {
        return null; // TODO
    }

    @Override
    public boolean loadExisting() {
        return false; // TODO
    }

    @Override
    public GraphExtension create(long byteCount) {
        return null; // TODO
    }

    @Override
    public void flush() {
        // TODO
    }

    @Override
    public void close() {
        // TODO
    }

    @Override
    public boolean isClosed() {
        return false; // TODO
    }

    @Override
    public long getCapacity() {
        return 0; // TODO
    }
}

