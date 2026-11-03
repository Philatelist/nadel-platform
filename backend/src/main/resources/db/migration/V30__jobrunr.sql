-- Feature 017: the tables of JobRunr 9.0.1 (org.jobrunr:jobrunr-spring-boot-4-starter, pom.xml), which schedules the
-- delivery workers (ADR 0007). JobRunr runs with jobrunr.database.skip-create=true, so it never creates or alters
-- tables itself: its schema comes only from Flyway, owned by the migration role, like every other table.
--
-- The statements are the PostgreSQL migrations of exactly that version, copied in order from the jar
-- (org/jobrunr/storage/sql/common/migrations/v000..v017, with org/jobrunr/storage/sql/postgres/migrations/v014 in
-- place of the common v014, as JobRunr itself picks them for PostgreSQL), default table prefix (none, schema public).
-- Empty migrations of the jar (v008, v011, v012, v017) have no statements. jobrunr_migrations records the scripts so
-- that the state is the one JobRunr would have created. A JobRunr upgrade brings its new scripts in a new migration.
--
-- No tenant_id and no RLS, deliberately, like event_publication: these are infrastructure rows without cooperative
-- data. Job arguments are a cooperative id and a channel only (DispatchDeliveries); the background job server polls
-- them without a bound cooperative, where a tenant policy would hide every job. The job bodies bind the cooperative
-- themselves (TenantContext.runAs) before they touch cooperative tables.
-- The previous release never reads these tables.

SET LOCAL lock_timeout = '10s';

-- v000__create_migrations_table.sql
CREATE TABLE jobrunr_migrations
(
    id          nchar(36) PRIMARY KEY,
    script      varchar(64) NOT NULL,
    installedOn varchar(29) NOT NULL
);

-- v001__create_job_table.sql
CREATE TABLE jobrunr_jobs
(
    id           NCHAR(36) PRIMARY KEY,
    version      int          NOT NULL,
    jobAsJson    text         NOT NULL,
    jobSignature VARCHAR(512) NOT NULL,
    state        VARCHAR(36)  NOT NULL,
    createdAt    TIMESTAMP    NOT NULL,
    updatedAt    TIMESTAMP    NOT NULL,
    scheduledAt  TIMESTAMP
);
CREATE INDEX jobrunr_state_idx ON jobrunr_jobs (state);
CREATE INDEX jobrunr_job_signature_idx ON jobrunr_jobs (jobSignature);
CREATE INDEX jobrunr_job_created_at_idx ON jobrunr_jobs (createdAt);
CREATE INDEX jobrunr_job_updated_at_idx ON jobrunr_jobs (updatedAt);
CREATE INDEX jobrunr_job_scheduled_at_idx ON jobrunr_jobs (scheduledAt);

-- v002__create_recurring_job_table.sql
CREATE TABLE jobrunr_recurring_jobs
(
    id        NCHAR(128) PRIMARY KEY,
    version   int  NOT NULL,
    jobAsJson text NOT NULL
);

-- v003__create_background_job_server_table.sql
CREATE TABLE jobrunr_backgroundjobservers
(
    id                     NCHAR(36) PRIMARY KEY,
    workerPoolSize         int           NOT NULL,
    pollIntervalInSeconds  int           NOT NULL,
    firstHeartbeat         TIMESTAMP(6)  NOT NULL,
    lastHeartbeat          TIMESTAMP(6)  NOT NULL,
    running                int           NOT NULL,
    systemTotalMemory      BIGINT        NOT NULL,
    systemFreeMemory       BIGINT        NOT NULL,
    systemCpuLoad          NUMERIC(3, 2) NOT NULL,
    processMaxMemory       BIGINT        NOT NULL,
    processFreeMemory      BIGINT        NOT NULL,
    processAllocatedMemory BIGINT        NOT NULL,
    processCpuLoad         NUMERIC(3, 2) NOT NULL
);
CREATE INDEX jobrunr_bgjobsrvrs_fsthb_idx ON jobrunr_backgroundjobservers (firstHeartbeat);
CREATE INDEX jobrunr_bgjobsrvrs_lsthb_idx ON jobrunr_backgroundjobservers (lastHeartbeat);

