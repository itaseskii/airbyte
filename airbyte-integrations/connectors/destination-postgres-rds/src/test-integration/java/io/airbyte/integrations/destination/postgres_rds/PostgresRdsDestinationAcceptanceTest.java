/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.postgres_rds;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import io.airbyte.commons.io.IOs;
import io.airbyte.commons.json.Jsons;
import io.airbyte.db.Database;
import io.airbyte.db.factory.DSLContextFactory;
import io.airbyte.db.factory.DatabaseDriver;
import io.airbyte.db.jdbc.JdbcUtils;
import io.airbyte.integrations.base.JavaBaseConstants;
import io.airbyte.integrations.destination.StandardNameTransformer;
import io.airbyte.integrations.standardtest.destination.JdbcDestinationAcceptanceTest;
import io.airbyte.integrations.standardtest.destination.comparator.TestDataComparator;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;

public class PostgresRdsDestinationAcceptanceTest extends JdbcDestinationAcceptanceTest {

  private final StandardNameTransformer namingResolver = new StandardNameTransformer();

  private JsonNode jsonNode;

  private final List<String> dropTables = new ArrayList<>();

  @Override
  protected String getImageName() {
    return "airbyte/destination-postgres-rds:dev";
  }

  @Override
  protected JsonNode getConfig() {
   return jsonNode;
  }

  @Override
  protected JsonNode getFailCheckConfig() {
    return Jsons.jsonNode(ImmutableMap.builder()
        .put(JdbcUtils.HOST_KEY, jsonNode.get(JdbcUtils.HOST_KEY).asText())
        .put(JdbcUtils.USERNAME_KEY, jsonNode.get(JdbcUtils.USERNAME_KEY).asText())
        .put(JdbcUtils.SCHEMA_KEY, "public")
        .put(JdbcUtils.PORT_KEY, jsonNode.get(JdbcUtils.PORT_KEY).asInt())
        .put(JdbcUtils.DATABASE_KEY, jsonNode.get(JdbcUtils.DATABASE_KEY).asText())
        .put(JdbcUtils.SSL_KEY, false)
        .build());
  }

  @Override
  protected List<JsonNode> retrieveRecords(final TestDestinationEnv env,
                                           final String streamName,
                                           final String namespace,
                                           final JsonNode streamSchema)
      throws Exception {
    return retrieveRecordsFromTable(namingResolver.getRawTableName(streamName), namespace)
        .stream()
        .map(r -> r.get(JavaBaseConstants.COLUMN_NAME_DATA))
        .collect(Collectors.toList());
  }

  @Override
  protected boolean implementsNamespaces() {
    return true;
  }

  @Override
  protected TestDataComparator getTestDataComparator() {
    return new PostgresTestDataComparator();
  }

  @Override
  protected boolean supportBasicDataTypeTest() {
    return true;
  }

  @Override
  protected boolean supportArrayDataTypeTest() {
    return true;
  }

  @Override
  protected boolean supportObjectDataTypeTest() {
    return true;
  }

  @Override
  protected boolean supportIncrementalSchemaChanges() { return true; }

  @Override
  protected List<JsonNode> retrieveNormalizedRecords(final TestDestinationEnv env, final String streamName, final String namespace)
      throws Exception {
    final String tableName = namingResolver.getIdentifier(streamName);
    return retrieveRecordsFromTable(tableName, namespace);
  }

  private List<JsonNode> retrieveRecordsFromTable(final String tableName, final String schemaName) throws SQLException {
    dropTables.add(schemaName + "." + tableName);
    try (final DSLContext dslContext = getDslContext()) {
      return new Database(dslContext)
          .query(ctx -> {
            ctx.execute("set time zone 'UTC';");
            return ctx.fetch(String.format("SELECT * FROM %s.%s ORDER BY %s ASC;", schemaName, tableName, JavaBaseConstants.COLUMN_NAME_EMITTED_AT))
                .stream()
                .map(this::getJsonFromRecord)
                .collect(Collectors.toList());
          });
    }
  }

  @Override
  protected void setup(final TestDestinationEnv testEnv) {
    jsonNode = Jsons.deserialize(IOs.readFile(Path.of("secrets/config.json")));
  }

  @Override
  protected void tearDown(final TestDestinationEnv testEnv) {
    dropTables.forEach(table -> {
      try (final DSLContext dslContext = getDslContext()) {
        new Database(dslContext)
            .query(ctx -> {
              ctx.execute("DROP TABLE IF EXISTS " + table + ";");
              return null;
            });
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    });

    dropTables.clear();
  }

  private DSLContext getDslContext() {
    return DSLContextFactory.create(
        jsonNode.get(JdbcUtils.USERNAME_KEY).asText(),
        jsonNode.get(PostgresRdsDestination.CREDENTIALS_KEY).get(JdbcUtils.PASSWORD_KEY).asText(),
        DatabaseDriver.POSTGRESQL.getDriverClassName(),
        "jdbc:postgresql://" + jsonNode.get(JdbcUtils.HOST_KEY).asText() + ":" + jsonNode.get(JdbcUtils.PORT_KEY).asInt()
            + "/" + jsonNode.get(JdbcUtils.DATABASE_KEY).asText(),
        SQLDialect.POSTGRES);
  }

}
