using System.Collections.Concurrent;
using System.Diagnostics;
using Microsoft.Data.Sqlite;
using StackExchange.Redis;

var builder = WebApplication.CreateBuilder(args);
builder.Services.AddOpenApi();

var app = builder.Build();

if (app.Environment.IsDevelopment())
{
    app.MapOpenApi();
}

// --- Configuration ---

var dgHost = Environment.GetEnvironmentVariable("DG_HOST") ?? "hash-test.apps.ocp4.masales.cloud";
var dgPort = int.Parse(Environment.GetEnvironmentVariable("DG_PORT") ?? "443");
var dgUser = Environment.GetEnvironmentVariable("DG_USER") ?? "application";
var dgPassword = Environment.GetEnvironmentVariable("DG_PASSWORD") ?? "application2026!";
var dgTls = bool.Parse(Environment.GetEnvironmentVariable("DG_TLS") ?? "true");
var dgTimeoutMs = int.Parse(Environment.GetEnvironmentVariable("DG_TIMEOUT_MS") ?? "10000");
var hashCount = int.Parse(Environment.GetEnvironmentVariable("HASH_COUNT") ?? "20");
var fieldsPerHash = int.Parse(Environment.GetEnvironmentVariable("FIELDS_PER_HASH") ?? "50");
var fieldValueSize = int.Parse(Environment.GetEnvironmentVariable("FIELD_VALUE_SIZE") ?? "500");

// --- Metrics ---

var metrics = new ConcurrentDictionary<string, long>
{
    ["cache_hits"] = 0,
    ["cache_misses"] = 0,
    ["cache_timeouts"] = 0,
    ["cache_errors"] = 0,
    ["db_fallbacks"] = 0,
    ["total_requests"] = 0,
};
var cacheLatencies = new ConcurrentBag<double>();
var dbLatencies = new ConcurrentBag<double>();
var timeoutLog = new ConcurrentBag<TimeoutEntry>();

// --- SQLite setup ---

var dbPath = Path.Combine(AppContext.BaseDirectory, "catalogs.db");
var sqliteConnectionString = $"Data Source={dbPath}";

InitializeDatabase(sqliteConnectionString, hashCount, fieldsPerHash, fieldValueSize);

// --- Redis connection ---

var options = new ConfigurationOptions
{
    EndPoints = { { dgHost, dgPort } },
    User = dgUser,
    Password = dgPassword,
    Ssl = dgTls,
    SslProtocols = System.Security.Authentication.SslProtocols.Tls12 | System.Security.Authentication.SslProtocols.Tls13,
    AbortOnConnectFail = false,
    ConnectTimeout = dgTimeoutMs,
    SyncTimeout = dgTimeoutMs,
    AsyncTimeout = dgTimeoutMs,
    Protocol = RedisProtocol.Resp3,
};

if (dgTls)
{
    options.CertificateValidation += (sender, cert, chain, errors) => true;
}

Console.WriteLine("=== Data Grid RESP3 Hash Test API ===");
Console.WriteLine($"Endpoint:  {dgHost}:{dgPort}");
Console.WriteLine($"TLS:       {dgTls}");
Console.WriteLine($"Timeout:   {dgTimeoutMs}ms");
Console.WriteLine($"Protocol:  RESP3");
Console.WriteLine($"Hashes:    {hashCount} x {fieldsPerHash} fields ({hashCount * fieldsPerHash} total entries)");
Console.WriteLine();

ConnectionMultiplexer? redis = null;
try
{
    redis = await ConnectionMultiplexer.ConnectAsync(options);
    Console.WriteLine($"Connected to Data Grid at {redis.GetEndPoints()[0]}");
}
catch (Exception ex)
{
    Console.WriteLine($"WARNING: Redis connection failed: {ex.Message}");
    Console.WriteLine("API will start but all requests will fallback to database.");
}

// --- Seed cache ---

if (redis is { IsConnected: true })
{
    Console.WriteLine("Seeding cache with catalog data...");
    var db = redis.GetDatabase();
    var random = new Random(42);
    var chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    for (int h = 0; h < hashCount; h++)
    {
        var entries = new HashEntry[fieldsPerHash];
        for (int f = 0; f < fieldsPerHash; f++)
        {
            var value = new string(Enumerable.Range(0, fieldValueSize)
                .Select(_ => chars[random.Next(chars.Length)]).ToArray());
            entries[f] = new HashEntry($"field_{f:D4}", value);
        }
        await db.HashSetAsync($"catalog_table_{h:D3}", entries);
    }
    Console.WriteLine($"  Seeded {hashCount} hashes with {fieldsPerHash} fields each.");
}

// --- Endpoints ---

