package com.bscllc.learning.citibike.db;

import java.sql.*;

/** Versioned storage migrations; partition publication and audit pointers share a transaction. */
public final class SchemaMigrator {
    private SchemaMigrator() {}
    public static void migrate(Connection c) throws SQLException {
        c.setAutoCommit(false);
        try (Statement s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_versions(version INTEGER PRIMARY KEY, applied_at TIMESTAMP NOT NULL)");
            try (ResultSet r=s.executeQuery("SELECT max(version) FROM schema_versions")) {
                r.next(); if(r.getInt(1)>3)throw new SQLException("Unsupported schema version");
            }
            s.execute("""
                CREATE TABLE IF NOT EXISTS rides (
                  ride_id VARCHAR NOT NULL, rideable_type VARCHAR NOT NULL, started_at TIMESTAMP NOT NULL,
                  ended_at TIMESTAMP NOT NULL, start_station_name VARCHAR, start_station_id VARCHAR,
                  end_station_name VARCHAR, end_station_id VARCHAR, start_lat DOUBLE NOT NULL, start_lng DOUBLE NOT NULL,
                  end_lat DOUBLE, end_lng DOUBLE, member_casual VARCHAR NOT NULL, source_file VARCHAR NOT NULL,
                  source_city VARCHAR NOT NULL, source_month VARCHAR NOT NULL, source_version VARCHAR NOT NULL)
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS ingestion_jobs (
                  id VARCHAR PRIMARY KEY, city VARCHAR NOT NULL, month VARCHAR NOT NULL, trigger VARCHAR NOT NULL,
                  status VARCHAR NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, reason VARCHAR,
                  created_at TIMESTAMP NOT NULL, started_at TIMESTAMP, finished_at TIMESTAMP, next_retry TIMESTAMP,
                  version_id VARCHAR, rows BIGINT NOT NULL DEFAULT 0)
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS ingestion_attempts (
                  job_id VARCHAR NOT NULL, attempt INTEGER NOT NULL, started_at TIMESTAMP NOT NULL,
                  finished_at TIMESTAMP,status VARCHAR NOT NULL,reason VARCHAR,rows BIGINT DEFAULT 0,
                  PRIMARY KEY(job_id,attempt))
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS archive_versions (
                  id VARCHAR PRIMARY KEY, object_key VARCHAR NOT NULL, etag VARCHAR, upstream_version VARCHAR,
                  modified VARCHAR, bytes BIGINT NOT NULL, sha256 VARCHAR NOT NULL, archive VARCHAR NOT NULL,
                  downloaded_at TIMESTAMP NOT NULL)
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS active_partitions (
                  city VARCHAR NOT NULL, month VARCHAR NOT NULL, version_id VARCHAR NOT NULL,
                  job_id VARCHAR NOT NULL, materialization_sha256 VARCHAR NOT NULL, nested_entry VARCHAR,
                  csv_count INTEGER NOT NULL, rows BIGINT NOT NULL, minimum_started TIMESTAMP NOT NULL,
                  maximum_started TIMESTAMP NOT NULL, policy VARCHAR NOT NULL, committed_at TIMESTAMP NOT NULL,
                  PRIMARY KEY(city,month))
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS source_csv (
                  version_id VARCHAR NOT NULL, city VARCHAR NOT NULL, month VARCHAR NOT NULL,
                  entry VARCHAR NOT NULL, provenance VARCHAR NOT NULL, sha256 VARCHAR NOT NULL,
                  rows BIGINT NOT NULL, missing_start_station BIGINT NOT NULL, missing_end_station BIGINT NOT NULL,
                  missing_end_coordinates BIGINT NOT NULL, outliers BIGINT NOT NULL,
                  PRIMARY KEY(city,month,entry))
                """);
            s.execute("INSERT INTO schema_versions SELECT 1,current_timestamp WHERE NOT EXISTS(SELECT 1 FROM schema_versions WHERE version=1)");
            long rideColumns;
            try(var r=s.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_name='rides'")){r.next();rideColumns=r.getLong(1);}
            if(rideColumns!=17)throw new SQLException("Unsupported existing rides schema");
            boolean needsV2;try(var r=s.executeQuery("SELECT count(*) FROM schema_versions WHERE version=2")){r.next();needsV2=r.getLong(1)==0;}
            if(needsV2) {
                s.execute("ALTER TABLE rides ALTER COLUMN start_lat DROP NOT NULL");
                s.execute("ALTER TABLE rides ALTER COLUMN start_lng DROP NOT NULL");
                s.execute("ALTER TABLE source_csv ADD COLUMN missing_start_coordinates BIGINT DEFAULT 0");
                s.execute("INSERT INTO schema_versions VALUES(2,current_timestamp)");
            }
            boolean needsV3;try(var r=s.executeQuery("SELECT count(*) FROM schema_versions WHERE version=3")){r.next();needsV3=r.getLong(1)==0;}
            if(needsV3) {
                s.execute("ALTER TABLE ingestion_attempts ADD COLUMN trigger VARCHAR DEFAULT 'unknown'");
                s.execute("INSERT INTO schema_versions VALUES(3,current_timestamp)");
            }
            c.commit();
        } catch(SQLException|RuntimeException e) { c.rollback(); throw e; }
        finally { c.setAutoCommit(true); }
    }
}
