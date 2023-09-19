package org.hswebframework.ezorm.rdb.supports.oracle;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.core.CastUtil;
import org.hswebframework.ezorm.core.meta.ObjectMetadata;
import org.hswebframework.ezorm.rdb.executor.SyncSqlExecutor;
import org.hswebframework.ezorm.rdb.executor.reactive.ReactiveSqlExecutor;
import org.hswebframework.ezorm.rdb.executor.wrapper.ColumnWrapperContext;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrapper;
import org.hswebframework.ezorm.rdb.metadata.RDBIndexMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBSchemaMetadata;
import org.hswebframework.ezorm.rdb.metadata.parser.IndexMetadataParser;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.hswebframework.ezorm.rdb.executor.SqlRequests.prepare;
import static org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers.*;

@Slf4j
@AllArgsConstructor(staticName = "of")
public class OracleIndexMetadataParser implements IndexMetadataParser {

    private static final String sql = "select idx.index_name," +
            "idx.table_name," +
            "idx.uniqueness," +
            "col.column_name," +
            "col.column_position," +
            "col.descend from all_ind_columns col " +
            "join all_indexes idx on col.index_name = idx.index_name " +
            "where idx.table_owner = ? and upper(idx.table_name) like ?";

    private static final String virtualColumnSql = "select column_name,data_default from all_tab_cols" +
            " where owner = ? and upper(table_name) like ? and virtual_column='YES'";

    private static final String primaryKeyIndexSql = "select index_name from all_constraints where " +
            "owner = ? and upper(table_name) like ? and constraint_type = 'P'";

    private final RDBSchemaMetadata schema;

    @Override
    public List<RDBIndexMetadata> parseTableIndex(String tableName) {
        String schemaName = schema.getName().toUpperCase();
        String tableUpperName = tableName.toUpperCase();

        return schema
                .<SyncSqlExecutor>findFeature(SyncSqlExecutor.ID)
                .map(sqlExecutor -> sqlExecutor
                        .select(prepare(sql, schemaName, tableUpperName),
                                new OracleIndexWrapper(
                                        sqlExecutor
                                                .select(prepare(virtualColumnSql, schemaName, tableUpperName), lowerCase(mapStream()))
                                                .map(CastUtil::<Map<String, String>>cast)
                                                .collect(Collectors.toMap(map -> map.get("column_name"), map -> map.get("data_default")))
                                        ,
                                        sqlExecutor
                                                .select(prepare(primaryKeyIndexSql, schemaName, tableUpperName),
                                                        stream(column("index_name", String::valueOf)))
                                                .collect(Collectors.toSet())
                                )))
                .orElseGet(() -> {
                    log.warn("unsupported SyncSqlExecutor");
                    return Collections.emptyList();
                });
    }

    @Override
    public Optional<RDBIndexMetadata> parseByName(String name) {
        return Optional.empty();
    }

    @Override
    public List<RDBIndexMetadata> parseAll() {
        return  parseTableIndex("%%");
    }

    @Override
    public Flux<RDBIndexMetadata> parseAllReactive() {
        return parseTableIndexReactive("%%");
    }

    @Override
    public Mono<RDBIndexMetadata> parseByNameReactive(String name) {
        return Mono.empty();
    }

    @Override
    public Flux<RDBIndexMetadata> parseTableIndexReactive(String tableName) {
        ReactiveSqlExecutor sqlExecutor = schema.findFeatureNow(ReactiveSqlExecutor.ID);
        String schemaName = schema.getName().toUpperCase();
        String tableUpperName = tableName.toUpperCase();

        return Mono
                .zip(sqlExecutor
                             .select(prepare(virtualColumnSql, schemaName, tableUpperName), lowerCase(map()))
                             .collectMap(map -> String.valueOf(map.get("column_name")), map -> String.valueOf(map.get("data_default"))),
                     sqlExecutor
                             .select(prepare(primaryKeyIndexSql, schemaName, tableUpperName),
                                     column("index_name", String::valueOf))
                             .collect(Collectors.toSet()),
                     OracleIndexWrapper::new)
                .flatMap(wrapper -> sqlExecutor
                        .select(prepare(sql, schemaName, tableUpperName), wrapper)
                        .then(Mono.fromSupplier(wrapper::getResult)))
                .flatMapIterable(Function.identity());
    }


    class OracleIndexWrapper implements ResultWrapper<Map<String, String>, List<RDBIndexMetadata>> {
        private final Map<Tuple2<String, String>, RDBIndexMetadata> mappingByName = new HashMap<>();

        private final Map<String, String> virtualColumn;
        private final Set<String> indexName;

        public OracleIndexWrapper(Map<String, String> virtualColumn, Set<String> indexName) {
            this.virtualColumn = virtualColumn;
            this.indexName = indexName;
        }

        @Override
        public Map<String, String> newRowInstance() {
            return new HashMap<>();
        }

        @Override
        public void wrapColumn(ColumnWrapperContext<Map<String, String>> context) {
            if (context.getResult() == null) {
                return;
            }
            context.getRowInstance().put(context.getColumnLabel().toLowerCase(), String.valueOf(context.getResult()));
        }

        @Override
        public boolean completedWrapRow(Map<String, String> result) {
            String name = result.get("index_name");
            String tableName = result.get("table_name");

            RDBIndexMetadata metadata = mappingByName.computeIfAbsent(Tuples.of(tableName, name), tp2 -> new RDBIndexMetadata(tp2.getT2()));
            metadata.setTableName(tableName);
            metadata.setUnique("UNIQUE".equals(result.get("uniqueness")));
            metadata.setPrimaryKey(indexName.contains(metadata.getName()));
            RDBIndexMetadata.IndexColumn column = new RDBIndexMetadata.IndexColumn();
            column.setSort("ASC".equalsIgnoreCase(result.get("descend")) ?
                                   RDBIndexMetadata.IndexSort.asc :
                                   RDBIndexMetadata.IndexSort.desc);
            column.setSortIndex(Integer.parseInt(result.get("column_position")));
            String columnName = result.get("column_name");

            column.setColumn(schema
                                     .getDialect()
                                     .clearQuote(virtualColumn.getOrDefault(columnName, columnName))
                                     .toLowerCase());

            metadata.getColumns().add(column);
            return true;
        }

        @Override
        public List<RDBIndexMetadata> getResult() {
            return new ArrayList<>(mappingByName.values());
        }
    }
}
