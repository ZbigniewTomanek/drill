/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.jdbc;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.drill.common.exceptions.UserException;
import org.apache.drill.common.exceptions.UserExceptionUtils;
import org.apache.drill.common.expression.PathSegment;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.exec.ExecConstants;
import org.apache.drill.exec.alias.AliasRegistryProvider;
import org.apache.drill.exec.planner.sql.SchemaUtilites;
import org.apache.drill.exec.store.AbstractSchema;
import org.apache.drill.exec.store.SchemaConfig;
import org.apache.drill.exec.store.StoragePlugin;
import org.apache.drill.exec.store.StoragePluginRegistry;
import org.apache.drill.exec.store.StoragePluginRegistry.PluginException;
import org.apache.drill.exec.store.SubSchemaWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Loads schemas from storage plugins later when {@link #getSubSchema(String, boolean)}
 * is called.
 */
public class DynamicRootSchema extends DynamicSchema {
  private static final Logger logger = LoggerFactory.getLogger(DynamicRootSchema.class);

  private static final String ROOT_SCHEMA_NAME = "";

  private final SchemaConfig schemaConfig;
  private final StoragePluginRegistry storages;
  private final AliasRegistryProvider aliasRegistryProvider;

  /** Creates a root schema. */
  DynamicRootSchema(StoragePluginRegistry storages, SchemaConfig schemaConfig, AliasRegistryProvider aliasRegistryProvider) {
    super(null, new RootSchema(storages), ROOT_SCHEMA_NAME);
    this.schemaConfig = schemaConfig;
    this.storages = storages;
    this.aliasRegistryProvider = aliasRegistryProvider;
  }

  @Override
  protected CalciteSchema getImplicitSubSchema(String schemaName,
                                               boolean caseSensitive) {
    String actualSchemaName = aliasRegistryProvider.getStorageAliasesRegistry()
      .getUserAliases(schemaConfig.getUserName()).get(SchemaPath.getSimplePath(schemaName).toExpr());
    return getSchema(actualSchemaName != null
        ? SchemaPath.parseFromString(actualSchemaName).getRootSegmentPath()
        : schemaName,
      caseSensitive);
  }

  private CalciteSchema getSchema(String schemaName, boolean caseSensitive) {
    // Drill registers schemas in lower case, see AbstractSchema constructor
    schemaName = schemaName == null ? null : schemaName.toLowerCase();
    CalciteSchema retSchema = subSchemaMap.map().get(schemaName);
    if (retSchema != null) {
      return retSchema;
    }

    loadSchemaFactory(schemaName, caseSensitive);
    retSchema = subSchemaMap.map().get(schemaName);
    return retSchema;
  }

  private SchemaPath resolveTableAlias(String alias) {
    return Optional.ofNullable(aliasRegistryProvider.getTableAliasesRegistry()
      .getUserAliases(schemaConfig.getUserName()).get(alias))
      .map(SchemaPath::parseFromString)
      .orElse(null);
  }

  private void registerSchemasWithRetry(StoragePlugin plugin) throws Exception {
    long maxAttempts = 1 + schemaConfig
      .getOption(ExecConstants.STORAGE_PLUGIN_RETRY_ATTEMPTS)
      .num_val;
    long retryDelayMs = schemaConfig
      .getOption(ExecConstants.STORAGE_PLUGIN_RETRY_DELAY)
      .num_val;
    int attempt=0;
    Exception lastAttemptEx = null;

    while (attempt++ < maxAttempts) {
      try {
        plugin.registerSchemas(schemaConfig, plus());
        return;
      } catch (Exception ex) {
        lastAttemptEx = ex;
        logger.warn(
          "Attempt {} of {} to register schemas for plugin {} failed.",
          attempt, maxAttempts, plugin,
          ex
        );

        if (attempt < maxAttempts) {
          logger.info(
            "Next attempt to register schemas for plugin {} will be made in {}ms.",
            plugin,
            retryDelayMs
          );
          try {
            Thread.sleep(retryDelayMs);
          } catch (InterruptedException intEx) {
            logger.warn(
              "Interrupted while waiting to make another attempt to register " +
                "schemas for plugin {}.",
              plugin,
              intEx
            );
          }
        }
      }
    }

    throw lastAttemptEx;
  }

  /**
   * Loads schema factory(storage plugin) for specified {@code schemaName}
   * @param schemaName the name of the schema
   * @param caseSensitive whether matching for the schema name is case sensitive
   */
  private void loadSchemaFactory(String schemaName, boolean caseSensitive) {
    StoragePlugin plugin = null;
    try {
      SchemaPlus schemaPlus = this.plus();
      plugin = storages.getPlugin(schemaName);
      if (plugin != null) {
        registerSchemasWithRetry(plugin);
        return;
      }

      // Could not find the plugin of schemaName. The schemaName could be `dfs.tmp`, a 2nd level schema under 'dfs'
      List<String> paths = SchemaUtilites.getSchemaPathAsList(schemaName);
      if (paths.size() == 2) {
        plugin = storages.getPlugin(paths.get(0));
        if (plugin == null) {
          return;
        }

        // Looking for the SchemaPlus for the top level (e.g. 'dfs') of schemaName (e.g. 'dfs.tmp')
        SchemaPlus firstLevelSchema = schemaPlus.getSubSchema(paths.get(0));
        if (firstLevelSchema == null) {
          // register schema for this storage plugin to 'this'.
          registerSchemasWithRetry(plugin);
          firstLevelSchema = schemaPlus.getSubSchema(paths.get(0));
        }
        // Load second level schemas for this storage plugin
        List<SchemaPlus> secondLevelSchemas = new ArrayList<>();
        for (String secondLevelSchemaName : firstLevelSchema.getSubSchemaNames()) {
          secondLevelSchemas.add(firstLevelSchema.getSubSchema(secondLevelSchemaName));
        }

        for (SchemaPlus schema : secondLevelSchemas) {
          org.apache.drill.exec.store.AbstractSchema drillSchema;
          try {
            drillSchema = schema.unwrap(AbstractSchema.class);
          } catch (ClassCastException e) {
            throw new RuntimeException(String.format("Schema '%s' is not expected under root schema", schema.getName()));
          }
          SubSchemaWrapper wrapper = new SubSchemaWrapper(drillSchema);
          schemaPlus.add(wrapper.getName(), wrapper);
        }
      }
    } catch (Exception ex) {
      logger.error("Failed to load schema for {}", schemaName, ex);
      // We can't proceed further without a schema, throw a runtime exception.
      UserException.Builder exceptBuilder =
          UserException
              .pluginError(ex)
              .message("Failed to load schema for schema %s", schemaName)
              .addContext("%s: %s", ex.getClass().getName(), ex.getMessage())
              .addContext(UserExceptionUtils.getUserHint(ex)); //Provide hint if it exists

      if (schemaConfig.getOption(ExecConstants.STORAGE_PLUGIN_AUTO_DISABLE).bool_val) {
        String msg = String.format(
          "The plugin %s will now be disabled (see SYSTEM option %s)",
          plugin.getName(),
          ExecConstants.STORAGE_PLUGIN_AUTO_DISABLE
        );
        exceptBuilder.addContext(msg);
        logger.warn(msg);

        try {
          storages.setEnabled(plugin.getName(), false);
        } catch (PluginException disableEx) {
          logger.error("Could not disable {}", plugin.getName(), disableEx);
        }
      }

      throw exceptBuilder.build(logger);
    }
  }

  @Override
  protected TableEntry getImplicitTable(String tableName, boolean caseSensitive) {
    return Optional.ofNullable(getTemporaryTable(tableName, caseSensitive))
      .<TableEntry>map(table -> new TableEntryImpl(this, tableName, table.getTable(), table.sqls))
      .orElse(super.getImplicitTable(tableName, true));
  }

  private TableEntry getTemporaryTable(String tableName, boolean caseSensitive) {
    CalciteSchema currentSchema = this;

    PathSegment.NameSegment pathSegment =
      Optional.ofNullable(resolveTableAlias(SchemaPath.getCompoundPath(tableName).toExpr()))
        .map(SchemaPath::getRootSegment)
        .orElse(null);

    if (pathSegment == null) {
      return null;
    }

    while (!pathSegment.isLastPath()) {
      currentSchema = currentSchema.getImplicitSubSchema(pathSegment.getPath(), caseSensitive);
      pathSegment = pathSegment.getChild().getNameSegment();
    }

    if (currentSchema != null) {
      return currentSchema.getTable(pathSegment.getNameSegment().getPath(), caseSensitive);
    }
    return null;
  }

  public static class RootSchema extends AbstractSchema {

    private final StoragePluginRegistry storages;

    public RootSchema(StoragePluginRegistry storages) {
      super(Collections.emptyList(), ROOT_SCHEMA_NAME);
      this.storages = storages;
    }

    @Override
    public Set<String> getSubSchemaNames() {
      return storages.availablePlugins();
    }

    @Override
    public String getTypeName() {
      return ROOT_SCHEMA_NAME;
    }

    @Override public Expression getExpression(SchemaPlus parentSchema,
                                              String name) {
      return Expressions.call(
          DataContext.ROOT,
          BuiltInMethod.DATA_CONTEXT_GET_ROOT_SCHEMA.method);
    }

    @Override
    public boolean showInInformationSchema() {
      return false;
    }
  }
}
