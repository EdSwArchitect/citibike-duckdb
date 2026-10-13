package com.bscllc.learning.citibike.resource;

import com.bscllc.learning.citibike.ingestion.*;
import com.bscllc.learning.citibike.validation.InvalidRequestException;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;

@Path("/api/v1/admin/ingestion")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ingestion-admin")
@Blocking
public class IngestionAdminResource {
    @Inject IngestionConfig config;
    @Inject IngestionCoordinator coordinator;
    @Inject IngestionRepository repository;
    public record MonthRequest(String month,String city) {}
    public record BackfillRequest(String from,String to,String city) {}
    private void enabled(){if(!config.adminEnabled()||!coordinator.enabled())throw new NotFoundException();}
    @POST @Path("/months")
    public Response month(MonthRequest request){enabled();if(request==null)throw new InvalidRequestException("A month is required");
        var jobs=coordinator.enqueue(request.city(),request.month(),request.month(),"manual");return Response.accepted(jobs.getFirst()).build();}
    @POST @Path("/backfills")
    public Response backfill(BackfillRequest request){enabled();if(request==null)throw new InvalidRequestException("Backfill bounds are required");
        return Response.accepted(coordinator.enqueue(request.city(),request.from(),request.to(),"backfill")).build();}
    @GET @Path("/jobs")
    public List<IngestionRepository.Job> jobs(@QueryParam("status")String status,@QueryParam("limit")@DefaultValue("20")int limit,@QueryParam("offset")@DefaultValue("0")int offset){
        enabled();if(limit<1||limit>100||offset<0||offset>10000)throw new InvalidRequestException("Pagination exceeds ingestion bounds");return repository.jobs(status,limit,offset);}
    @GET @Path("/jobs/{id}")
    public IngestionRepository.Job job(@PathParam("id")String id){enabled();if(!id.matches("[a-f0-9-]{36}"))throw new InvalidRequestException("Invalid job ID");
        var job=repository.get(id);if(job==null)throw new NotFoundException();return job;}
    @GET @Path("/status")
    public IngestionRepository.Status status(){enabled();return repository.status();}
}
