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
package com.graphhopper;

import com.graphhopper.config.CHProfile;
import com.graphhopper.config.LMProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.reader.dem.*;
import com.graphhopper.reader.osm.OSMReader;
import com.graphhopper.reader.osm.conditional.DateRangeParser;
import com.graphhopper.routing.*;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.routing.calt.CaltPreparationHandler;
import com.graphhopper.routing.ch.CHPreparationHandler;
import com.graphhopper.routing.ch.PrepareContractionHierarchies;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.lm.LMConfig;
import com.graphhopper.routing.lm.LMPreparationHandler;
import com.graphhopper.routing.lm.LandmarkStorage;
import com.graphhopper.routing.lm.PrepareLandmarks;
import com.graphhopper.routing.subnetwork.PrepareRoutingSubnetworks;
import com.graphhopper.routing.subnetwork.PrepareRoutingSubnetworks.PrepareJob;
import com.graphhopper.routing.util.*;
import com.graphhopper.routing.util.parsers.DefaultTagParserFactory;
import com.graphhopper.routing.util.parsers.TagParserFactory;
import com.graphhopper.routing.util.spatialrules.AbstractSpatialRule;
import com.graphhopper.routing.util.spatialrules.SpatialRuleLookup;
import com.graphhopper.routing.util.spatialrules.SpatialRuleLookupBuilder;
import com.graphhopper.routing.weighting.*;
import com.graphhopper.routing.weighting.custom.CustomProfile;
import com.graphhopper.routing.weighting.custom.CustomWeighting;
import com.graphhopper.storage.*;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.LocationIndexTree;
import com.graphhopper.util.*;
import com.graphhopper.util.Parameters.CH;
import com.graphhopper.util.Parameters.Landmark;
import com.graphhopper.util.Parameters.Routing;
import com.graphhopper.util.details.PathDetailsBuilderFactory;
import com.graphhopper.util.exceptions.PointNotFoundException;
import com.graphhopper.util.shapes.BBox;
import com.graphhopper.util.shapes.GHPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.dustinj.timezonemap.TimeZoneMap;
import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.locks.Lock;

import static com.graphhopper.routing.weighting.Weighting.INFINITE_U_TURN_COSTS;
import static com.graphhopper.util.Helper.*;
import static com.graphhopper.util.Parameters.Algorithms.*;

/**
 * Easy to use access point to configure import and (offline) routing.
 *
 * @author Peter Karich
 * @see GraphHopperAPI
 */
public class GraphHopper implements GraphHopperAPI {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Map<String, Profile> profilesByName = new LinkedHashMap<>();
    private final String fileLockName = "gh.lock";
    // utils
    private final TranslationMap trMap = new TranslationMap().doImport();
    boolean removeZipped = true;
    // for graph:
    private GraphHopperStorage ghStorage;
    private final EncodingManager.Builder emBuilder = new EncodingManager.Builder();
    private EncodingManager encodingManager;
    private int defaultSegmentSize = -1;
    private String ghLocation = "";
    private DAType dataAccessType = DAType.RAM_STORE;
    private boolean sortGraph = false;
    private boolean elevation = false;
    private LockFactory lockFactory = new NativeFSLockFactory();
    private boolean allowWrites = true;
    private boolean fullyLoaded = false;
    private boolean smoothElevation = false;
    private double longEdgeSamplingDistance = Double.MAX_VALUE;
    // for routing
    private final RouterConfig routerConfig = new RouterConfig();
    // for index
    private LocationIndex locationIndex;
    private int preciseIndexResolution = 300;
    private int maxRegionSearch = 4;
    // for prepare
    private int minNetworkSize = 200;
    // for LM
    private final JsonFeatureCollection landmarkSplittingFeatureCollection;

    // preparation handlers
    private final LMPreparationHandler lmPreparationHandler = new LMPreparationHandler();
    private final CHPreparationHandler chPreparationHandler = new CHPreparationHandler();
    // ORS-GH MOD START - additional field to support CALT routing algorithm
    private final CaltPreparationHandler caltPreparationHandler = new CaltPreparationHandler();
    // ORS-GH MOD END

    // for data reader
    private String osmFile;
    private double dataReaderWayPointMaxDistance = 1;
    private int dataReaderWorkerThreads = 2;
    private ElevationProvider eleProvider = ElevationProvider.NOOP;
    private FlagEncoderFactory flagEncoderFactory = new DefaultFlagEncoderFactory();
    private EncodedValueFactory encodedValueFactory = new DefaultEncodedValueFactory();
    private TagParserFactory tagParserFactory = new DefaultTagParserFactory();
    private PathDetailsBuilderFactory pathBuilderFactory = new PathDetailsBuilderFactory();

    // ORS-GH MOD START
    protected EdgeFilterFactory edgeFilterFactory = EdgeFilterFactory.DEFAULT;
    protected PathProcessorFactory pathProcessorFactory = PathProcessorFactory.DEFAULT;
    protected WeightingFactory weightingFactory;
    protected GraphStorageFactory graphStorageFactory;

    public void setEdgeFilterFactory(EdgeFilterFactory newFactory) {
        this.edgeFilterFactory = newFactory;
    }

    public void setPathProcessorFactory(PathProcessorFactory newFactory) {
        this.pathProcessorFactory = newFactory;
    }

    public void setWeightingFactory(WeightingFactory weightingFactory) {
        this.weightingFactory = weightingFactory;
    }

    public void setGraphStorageFactory(GraphStorageFactory graphStorageFactory) {
        this.graphStorageFactory = graphStorageFactory;
    }
    // ORS-GH MOD END

    public GraphHopper() {
        this(null);
    }

    public GraphHopper(JsonFeatureCollection landmarkSplittingFeatureCollection) {
        this.landmarkSplittingFeatureCollection = landmarkSplittingFeatureCollection;
    }

    public EncodingManager.Builder getEncodingManagerBuilder() {
        return emBuilder;
    }

    public EncodingManager getEncodingManager() {
        if (encodingManager == null)
            throw new IllegalStateException("EncodingManager not yet build");
        return encodingManager;
    }

    public ElevationProvider getElevationProvider() {
        return eleProvider;
    }

    public GraphHopper setElevationProvider(ElevationProvider eleProvider) {
        if (eleProvider == null || eleProvider == ElevationProvider.NOOP)
            setElevation(false);
        else
            setElevation(true);
        this.eleProvider = eleProvider;
        return this;
    }

    /**
     * Threads for data reading.
     */
    protected int getWorkerThreads() {
        return dataReaderWorkerThreads;
    }

    /**
     * Return maximum distance (in meter) to reduce points via douglas peucker while OSM import.
     */
    protected double getWayPointMaxDistance() {
        return dataReaderWayPointMaxDistance;
    }

    /**
     * This parameter specifies how to reduce points via douglas peucker while OSM import. Higher
     * value means more details, unit is meter. Default is 1. Disable via 0.
     */
    public GraphHopper setWayPointMaxDistance(double wayPointMaxDistance) {
        this.dataReaderWayPointMaxDistance = wayPointMaxDistance;
        return this;
    }

    public GraphHopper setPathDetailsBuilderFactory(PathDetailsBuilderFactory pathBuilderFactory) {
        this.pathBuilderFactory = pathBuilderFactory;
        return this;
    }

    public PathDetailsBuilderFactory getPathDetailsBuilderFactory() {
        return pathBuilderFactory;
    }

    /**
     * Precise location resolution index means also more space (disc/RAM) could be consumed and
     * probably slower query times, which would be e.g. not suitable for Android. The resolution
     * specifies the tile width (in meter).
     */
    public GraphHopper setPreciseIndexResolution(int precision) {
        ensureNotLoaded();
        preciseIndexResolution = precision;
        return this;
    }

