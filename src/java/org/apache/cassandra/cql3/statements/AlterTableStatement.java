/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cassandra.cql3.statements;

import java.util.*;

import org.apache.avro.util.Utf8;

import org.apache.cassandra.cql3.*;
import org.apache.cassandra.config.*;
import org.apache.cassandra.db.marshal.TypeParser;
import org.apache.cassandra.db.migration.avro.CfDef;
import org.apache.cassandra.db.migration.avro.ColumnDef;
import org.apache.cassandra.thrift.InvalidRequestException;
import static org.apache.cassandra.thrift.ThriftValidation.validateColumnFamily;

public class AlterTableStatement extends CFStatement
{
    public static enum Type
    {
        ADD, ALTER, DROP, OPTS
    }

    public final Type oType;
    public final String validator;
    public final ColumnIdentifier columnName;
    private final CFPropDefs cfProps = new CFPropDefs();


    public AlterTableStatement(CFName name, Type type, ColumnIdentifier columnName, String validator, Map<String, String> propertyMap)
    {
        super(name);
        this.oType = type;
        this.columnName = null;
        this.validator = CFPropDefs.comparators.get(validator); // used only for ADD/ALTER commands
        this.cfProps.addAll(propertyMap);
    }

    public CfDef getCfDef() throws ConfigurationException, InvalidRequestException
    {
        CFMetaData meta = validateColumnFamily(keyspace(), columnFamily());
        CfDef avroDef = meta.toAvro();

        CFDefinition cfDef = meta.getCfDef();
        CFDefinition.Name name = this.oType == Type.OPTS ? null : cfDef.get(columnName);
        switch (oType)
        {
            case ADD:
                if (cfDef.isCompact())
                    throw new InvalidRequestException("Cannot add new column to a compact CF");
                if (name != null)
                {
                    switch (name.kind)
                    {
                        case KEY_ALIAS:
                        case COLUMN_ALIAS:
                            throw new InvalidRequestException(String.format("Invalid column name %s because it conflicts with a PRIMARY KEY part", columnName));
                        case COLUMN_METADATA:
                            throw new InvalidRequestException(String.format("Invalid column name %s because it conflicts with an existing column", columnName));
                    }
                }
                avroDef.column_metadata.add(new ColumnDefinition(columnName.key,
                                                               TypeParser.parse(validator),
                                                               null,
                                                               null,
                                                               null).toAvro());
                break;

            case ALTER:
                if (name == null)
                    throw new InvalidRequestException(String.format("Column %s was not found in CF %s", columnName, columnFamily()));

                switch (name.kind)
                {
                    case KEY_ALIAS:
                    case COLUMN_ALIAS:
                        throw new InvalidRequestException(String.format("Cannot alter PRIMARY KEY part %s", columnName));
                    case VALUE_ALIAS:
                        avroDef.default_validation_class = TypeParser.parse(validator).toString();
                        break;
                    case COLUMN_METADATA:
                        ColumnDefinition column = meta.getColumnDefinition(columnName.key);
                        column.setValidator(TypeParser.parse(validator));
                        avroDef.column_metadata.add(column.toAvro());
                        break;
                }
                break;

            case DROP:
                if (cfDef.isCompact())
                    throw new InvalidRequestException("Cannot drop columns from a compact CF");
                if (name == null)
                    throw new InvalidRequestException(String.format("Column %s was not found in CF %s", columnName, columnFamily()));

                switch (name.kind)
                {
                    case KEY_ALIAS:
                    case COLUMN_ALIAS:
                        throw new InvalidRequestException(String.format("Cannot drop PRIMARY KEY part %s", columnName));
                    case COLUMN_METADATA:
                        ColumnDef toDelete = null;
                        for (ColumnDef columnDef : avroDef.column_metadata)
                        {
                            if (columnDef.name.equals(columnName.key))
                                toDelete = columnDef;
                        }
                        assert toDelete != null;

                        ColumnDefinition column = meta.getColumnDefinition(columnName.key);
                        column.setValidator(TypeParser.parse(validator));
                        avroDef.column_metadata.add(column.toAvro());
                        // it is impossible to use ColumnDefinition.deflate() in remove() method
                        // it will throw java.lang.ClassCastException: java.lang.String cannot be cast to org.apache.avro.util.Utf8
                        // some where deep inside of Avro
                        avroDef.column_metadata.remove(toDelete);
                        break;
                }
                break;
            case OPTS:
                if (cfProps == null)
                    throw new InvalidRequestException(String.format("ALTER COLUMNFAMILY WITH invoked, but no parameters found"));

                cfProps.validate();
                applyPropertiesToCfDef(avroDef, cfProps);
                break;
        }

        return avroDef;
    }

    public static void applyPropertiesToCfDef(CfDef cfDef, CFPropDefs cfProps) throws InvalidRequestException
    {
        if (cfProps.hasProperty(CFPropDefs.KW_COMMENT))
        {
            cfDef.comment = new Utf8(cfProps.get(CFPropDefs.KW_COMMENT));
        }

        cfDef.read_repair_chance = cfProps.getDouble(CFPropDefs.KW_READREPAIRCHANCE, cfDef.read_repair_chance);
        cfDef.gc_grace_seconds = cfProps.getInt(CFPropDefs.KW_GCGRACESECONDS, cfDef.gc_grace_seconds);
        cfDef.replicate_on_write = cfProps.getBoolean(CFPropDefs.KW_REPLICATEONWRITE, cfDef.replicate_on_write);
        cfDef.min_compaction_threshold = cfProps.getInt(CFPropDefs.KW_MINCOMPACTIONTHRESHOLD, cfDef.min_compaction_threshold);
        cfDef.max_compaction_threshold = cfProps.getInt(CFPropDefs.KW_MAXCOMPACTIONTHRESHOLD, cfDef.max_compaction_threshold);

        if (!cfProps.compactionStrategyOptions.isEmpty())
        {
            cfDef.compaction_strategy_options = new HashMap<CharSequence, CharSequence>();
            for (Map.Entry<String, String> entry : cfProps.compactionStrategyOptions.entrySet())
            {
                cfDef.compaction_strategy_options.put(new Utf8(entry.getKey()), new Utf8(entry.getValue()));
            }
        }

        if (!cfProps.compressionParameters.isEmpty())
        {
            cfDef.compression_options = new HashMap<CharSequence, CharSequence>();
            for (Map.Entry<String, String> entry : cfProps.compressionParameters.entrySet())
            {
                cfDef.compression_options.put(new Utf8(entry.getKey()), new Utf8(entry.getValue()));
            }
        }
    }

    public String toString()
    {
        return String.format("AlterTableStatement(name=%s, type=%s, column=%s, validator=%s)",
                             cfName,
                             oType,
                             columnName,
                             validator);
    }

}