-- v004__create_job_stats_view.sql
CREATE TABLE jobrunr_job_counters
(
    name   NCHAR(36) PRIMARY KEY,
    amount int NOT NULL
);

INSERT INTO jobrunr_job_counters (name, amount)
VALUES ('AWAITING', 0);
INSERT INTO jobrunr_job_counters (name, amount)
VALUES ('SCHEDULED', 0);
INSERT INTO jobrunr_job_counters (name, amount)
VALUES ('ENQUEUED', 0);
INSERT INTO jobrunr_job_counters (name, amount)
VALUES ('PROCESSING', 0);
INSERT INTO jobrunr_job_counters (name, amount)
VALUES ('FAILED', 0);
INSERT INTO jobrunr_job_counters (name, amount)
VALUES ('SUCCEEDED', 0);

CREATE VIEW jobrunr_jobs_stats
AS
SELECT count(*)                                                                           AS total,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'AWAITING')             AS awaiting,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'SCHEDULED')            AS scheduled,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'ENQUEUED')             AS enqueued,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'PROCESSING')           AS processing,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'FAILED')               AS failed,
       (SELECT((SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'SUCCEEDED') +
               (SELECT amount FROM jobrunr_job_counters jc WHERE jc.name = 'SUCCEEDED'))) AS succeeded,
       (SELECT count(*) FROM jobrunr_backgroundjobservers)                                AS nbrOfBackgroundJobServers,
       (SELECT count(*) FROM jobrunr_recurring_jobs)                                      AS nbrOfRecurringJobs
FROM jobrunr_jobs j;

-- v005__update_job_stats_view.sql
DROP VIEW jobrunr_jobs_stats;

CREATE VIEW jobrunr_jobs_stats
AS
SELECT count(*)                                                                           AS total,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'AWAITING')             AS awaiting,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'SCHEDULED')            AS scheduled,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'ENQUEUED')             AS enqueued,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'PROCESSING')           AS processing,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'FAILED')               AS failed,
       (SELECT((SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'SUCCEEDED') +
               (SELECT amount FROM jobrunr_job_counters jc WHERE jc.name = 'SUCCEEDED'))) AS succeeded,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'DELETED')              AS deleted,
       (SELECT count(*) FROM jobrunr_backgroundjobservers)                                AS nbrOfBackgroundJobServers,
       (SELECT count(*) FROM jobrunr_recurring_jobs)                                      AS nbrOfRecurringJobs
FROM jobrunr_jobs j;

-- v006__alter_table_jobs_add_recurringjob.sql
ALTER TABLE jobrunr_jobs
    ADD recurringJobId VARCHAR(128);
CREATE INDEX jobrunr_job_rci_idx ON jobrunr_jobs (recurringJobId);

-- v007__alter_table_backgroundjobserver_add_delete_config.sql
ALTER TABLE jobrunr_backgroundjobservers
    ADD deleteSucceededJobsAfter VARCHAR(32);
ALTER TABLE jobrunr_backgroundjobservers
    ADD permanentlyDeleteJobsAfter VARCHAR(32);

-- v008__alter_table_jobs_increase_jobAsJson_size.sql: empty migration

-- v009__change_jobrunr_job_counters_to_jobrunr_metadata.sql
CREATE TABLE jobrunr_metadata
(
    id        varchar(156) PRIMARY KEY,
    name      varchar(92) NOT NULL,
    owner     varchar(64) NOT NULL,
    value     text        NOT NULL,
    createdAt TIMESTAMP   NOT NULL,
    updatedAt TIMESTAMP   NOT NULL
);

INSERT INTO jobrunr_metadata (id, name, owner, value, createdAt, updatedAt)
VALUES ('succeeded-jobs-counter-cluster', 'succeeded-jobs-counter', 'cluster',
        cast((SELECT amount FROM jobrunr_job_counters WHERE name = 'SUCCEEDED') AS char(10)), CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP);

