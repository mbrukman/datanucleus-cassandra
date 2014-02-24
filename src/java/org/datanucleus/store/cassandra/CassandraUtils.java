/**********************************************************************
Copyright (c) 2014 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
    ...
**********************************************************************/
package org.datanucleus.store.cassandra;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ExecutionContext;
import org.datanucleus.FetchPlan;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.identity.IdentityUtils;
import org.datanucleus.identity.OID;
import org.datanucleus.identity.OIDFactory;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.ColumnMetaData;
import org.datanucleus.metadata.IdentityType;
import org.datanucleus.metadata.RelationType;
import org.datanucleus.metadata.VersionMetaData;
import org.datanucleus.state.ObjectProvider;
import org.datanucleus.store.FieldValues;
import org.datanucleus.store.StoreManager;
import org.datanucleus.store.cassandra.fieldmanager.FetchFieldManager;
import org.datanucleus.store.schema.naming.ColumnType;
import org.datanucleus.store.types.TypeManager;
import org.datanucleus.store.types.converters.TypeConverter;
import org.datanucleus.util.ClassUtils;
import org.datanucleus.util.NucleusLogger;

import com.datastax.driver.core.Row;

/**
 * Utility methods for handling Cassandra datastores.
 */
public class CassandraUtils
{
    static Map<String, String> cassandraTypeByJavaType = new HashMap<String, String>();

    static
    {
        cassandraTypeByJavaType.put(boolean.class.getName(), "boolean");
        cassandraTypeByJavaType.put(byte.class.getName(), "int");
        cassandraTypeByJavaType.put(char.class.getName(), "varchar");
        cassandraTypeByJavaType.put(double.class.getName(), "double");
        cassandraTypeByJavaType.put(float.class.getName(), "float");
        cassandraTypeByJavaType.put(int.class.getName(), "int");
        cassandraTypeByJavaType.put(long.class.getName(), "bigint");
        cassandraTypeByJavaType.put(short.class.getName(), "int");
        cassandraTypeByJavaType.put(Boolean.class.getName(), "boolean");
        cassandraTypeByJavaType.put(Byte.class.getName(), "int");
        cassandraTypeByJavaType.put(Character.class.getName(), "varchar");
        cassandraTypeByJavaType.put(Double.class.getName(), "double");
        cassandraTypeByJavaType.put(Float.class.getName(), "float");
        cassandraTypeByJavaType.put(Integer.class.getName(), "int");
        cassandraTypeByJavaType.put(Long.class.getName(), "bigint");
        cassandraTypeByJavaType.put(Short.class.getName(), "int");

        cassandraTypeByJavaType.put(String.class.getName(), "varchar");
        cassandraTypeByJavaType.put(BigDecimal.class.getName(), "double");
        cassandraTypeByJavaType.put(BigInteger.class.getName(), "bigint");
        cassandraTypeByJavaType.put(Date.class.getName(), "timestamp");
        cassandraTypeByJavaType.put(Time.class.getName(), "timestamp");
        cassandraTypeByJavaType.put(java.sql.Date.class.getName(), "timestamp");
        cassandraTypeByJavaType.put(Timestamp.class.getName(), "timestamp");
        cassandraTypeByJavaType.put(Calendar.class.getName(), "timestamp");
    }

