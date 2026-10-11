package com.bscllc.learning.citibike.download;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Read-only sanitized status; this endpoint cannot trigger downloads. */
@Path("/api/v1/download/status")
@Produces(MediaType.APPLICATION_JSON)
public class DownloadStatusResource {
    @Inject DownloadCoordinator coordinator;
    @GET public DownloadCoordinator.Status status() { return coordinator.status(); }
}
