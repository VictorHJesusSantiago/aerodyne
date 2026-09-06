package io.aerodyne.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Aerodyne flight planning service.
 *
 * <p>The engine underneath is plain Java with no framework in it: the geodesy, the ARINC parser,
 * the GRIB decoder, the fuel integration and the ETOPS assessment all run without Spring and are
 * tested without it. This module is the part that makes them reachable — persistence on PostGIS,
 * REST endpoints, and the wiring between them — and it deliberately holds no planning logic of
 * its own.
 */
@SpringBootApplication
public class AerodyneApplication {

    public static void main(String[] args) {
        SpringApplication.run(AerodyneApplication.class, args);
    }
}