    public GraphHopper setMinNetworkSize(int minNetworkSize) {
        ensureNotLoaded();
        this.minNetworkSize = minNetworkSize;
        return this;
    }

    /**
     * Only valid option for in-memory graph and if you e.g. want to disable store on flush for unit
     * tests. Specify storeOnFlush to true if you want that existing data will be loaded FROM disc
     * and all in-memory data will be flushed TO disc after flush is called e.g. while OSM import.
     *
     * @param storeOnFlush true by default
     */
    public GraphHopper setStoreOnFlush(boolean storeOnFlush) {
        ensureNotLoaded();
        if (storeOnFlush)
            dataAccessType = DAType.RAM_STORE;
        else
            dataAccessType = DAType.RAM;
        return this;
    }

    /**
     * Sets the routing profiles that shall be supported by this GraphHopper instance. The (and only the) given profiles
     * can be used for routing without preparation and for CH/LM preparation.
     * <p>
     * Here is an example how to setup two CH profiles and one LM profile (via the Java API)
     *
     * <pre>
     * {@code
     *   hopper.setProfiles(
     *     new Profile("my_car").setVehicle("car").setWeighting("shortest"),
     *     new Profile("your_bike").setVehicle("bike").setWeighting("fastest")
     *   );
     *   hopper.getCHPreparationHandler().setCHProfiles(
     *     new CHProfile("my_car"),
     *     new CHProfile("your_bike")
     *   );
     *   hopper.getLMPreparationHandler().setLMProfiles(
     *     new LMProfile("your_bike")
     *   );
     * }
     * </pre>
     * <p>
     * See also https://github.com/graphhopper/graphhopper/pull/1922.
     *
     * @see CHPreparationHandler#setCHProfiles
     * @see LMPreparationHandler#setLMProfiles
     */
    public GraphHopper setProfiles(Profile... profiles) {
        return setProfiles(Arrays.asList(profiles));
    }

    public GraphHopper setProfiles(List<Profile> profiles) {
        if (!profilesByName.isEmpty())
            throw new IllegalArgumentException("Cannot initialize profiles multiple times");
        if (encodingManager != null)
            throw new IllegalArgumentException("Cannot set profiles after EncodingManager was built");
        for (Profile profile : profiles) {
            Profile previous = this.profilesByName.put(profile.getName(), profile);
            if (previous != null)
                throw new IllegalArgumentException("Profile names must be unique. Duplicate name: '" + profile.getName() + "'");
        }
        return this;
    }

    public List<Profile> getProfiles() {
        return new ArrayList<>(profilesByName.values());
    }

    /**
     * Returns the profile for the given profile name, or null if it does not exist
     */
    public Profile getProfile(String profileName) {
        return profilesByName.get(profileName);
    }

    /**
     * @return true if storing and fetching elevation data is enabled. Default is false
     */
    public boolean hasElevation() {
        return elevation;
    }

    /**
     * Enable storing and fetching elevation data. Default is false
     */
    public GraphHopper setElevation(boolean includeElevation) {
        this.elevation = includeElevation;
        return this;
    }

    // ORS-GH MOD START
    // CALT
    @Deprecated
    public boolean isSimplifyResponse() {
        return getRouterConfig().isSimplifyResponse();
    }

    public boolean isFullyLoaded() {
        return fullyLoaded;
    }
    // ORS-GH MOD END

    /**
     * Sets the distance distance between elevation samples on long edges
     */
    public GraphHopper setLongEdgeSamplingDistance(double longEdgeSamplingDistance) {
        this.longEdgeSamplingDistance = longEdgeSamplingDistance;
        return this;
    }

    /**
     * Sets the max elevation discrepancy between way points and the simplified polyline in meters
     */
    public GraphHopper setElevationWayPointMaxDistance(double elevationWayPointMaxDistance) {
        this.routerConfig.setElevationWayPointMaxDistance(elevationWayPointMaxDistance);
        return this;
    }

    //ORS-GH MOD START
    @Deprecated // TODO ORS (minor): use RouterConfig instead
    public GraphHopper setSimplifyResponse(boolean doSimplify) {
        this.getRouterConfig().setSimplifyResponse(doSimplify);
        return this;
    }
    //ORS-GH MOD END

    public String getGraphHopperLocation() {
        return ghLocation;
    }

    /**
     * Sets the graphhopper folder.
     */
    public GraphHopper setGraphHopperLocation(String ghLocation) {
        ensureNotLoaded();
        if (ghLocation == null)
            throw new IllegalArgumentException("graphhopper location cannot be null");

        this.ghLocation = ghLocation;
        return this;
    }

    public String getOSMFile() {
        return osmFile;
    }

    /**
     * This file can be an osm xml (.osm), a compressed xml (.osm.zip or .osm.gz) or a protobuf file
     * (.pbf).
     */
    public GraphHopper setOSMFile(String osmFile) {
        ensureNotLoaded();
        if (isEmpty(osmFile))
            throw new IllegalArgumentException("OSM file cannot be empty.");

        this.osmFile = osmFile;
        return this;
    }

    /**
     * The underlying graph used in algorithms.
     *
     * @throws IllegalStateException if graph is not instantiated.
     */
    public GraphHopperStorage getGraphHopperStorage() {
        if (ghStorage == null)
            throw new IllegalStateException("GraphHopper storage not initialized");

        return ghStorage;
    }

    public void setGraphHopperStorage(GraphHopperStorage ghStorage) {
        this.ghStorage = ghStorage;
        setFullyLoaded();
    }

    /**
     * The location index created from the graph.
     *
     * @throws IllegalStateException if index is not initialized
     */
    public LocationIndex getLocationIndex() {
        if (locationIndex == null)
            throw new IllegalStateException("LocationIndex not initialized");

        return locationIndex;
    }

    protected void setLocationIndex(LocationIndex locationIndex) {
        this.locationIndex = locationIndex;
    }

    /**
     * Sorts the graph which requires more RAM while import. See #12
     */
    public GraphHopper setSortGraph(boolean sortGraph) {
        ensureNotLoaded();
        this.sortGraph = sortGraph;
        return this;
    }

    public boolean isAllowWrites() {
        return allowWrites;
    }

    /**
     * Specifies if it is allowed for GraphHopper to write. E.g. for read only filesystems it is not
     * possible to create a lock file and so we can avoid write locks.
     */
    public GraphHopper setAllowWrites(boolean allowWrites) {
        this.allowWrites = allowWrites;
        return this;
    }

    public TranslationMap getTranslationMap() {
        return trMap;
    }

    public GraphHopper setFlagEncoderFactory(FlagEncoderFactory factory) {
        this.flagEncoderFactory = factory;
        return this;
    }

    public EncodedValueFactory getEncodedValueFactory() {
        return this.encodedValueFactory;
    }

    public GraphHopper setEncodedValueFactory(EncodedValueFactory factory) {
        this.encodedValueFactory = factory;
        return this;
    }

    public TagParserFactory getTagParserFactory() {
        return this.tagParserFactory;
    }

    public GraphHopper setTagParserFactory(TagParserFactory factory) {
        this.tagParserFactory = factory;
        return this;
    }

