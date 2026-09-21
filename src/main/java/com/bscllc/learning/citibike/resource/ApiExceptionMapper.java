package com.bscllc.learning.citibike.resource;

import com.bscllc.learning.citibike.db.DataAccessException;
import com.bscllc.learning.citibike.db.DatabaseBusyException;
import com.bscllc.learning.citibike.dto.ApiErrorResponse;
import com.bscllc.learning.citibike.validation.InvalidRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

@Provider
public class ApiExceptionMapper implements ExceptionMapper<Throwable> {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionMapper.class);

    @Override
    public Response toResponse(Throwable exception) {
        if (exception instanceof InvalidRequestException) {
            return response(Response.Status.BAD_REQUEST, "invalid_request", exception.getMessage());
        }
        if (exception instanceof DatabaseBusyException) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "1")
                    .entity(error("database_unavailable", "Ride data is temporarily unavailable"))
                    .build();
        }
        if (exception instanceof WebApplicationException webException) {
            int status = webException.getResponse().getStatus();
            return Response.status(status)
                    .entity(error("http_error", "The requested resource could not be served"))
                    .build();
        }
        if (exception instanceof DataAccessException) {
            LOG.error("DuckDB request failed", exception);
        } else {
            LOG.error("Unexpected API failure", exception);
        }
        return response(Response.Status.INTERNAL_SERVER_ERROR,
                "internal_error", "The request could not be completed");
    }

    private static Response response(Response.Status status, String code, String message) {
        return Response.status(status).entity(error(code, message)).build();
    }

    private static ApiErrorResponse error(String code, String message) {
        return new ApiErrorResponse(Instant.now(), code, message);
    }
}