    /**
     * Method to return the Cassandra column type that the specified member will be stored as.
     * TODO This makes the assumption that when we have a persistable type we persist a String form of the id (into a varchar).
     * @param mmd Metadata for the member
     * @param typeMgr Type manager
     * @return The cassandra column type
     */
    public static String getCassandraColumnTypeForMember(AbstractMemberMetaData mmd, TypeManager typeMgr, ClassLoaderResolver clr)
    {
        // TODO Make use of jdbc-type in mmd.getColumnMetaData()
        Class type = mmd.getType();
        String cTypeName = cassandraTypeByJavaType.get(type.getName());
        if (cTypeName != null)
        {
            return cTypeName;
        }
        else if (mmd.isSerialized() && Serializable.class.isAssignableFrom(type))
        {
            return "blob";
        }
        else if (Enum.class.isAssignableFrom(type))
        {
            ColumnMetaData[] colmds = mmd.getColumnMetaData();
            if (colmds != null && colmds.length == 1)
            {
                if (colmds[0].getJdbcType().equalsIgnoreCase("varchar"))
                {
                    return "varchar";
                }
            }
            return "int";
        }

        RelationType relType = mmd.getRelationType(clr);
        // TODO Support embedded relations

        if (RelationType.isRelationSingleValued(relType))
        {
            return "varchar";
        }
        else if (RelationType.isRelationMultiValued(relType))
        {
            if (mmd.hasCollection())
            {
                if (List.class.isAssignableFrom(mmd.getType()))
                {
                    return "list<varchar>";
                }
                else if (Set.class.isAssignableFrom(mmd.getType()))
                {
                    return "set<varchar>";
                }
                else
                {
                	return "list<varchar>";
                }
            }
            else if (mmd.hasMap())
            {
                String keyType = null;
                String valType = null;
                if (mmd.getMap().keyIsPersistent())
                {
                    keyType = "varchar";
                }
                else
                {
                    keyType = cassandraTypeByJavaType.get(mmd.getMap().getKeyType());
                }
                if (mmd.getMap().valueIsPersistent())
                {
                    valType = "varchar";
                }
                else
                {
                    valType = cassandraTypeByJavaType.get(mmd.getMap().getValueType());
                }
                return "map<" + keyType + "," + valType + ">";
            }
        }
        else
        {
            if (mmd.hasCollection())
            {
                String elementType = mmd.getCollection().getElementType();
                String cqlElementType = cassandraTypeByJavaType.get(elementType);
                if (cqlElementType != null)
                {
                    if (List.class.isAssignableFrom(mmd.getType()))
                    {
                        return "list<" + cqlElementType + ">";
                    }
                    else if (Set.class.isAssignableFrom(mmd.getType()))
                    {
                        return "set<" + cqlElementType + ">";
                    }
                    else
                    {
                    	return "list<" + cqlElementType + ">";
                    }
                }
                else
                {
                    NucleusLogger.DATASTORE_SCHEMA.warn("Unable to generate schema for collection element of type " + elementType + ". Please report this");
                }
            }
            else if (mmd.hasMap())
            {
                String keyType = mmd.getMap().getKeyType();
                String valType = mmd.getMap().getValueType();
                String cqlKeyType = cassandraTypeByJavaType.get(keyType);
                String cqlValType = cassandraTypeByJavaType.get(valType);
                if (cqlKeyType != null && cqlValType != null)
                {
                    return "map<" + cqlKeyType + "," + cqlValType + ">";
                }
                else
                {
                    if (cqlKeyType == null)
                    {
                        NucleusLogger.DATASTORE_SCHEMA.warn("Unable to generate schema for map key of type " + keyType + ". Please report this");
                    }
                    if (cqlValType == null)
                    {
                        NucleusLogger.DATASTORE_SCHEMA.warn("Unable to generate schema for map value of type " + valType + ". Please report this");
                    }
                }
            }

            // No direct mapping, so find a converter
            TypeConverter stringConverter = typeMgr.getTypeConverterForType(type, String.class);
            if (stringConverter != null)
            {
                return "varchar";
            }
            TypeConverter longConverter = typeMgr.getTypeConverterForType(type, Long.class);
            if (longConverter != null)
            {
                return "bigint";
            }
            TypeConverter intConverter = typeMgr.getTypeConverterForType(type, Integer.class);
            if (intConverter != null)
            {
                return "int";
            }
            if (Serializable.class.isAssignableFrom(type))
            {
                return "blob";
            }
        }

        // Fallback to varchar - maybe BLOB would be better???
        return "varchar";
    }

    /**
     * Convenience method to return the Cassandra type that we would store the provided type as. Note that this does not support container (Collection, Map) types
     * just single value types.
     * @param type The java type
     * @param serialised Whether it should be serialised
     * @param typeMgr The type manager
     * @param jdbcType Any jdbc-type that has been specified to take into account
     * @return The Cassandra type
     */
    public static String getCassandraTypeForNonPersistableType(Class type, boolean serialised, TypeManager typeMgr, String jdbcType)
    {
        String cTypeName = cassandraTypeByJavaType.get(type.getName());
        if (cTypeName != null)
        {
            return cTypeName;
        }
        else if (serialised && Serializable.class.isAssignableFrom(type))
        {
            return "blob";
        }
        else if (Enum.class.isAssignableFrom(type))
        {
            if (jdbcType != null && jdbcType.equalsIgnoreCase("varchar"))
            {
                return "varchar";
            }
            return "int";
        }

        // No direct mapping, so find a converter
        TypeConverter stringConverter = typeMgr.getTypeConverterForType(type, String.class);
        if (stringConverter != null)
        {
            return "varchar";
        }
        TypeConverter longConverter = typeMgr.getTypeConverterForType(type, Long.class);
        if (longConverter != null)
        {
            return "bigint";
        }
        TypeConverter intConverter = typeMgr.getTypeConverterForType(type, Integer.class);
        if (intConverter != null)
        {
            return "int";
        }
        if (Serializable.class.isAssignableFrom(type))
        {
            return "blob";
        }

        // Fallback to varchar
        return "varchar";
    }

