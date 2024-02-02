package org.hswebframework.ezorm.rdb.operator.builder;

import org.apache.commons.collections4.CollectionUtils;
import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBFeatureType;
import org.hswebframework.ezorm.rdb.metadata.RDBSchemaMetadata;
import org.hswebframework.ezorm.rdb.metadata.TableOrViewMetadata;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.BlockSqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.NativeSql;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.PrepareSqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.SqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.query.QuerySqlBuilder;
import org.hswebframework.ezorm.rdb.operator.dml.query.QueryOperatorParameter;
import org.hswebframework.ezorm.rdb.operator.dml.query.SelectColumn;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.hswebframework.ezorm.rdb.metadata.RDBFeatures.*;
import static org.hswebframework.ezorm.rdb.operator.builder.fragments.function.FunctionFragmentBuilder.createFeatureId;

public class DefaultQuerySqlBuilder implements QuerySqlBuilder {

    protected RDBSchemaMetadata schema;

    protected DefaultQuerySqlBuilder(RDBSchemaMetadata schema) {
        this.schema = schema;
    }

    public static DefaultQuerySqlBuilder of(RDBSchemaMetadata schema) {
        return new DefaultQuerySqlBuilder(schema);
    }

    protected Optional<SqlFragments> select(QueryOperatorParameter parameter, TableOrViewMetadata metadata) {
        return metadata.getFeature(select)
                       .map(builder -> builder.createFragments(parameter))
                       .filter(SqlFragments::isNotEmpty);
    }

    protected Optional<SqlFragments> where(QueryOperatorParameter parameter, TableOrViewMetadata metadata) {
        return metadata.getFeature(where)
                       .map(builder -> builder.createFragments(parameter))
                       .filter(SqlFragments::isNotEmpty);
    }

    protected Optional<SqlFragments> join(QueryOperatorParameter parameter, TableOrViewMetadata metadata) {
        return metadata.getFeature(selectJoin)
                       .map(builder -> builder.createFragments(parameter))
                       .filter(SqlFragments::isNotEmpty);
    }

    protected Optional<SqlFragments> orderBy(QueryOperatorParameter parameter, TableOrViewMetadata metadata) {
        return metadata.getFeature(orderBy)
                       .map(builder -> builder.createFragments(parameter))
                       .filter(SqlFragments::isNotEmpty);
    }

    protected SqlFragments from(TableOrViewMetadata metadata, QueryOperatorParameter parameter) {
        return PrepareSqlFragments
            .of()
            .addSql("from")
            .addSql(metadata.getFullName())
            .addSql(parameter.getFromAlias());
    }

    protected Optional<SqlFragments> groupBy(QueryOperatorParameter parameter, TableOrViewMetadata metadata) {
        List<SelectColumn> groupBy = parameter.getGroupBy();
        if (CollectionUtils.isEmpty(groupBy)) {
            return Optional.empty();
        }
        PrepareSqlFragments sql = PrepareSqlFragments.of();

        for (SelectColumn column : groupBy) {
            if (column instanceof NativeSql) {
                sql.addSql(((NativeSql) column).getSql())
                    .addParameter(((NativeSql) column).getParameters());
            } else {
                RDBColumnMetadata columnMetadata = metadata
                    .getColumn(column.getColumn())
                    .orElseThrow(() -> new IllegalArgumentException("unknown column " + column.getColumn()));
                String fullName = columnMetadata.getFullName();

                String function = column.getFunction();
                if (function != null) {
                    sql.addFragments(
                        metadata
                            .findFeature(createFeatureId(function))
                            .map(fragment -> fragment.create(fullName, columnMetadata, column))
                            .filter(SqlFragments::isNotEmpty)
                            .orElseThrow(() -> new UnsupportedOperationException("unsupported function:" + column))
                    );
                } else {
                    sql.addSql(fullName);
                }
            }
            sql.addSql(",");

        }
        sql.removeLastSql();
        return Optional.of(sql);
    }


    protected SqlRequest build(TableOrViewMetadata metadata, QueryOperatorParameter parameter) {
        BlockSqlFragments fragments = BlockSqlFragments.of();

        fragments.addBlock(FragmentBlock.before, "select");

        fragments.addBlock(FragmentBlock.selectColumn, select(parameter, metadata)
            .orElseGet(() -> PrepareSqlFragments.of().addSql("*")));

        fragments.addBlock(FragmentBlock.selectFrom, from(metadata, parameter));


        join(parameter, metadata)
            .ifPresent(join -> fragments.addBlock(FragmentBlock.join, join));
        //where

        where(parameter, metadata)
            .ifPresent(where ->
                           fragments.addBlock(FragmentBlock.where, "where")
                                    .addBlock(FragmentBlock.where, where));

        //group by
        groupBy(parameter, metadata)
            .ifPresent(groupBy ->
                           fragments.addBlock(FragmentBlock.groupBy, "group by")
                                    .addBlock(FragmentBlock.groupBy, groupBy));
        //having

        //order by
        orderBy(parameter, metadata)
            .ifPresent(order -> fragments.addBlock(FragmentBlock.orderBy, "order by")
                                         .addBlock(FragmentBlock.orderBy, order));

        if (Boolean.TRUE.equals(parameter.getForUpdate())) {
            fragments.addBlock(FragmentBlock.after, PrepareSqlFragments.of().addSql("for update"));
        }
        //分页
        else if (parameter.getPageIndex() != null && parameter.getPageSize() != null) {
            return metadata.<Paginator>findFeature(RDBFeatureType.paginator.getId())
                           .map(paginator -> paginator.doPaging(fragments, parameter.getPageIndex(), parameter.getPageSize()))
                           .map(SqlFragments::toRequest)
                           .orElseGet(fragments::toRequest);

        }
        return fragments.toRequest();
    }

    @Override
    public SqlRequest build(QueryOperatorParameter parameter) {
        String from = parameter.getFrom();
        if (from == null || from.isEmpty()) {
            throw new UnsupportedOperationException("from table or view not set");
        }
        TableOrViewMetadata metadata = schema
            .findTableOrView(from)
            .orElseThrow(() -> new UnsupportedOperationException("table or view [" + from + "] doesn't exist "));

        return build(metadata, parameter);
    }

    @Override
    public Mono<SqlRequest> buildAsync(QueryOperatorParameter parameter) {
        String from = parameter.getFrom();
        if (from == null || from.isEmpty()) {
            throw new UnsupportedOperationException("from table or view not set");
        }
        return schema
            .findTableOrViewReactive(from)
            .switchIfEmpty(Mono.error(() -> new UnsupportedOperationException("table or view [" + from + "] doesn't exist ")))
            .map(metadata -> this.build(metadata, parameter));

    }
}
