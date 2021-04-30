/**
 * NoiseModelling is an open-source tool designed to produce environmental noise maps on very large urban areas. It can be used as a Java library or be controlled through a user friendly web interface.
 *
 * This version is developed by the DECIDE team from the Lab-STICC (CNRS) and by the Mixt Research Unit in Environmental Acoustics (Université Gustave Eiffel).
 * <http://noise-planet.org/noisemodelling.html>
 *
 * NoiseModelling is distributed under GPL 3 license. You can read a copy of this License in the file LICENCE provided with this software.
 *
 * Contact: contact@noise-planet.org
 *
 */


package org.noise_planet.noisemodelling.wps.NoiseModelling

import geoserver.GeoServer
import geoserver.catalog.Store
import groovy.sql.Sql
import org.geotools.jdbc.JDBCDataStore
import org.h2gis.utilities.SFSUtilities
import org.h2gis.utilities.SpatialResultSet
import org.h2gis.utilities.TableLocation
import org.h2gis.utilities.wrapper.ConnectionWrapper
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.LineString
import org.noise_planet.noisemodelling.emission.RailWayLW
import org.noise_planet.noisemodelling.jdbc.LDENConfig
import org.noise_planet.noisemodelling.jdbc.LDENPropagationProcessData
import org.noise_planet.noisemodelling.jdbc.RailWayLWIterator
import org.noise_planet.noisemodelling.propagation.PropagationProcessPathData

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException

/**
 * @Author Pierre Aumond,  Univ Gustave Eiffel
 * @Author Adrien Le Bellec,  Univ Gustave Eiffel
 * @Author Olivier Chiello, Univ Gustave Eiffel
 */

title = 'Compute railway emission noise map from vehicule, traffic table AND section table.'
description = 'Compute Rail Emission Noise Map from Day Evening Night traffic flow rate and speed estimates (specific format, see input details). ' +
        '</br> </br> <b> The output table is called : LW_RAILWAY </b> '

inputs = [
        tableRailwayTraffic: [
                name                                                                                                                            : 'Railway traffic table name',
                title                                                                                                                           : 'Railway traffic table name',
                description                                                                                                                     : "<b>Name of the Rail traffic table.</b>  </br>  " +
                        "<br>  This function recognize the following columns (* mandatory) : </br><ul>" +
                        "<li><b> idTraffic </b>* : an identifier. It shall be a primary key (INT, PRIMARY KEY)</li>" +
                        "<li><b> idSection </b>* : an identifier. (INT)</li>" +
                        "<li><b> TYPETRAIN </b>* : Type vehicle (STRING)/li>" +
                        "<li><b> speedVehic </b>* : Maximum Train speed (DOUBLE) </li>" +
                        "<li><b> TDAY </b><b> TEVENING </b><b> TNIGHT </b> : Hourly average train count (6-18h)(18-22h)(22-6h) (INT)</li>", type: String.class],
        tableRailwayTrack  : [
                name                                           : 'RailWay Geom table name', title: 'RailWay Track table name', description: "<b>Name of the Railway Track table.</b>  </br>  " +
                "<br>  This function recognize the following columns (* mandatory) : </br><ul>" +
                "<li><b> idSection </b>* : an identifier. It shall be a primary key(INTEGER, PRIMARY KEY)</li>" +
                "<li><b> nTrack </b>* : Number of tracks (INTEGER) /li>" +
                "<li><b> speedTrack </b>* : Maximum speed on the section in km/h (DOUBLE) </li>" +
                "<li><b> trackTrans </b> : Track transfer function identifier (INTEGER) </li>" +
                "<li><b> railRoughn </b> : Rail roughness identifier (INTEGER)  </li>" +
                "<li><b> impactNois </b> : Impact noise identifier (INTEGER) </li>" +
                "<li><b> curvature </b> : Listed code describing the curvature of the section (INTEGER) </li>" +
                "<li><b> bridgeTran </b> : Bridge transfer function identifier (INTEGER) </li>" +
                "<li><b> speedComme </b> : Commercial speed on the section in km/h (DOUBLE) </li>" +
                "<li><b> isTunnel </b> : (BOOLEAN) </li>", type: String.class]
]