DROP VIEW jobrunr_jobs_stats;
DROP TABLE jobrunr_job_counters;

CREATE VIEW jobrunr_jobs_stats
AS
SELECT count(*)                                                                 AS total,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'AWAITING')   AS awaiting,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'SCHEDULED')  AS scheduled,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'ENQUEUED')   AS enqueued,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'PROCESSING') AS processing,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'FAILED')     AS failed,
       (SELECT((SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'SUCCEEDED') +
               (SELECT cast(cast(value AS char(10)) AS decimal(10, 0))
                FROM jobrunr_metadata jm
                WHERE jm.id = 'succeeded-jobs-counter-cluster')))               AS succeeded,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'DELETED')    AS deleted,
       (SELECT count(*) FROM jobrunr_backgroundjobservers)                      AS nbrOfBackgroundJobServers,
       (SELECT count(*) FROM jobrunr_recurring_jobs)                            AS nbrOfRecurringJobs
FROM jobrunr_jobs j;

-- v010__change_job_stats.sql
DROP VIEW jobrunr_jobs_stats;

CREATE VIEW jobrunr_jobs_stats
AS
SELECT count(*)                                                                 AS total,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'AWAITING')   AS awaiting,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'SCHEDULED')  AS scheduled,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'ENQUEUED')   AS enqueued,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'PROCESSING') AS processing,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'FAILED')     AS failed,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'SUCCEEDED')  AS succeeded,
       (SELECT cast(cast(value AS char(10)) AS decimal(10, 0))
        FROM jobrunr_metadata jm
        WHERE jm.id = 'succeeded-jobs-counter-cluster')                         AS allTimeSucceeded,
       (SELECT count(*) FROM jobrunr_jobs jobs WHERE jobs.state = 'DELETED')    AS deleted,
       (SELECT count(*) FROM jobrunr_backgroundjobservers)                      AS nbrOfBackgroundJobServers,
       (SELECT count(*) FROM jobrunr_recurring_jobs)                            AS nbrOfRecurringJobs
FROM jobrunr_jobs j;

-- v011__change_sqlserver_text_to_varchar.sql: empty migration
-- v012__change_oracle_alter_jobrunr_metadata_column_size.sql: empty migration

-- v013__alter_table_recurring_job_add_createdAt.sql
ALTER TABLE jobrunr_recurring_jobs
    ADD createdAt BIGINT NOT NULL DEFAULT '0';
CREATE INDEX jobrunr_recurring_job_created_at_idx ON jobrunr_recurring_jobs (createdAt);

-- v014__improve_job_stats.sql (the PostgreSQL version)
DROP VIEW jobrunr_jobs_stats;
CREATE VIEW jobrunr_jobs_stats
AS
with job_stat_results AS (SELECT state, count(*) AS count
    FROM jobrunr_jobs
    GROUP BY ROLLUP (state
)
)
SELECT coalesce((SELECT count FROM job_stat_results WHERE state IS NULL), 0)        AS total,
       coalesce((SELECT count FROM job_stat_results WHERE state = 'SCHEDULED'), 0)  AS scheduled,
       coalesce((SELECT count FROM job_stat_results WHERE state = 'ENQUEUED'), 0)   AS enqueued,
       coalesce((SELECT count FROM job_stat_results WHERE state = 'PROCESSING'), 0) AS processing,
       coalesce((SELECT count FROM job_stat_results WHERE state = 'FAILED'), 0)     AS failed,
       coalesce((SELECT count FROM job_stat_results WHERE state = 'SUCCEEDED'), 0)  AS succeeded,
       coalesce((SELECT cast(cast(value AS char(10)) AS decimal(10, 0))
                 FROM jobrunr_metadata jm
                 WHERE jm.id = 'succeeded-jobs-counter-cluster'), 0)                AS allTimeSucceeded,
       coalesce((SELECT count FROM job_stat_results WHERE state = 'DELETED'), 0)    AS deleted,
       (SELECT count(*) FROM jobrunr_backgroundjobservers)                          AS nbrOfBackgroundJobServers,
       (SELECT count(*) FROM jobrunr_recurring_jobs)                                AS nbrOfRecurringJobs;