app.MapGet("/catalog/{tableName}/{fieldName}", async (string tableName, string fieldName) =>
{
    metrics.AddOrUpdate("total_requests", 1, (_, v) => v + 1);

    if (redis is { IsConnected: true })
    {
        var sw = Stopwatch.StartNew();
        try
        {
            var result = await redis.GetDatabase().HashGetAsync(tableName, fieldName);
            sw.Stop();
            cacheLatencies.Add(sw.Elapsed.TotalMilliseconds);

            if (!result.IsNull)
            {
                metrics.AddOrUpdate("cache_hits", 1, (_, v) => v + 1);
                return Results.Ok(new
                {
                    source = "cache",
                    table = tableName,
                    field = fieldName,
                    value = result.ToString(),
                    latencyMs = Math.Round(sw.Elapsed.TotalMilliseconds, 1)
                });
            }

            metrics.AddOrUpdate("cache_misses", 1, (_, v) => v + 1);
        }
        catch (RedisTimeoutException ex)
        {
            sw.Stop();
            metrics.AddOrUpdate("cache_timeouts", 1, (_, v) => v + 1);
            timeoutLog.Add(new TimeoutEntry(
                DateTime.UtcNow, tableName, fieldName, "HGET",
                sw.Elapsed.TotalMilliseconds, ex.Message));
            Console.WriteLine($"TIMEOUT: HGET {tableName}/{fieldName} ({sw.Elapsed.TotalMilliseconds:F1}ms)");
        }
        catch (Exception ex)
        {
            sw.Stop();
            metrics.AddOrUpdate("cache_errors", 1, (_, v) => v + 1);
            Console.WriteLine($"ERROR: HGET {tableName}/{fieldName} - {ex.GetType().Name}: {ex.Message}");
        }
    }

    metrics.AddOrUpdate("db_fallbacks", 1, (_, v) => v + 1);
    var dbSw = Stopwatch.StartNew();
    var dbValue = QueryDatabase(sqliteConnectionString, tableName, fieldName);
    dbSw.Stop();
    dbLatencies.Add(dbSw.Elapsed.TotalMilliseconds);

    return Results.Ok(new
    {
        source = "database",
        table = tableName,
        field = fieldName,
        value = dbValue,
        latencyMs = Math.Round(dbSw.Elapsed.TotalMilliseconds, 1)
    });
});

app.MapGet("/catalogs", async () =>
{
    metrics.AddOrUpdate("total_requests", 1, (_, v) => v + 1);
    var targetField = $"field_{Random.Shared.Next(fieldsPerHash):D4}";
    var results = new List<object>();

    if (redis is { IsConnected: true })
    {
        var sw = Stopwatch.StartNew();
        try
        {
            var tasks = Enumerable.Range(0, hashCount)
                .Select(h => redis.GetDatabase().HashGetAsync($"catalog_table_{h:D3}", targetField))
                .ToArray();
            var values = await Task.WhenAll(tasks);
            sw.Stop();
            cacheLatencies.Add(sw.Elapsed.TotalMilliseconds);
            metrics.AddOrUpdate("cache_hits", 1, (_, v) => v + 1);

            for (int h = 0; h < hashCount; h++)
            {
                results.Add(new
                {
                    table = $"catalog_table_{h:D3}",
                    field = targetField,
                    value = values[h].ToString(),
                    source = "cache"
                });
            }

            return Results.Ok(new
            {
                source = "cache",
                field = targetField,
                count = results.Count,
                latencyMs = Math.Round(sw.Elapsed.TotalMilliseconds, 1),
                entries = results
            });
        }
        catch (RedisTimeoutException ex)
        {
            sw.Stop();
            metrics.AddOrUpdate("cache_timeouts", 1, (_, v) => v + 1);
            timeoutLog.Add(new TimeoutEntry(
                DateTime.UtcNow, "ALL", targetField, "MULTI-HGET",
                sw.Elapsed.TotalMilliseconds, ex.Message));
            Console.WriteLine($"TIMEOUT: MULTI-HGET ({sw.Elapsed.TotalMilliseconds:F1}ms) - {ex.Message}");
        }
        catch (Exception ex)
        {
            sw.Stop();
            metrics.AddOrUpdate("cache_errors", 1, (_, v) => v + 1);
            Console.WriteLine($"ERROR: MULTI-HGET - {ex.GetType().Name}: {ex.Message}");
        }
    }

    metrics.AddOrUpdate("db_fallbacks", 1, (_, v) => v + 1);
    var dbSw = Stopwatch.StartNew();
    for (int h = 0; h < hashCount; h++)
    {
        var tableName = $"catalog_table_{h:D3}";
        var dbValue = QueryDatabase(sqliteConnectionString, tableName, targetField);
        results.Add(new
        {
            table = tableName,
            field = targetField,
            value = dbValue,
            source = "database"
        });
    }
    dbSw.Stop();
    dbLatencies.Add(dbSw.Elapsed.TotalMilliseconds);

    return Results.Ok(new
    {
        source = "database",
        field = targetField,
        count = results.Count,
        latencyMs = Math.Round(dbSw.Elapsed.TotalMilliseconds, 1),
        entries = results
    });
});

