package com.bscllc.learning.citibike;

import com.bscllc.learning.citibike.db.DataAccessException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.sql.SQLException;

/** Compiled only into the test artifact; exercises the real HTTP exception mapper. */
@Path("/test/failure")
public class TestOnlyFailureResource {
    @GET @Path("/jdbc")
    public String jdbc() {
        throw new DataAccessException("LEAK_PROBE SELECT secret", new SQLException("LEAK_PROBE SQL and station input"));
    }

    @GET @Path("/unexpected")
    public String unexpected() {
        throw new IllegalStateException("LEAK_PROBE unexpected detail");
    }
}
