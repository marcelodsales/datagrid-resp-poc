package hashtest;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class HashTestSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:5100");
    private static final int HASH_COUNT = Integer.getInteger("hashCount", 20);
    private static final int FIELDS_PER_HASH = Integer.getInteger("fieldsPerHash", 50);

    HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .shareConnections();

    // Random table and field feeders
    Iterator<java.util.Map<String, Object>> tableFieldFeeder =
            Stream.generate(() -> Map.<String, Object>of(
                    "tableName", String.format("catalog_table_%03d", ThreadLocalRandom.current().nextInt(HASH_COUNT)),
                    "fieldName", String.format("field_%04d", ThreadLocalRandom.current().nextInt(FIELDS_PER_HASH))
            )).iterator();

    // Scenario 1: Single HGET — one cache lookup per request
    ScenarioBuilder singleHget = scenario("Single HGET")
            .feed(tableFieldFeeder)
            .exec(
                    http("HGET #{tableName}/#{fieldName}")
                            .get("/catalog/#{tableName}/#{fieldName}")
                            .check(status().is(200))
                            .check(jsonPath("$.source").saveAs("source"))
            );

    // Scenario 2: Multi-HGET — 20 parallel hash lookups per request (customer pattern)
    ScenarioBuilder multiHget = scenario("Multi HGET (20 parallel)")
            .exec(
                    http("MULTI-HGET /catalogs")
                            .get("/catalogs")
                            .check(status().is(200))
                            .check(jsonPath("$.source").saveAs("source"))
                            .check(jsonPath("$.count").ofInt().is(HASH_COUNT))
            );

    // Scenario 3: Mixed workload — 80% single HGET, 20% multi-HGET
    ScenarioBuilder mixed = scenario("Mixed Workload")
            .randomSwitch().on(
                    percent(80.0).then(
                            feed(tableFieldFeeder).exec(
                                    http("HGET #{tableName}/#{fieldName}")
                                            .get("/catalog/#{tableName}/#{fieldName}")
                                            .check(status().is(200))
                            )
                    ),
                    percent(20.0).then(
                            exec(
                                    http("MULTI-HGET /catalogs")
                                            .get("/catalogs")
                                            .check(status().is(200))
                            )
                    )
            );

    {
        setUp(
                // Phase 1: Warmup with single HGET (low concurrency)
                singleHget.injectOpen(
                        rampUsersPerSec(1).to(10).during(30),
                        constantUsersPerSec(10).during(30)
                ).protocols(httpProtocol),

                // Phase 2: Multi-HGET under load (customer's actual pattern)
                multiHget.injectOpen(
                        nothingFor(10),
                        rampUsersPerSec(1).to(5).during(30),
                        constantUsersPerSec(5).during(60)
                ).protocols(httpProtocol),

                // Phase 3: Mixed workload ramp-up (stress test)
                mixed.injectOpen(
                        nothingFor(30),
                        rampUsersPerSec(1).to(20).during(30),
                        constantUsersPerSec(20).during(60),
                        rampUsersPerSec(20).to(50).during(30)
                ).protocols(httpProtocol)
        ).assertions(
                global().responseTime().percentile3().lt(5000),
                global().failedRequests().percent().lt(5.0)
        );
    }
}