outputs = [result: [name: 'Result output string', title: 'Result output string', description: 'This type of result does not allow the blocks to be linked together.', type: String.class]]

// Open Connection to Geoserver
static Connection openGeoserverDataStoreConnection(String dbName) {
    if (dbName == null || dbName.isEmpty()) {
        dbName = new GeoServer().catalog.getStoreNames().get(0)
    }
    Store store = new GeoServer().catalog.getStore(dbName)
    JDBCDataStore jdbcDataStore = (JDBCDataStore) store.getDataStoreInfo().getDataStore(null)
    return jdbcDataStore.getDataSource().getConnection()
}

// run the script
def run(input) {

    // Get name of the database
    // by default an embedded h2gis database is created
    // Advanced user can replace this database for a postGis or h2Gis server database.
    String dbName = "h2gisdb"

    // Open connection
    openGeoserverDataStoreConnection(dbName).withCloseable {
        Connection connection ->
            return [result: exec(connection, input)]
    }
}

// main function of the script
def exec(Connection connection, input) {
    //Load GeneralTools.groovy
    File generalTools = new File(new File("").absolutePath + "/data_dir/scripts/wpsTools/GeneralTools.groovy")

    //if we are in dev, the path is not the same as for geoserver
    if (new File("").absolutePath.substring(new File("").absolutePath.length() - 11) == 'wps_scripts') {
        generalTools = new File(new File("").absolutePath + "/src/main/groovy/org/noise_planet/noisemodelling/wpsTools/GeneralTools.groovy")
    }

    // Get external tools
    Class groovyClass = new GroovyClassLoader(getClass().getClassLoader()).parseClass(generalTools)
    GroovyObject tools = (GroovyObject) groovyClass.newInstance()

    //Need to change the ConnectionWrapper to WpsConnectionWrapper to work under postGIS database
    connection = new ConnectionWrapper(connection)

    // output string, the information given back to the user
    String resultString = null

    // print to command window
    System.out.println('Start : Railway Emission from DEN')
    def start = new Date()

    // -------------------
    // Get every inputs
    // -------------------

    String sources_geom_table_name = input['tableRailwayTrack'] as String
    // do it case-insensitive
    sources_geom_table_name = sources_geom_table_name.toUpperCase()

    String sources_table_traffic_name = input['tableRailwayTraffic'] as String
    // do it case-insensitive
    sources_table_traffic_name = sources_table_traffic_name.toUpperCase()

    //Get the geometry field of the source table
    TableLocation sourceTableIdentifier = TableLocation.parse(sources_geom_table_name)
    List<String> geomFields = SFSUtilities.getGeometryFields(connection, sourceTableIdentifier)
    if (geomFields.isEmpty()) {
        resultString = String.format("The table %s does not exists or does not contain a geometry field", sourceTableIdentifier)
        throw new SQLException(String.format("The table %s does not exists or does not contain a geometry field", sourceTableIdentifier))
    }


    // -------------------
    // Init table LW_RAIL
    // -------------------

    // Create a sql connection to interact with the database in SQL
    Sql sql = new Sql(connection)

    // drop table LW_RAILWAY if exists and the create and prepare the table
    sql.execute("drop table if exists LW_RAILWAY;")

    sql.execute("create table LW_RAILWAY (ID_SECTION int, the_geom geometry, DIRECTIVITYID int,HEIGHT double," +
            "LWD50 double precision,LWD63 double precision,LWD80 double precision, LWD125 double precision," +
            "LWD160 double precision,LWD200 double precision, LWD250 double precision,LWD315 double precision," +
            "LWD400 double precision, LWD500 double precision, LWD630 double precision,LWD800 double precision," +
            "LWD1000 double precision,LWD1250 double precision,LWD1600 double precision,LWD2000 double precision," +
            "LWD2500 double precision,LWD3150 double precision, LWD4000 double precision,LWD5000 double precision," +
            "LWD6300 double precision,LWD8000 double precision,LWD10000 double precision," +

            "LWE50 double precision,LWE63 double precision,LWE80 double precision, LWE125 double precision," +
            "LWE160 double precision,LWE200 double precision, LWE250 double precision,LWE315 double precision," +
            "LWE400 double precision, LWE500 double precision, LWE630 double precision,LWE800 double precision," +
            "LWE1000 double precision,LWE1250 double precision,LWE1600 double precision,LWE2000 double precision," +
            "LWE2500 double precision,LWE3150 double precision, LWE4000 double precision,LWE5000 double precision," +
            "LWE6300 double precision,LWE8000 double precision,LWE10000 double precision," +

            "LWN50 double precision,LWN63 double precision,LWN80 double precision, LWN125 double precision," +
            "LWN160 double precision,LWN200 double precision, LWN250 double precision,LWN315 double precision," +
            "LWN400 double precision, LWN500 double precision, LWN630 double precision,LWN800 double precision," +
            "LWN1000 double precision,LWN1250 double precision,LWN1600 double precision,LWN2000 double precision," +
            "LWN2500 double precision,LWN3150 double precision, LWN4000 double precision,LWN5000 double precision," +
            "LWN6300 double precision,LWN8000 double precision,LWN10000 double precision);")

    def qry00 = 'INSERT INTO LW_RAILWAY(ID_SECTION, the_geom, DIRECTIVITYID, HEIGHT,' +
            'LWD50,LWD63,LWD80, LWD125, LWD160, LWD200, LWD250, LWD315, LWD400, LWD500, LWD630, LWD800,LWD1000,' +
            'LWD1250, LWD1600, LWD2000, LWD2500, LWD3150, LWD4000, LWD5000, LWD6300,LWD8000, LWD10000,' +

            'LWE50,LWE63,LWE80, LWE125, LWE160, LWE200, LWE250, LWE315, LWE400, LWE500, LWE630, LWE800,LWE1000,' +
            'LWE1250, LWE1600, LWE2000, LWE2500, LWE3150, LWE4000, LWE5000, LWE6300,LWE8000, LWE10000,' +

            'LWN50,LWN63,LWN80, LWN125, LWN160, LWN200, LWN250, LWN315, LWN400, LWN500, LWN630, LWN800,LWN1000,' +
            'LWN1250, LWN1600, LWN2000, LWN2500, LWN3150, LWN4000, LWN5000, LWN6300,LWN8000, LWN10000) ' +
            'VALUES (?,?,?,?,' +
            '?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,' +
            '?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,' +
            '?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?);'

    // --------------------------------------
    // Start calculation and fill the table
    // --------------------------------------

    // Get Class to compute LW

    LDENConfig ldenConfig = new LDENConfig(LDENConfig.INPUT_MODE.INPUT_MODE_RAILWAY_FLOW)
    ldenConfig.setPropagationProcessPathData(new PropagationProcessPathData())
    ldenConfig.setCoefficientVersion(2)

    LDENPropagationProcessData process = new LDENPropagationProcessData(null, ldenConfig);

    // Get size of the table (number of rail segments
    PreparedStatement st = connection.prepareStatement("SELECT COUNT(*) AS total FROM " + sources_geom_table_name)
    SpatialResultSet rs1 = st.executeQuery().unwrap(SpatialResultSet.class)

    while (rs1.next()) {
        nSection = rs1.getInt("total")
        System.println('The table Rail Geom has ' + nSection + ' rail segments.')
    }

    RailWayLWIterator railWayLWIterator = new RailWayLWIterator(connection, sources_geom_table_name, sources_table_traffic_name, ldenConfig)
    RailWayLWIterator.RailWayLWGeom railWayLWGeom;

    while ((railWayLWGeom = railWayLWIterator.next()) != null) {
        RailWayLW railWayLWDay = railWayLWGeom.getRailWayLWDay()
        RailWayLW railWayLWEvening = railWayLWGeom.getRailWayLWEvening()
        RailWayLW railWayLWNight = railWayLWGeom.getRailWayLWNight()
        List<LineString> geometries = railWayLWGeom.getRailWayLWGeometry(2) //set distance between Rail
        int pk = railWayLWGeom.getPK()
        double[] LWDay
        double[] LWEvening
        double[] LWNight
        double heightSource
        int directivityId
        for (int iSource = 0; iSource < 6; iSource++) {
            switch (iSource) {
                case 0:
                    LWDay = railWayLWDay.getLWRolling()
                    LWEvening = railWayLWEvening.getLWRolling()
                    LWNight = railWayLWNight.getLWRolling()
                    heightSource = 0.5
                    directivityId = 1
                    break
                case 1:
                    LWDay = railWayLWDay.getLWTractionA()
                    LWEvening = railWayLWEvening.getLWTractionA()
                    LWNight = railWayLWNight.getLWTractionA()
                    heightSource = 0.5
                    directivityId = 2
                    break
                case 2:
                    LWDay = railWayLWDay.getLWTractionB()
                    LWEvening = railWayLWEvening.getLWTractionB()
                    LWNight = railWayLWNight.getLWTractionB()
                    heightSource = 4
                    directivityId = 3
                    break
                case 3:
                    LWDay = railWayLWDay.getLWAerodynamicA()
                    LWEvening = railWayLWEvening.getLWAerodynamicA()
                    LWNight = railWayLWNight.getLWAerodynamicA()
                    heightSource = 0.5
                    directivityId = 4
                    break
                case 4:
                    LWDay = railWayLWDay.getLWAerodynamicB()
                    LWEvening = railWayLWEvening.getLWAerodynamicB()
                    LWNight = railWayLWNight.getLWAerodynamicB()
                    heightSource = 4
                    directivityId = 5
                    break
                case 5:
                    LWDay = railWayLWDay.getLWBridge()
                    LWEvening = railWayLWEvening.getLWBridge()
                    LWNight = railWayLWNight.getLWBridge()
                    heightSource = 0.5
                    directivityId = 6
                    break
            }
            for (int nTrack = 0; nTrack < geometries.size(); nTrack++) {

                sql.withBatch(100, qry00) { ps ->
                    ps.addBatch(
                            pk as int, (Geometry) geometries.get(nTrack) as Geometry, directivityId as int, heightSource as double,
                            LWDay[0], LWDay[1], LWDay[2], LWDay[3],
                            LWDay[4], LWDay[5], LWDay[6], LWDay[7],
                            LWDay[8], LWDay[9], LWDay[10], LWDay[11],
                            LWDay[12], LWDay[13], LWDay[14], LWDay[15],
                            LWDay[16], LWDay[17], LWDay[18], LWDay[19],
                            LWDay[20], LWDay[21], LWDay[22],

                            LWEvening[0], LWEvening[1], LWEvening[2], LWEvening[3],
                            LWEvening[4], LWEvening[5], LWEvening[6], LWEvening[7],
                            LWEvening[8], LWEvening[9], LWEvening[10], LWEvening[11],
                            LWEvening[12], LWEvening[13], LWEvening[14], LWEvening[15],
                            LWEvening[16], LWEvening[17], LWEvening[18], LWEvening[19],
                            LWEvening[20], LWEvening[21], LWEvening[22],

                            LWNight[0], LWNight[1], LWNight[2], LWNight[3],
                            LWNight[4], LWNight[5], LWNight[6], LWNight[7],
                            LWNight[8], LWNight[9], LWNight[10], LWNight[11],
                            LWNight[12], LWNight[13], LWNight[14], LWNight[15],
                            LWNight[16], LWNight[17], LWNight[18], LWNight[19],
                            LWNight[20], LWNight[21], LWNight[22]
                    )

                }
            }
        }

    }


    // Fusion geometry and traffic table

    // Add Z dimension to the rail segments
    sql.execute("UPDATE LW_RAILWAY SET THE_GEOM = ST_UPDATEZ(The_geom,0.01);")

    // Add primary key to the LW table
    sql.execute("ALTER TABLE  LW_RAILWAY  ADD PK INT AUTO_INCREMENT PRIMARY KEY;")


    resultString = "Calculation Done ! The table LW_RAILWAY has been created."

    // print to command window
    System.out.println('\nResult : ' + resultString)
    System.out.println('End : LW_RAILWAY from Emission')
    // System.out.println('Duration : ' + TimeCategory.minus(new Date(), start))

    // print to WPS Builder
    return resultString

}