    /**
     * Reads the configuration from a {@link GraphHopperConfig} object which can be manually filled, or more typically
     * is read from `config.yml`.
     */
    public GraphHopper init(GraphHopperConfig ghConfig) {
        // disabling_allowed config options were removed for GH 3.0
        if (ghConfig.has("routing.ch.disabling_allowed"))
            throw new IllegalArgumentException("The 'routing.ch.disabling_allowed' configuration option is no longer supported");
        if (ghConfig.has("routing.lm.disabling_allowed"))
            throw new IllegalArgumentException("The 'routing.lm.disabling_allowed' configuration option is no longer supported");
        if (ghConfig.has("osmreader.osm"))
            throw new IllegalArgumentException("Instead osmreader.osm use datareader.file, for other changes see CHANGELOG.md");

        String tmpOsmFile = ghConfig.getString("datareader.file", "");
        if (!isEmpty(tmpOsmFile))
            osmFile = tmpOsmFile;

        String graphHopperFolder = ghConfig.getString("graph.location", "");
        if (isEmpty(graphHopperFolder) && isEmpty(ghLocation)) {
            if (isEmpty(osmFile))
                throw new IllegalArgumentException("If no graph.location is provided you need to specify an OSM file.");

            graphHopperFolder = pruneFileEnd(osmFile) + "-gh";
        }

        // graph
        setGraphHopperLocation(graphHopperFolder);
        defaultSegmentSize = ghConfig.getInt("graph.dataaccess.segment_size", defaultSegmentSize);

        String graphDATypeStr = ghConfig.getString("graph.dataaccess", "RAM_STORE");
        dataAccessType = DAType.fromString(graphDATypeStr);

        sortGraph = ghConfig.getBool("graph.do_sort", sortGraph);
        removeZipped = ghConfig.getBool("graph.remove_zipped", removeZipped);

        if (encodingManager != null)
            throw new IllegalStateException("Cannot call init twice. EncodingManager was already initialized.");

        emBuilder.setEnableInstructions(ghConfig.getBool("datareader.instructions", true));
        emBuilder.setPreferredLanguage(ghConfig.getString("datareader.preferred_language", ""));
        emBuilder.setDateRangeParser(DateRangeParser.createInstance(ghConfig.getString("datareader.date_range_parser_day", "")));
        setProfiles(ghConfig.getProfiles());
        // currently we cannot require profiles at this point as GTFS module does not use them
        encodingManager = buildEncodingManager(ghConfig, false);

        if (ghConfig.getString("graph.locktype", "native").equals("simple"))
            lockFactory = new SimpleFSLockFactory();
        else
            lockFactory = new NativeFSLockFactory();

        // elevation
        this.smoothElevation = ghConfig.getBool("graph.elevation.smoothing", false);
        this.longEdgeSamplingDistance = ghConfig.getDouble("graph.elevation.long_edge_sampling_distance", Double.MAX_VALUE);
        setElevationWayPointMaxDistance(ghConfig.getDouble("graph.elevation.way_point_max_distance", Double.MAX_VALUE));
        ElevationProvider elevationProvider = createElevationProvider(ghConfig);
        setElevationProvider(elevationProvider);

        if (longEdgeSamplingDistance < Double.MAX_VALUE && !elevationProvider.getInterpolate())
            logger.warn("Long edge sampling enabled, but bilinear interpolation disabled. See #1953");

        // optimizable prepare
        minNetworkSize = ghConfig.getInt("prepare.min_network_size", minNetworkSize);

        // prepare CH&LM
        chPreparationHandler.init(ghConfig);
        lmPreparationHandler.init(ghConfig);

        // osm import
        dataReaderWayPointMaxDistance = ghConfig.getDouble(Routing.INIT_WAY_POINT_MAX_DISTANCE, dataReaderWayPointMaxDistance);

        dataReaderWorkerThreads = ghConfig.getInt("datareader.worker_threads", dataReaderWorkerThreads);

        // index
        preciseIndexResolution = ghConfig.getInt("index.high_resolution", preciseIndexResolution);
        maxRegionSearch = ghConfig.getInt("index.max_region_search", maxRegionSearch);

        // routing
        routerConfig.setMaxVisitedNodes(ghConfig.getInt(Routing.INIT_MAX_VISITED_NODES, routerConfig.getMaxVisitedNodes()));
        routerConfig.setMaxRoundTripRetries(ghConfig.getInt(RoundTrip.INIT_MAX_RETRIES, routerConfig.getMaxRoundTripRetries()));
        routerConfig.setNonChMaxWaypointDistance(ghConfig.getInt(Parameters.NON_CH.MAX_NON_CH_POINT_DISTANCE, routerConfig.getNonChMaxWaypointDistance()));
        int activeLandmarkCount = ghConfig.getInt(Landmark.ACTIVE_COUNT_DEFAULT, Math.min(8, lmPreparationHandler.getLandmarks()));
        if (activeLandmarkCount > lmPreparationHandler.getLandmarks())
            throw new IllegalArgumentException("Default value for active landmarks " + activeLandmarkCount
                    + " should be less or equal to landmark count of " + lmPreparationHandler.getLandmarks());
        routerConfig.setActiveLandmarkCount(activeLandmarkCount);

        return this;
    }

    private EncodingManager buildEncodingManager(GraphHopperConfig ghConfig, boolean requireProfilesByName) {
        String flagEncodersStr = ghConfig.getString("graph.flag_encoders", "");
        String encodedValueStr = ghConfig.getString("graph.encoded_values", "");
        Map<String, String> flagEncoderMap = new LinkedHashMap<>(), implicitFlagEncoderMap = new HashMap<>();
        for (String encoderStr : Arrays.asList(flagEncodersStr.split(","))) {
            String key = encoderStr.split("\\|")[0];
            if (!key.isEmpty()) {
                if (flagEncoderMap.containsKey(key))
                    throw new IllegalArgumentException("FlagEncoder " + key + " needs to be unique");
                flagEncoderMap.put(key, encoderStr);
            }
        }
        if (requireProfilesByName && profilesByName.isEmpty())
            throw new IllegalStateException("no profiles exist but assumed to create EncodingManager. E.g. provide them in GraphHopperConfig when calling GraphHopper.init");
        for (Profile profile : profilesByName.values()) {
            emBuilder.add(Subnetwork.create(profile.getName()));
            if (!flagEncoderMap.containsKey(profile.getVehicle())
                    // overwrite key in implicit map if turn cost support required
                    && (!implicitFlagEncoderMap.containsKey(profile.getVehicle()) || profile.isTurnCosts()))
                implicitFlagEncoderMap.put(profile.getVehicle(), profile.getVehicle() + (profile.isTurnCosts() ? "|turn_costs=true" : ""));
        }
        flagEncoderMap.putAll(implicitFlagEncoderMap);
        flagEncoderMap.values().stream().forEach(s -> emBuilder.addIfAbsent(flagEncoderFactory, s));
        for (String tpStr : encodedValueStr.split(",")) {
            if (!tpStr.isEmpty()) emBuilder.addIfAbsent(tagParserFactory, tpStr);
        }

        return emBuilder.build();
    }

    private static ElevationProvider createElevationProvider(GraphHopperConfig ghConfig) {
        String eleProviderStr = toLowerCase(ghConfig.getString("graph.elevation.provider", "noop"));

        if (ghConfig.has("graph.elevation.calcmean"))
            throw new IllegalArgumentException("graph.elevation.calcmean is deprecated, use graph.elevation.interpolate");

        boolean interpolate = ghConfig.has("graph.elevation.interpolate")
                ? "bilinear".equals(ghConfig.getString("graph.elevation.interpolate", "none"))
                : ghConfig.getBool("graph.elevation.calc_mean", false);

        String cacheDirStr = ghConfig.getString("graph.elevation.cache_dir", "");
        if (cacheDirStr.isEmpty() && ghConfig.has("graph.elevation.cachedir"))
            throw new IllegalArgumentException("use graph.elevation.cache_dir not cachedir in configuration");

        String baseURL = ghConfig.getString("graph.elevation.base_url", "");
        if (baseURL.isEmpty() && ghConfig.has("graph.elevation.baseurl"))
            throw new IllegalArgumentException("use graph.elevation.base_url not baseurl in configuration");

        boolean removeTempElevationFiles = ghConfig.getBool("graph.elevation.cgiar.clear", true);
        removeTempElevationFiles = ghConfig.getBool("graph.elevation.clear", removeTempElevationFiles);

        DAType elevationDAType = DAType.fromString(ghConfig.getString("graph.elevation.dataaccess", "MMAP"));
        ElevationProvider elevationProvider = ElevationProvider.NOOP;
        if (eleProviderStr.equalsIgnoreCase("srtm")) {
            elevationProvider = new SRTMProvider(cacheDirStr);
        } else if (eleProviderStr.equalsIgnoreCase("cgiar")) {
            elevationProvider = new CGIARProvider(cacheDirStr);
        } else if (eleProviderStr.equalsIgnoreCase("gmted")) {
            elevationProvider = new GMTEDProvider(cacheDirStr);
        } else if (eleProviderStr.equalsIgnoreCase("srtmgl1")) {
            elevationProvider = new SRTMGL1Provider(cacheDirStr);
        } else if (eleProviderStr.equalsIgnoreCase("multi")) {
            elevationProvider = new MultiSourceElevationProvider(cacheDirStr);
        } else if (eleProviderStr.equalsIgnoreCase("skadi")) {
            elevationProvider = new SkadiProvider(cacheDirStr);
        }

        elevationProvider.setAutoRemoveTemporaryFiles(removeTempElevationFiles);
        elevationProvider.setInterpolate(interpolate);
        if (!baseURL.isEmpty())
            elevationProvider.setBaseURL(baseURL);
        elevationProvider.setDAType(elevationDAType);
        return elevationProvider;
    }

