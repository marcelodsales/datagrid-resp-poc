package com.redhat.demo.cache;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/api/cache")
@Produces(MediaType.APPLICATION_JSON)
public class CacheController {

    @Inject
    CacheService cacheService;

    @PUT
    @Path("/{key}")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response put(@PathParam("key") String key, String value) {
        cacheService.put(key, value);
        return Response.ok(Map.of("key", key, "value", value, "action", "stored")).build();
    }

    @GET
    @Path("/{key}")
    public Response get(@PathParam("key") String key) {
        String value = cacheService.get(key);
        if (value == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("key", key, "error", "not found"))
                    .build();
        }
        return Response.ok(Map.of("key", key, "value", value)).build();
    }

    @DELETE
    @Path("/{key}")
    public Response delete(@PathParam("key") String key) {
        cacheService.delete(key);
        return Response.ok(Map.of("key", key, "action", "deleted")).build();
    }

    @GET
    @Path("/keys")
    public Response keys() {
        return Response.ok(Map.of("keys", cacheService.keys())).build();
    }

    @GET
    @Path("/info")
    public Response info() {
        return Response.ok(cacheService.info()).build();
    }
}