    /**
     * Convenience method to convert from a non-persistable value to the value to be stored in Cassandra.
     * @param value Value for the member
     * @param datastoreType Cassandra column type
     * @return The value to be stored
     */
    public static Object getDatastoreValueForNonPersistableValue(Object value, String datastoreType, boolean serialised, TypeManager typeMgr)
    {
        if (value == null)
        {
            return value;
        }
        else if (serialised && value instanceof Serializable)
        {
            // Convert to byte[] and use that
            TypeConverter serialConv = typeMgr.getTypeConverterForType(Serializable.class, byte[].class);
            return serialConv.toDatastoreType(value);
        }
        else if (value.getClass() == Character.class)
        {
            return "" + value;
        }
        else if (ClassUtils.isPrimitiveWrapperType(value.getClass().getName()))
        {
            return value;
        }
        else if (value instanceof Enum)
        {
            // Persist as ordinal unless user specifies jdbc-type of "varchar"
            if (datastoreType.equals("varchar"))
            {
                return ((Enum)value).name();
            }
            return ((Enum)value).ordinal();
        }
        else if (value instanceof Date)
        {
            return value;
        }

        TypeConverter stringConverter = typeMgr.getTypeConverterForType(value.getClass(), String.class);
        if (stringConverter != null)
        {
            return stringConverter.toDatastoreType(value);
        }
        TypeConverter longConverter = typeMgr.getTypeConverterForType(value.getClass(), Long.class);
        if (longConverter != null)
        {
            return longConverter.toDatastoreType(value);
        }

        // TODO Support any other types
        return value;
    }

    /**
     * Method to take a ResultSet Row and convert it into a persistable object.
     * @param row The results row
     * @param cmd Metadata for the class of which this is an instance (or subclass)
     * @param ec ExecutionContext managing it
     * @param fpMembers FetchPlan members to populate
     * @param ignoreCache Whether to ignore the cache when instantiating this
     * @return The persistable object for this row.
     */
    public static Object getPojoForRowForCandidate(Row row, AbstractClassMetaData cmd, ExecutionContext ec, int[] fpMembers, boolean ignoreCache)
    {
        if (cmd.hasDiscriminatorStrategy())
        {
            // Determine the class from the discriminator property
//            String discrimColName = ec.getStoreManager().getNamingFactory().getColumnName(cmd, ColumnType.DISCRIMINATOR_COLUMN);
            // TODO Get the value for this column
        }

        Object pojo = null;
        if (cmd.getIdentityType() == IdentityType.APPLICATION)
        {
            pojo = CassandraUtils.getObjectUsingApplicationIdForRow(row, cmd, ec, ignoreCache, fpMembers);
        }
        else if (cmd.getIdentityType() == IdentityType.DATASTORE)
        {
            pojo = CassandraUtils.getObjectUsingDatastoreIdForRow(row, cmd, ec, ignoreCache, fpMembers);
        }
        else
        {
            throw new NucleusUserException("Attempt to get candidate for class " + cmd.getFullClassName() + " but uses nondurable-identity and this is not supported by this datastore");
        }
        return pojo;
    }

