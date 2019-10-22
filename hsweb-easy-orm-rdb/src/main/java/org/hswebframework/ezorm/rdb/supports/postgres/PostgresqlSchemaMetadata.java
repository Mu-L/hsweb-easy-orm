package org.hswebframework.ezorm.rdb.supports.postgres;

import org.hswebframework.ezorm.rdb.metadata.RDBSchemaMetadata;
import org.hswebframework.ezorm.rdb.metadata.dialect.Dialect;

public class PostgresqlSchemaMetadata extends RDBSchemaMetadata {

    public PostgresqlSchemaMetadata(String name) {
        super(name);
        addFeature(new PostgresqlPaginator());
        addFeature(new PostgresqlAlterTableSqlBuilder());

        addFeature(new PostgresqlTableMetadataParser(this));
        addFeature(new PostgresqlIndexMetadataParser(this));
        addFeature(Dialect.POSTGRES);
    }

}