    private void printInfo() {
        logger.info("version " + Constants.VERSION + "|" + Constants.BUILD_DATE + " (" + Constants.getVersions() + ")");
        if (ghStorage != null)
            logger.info("graph " + ghStorage.toString() + ", details:" + ghStorage.toDetailsString());
    }

    /**
     * Imports provided data from disc and creates graph. Depending on the settings the resulting
     * graph will be stored to disc so on a second call this method will only load the graph from
     * disc which is usually a lot faster.
     */
    public GraphHopper importOrLoad() {
        if (!load(ghLocation)) {
            printInfo();
            process(ghLocation, false);
        } else {
            printInfo();
        }
        return this;
    }

    /**
     * Imports and processes data, storing it to disk when complete.
     */
    public void importAndClose() {
        if (!load(ghLocation)) {
            printInfo();
            process(ghLocation, true);
        } else {
            printInfo();
            logger.info("Graph already imported into " + ghLocation);
        }
        close();
    }

    /**
     * Creates the graph from OSM data.
     */
    private void process(String graphHopperLocation, boolean closeEarly) {
        setGraphHopperLocation(graphHopperLocation);
        GHLock lock = null;
        try {
            if (ghStorage == null)
                throw new IllegalStateException("GraphHopperStorage must be initialized before starting the import");
            if (ghStorage.getDirectory().getDefaultType().isStoring()) {
                lockFactory.setLockDir(new File(graphHopperLocation));
                lock = lockFactory.create(fileLockName, true);
                if (!lock.tryLock())
                    throw new RuntimeException("To avoid multiple writers we need to obtain a write lock but it failed. In " + graphHopperLocation, lock.getObtainFailedReason());
            }
            ensureWriteAccess();
            importOSM();
            cleanUp();
            postProcessing(closeEarly);
            flush();
        } finally {
            if (lock != null)
                lock.release();
        }
    }

    protected void importOSM() {
        if (osmFile == null)
            throw new IllegalStateException("Couldn't load from existing folder: " + ghLocation
                    + " but also cannot use file for DataReader as it wasn't specified!");

        logger.info("start creating graph from " + osmFile);
        OSMReader reader = new OSMReader(ghStorage).setFile(_getOSMFile()).
                setElevationProvider(eleProvider).
                setWorkerThreads(dataReaderWorkerThreads).
                setWayPointMaxDistance(dataReaderWayPointMaxDistance).
                setWayPointElevationMaxDistance(routerConfig.getElevationWayPointMaxDistance()).
                setSmoothElevation(smoothElevation).
                setLongEdgeSamplingDistance(longEdgeSamplingDistance);
        logger.info("using " + ghStorage.toString() + ", memory:" + getMemInfo());
        try {
            reader.readGraph();
        } catch (IOException ex) {
            throw new RuntimeException("Cannot read file " + getOSMFile(), ex);
        }
        DateFormat f = createFormatter();
        ghStorage.getProperties().put("datareader.import.date", f.format(new Date()));
        if (reader.getDataDate() != null)
            ghStorage.getProperties().put("datareader.data.date", f.format(reader.getDataDate()));
    }

    /**
     * Currently we use this for a few tests where the dataReaderFile is loaded from the classpath
     */
    protected File _getOSMFile() {
        return new File(osmFile);
    }

    /**
     * Opens existing graph folder.
     *
     * @param graphHopperFolder is the folder containing graphhopper files. Can be a compressed file
     *                          too ala folder-content.ghz.
     */
    public boolean load(String graphHopperFolder) {
        if (isEmpty(graphHopperFolder))
            throw new IllegalStateException("GraphHopperLocation is not specified. Call setGraphHopperLocation or init before");

        if (fullyLoaded)
            throw new IllegalStateException("graph is already successfully loaded");

        File tmpFileOrFolder = new File(graphHopperFolder);

        if (!tmpFileOrFolder.isDirectory() && tmpFileOrFolder.exists()) {
            throw new IllegalArgumentException("GraphHopperLocation cannot be an existing file. Has to be either non-existing or a folder.");
        } else {
            File compressed = new File(graphHopperFolder + ".ghz");
            if (compressed.exists() && !compressed.isDirectory()) {
                try {
                    new Unzipper().unzip(compressed.getAbsolutePath(), graphHopperFolder, removeZipped);
                } catch (IOException ex) {
                    throw new RuntimeException("Couldn't extract file " + compressed.getAbsolutePath()
                            + " to " + graphHopperFolder, ex);
                }
            }
        }

        setGraphHopperLocation(graphHopperFolder);

        if (!allowWrites && dataAccessType.isMMap())
            dataAccessType = DAType.MMAP_RO;
        if (encodingManager == null) {
            StorableProperties properties = new StorableProperties(new GHDirectory(ghLocation, dataAccessType));
            encodingManager = properties.loadExisting()
                    ? EncodingManager.create(emBuilder, encodedValueFactory, flagEncoderFactory, properties)
                    : buildEncodingManager(new GraphHopperConfig(), true);
        }

        GHDirectory dir = new GHDirectory(ghLocation, dataAccessType);
        // TODO ORS (major): Do we need to create ORSGraphHopper here through GraphStorageFactory? E.g.:
        //if (graphStorageFactory != null) {
//      //      ghStorage = graphStorageFactory.createStorage(...);
//      //} else { // Fallback to GH origial
        ghStorage = new GraphHopperStorage(dir, encodingManager, hasElevation(), encodingManager.needsTurnCostsSupport(), defaultSegmentSize);
        checkProfilesConsistency();

        if (lmPreparationHandler.isEnabled())
            initLMPreparationHandler();

        List<CHConfig> chConfigs;
        if (chPreparationHandler.isEnabled()) {
            initCHPreparationHandler();
            chConfigs = chPreparationHandler.getCHConfigs();
        } else {
            chConfigs = Collections.emptyList();
        }

        ghStorage.addCHGraphs(chConfigs);

        // TODO ORS: add calt here

        if (!new File(graphHopperFolder).exists())
            return false;

        GHLock lock = null;
        try {
            // create locks only if writes are allowed, if they are not allowed a lock cannot be created
            // (e.g. on a read only filesystem locks would fail)
            if (ghStorage.getDirectory().getDefaultType().isStoring() && isAllowWrites()) {
                lockFactory.setLockDir(new File(ghLocation));
                lock = lockFactory.create(fileLockName, false);
                if (!lock.tryLock())
                    throw new RuntimeException("To avoid reading partial data we need to obtain the read lock but it failed. In " + ghLocation, lock.getObtainFailedReason());
            }

            if (!ghStorage.loadExisting())
                return false;

            postProcessing(false);
            setFullyLoaded();
            return true;
        } finally {
            if (lock != null)
                lock.release();
        }
    }