    private static Object getObjectUsingApplicationIdForRow(final Row row, 
            final AbstractClassMetaData cmd, final ExecutionContext ec, boolean ignoreCache, final int[] fpMembers)
    {
        final FetchFieldManager fm = new FetchFieldManager(ec, row, cmd);
        Object id = IdentityUtils.getApplicationIdentityForResultSetRow(ec, cmd, null, false, fm);

        StoreManager storeMgr = ec.getStoreManager();
        Class type = ec.getClassLoaderResolver().classForName(cmd.getFullClassName());
        Object pc = ec.findObject(id, 
            new FieldValues()
            {
                public void fetchFields(ObjectProvider op)
                {
                    op.replaceFields(fpMembers, fm);
                }
                public void fetchNonLoadedFields(ObjectProvider op)
                {
                    op.replaceNonLoadedFields(fpMembers, fm);
                }
                public FetchPlan getFetchPlanForLoading()
                {
                    return null;
                }
            }, type, ignoreCache, false);

        if (cmd.isVersioned())
        {
            // Set the version on the retrieved object
            ObjectProvider op = ec.findObjectProvider(pc);
            Object version = null;
            VersionMetaData vermd = cmd.getVersionMetaDataForClass();
            if (vermd.getFieldName() != null)
            {
                // Get the version from the field value
                AbstractMemberMetaData verMmd = cmd.getMetaDataForMember(vermd.getFieldName());
                version = op.provideField(verMmd.getAbsoluteFieldNumber());
            }
            else
            {
                // Get the surrogate version from the datastore
                version = row.getInt(storeMgr.getNamingFactory().getColumnName(cmd, ColumnType.VERSION_COLUMN));
            }
            op.setVersion(version);
        }

        return pc;
    }

    private static Object getObjectUsingDatastoreIdForRow(final Row row, 
            final AbstractClassMetaData cmd, final ExecutionContext ec, boolean ignoreCache, final int[] fpMembers)
    {
        Object idKey = null;
        StoreManager storeMgr = ec.getStoreManager();
        if (storeMgr.isStrategyDatastoreAttributed(cmd, -1))
        {
            // TODO Support this?
        }
        else
        {
            idKey = row.getLong(storeMgr.getNamingFactory().getColumnName(cmd, ColumnType.DATASTOREID_COLUMN));
        }

        final FetchFieldManager fm = new FetchFieldManager(ec, row, cmd);
        OID oid = OIDFactory.getInstance(ec.getNucleusContext(), cmd.getFullClassName(), idKey);
        Class type = ec.getClassLoaderResolver().classForName(cmd.getFullClassName());
        Object pc = ec.findObject(oid, 
            new FieldValues()
            {
                // ObjectProvider calls the fetchFields method
                public void fetchFields(ObjectProvider op)
                {
                    op.replaceFields(fpMembers, fm);
                }
                public void fetchNonLoadedFields(ObjectProvider op)
                {
                    op.replaceNonLoadedFields(fpMembers, fm);
                }
                public FetchPlan getFetchPlanForLoading()
                {
                    return null;
                }
            }, type, ignoreCache, false);

        if (cmd.isVersioned())
        {
            // Set the version on the retrieved object
            ObjectProvider op = ec.findObjectProvider(pc);
            Object version = null;
            VersionMetaData vermd = cmd.getVersionMetaDataForClass();
            if (vermd.getFieldName() != null)
            {
                // Get the version from the field value
                AbstractMemberMetaData verMmd = cmd.getMetaDataForMember(vermd.getFieldName());
                version = op.provideField(verMmd.getAbsoluteFieldNumber());
            }
            else
            {
                // Get the surrogate version from the datastore
                version = row.getInt(storeMgr.getNamingFactory().getColumnName(cmd, ColumnType.VERSION_COLUMN));
            }
            op.setVersion(version);
        }
        return pc;
    }

    /**
     * Convenience method to log the provided CQL statement, substituting the provided parameters for any "?" in the statement
     * @param stmt The CQL statement
     * @param values Any parameter values
     * @param logger The logger to log to (at DEBUG level).
     */
    public static void logCqlStatement(String stmt, Object[] values, NucleusLogger logger)
    {
        if (values == null || values.length == 0)
        {
            logger.debug(stmt);
            return;
        }

        StringBuilder str = new StringBuilder();
        int paramNo = 0;
        int currentPos = 0;
        boolean moreParams = true;
        while (moreParams)
        {
            int pos = stmt.indexOf('?', currentPos);
            if (pos > 0)
            {
                str.append(stmt.substring(currentPos, pos));
                str.append('<').append("" + values[paramNo]).append('>');
                paramNo++;

                currentPos = pos+1;
            }
            else
            {
                moreParams = false;
            }
        }
        str.append(stmt.substring(currentPos));

        logger.debug(str.toString());
    }
}