DROP INDEX jobrunr_job_updated_at_idx;
CREATE INDEX jobrunr_jobs_state_updated_idx ON jobrunr_jobs (state ASC, updatedAt ASC);

-- v015__alter_table_backgroundjobserver_add_name.sql
ALTER TABLE jobrunr_backgroundjobservers
    ADD name VARCHAR(128);

-- v016__alter_jobs_stats_add_awaiting_jobs.sql
DROP VIEW jobrunr_jobs_stats;
CREATE VIEW jobrunr_jobs_stats
AS
with job_stat_results AS (SELECT state, count(*) AS count
    FROM jobrunr_jobs
    GROUP BY state
)
SELECT coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results), 0)                            AS total,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'AWAITING'), 0)   AS awaiting,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'SCHEDULED'), 0)  AS scheduled,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'ENQUEUED'), 0)   AS enqueued,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'PROCESSING'), 0) AS processing,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'PROCESSED'), 0)  AS processed,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'FAILED'), 0)     AS failed,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'SUCCEEDED'), 0)  AS succeeded,
       coalesce((SELECT cASt(cASt(value AS char(10)) AS decimal(10, 0))
                 FROM jobrunr_metadata jm
                 WHERE jm.id = 'succeeded-jobs-counter-cluster'),
                0)                                                                                        AS allTimeSucceeded,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'DELETED'), 0)    AS deleted,
       (SELECT count(*) FROM jobrunr_backgroundjobservers)                                                AS nbrOfBackgroundJobServers,
       (SELECT count(*) FROM jobrunr_recurring_jobs)                                                      AS nbrOfRecurringJobs;

-- v017__change_job_stats_view_to_sql_security_invoker.sql: empty migration

-- ---- the scripts above, as JobRunr's own creator records them

INSERT INTO jobrunr_migrations (id, script, installedOn)
SELECT gen_random_uuid()::text, s.script, to_char(localtimestamp, 'YYYY-MM-DD"T"HH24:MI:SS.US')
FROM (VALUES ('v000__create_migrations_table.sql'), ('v001__create_job_table.sql'),
             ('v002__create_recurring_job_table.sql'), ('v003__create_background_job_server_table.sql'),
             ('v004__create_job_stats_view.sql'), ('v005__update_job_stats_view.sql'),
             ('v006__alter_table_jobs_add_recurringjob.sql'),
             ('v007__alter_table_backgroundjobserver_add_delete_config.sql'),
             ('v008__alter_table_jobs_increase_jobAsJson_size.sql'),
             ('v009__change_jobrunr_job_counters_to_jobrunr_metadata.sql'), ('v010__change_job_stats.sql'),
             ('v011__change_sqlserver_text_to_varchar.sql'),
             ('v012__change_oracle_alter_jobrunr_metadata_column_size.sql'),
             ('v013__alter_table_recurring_job_add_createdAt.sql'), ('v014__improve_job_stats.sql'),
             ('v015__alter_table_backgroundjobserver_add_name.sql'),
             ('v016__alter_jobs_stats_add_awaiting_jobs.sql'),
             ('v017__change_job_stats_view_to_sql_security_invoker.sql')) AS s(script);

-- ---- grants

-- The application role reads and writes the jobs, the servers' heartbeats and the metadata (the default privileges of
-- V1 already give this; stated here because JobRunr cannot work without it). It never changes the schema.
GRANT SELECT, INSERT, UPDATE, DELETE ON jobrunr_jobs, jobrunr_recurring_jobs, jobrunr_backgroundjobservers,
    jobrunr_metadata, jobrunr_migrations TO nadel_app;
GRANT SELECT ON jobrunr_jobs_stats TO nadel_app;
REVOKE TRUNCATE ON jobrunr_jobs, jobrunr_recurring_jobs, jobrunr_backgroundjobservers, jobrunr_metadata,
    jobrunr_migrations FROM nadel_app;
