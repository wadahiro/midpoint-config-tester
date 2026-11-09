-- Another system's audit table that a script reads over JDBC (DatabaseScenarioTest)
CREATE TABLE m_audit_event (
    id SERIAL PRIMARY KEY,
    eventType INT4 NOT NULL,
    targetOid VARCHAR(36),
    targetName VARCHAR(255),
    timestampValue TIMESTAMP
);
INSERT INTO m_audit_event (eventType, targetOid, targetName, timestampValue)
    VALUES (3, '0000-seed', 'seeded', TIMESTAMP '2020-01-01 00:00:00');