    private void checkProfilesConsistency() {
        EncodingManager encodingManager = getEncodingManager();
        for (Profile profile : profilesByName.values()) {
            if (!encodingManager.hasEncoder(profile.getVehicle())) {
                throw new IllegalArgumentException("Unknown vehicle '" + profile.getVehicle() + "' in profile: " + profile + ". Make sure all vehicles used in 'profiles' exist in 'graph.flag_encoders'");
            }
            FlagEncoder encoder = encodingManager.getEncoder(profile.getVehicle());
            if (profile.isTurnCosts() && !encoder.supportsTurnCosts()) {
                throw new IllegalArgumentException("The profile '" + profile.getName() + "' was configured with " +
                        "'turn_costs=true', but the corresponding vehicle '" + profile.getVehicle() + "' does not support turn costs." +
                        "\nYou need to add `|turn_costs=true` to the vehicle in `graph.flag_encoders`");
            }
            try {
                createWeighting(profile, new PMap());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Could not create weighting for profile: '" + profile.getName() + "'.\n" +
                        "Profile: " + profile + "\n" +
                        "Error: " + e.getMessage());
            }

            if (profile instanceof CustomProfile) {
                CustomModel customModel = ((CustomProfile) profile).getCustomModel();
                if (customModel == null)
                    throw new IllegalArgumentException("custom model for profile '" + profile.getName() + "' was empty");
                if (!CustomWeighting.NAME.equals(profile.getWeighting()))
                    throw new IllegalArgumentException("profile '" + profile.getName() + "' has a custom model but " +
                            "weighting=" + profile.getWeighting() + " was defined");
            }
        }

        Set<String> chProfileSet = new LinkedHashSet<>(chPreparationHandler.getCHProfiles().size());
        for (CHProfile chProfile : chPreparationHandler.getCHProfiles()) {
            boolean added = chProfileSet.add(chProfile.getProfile());
            if (!added) {
                throw new IllegalArgumentException("Duplicate CH reference to profile '" + chProfile.getProfile() + "'");
            }
            if (!profilesByName.containsKey(chProfile.getProfile())) {
                throw new IllegalArgumentException("CH profile references unknown profile '" + chProfile.getProfile() + "'");
            }
        }
        Map<String, LMProfile> lmProfileMap = new LinkedHashMap<>(lmPreparationHandler.getLMProfiles().size());
        for (LMProfile lmProfile : lmPreparationHandler.getLMProfiles()) {
            LMProfile previous = lmProfileMap.put(lmProfile.getProfile(), lmProfile);
            if (previous != null) {
                throw new IllegalArgumentException("Multiple LM profiles are using the same profile '" + lmProfile.getProfile() + "'");
            }
            if (!profilesByName.containsKey(lmProfile.getProfile())) {
                throw new IllegalArgumentException("LM profile references unknown profile '" + lmProfile.getProfile() + "'");
            }
            if (lmProfile.usesOtherPreparation() && !profilesByName.containsKey(lmProfile.getPreparationProfile())) {
                throw new IllegalArgumentException("LM profile references unknown preparation profile '" + lmProfile.getPreparationProfile() + "'");
            }
        }
        for (LMProfile lmProfile : lmPreparationHandler.getLMProfiles()) {
            if (lmProfile.usesOtherPreparation() && !lmProfileMap.containsKey(lmProfile.getPreparationProfile())) {
                throw new IllegalArgumentException("Unknown LM preparation profile '" + lmProfile.getPreparationProfile() + "' in LM profile '" + lmProfile.getProfile() + "' cannot be used as preparation_profile");
            }
            if (lmProfile.usesOtherPreparation() && lmProfileMap.get(lmProfile.getPreparationProfile()).usesOtherPreparation()) {
                throw new IllegalArgumentException("Cannot use '" + lmProfile.getPreparationProfile() + "' as preparation_profile for LM profile '" + lmProfile.getProfile() + "', because it uses another profile for preparation itself.");
            }
        }
    }

    public final CHPreparationHandler getCHPreparationHandler() {
        return chPreparationHandler;
    }

    // TODO ORS (info): this was renamed from initCHAlgoFactoryDecorator and we had changed access to public
    private void initCHPreparationHandler() {
        if (chPreparationHandler.hasCHConfigs()) {
            return;
        }

        for (CHProfile chProfile : chPreparationHandler.getCHProfiles()) {
            Profile profile = profilesByName.get(chProfile.getProfile());
            if (profile.isTurnCosts()) {
                chPreparationHandler.addCHConfig(CHConfig.edgeBased(profile.getName(), createWeighting(profile, new PMap())));
            } else {
                chPreparationHandler.addCHConfig(CHConfig.nodeBased(profile.getName(), createWeighting(profile, new PMap())));
            }
        }
    }

    public final LMPreparationHandler getLMPreparationHandler() {
        return lmPreparationHandler;
    }

    // TODO ORS (info): this was renamed from initLMAlgoFactoryDecorator and we had changed access to public
    private void initLMPreparationHandler() {
        if (lmPreparationHandler.hasLMProfiles())
            return;

        for (LMProfile lmProfile : lmPreparationHandler.getLMProfiles()) {
            if (lmProfile.usesOtherPreparation())
                continue;
            Profile profile = profilesByName.get(lmProfile.getProfile());
            // Note that we have to make sure the weighting used for LM preparation does not include turn costs, because
            // the LM preparation is running node-based and the landmark weights will be wrong if there are non-zero
            // turn costs, see discussion in #1960
            // Running the preparation without turn costs is also useful to allow e.g. changing the u_turn_costs per
            // request (we have to use the minimum weight settings (= no turn costs) for the preparation)
            Weighting weighting = createWeighting(profile, new PMap(), true);
            lmPreparationHandler.addLMConfig(new LMConfig(profile.getName(), weighting));
        }
    }

    /**
     * Does the preparation and creates the location index
     */
    public final void postProcessing() {
        postProcessing(false);
    }

    /**
     * Does the preparation and creates the location index
     *
     * @param closeEarly release resources as early as possible
     */
    protected void postProcessing(boolean closeEarly) {
        // Later: move this into the GraphStorage.optimize method
        // Or: Doing it after preparation to optimize shortcuts too. But not possible yet #12

        if (sortGraph) {
            if (ghStorage.isCHPossible() && isCHPrepared())
                throw new IllegalArgumentException("Sorting a prepared CHGraph is not possible yet. See #12");

            GraphHopperStorage newGraph = GHUtility.newStorage(ghStorage);
            GHUtility.sortDFS(ghStorage, newGraph);
            logger.info("graph sorted (" + getMemInfo() + ")");
            ghStorage = newGraph;
        }

        if (!hasInterpolated() && hasElevation()) {
            interpolateBridgesTunnelsAndFerries();
        }

        // ORS-GH MOD START
        // needed for TD routing
        BBox bb = ghStorage.getBounds();
        ghStorage.setTimeZoneMap(TimeZoneMap.forRegion(bb.minLat, bb.minLon, bb.maxLat, bb.maxLon));
        // ORS-GH MOD END

        initLocationIndex();

        importPublicTransit();

        if (lmPreparationHandler.isEnabled())
            lmPreparationHandler.createPreparations(ghStorage, locationIndex);
        loadOrPrepareLM(closeEarly);

        if (chPreparationHandler.isEnabled())
            chPreparationHandler.createPreparations(ghStorage);
        if (isCHPrepared()) {
            // check loaded profiles
            for (CHProfile profile : chPreparationHandler.getCHProfiles()) {
                if (!getProfileVersion(profile.getProfile()).equals("" + profilesByName.get(profile.getProfile()).getVersion()))
                    throw new IllegalArgumentException("CH preparation of " + profile.getProfile() + " already exists in storage and doesn't match configuration");
            }
        } else {
            prepareCH(closeEarly);
        }

        // TODO ORS: insert Calt here
    }

    protected void importPublicTransit() {
    }

    private static final String INTERPOLATION_KEY = "prepare.elevation_interpolation.done";

    private boolean hasInterpolated() {
        return "true".equals(ghStorage.getProperties().get(INTERPOLATION_KEY));
    }

    void interpolateBridgesTunnelsAndFerries() {
        if (ghStorage.getEncodingManager().hasEncodedValue(RoadEnvironment.KEY)) {
            EnumEncodedValue<RoadEnvironment> roadEnvEnc = ghStorage.getEncodingManager().getEnumEncodedValue(RoadEnvironment.KEY, RoadEnvironment.class);
            StopWatch sw = new StopWatch().start();
            new EdgeElevationInterpolator(ghStorage, roadEnvEnc, RoadEnvironment.TUNNEL).execute();
            float tunnel = sw.stop().getSeconds();
            sw = new StopWatch().start();
            new EdgeElevationInterpolator(ghStorage, roadEnvEnc, RoadEnvironment.BRIDGE).execute();
            float bridge = sw.stop().getSeconds();
            // The SkadiProvider contains bathymetric data. For ferries this can result in bigger elevation changes
            // See #2098 for mor information
            sw = new StopWatch().start();
            new EdgeElevationInterpolator(ghStorage, roadEnvEnc, RoadEnvironment.FERRY).execute();
            ghStorage.getProperties().put(INTERPOLATION_KEY, true);
            logger.info("Bridge interpolation " + (int) bridge + "s, " + "tunnel interpolation " + (int) tunnel + "s, ferry interpolation " + (int) sw.stop().getSeconds() + "s");
        }
    }

    public final Weighting createWeighting(Profile profile, PMap hints) {
        return createWeighting(profile, hints, false);
    }

    public final Weighting createWeighting(Profile profile, PMap hints, boolean disableTurnCosts) {
        return createWeightingFactory().createWeighting(profile, hints, disableTurnCosts);
    }

    protected WeightingFactory createWeightingFactory() {
        return new DefaultWeightingFactory(ghStorage, getEncodingManager());
    }

    // ORS-GH MOD START - additional method
    /**
     * Potentially wraps the specified weighting into a TimeDependentAccessWeighting.
     */
    public Weighting createTimeDependentAccessWeighting(Weighting weighting, String algo) {
        FlagEncoder flagEncoder = weighting.getFlagEncoder();
        if (encodingManager.hasEncodedValue(EncodingManager.getKey(flagEncoder, ConditionalEdges.ACCESS)) && isAlgorithmTimeDependent(algo))
            return new TimeDependentAccessWeighting(weighting, ghStorage, flagEncoder);
        else
            return weighting;
    }
    // ORS-GH MOD END

    private boolean isAlgorithmTimeDependent(String algo) {
        return ("td_dijkstra".equals(algo) || "td_astar".equals(algo)) ? true : false;
    }

    @Override
    public GHResponse route(GHRequest request) {
        return createRouter().route(request);
    }

    private Router createRouter() {
        if (ghStorage == null || !fullyLoaded)
            throw new IllegalStateException("Do a successful call to load or importOrLoad before routing");
        if (ghStorage.isClosed())
            throw new IllegalStateException("You need to create a new GraphHopper instance as it is already closed");
        if (locationIndex == null)
            throw new IllegalStateException("Location index not initialized");

        Map<String, CHGraph> chGraphs = new LinkedHashMap<>();
        for (CHProfile chProfile : chPreparationHandler.getCHProfiles()) {
            String chGraphName = chPreparationHandler.getPreparation(chProfile.getProfile()).getCHConfig().getName();
            chGraphs.put(chProfile.getProfile(), ghStorage.getCHGraph(chGraphName));
        }
        Map<String, LandmarkStorage> landmarks = new LinkedHashMap<>();
        for (LMProfile lmp : lmPreparationHandler.getLMProfiles()) {
            landmarks.put(lmp.getProfile(),
                    lmp.usesOtherPreparation()
                            // cross-querying
                            ? lmPreparationHandler.getPreparation(lmp.getPreparationProfile()).getLandmarkStorage()
                            : lmPreparationHandler.getPreparation(lmp.getProfile()).getLandmarkStorage());
        }
        return doCreateRouter(ghStorage, locationIndex, profilesByName, pathBuilderFactory,
                trMap, routerConfig, createWeightingFactory(), chGraphs, landmarks);
    }

    protected Router doCreateRouter(GraphHopperStorage ghStorage, LocationIndex locationIndex, Map<String, Profile> profilesByName,
                                    PathDetailsBuilderFactory pathBuilderFactory, TranslationMap trMap, RouterConfig routerConfig,
                                    WeightingFactory weightingFactory, Map<String, CHGraph> chGraphs, Map<String, LandmarkStorage> landmarks) {
        return new Router(ghStorage, locationIndex, profilesByName, pathBuilderFactory,
                trMap, routerConfig, weightingFactory, chGraphs, landmarks
        );
    }

    // TODO ORS (minor): Keep this for reference until upgrade is done