app.MapGet("/metrics", () =>
{
    var cacheLatList = cacheLatencies.ToArray();
    var dbLatList = dbLatencies.ToArray();
    Array.Sort(cacheLatList);
    Array.Sort(dbLatList);

    return Results.Ok(new
    {
        counters = metrics.ToDictionary(kv => kv.Key, kv => kv.Value),
        cacheLatency = cacheLatList.Length > 0 ? new
        {
            count = cacheLatList.Length,
            min = Math.Round(cacheLatList[0], 1),
            median = Math.Round(cacheLatList[cacheLatList.Length / 2], 1),
            p95 = Math.Round(cacheLatList[(int)(cacheLatList.Length * 0.95)], 1),
            p99 = Math.Round(cacheLatList[(int)(cacheLatList.Length * 0.99)], 1),
            max = Math.Round(cacheLatList[^1], 1),
        } : null,
        dbLatency = dbLatList.Length > 0 ? new
        {
            count = dbLatList.Length,
            min = Math.Round(dbLatList[0], 1),
            median = Math.Round(dbLatList[dbLatList.Length / 2], 1),
            p95 = Math.Round(dbLatList[(int)(dbLatList.Length * 0.95)], 1),
            p99 = Math.Round(dbLatList[(int)(dbLatList.Length * 0.99)], 1),
            max = Math.Round(dbLatList[^1], 1),
        } : null,
        recentTimeouts = timeoutLog.OrderByDescending(t => t.Timestamp).Take(20)
    });
});

app.MapGet("/metrics/reset", () =>
{
    foreach (var key in metrics.Keys)
        metrics[key] = 0;
    cacheLatencies.Clear();
    dbLatencies.Clear();
    timeoutLog.Clear();
    return Results.Ok(new { message = "Metrics reset" });
});

app.MapGet("/health", () => Results.Ok(new
{
    status = "UP",
    redis = redis is { IsConnected: true } ? "connected" : "disconnected",
    database = "SQLite (embedded)"
}));

app.Run();

// --- Helper methods ---

static void InitializeDatabase(string connectionString, int hashCount, int fieldsPerHash, int fieldValueSize)
{
    using var connection = new SqliteConnection(connectionString);
    connection.Open();

    using var cmd = connection.CreateCommand();
    cmd.CommandText = """
        CREATE TABLE IF NOT EXISTS catalog_entries (
            table_name TEXT NOT NULL,
            field_name TEXT NOT NULL,
            field_value TEXT NOT NULL,
            PRIMARY KEY (table_name, field_name)
        )
        """;
    cmd.ExecuteNonQuery();

    cmd.CommandText = "SELECT COUNT(*) FROM catalog_entries";
    var count = (long)cmd.ExecuteScalar()!;
    if (count > 0)
    {
        Console.WriteLine($"Database already populated ({count} entries).");
        return;
    }

    Console.WriteLine("Populating SQLite database...");
    var random = new Random(42);
    var chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    using var transaction = connection.BeginTransaction();
    using var insertCmd = connection.CreateCommand();
    insertCmd.CommandText = "INSERT INTO catalog_entries (table_name, field_name, field_value) VALUES ($table, $field, $value)";
    var tableParam = insertCmd.Parameters.Add("$table", SqliteType.Text);
    var fieldParam = insertCmd.Parameters.Add("$field", SqliteType.Text);
    var valueParam = insertCmd.Parameters.Add("$value", SqliteType.Text);

    for (int h = 0; h < hashCount; h++)
    {
        tableParam.Value = $"catalog_table_{h:D3}";
        for (int f = 0; f < fieldsPerHash; f++)
        {
            fieldParam.Value = $"field_{f:D4}";
            valueParam.Value = new string(Enumerable.Range(0, fieldValueSize)
                .Select(_ => chars[random.Next(chars.Length)]).ToArray());
            insertCmd.ExecuteNonQuery();
        }
    }
    transaction.Commit();
    Console.WriteLine($"  Inserted {hashCount * fieldsPerHash} entries into SQLite.");
}

static string? QueryDatabase(string connectionString, string tableName, string fieldName)
{
    using var connection = new SqliteConnection(connectionString);
    connection.Open();
    using var cmd = connection.CreateCommand();
    cmd.CommandText = "SELECT field_value FROM catalog_entries WHERE table_name = $table AND field_name = $field";
    cmd.Parameters.AddWithValue("$table", tableName);
    cmd.Parameters.AddWithValue("$field", fieldName);
    return cmd.ExecuteScalar()?.ToString();
}

record TimeoutEntry(DateTime Timestamp, string Table, string Field, string Operation, double ElapsedMs, string Error);