//    /**
//     * This method calculates the alternative path list using the low level Path objects.
//     */
//    public List<Path> calcPaths(GHRequest request, GHResponse ghRsp) {
//        if (ghStorage == null || !fullyLoaded)
//            throw new IllegalStateException("Do a successful call to load or importOrLoad before routing");
//
//        if (ghStorage.isClosed())
//            throw new IllegalStateException("You need to create a new GraphHopper instance as it is already closed");
//
//        // default handling
//        String vehicle = request.getVehicle();
//        if (vehicle.isEmpty()) {
//            vehicle = getDefaultVehicle().toString();
//            request.setVehicle(vehicle);
//        }
//
//        Lock readLock = readWriteLock.readLock();
//        readLock.lock();
//        try {
//            if (!encodingManager.hasEncoder(vehicle))
//                throw new IllegalArgumentException("Vehicle not supported: " + vehicle + ". Supported are: " + encodingManager.toString());
//
//            FlagEncoder encoder = encodingManager.getEncoder(vehicle);
//            HintsMap hints = request.getHints();
//
//            // we use edge-based routing if the encoder supports turn-costs *unless* the edge_based parameter is set
//            // explicitly.
//            TraversalMode tMode = encoder.supports(TurnWeighting.class) ? TraversalMode.EDGE_BASED : TraversalMode.NODE_BASED;
//            if (hints.has(Routing.EDGE_BASED))
//                tMode = hints.getBool(Routing.EDGE_BASED, false) ? TraversalMode.EDGE_BASED : TraversalMode.NODE_BASED;
//
//            if (tMode.isEdgeBased() && !encoder.supports(TurnWeighting.class)) {
//                throw new IllegalArgumentException("You need a turn cost extension to make use of edge_based=true, e.g. use car|turn_costs=true");
//            }
//
//            boolean disableCH = hints.getBool(CH.DISABLE, false);
//            if (!chFactoryDecorator.isDisablingAllowed() && disableCH)
//                throw new IllegalArgumentException("Disabling CH not allowed on the server-side");
//
//            boolean disableLM = hints.getBool(Landmark.DISABLE, false);
//            if (!lmFactoryDecorator.isDisablingAllowed() && disableLM)
//                throw new IllegalArgumentException("Disabling LM not allowed on the server-side");
//
//            String algoStr = request.getAlgorithm();
//            if (algoStr.isEmpty())
//                algoStr = chFactoryDecorator.isEnabled() && !disableCH ? DIJKSTRA_BI : ASTAR_BI;
//
//            List<GHPoint> points = request.getPoints();
//            // TODO Maybe we should think about a isRequestValid method that checks all that stuff that we could do to fail fast
//            // For example see #734
//            checkIfPointsAreInBounds(points);
//
//            RoutingTemplate routingTemplate;
//            if (ROUND_TRIP.equalsIgnoreCase(algoStr))
//                routingTemplate = new RoundTripRoutingTemplate(request, ghRsp, locationIndex, encodingManager, maxRoundTripRetries);
//            else if (ALT_ROUTE.equalsIgnoreCase(algoStr))
//                routingTemplate = new AlternativeRoutingTemplate(request, ghRsp, locationIndex, encodingManager);
//            else
//                routingTemplate = new ViaRoutingTemplate(request, ghRsp, locationIndex, encodingManager);
//
//            // ORS-GH MOD START - additional code TODO ORS: Put this mod at the appropriate place (Router?)
//            EdgeFilter edgeFilter = edgeFilterFactory.createEdgeFilter(request.getAdditionalHints(), encoder, ghStorage);
//            routingTemplate.setEdgeFilter(edgeFilter);
//
//            if (request.getAlgorithm().equals("alternative_route")) {
//                for (int c = 0; c < request.getHints().getInt("alternative_route.max_paths", 2); c++) {
//                    ghRsp.addReturnObject(pathProcessorFactory.createPathProcessor(request.getAdditionalHints(), encoder, getGraphHopperStorage()));
//                }
//            } else {
//                ghRsp.addReturnObject(pathProcessorFactory.createPathProcessor(request.getAdditionalHints(), encoder, getGraphHopperStorage()));
//            }
//            List<PathProcessor> ppList = new ArrayList<>();
//            for (Object o : ghRsp.getReturnObjects()) {
//                if (o instanceof PathProcessor) {
//                    ppList.add((PathProcessor)o);
//                }
//            }
//            // ORS MOD END
//
//            List<Path> altPaths = null;
//            int maxRetries = routingTemplate.getMaxRetries();
//            Locale locale = request.getLocale();
//            Translation tr = trMap.getWithFallBack(locale);
//            for (int i = 0; i < maxRetries; i++) {
//                StopWatch sw = new StopWatch().start();
//                List<QueryResult> qResults = routingTemplate.lookup(points, encoder);
//
//                // DONE: ORS-GH MOD START - check for max search distances
//                double[] radiuses = request.getMaxSearchDistances();
//                if (points.size() == qResults.size()) {
//                    for (int placeIndex = 0; placeIndex < points.size(); placeIndex++) {
//                        QueryResult qr = qResults.get(placeIndex);
//                        if ((radiuses != null) && qr.isValid() && (qr.getQueryDistance() > radiuses[placeIndex]) && (radiuses[placeIndex] != -1.0)) {
//                            ghRsp.addError(new PointNotFoundException("Cannot find point " + placeIndex + ": " + points.get(placeIndex) + " within a radius of " + radiuses[placeIndex] + " meters.", placeIndex));
//                        }
//                    }
//                }
//                // ORS-GH MOD END
//
//                ghRsp.addDebugInfo("idLookup:" + sw.stop().getSeconds() + "s");
//                if (ghRsp.hasErrors())
//                    return Collections.emptyList();
//
//                RoutingAlgorithmFactory tmpAlgoFactory = getAlgorithmFactory(hints);
//                Weighting weighting;
//                QueryGraph queryGraph;
//
//                if (chFactoryDecorator.isEnabled() && !disableCH) {
//                    boolean forceCHHeading = hints.getBool(CH.FORCE_HEADING, false);
//                    if (!forceCHHeading && request.hasFavoredHeading(0))
//                        throw new IllegalArgumentException("Heading is not (fully) supported for CHGraph. See issue #483");
//
//                    // if LM is enabled we have the LMFactory with the CH algo!
//                    RoutingAlgorithmFactory chAlgoFactory = tmpAlgoFactory;
//                    if (tmpAlgoFactory instanceof LMAlgoFactoryDecorator.LMRAFactory)
//                        chAlgoFactory = ((LMAlgoFactoryDecorator.LMRAFactory) tmpAlgoFactory).getDefaultAlgoFactory();
//
//                    if (chAlgoFactory instanceof PrepareContractionHierarchies) {
//                        com.graphhopper.storage.CHProfile chProfile = ((PrepareContractionHierarchies) chAlgoFactory).getCHProfile();
//                        queryGraph = new QueryGraph(ghStorage.getCHGraph(chProfile));
//                        queryGraph.lookup(qResults);
//                        weighting = chProfile.getWeighting();
//                    } else {
//                        throw new IllegalStateException("Although CH was enabled a non-CH algorithm factory was returned " + tmpAlgoFactory);
//                    }
//                } else {
//                    checkNonChMaxWaypointDistance(points);
//                    queryGraph = new QueryGraph(ghStorage);
//                    queryGraph.lookup(qResults);
//                    weighting = createWeighting(hints, encoder, queryGraph);
//                }
//                ghRsp.addDebugInfo("tmode:" + tMode.toString());
//
//                int maxVisitedNodesForRequest = hints.getInt(Routing.MAX_VISITED_NODES, maxVisitedNodes);
//                if (maxVisitedNodesForRequest > maxVisitedNodes)
//                    throw new IllegalArgumentException("The max_visited_nodes parameter has to be below or equal to:" + maxVisitedNodes);
//
//                weighting = createTimeDependentAccessWeighting(weighting, algoStr);
//
//                int uTurnCostInt = request.getHints().getInt(Routing.U_TURN_COSTS, INFINITE_U_TURN_COSTS);
//                if (uTurnCostInt != INFINITE_U_TURN_COSTS && !tMode.isEdgeBased()) {
//                    throw new IllegalArgumentException("Finite u-turn costs can only be used for edge-based routing, use `" + Routing.EDGE_BASED + "=true'");
//                }
//                double uTurnCosts = uTurnCostInt == INFINITE_U_TURN_COSTS ? Double.POSITIVE_INFINITY : uTurnCostInt;
//                weighting = createTurnWeighting(queryGraph, weighting, tMode, uTurnCosts);
//
//                if (weighting.isTimeDependent()) {
//                    String departureTimeString = hints.get("pt.earliest_departure_time", "");
//                    if (!departureTimeString.isEmpty())
//                        hints.put("departure", departureTimeString);
//                }
//
//                AlgorithmOptions algoOpts = AlgorithmOptions.start().
//                        algorithm(algoStr).traversalMode(tMode).weighting(weighting).
//                        maxVisitedNodes(maxVisitedNodesForRequest).
//                        hints(hints).
//                        build();
//
//                // DONE: ORS-GH MOD START
//                algoOpts.setEdgeFilter(edgeFilter);
//                // ORS MOD END
//
//                // do the actual route calculation !
//                altPaths = routingTemplate.calcPaths(queryGraph, tmpAlgoFactory, algoOpts);
//
//                boolean tmpEnableInstructions = hints.getBool(Routing.INSTRUCTIONS, getEncodingManager().isEnableInstructions());
//                boolean tmpCalcPoints = hints.getBool(Routing.CALC_POINTS, calcPoints);
//                double wayPointMaxDistance = hints.getDouble(Routing.WAY_POINT_MAX_DISTANCE, 1d);
//
//                DouglasPeucker peucker = new DouglasPeucker().setMaxDistance(wayPointMaxDistance);
//                PathMerger pathMerger = new PathMerger().
//                        setCalcPoints(tmpCalcPoints).
//                        setDouglasPeucker(peucker).
//                        setEnableInstructions(tmpEnableInstructions).
//                        setPathDetailsBuilders(pathBuilderFactory, request.getPathDetails()).
//                        // DONE: ORS MOD START
//                                setPathProcessor(ppList.toArray(new PathProcessor[]{})).
//                        // ORS MOD END
//                                setSimplifyResponse(simplifyResponse && wayPointMaxDistance > 0);
//
//                if (request.hasFavoredHeading(0))
//                    pathMerger.setFavoredHeading(request.getFavoredHeading(0));
//
//                if (routingTemplate.isReady(pathMerger, tr))
//                    break;
//            }
//
//            return altPaths;
//
//        } catch (IllegalArgumentException ex) {
//            ghRsp.addError(ex);
//            return Collections.emptyList();
//        } finally {
//            readLock.unlock();
//        }
//    }

    protected LocationIndex createLocationIndex(Directory dir) {
        LocationIndexTree tmpIndex = new LocationIndexTree(ghStorage, dir);
        tmpIndex.setResolution(preciseIndexResolution);
        tmpIndex.setMaxRegionSearch(maxRegionSearch);
        if (!tmpIndex.loadExisting()) {
            ensureWriteAccess();
            tmpIndex.prepareIndex();
        }

        return tmpIndex;
    }

    /**
     * Initializes the location index after the import is done.
     */
    protected void initLocationIndex() {
        if (locationIndex != null)
            throw new IllegalStateException("Cannot initialize locationIndex twice!");

        locationIndex = createLocationIndex(ghStorage.getDirectory());
    }

    private boolean isCHPrepared() {
        return "true".equals(ghStorage.getProperties().get(CH.PREPARE + "done"));
    }

    private String getProfileVersion(String profile) {
        return ghStorage.getProperties().get("graph.profiles." + profile + ".version");
    }

    private void setProfileVersion(String profile, int version) {
        ghStorage.getProperties().put("graph.profiles." + profile + ".version", version);
    }

    protected void prepareCH(boolean closeEarly) {
        for (CHProfile profile : chPreparationHandler.getCHProfiles()) {
            if (!getProfileVersion(profile.getProfile()).isEmpty()
                    && !getProfileVersion(profile.getProfile()).equals("" + profilesByName.get(profile.getProfile()).getVersion()))
                throw new IllegalArgumentException("CH preparation of " + profile.getProfile() + " already exists in storage and doesn't match configuration");
        }

        boolean chEnabled = chPreparationHandler.isEnabled();
        if (chEnabled) {
            ensureWriteAccess();

            if (closeEarly) {
                locationIndex.close();
                boolean includesCustomProfiles = getProfiles().stream().anyMatch(p -> p instanceof CustomProfile);
                if (!includesCustomProfiles)
                    // when there are custom profiles we must not close way geometry or StringIndex, because
                    // they might be needed to evaluate the custom weighting during CH preparation
                    ghStorage.flushAndCloseEarly();
            }

            ghStorage.freeze();
            chPreparationHandler.prepare(ghStorage.getProperties(), closeEarly);
            ghStorage.getProperties().put(CH.PREPARE + "done", true);
            for (CHProfile profile : chPreparationHandler.getCHProfiles()) {
                // potentially overwrite existing keys from LM
                setProfileVersion(profile.getProfile(), profilesByName.get(profile.getProfile()).getVersion());
            }
        }
    }

    /**
     * For landmarks it is required to always call this method: either it creates the landmark data or it loads it.
     */
    protected void loadOrPrepareLM(boolean closeEarly) {
        if (!lmPreparationHandler.isEnabled() || lmPreparationHandler.getPreparations().isEmpty()) {
            return;
        }

        if (landmarkSplittingFeatureCollection != null && !landmarkSplittingFeatureCollection.getFeatures().isEmpty()) {
            SpatialRuleLookup ruleLookup = SpatialRuleLookupBuilder.buildIndex(
                    Collections.singletonList(landmarkSplittingFeatureCollection), "area",
                    (id, polygons) -> new AbstractSpatialRule(polygons) {
                        @Override
                        public String getId() {
                            return id;
                        }
                    });
            for (PrepareLandmarks prep : getLMPreparationHandler().getPreparations()) {
                // the ruleLookup splits certain areas from each other but avoids making this a permanent change so that other algorithms still can route through these regions.
                if (ruleLookup != null && !ruleLookup.getRules().isEmpty()) {
                    prep.setSpatialRuleLookup(ruleLookup);
                }
            }
        }

        for (LMProfile profile : lmPreparationHandler.getLMProfiles()) {
            if (!getProfileVersion(profile.getProfile()).isEmpty()
                    && !getProfileVersion(profile.getProfile()).equals("" + profilesByName.get(profile.getProfile()).getVersion()))
                throw new IllegalArgumentException("LM preparation of " + profile.getProfile() + " already exists in storage and doesn't match configuration");
        }
        ensureWriteAccess();
        ghStorage.freeze();
        if (lmPreparationHandler.loadOrDoWork(ghStorage.getProperties(), closeEarly)) {
            ghStorage.getProperties().put(Landmark.PREPARE + "done", true);
            for (LMProfile profile : lmPreparationHandler.getLMProfiles()) {
                // potentially overwrite existing keys from CH
                setProfileVersion(profile.getProfile(), profilesByName.get(profile.getProfile()).getVersion());
            }
        }
    }

    /**
     * Internal method to clean up the graph.
     */
    protected void cleanUp() {
        PrepareRoutingSubnetworks preparation = new PrepareRoutingSubnetworks(ghStorage, buildSubnetworkRemovalJobs());
        preparation.setMinNetworkSize(minNetworkSize);
        preparation.doWork();
        logger.info("nodes: " + Helper.nf(ghStorage.getNodes()) + ", edges: " + Helper.nf(ghStorage.getEdges()));
    }

    private List<PrepareJob> buildSubnetworkRemovalJobs() {
        List<PrepareJob> jobs = new ArrayList<>();
        for (Profile profile : profilesByName.values()) {
            // if turn costs are enabled use u-turn costs of zero as we only want to make sure the graph is fully connected assuming finite u-turn costs
            Weighting weighting = createWeighting(profile, new PMap().putObject(Parameters.Routing.U_TURN_COSTS, 0));
            jobs.add(new PrepareJob(encodingManager.getBooleanEncodedValue(Subnetwork.key(profile.getName())), weighting));
        }
        return jobs;
    }

    protected void flush() {
        logger.info("flushing graph " + ghStorage.toString() + ", details:" + ghStorage.toDetailsString() + ", "
                + getMemInfo() + ")");
        ghStorage.flush();
        logger.info("flushed graph " + getMemInfo() + ")");
        setFullyLoaded();
    }

    /**
     * Releases all associated resources like memory or files. But it does not remove them. To
     * remove the files created in graphhopperLocation you have to call clean().
     */
    public void close() {
        if (ghStorage != null)
            ghStorage.close();

        if (locationIndex != null)
            locationIndex.close();

        try {
            lockFactory.forceRemove(fileLockName, true);
        } catch (Exception ex) {
            // silently fail e.g. on Windows where we cannot remove an unreleased native lock
        }
    }

    /**
     * Removes the on-disc routing files. Call only after calling close or before importOrLoad or
     * load
     */
    public void clean() {
        if (getGraphHopperLocation().isEmpty())
            throw new IllegalStateException("Cannot clean GraphHopper without specified graphHopperLocation");

        File folder = new File(getGraphHopperLocation());
        removeDir(folder);
    }

    protected void ensureNotLoaded() {
        if (fullyLoaded)
            throw new IllegalStateException("No configuration changes are possible after loading the graph");
    }

    protected void ensureWriteAccess() {
        if (!allowWrites)
            throw new IllegalStateException("Writes are not allowed!");
    }

    private void setFullyLoaded() {
        fullyLoaded = true;
    }

    public boolean getFullyLoaded() {
        return fullyLoaded;
    }

    public RouterConfig getRouterConfig() {
        return routerConfig;
    }
}
