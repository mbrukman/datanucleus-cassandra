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
import java.nio.ByteBuffer;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

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
import org.datanucleus.store.schema.table.Column;
import org.datanucleus.store.schema.table.Table;
import org.datanucleus.store.types.TypeManager;
import org.datanucleus.store.types.converters.TypeConverter;
import org.datanucleus.util.ClassUtils;
import org.datanucleus.util.NucleusLogger;
import org.datanucleus.util.StringUtils;

import com.datastax.driver.core.Row;

/**
 * Utility methods for handling Cassandra datastores.
 */
public class CassandraUtils
{
    static Map<String, String> cassandraTypeByJavaType = new HashMap<String, String>();

    static Map<String, Class> datastoreTypeByCassandraType = new HashMap<String, Class>();

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
        cassandraTypeByJavaType.put(TimeZone.class.getName(), "varchar");
        cassandraTypeByJavaType.put(Locale.class.getName(), "varchar");

        datastoreTypeByCassandraType.put("timestamp", Date.class);
        datastoreTypeByCassandraType.put("boolean", Boolean.class);
        datastoreTypeByCassandraType.put("int", Integer.class);
        datastoreTypeByCassandraType.put("double", Double.class);
        datastoreTypeByCassandraType.put("float", Float.class);
        datastoreTypeByCassandraType.put("bigint", Long.class);
        datastoreTypeByCassandraType.put("varchar", String.class);
        datastoreTypeByCassandraType.put("blob", ByteBuffer.class);
    }

    public static class CassandraTypeDetails
    {
        String typeName;
        TypeConverter typeConverter;
        public CassandraTypeDetails(String typeName, TypeConverter conv)
        {
            this.typeName = typeName;
            this.typeConverter = conv;
        }
    }

    public static Class getJavaTypeForCassandraType(String cassandraType)
    {
        return datastoreTypeByCassandraType.get(cassandraType);
    }

    public static Object getMemberValueForColumnWithConverter(Row row, Column column)
    {
        if (column.getTypeName().equals("varchar"))
        {
            return column.getTypeConverter().toMemberType(row.getString(column.getIdentifier()));
        }
        else if (column.getTypeName().equals("int"))
        {
            return column.getTypeConverter().toMemberType(row.getInt(column.getIdentifier()));
        }
        else if (column.getTypeName().equals("boolean"))
        {
            return column.getTypeConverter().toMemberType(row.getBool(column.getIdentifier()));
        }
        else if (column.getTypeName().equals("double"))
        {
            return column.getTypeConverter().toMemberType(row.getDouble(column.getIdentifier()));
        }
        else if (column.getTypeName().equals("float"))
        {
            return column.getTypeConverter().toMemberType(row.getFloat(column.getIdentifier()));
        }
        else if (column.getTypeName().equals("bigint"))
        {
            return column.getTypeConverter().toMemberType(row.getLong(column.getIdentifier()));
        }
        else if (column.getTypeName().equals("timestamp"))
        {
            return column.getTypeConverter().toMemberType(row.getDate(column.getIdentifier()));
        }
        else if (column.getTypeName().equals("blob"))
        {
            return column.getTypeConverter().toMemberType(row.getBytes(column.getIdentifier()));
        }
        return null;
    }

    public static Object getJavaValueForDatastoreValue(Object datastoreValue, String cassandraType, Class javaType, ExecutionContext ec)
    {
        if (datastoreValue == null)
        {
            return null;
        }

        if (cassandraType.equals("blob") && datastoreValue instanceof ByteBuffer)
        {
            // Serialised field
            TypeConverter<Serializable, ByteBuffer> serialConv = ec.getTypeManager().getTypeConverterForType(Serializable.class, ByteBuffer.class);
            return serialConv.toMemberType((ByteBuffer) datastoreValue);
        }
        else if (javaType.isEnum())
        {
            if (cassandraType.equals("int"))
            {
                return javaType.getEnumConstants()[(Integer) datastoreValue];
            }
            else
            {
                return Enum.valueOf(javaType, (String)datastoreValue);
            }
        }
        else if (java.sql.Date.class.isAssignableFrom(javaType))
        {
            if (cassandraType.equals("varchar"))
            {
                TypeConverter stringConverter = ec.getTypeManager().getTypeConverterForType(javaType, String.class);
                if (stringConverter != null)
                {
                    return stringConverter.toMemberType((String)datastoreValue);
                }
            }
            // TODO There is a TypeConverter for this
            return new java.sql.Date(((Date)datastoreValue).getTime());
        }
        else if (java.sql.Time.class.isAssignableFrom(javaType))
        {
            if (cassandraType.equals("varchar"))
            {
                TypeConverter stringConverter = ec.getTypeManager().getTypeConverterForType(javaType, String.class);
                if (stringConverter != null)
                {
                    return stringConverter.toMemberType((String)datastoreValue);
                }
            }
            // TODO There is a TypeConverter for this
            return new java.sql.Time(((Date)datastoreValue).getTime());
        }
        else if (java.sql.Timestamp.class.isAssignableFrom(javaType))
        {
            if (cassandraType.equals("varchar"))
            {
                TypeConverter stringConverter = ec.getTypeManager().getTypeConverterForType(javaType, String.class);
                if (stringConverter != null)
                {
                    return stringConverter.toMemberType((String)datastoreValue);
                }
            }
            // TODO There is a TypeConverter for this
            return new java.sql.Timestamp(((Date)datastoreValue).getTime());
        }
        else if (Calendar.class.isAssignableFrom(javaType))
        {
            if (cassandraType.equals("varchar"))
            {
                TypeConverter stringConverter = ec.getTypeManager().getTypeConverterForType(javaType, String.class);
                if (stringConverter != null)
                {
                    return stringConverter.toMemberType((String)datastoreValue);
                }
            }
            // TODO There is a TypeConverter for this
            Calendar cal = Calendar.getInstance();
            cal.setTime((Date)datastoreValue);
            return cal;
        }
        else if (Date.class.isAssignableFrom(javaType))
        {
            if (cassandraType.equals("varchar"))
            {
                TypeConverter stringConverter = ec.getTypeManager().getTypeConverterForType(javaType, String.class);
                if (stringConverter != null)
                {
                    return stringConverter.toMemberType((String)datastoreValue);
                }
            }
            return (Date)datastoreValue;
        }
        else if (datastoreValue instanceof String)
        {
            TypeConverter converter = ec.getTypeManager().getTypeConverterForType(javaType, String.class);
            if (converter != null)
            {
                return converter.toMemberType((String)datastoreValue);
            }
        }
        else if (datastoreValue instanceof Long)
        {
            TypeConverter converter = ec.getTypeManager().getTypeConverterForType(javaType, Long.class);
            if (converter != null)
            {
                return converter.toMemberType((Long)datastoreValue);
            }
        }
        else if (datastoreValue instanceof Integer)
        {
            TypeConverter converter = ec.getTypeManager().getTypeConverterForType(javaType, Integer.class);
            if (converter != null)
            {
                return converter.toMemberType((Integer)datastoreValue);
            }
        }

        return datastoreValue;
    }

    /**
     * Method to return the Cassandra column type that the specified member will be stored as.
     * @param mmd Metadata for the member
     * @param typeMgr Type manager
     * @return The cassandra column type details
     */
    public static CassandraTypeDetails getCassandraColumnTypeForMember(AbstractMemberMetaData mmd, TypeManager typeMgr, ClassLoaderResolver clr)
    {
        String type = null;
        TypeConverter typeConv = null;

        Class memberType = mmd.getType();
        String typeConvName = mmd.getTypeConverterName();
        if (typeConvName != null)
        {
            // User has specified the TypeConverter
            typeConv = typeMgr.getTypeConverterForName(typeConvName);
            Class datastoreType = TypeManager.getDatastoreTypeForTypeConverter(typeConv, mmd.getType());
            type = cassandraTypeByJavaType.get(datastoreType.getName());
            return new CassandraTypeDetails(type, typeConv);
        }
        else
        {
            typeConv = typeMgr.getAutoApplyTypeConverterForType(mmd.getType());
            if (typeConv != null)
            {
                // No user-defined converter, but autoApply defined for this member type, so use that
                Class datastoreType = TypeManager.getDatastoreTypeForTypeConverter(typeConv, mmd.getType());
                type = cassandraTypeByJavaType.get(datastoreType.getName());
                return new CassandraTypeDetails(type, typeConv);
            }
        }

        RelationType relType = mmd.getRelationType(clr);
        if (relType == RelationType.NONE)
        {
            if (mmd.isSerialized() && Serializable.class.isAssignableFrom(memberType))
            {
                type = "blob";
                typeConv = typeMgr.getTypeConverterForType(Serializable.class, ByteBuffer.class);
            }
            else if (mmd.hasCollection())
            {
                // Collection<NonPC>
                Class elementType = clr.classForName(mmd.getCollection().getElementType());
                String cqlElementType = mmd.getCollection().isSerializedElement() ? "blob" : getCassandraTypeForNonPersistableType(elementType, false, typeMgr, null);
                if (cqlElementType != null)
                {
                    if (List.class.isAssignableFrom(mmd.getType()))
                    {
                        type = "list<" + cqlElementType + ">";
                    }
                    else if (Set.class.isAssignableFrom(mmd.getType()))
                    {
                        type = "set<" + cqlElementType + ">";
                    }
                    else
                    {
                        if (mmd.getOrderMetaData() != null)
                        {
                            type = "list<" + cqlElementType + ">";
                        }
                        else
                        {
                            type = "set<" + cqlElementType + ">";
                        }
                    }
                }
                else
                {
                    NucleusLogger.DATASTORE_SCHEMA.warn("Unable to generate schema for collection element of type " + elementType.getName() + ". Please report this");
                }
            }
            else if (mmd.hasMap())
            {
                // Map<NonPC, NonPC>
                Class keyType = clr.classForName(mmd.getMap().getKeyType());
                Class valType = clr.classForName(mmd.getMap().getValueType());
                String cqlKeyType = mmd.getMap().isSerializedKey() ? "blob" : getCassandraTypeForNonPersistableType(keyType, false, typeMgr, null);
                String cqlValType = mmd.getMap().isSerializedValue() ? "blob" : getCassandraTypeForNonPersistableType(valType, false, typeMgr, null);
                if (cqlKeyType != null && cqlValType != null)
                {
                    type = "map<" + cqlKeyType + "," + cqlValType + ">";
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
            else if (mmd.hasArray())
            {
                // NonPC[]
                Class elementType = clr.classForName(mmd.getArray().getElementType());
                String cqlElementType = mmd.getArray().isSerializedElement() ? "blob" : getCassandraTypeForNonPersistableType(elementType, false, typeMgr, null);
                if (cqlElementType != null)
                {
                    type = "list<" + cqlElementType + ">";
                }
                else
                {
                    NucleusLogger.DATASTORE_SCHEMA.warn("Unable to generate schema for array element of type " + elementType + ". Please report this");
                }
            }
            else
            {
                ColumnMetaData[] colmds = mmd.getColumnMetaData();
                if (colmds != null && colmds.length == 1 && !StringUtils.isWhitespace(colmds[0].getJdbcType()))
                {
                    // Use jdbc-type where it is specified
                    String jdbcType = colmds[0].getJdbcType();
                    if (jdbcType.equalsIgnoreCase("varchar") || jdbcType.equalsIgnoreCase("longvarchar"))
                    {
                        type = "varchar";
                        typeConv = typeMgr.getTypeConverterForType(mmd.getType(), String.class);
                    }
                    else if (jdbcType.equalsIgnoreCase("bigint"))
                    {
                        type = "bigint";
                        typeConv = typeMgr.getTypeConverterForType(mmd.getType(), Long.class);
                    }
                    else if (jdbcType.equalsIgnoreCase("blob"))
                    {
                        type = "blob";
                        typeConv = typeMgr.getTypeConverterForType(mmd.getType(), ByteBuffer.class);
                    }
                    else if (jdbcType.equalsIgnoreCase("decimal"))
                    {
                        type = "double";
                        typeConv = typeMgr.getTypeConverterForType(mmd.getType(), Double.class);
                    }
                    else if (jdbcType.equalsIgnoreCase("integer"))
                    {
                        type = "int";
                        typeConv = typeMgr.getTypeConverterForType(mmd.getType(), Integer.class);
                    }
                    // TODO Support other jdbc-type values
                }

                if (type == null)
                {
                    // Fallback to defaults based on the member type
                    String cTypeName = cassandraTypeByJavaType.get(memberType.getName());
                    if (cTypeName != null)
                    {
                        type = cTypeName;
                        Class datastoreType = datastoreTypeByCassandraType.get(type);
                        if (datastoreType != null)
                        {
                            typeConv = typeMgr.getTypeConverterForType(mmd.getType(), datastoreType);
                        }
                    }
                    else if (Enum.class.isAssignableFrom(memberType))
                    {
                        // Default to persisting the Enum.ordinal (can use Enum.name if varchar specified above)
                        type = "int";
                    }
                    else
                    {
                        // No direct mapping, so find a converter
                        TypeConverter stringConverter = typeMgr.getTypeConverterForType(memberType, String.class);
                        if (stringConverter != null)
                        {
                            type = "varchar";
                            typeConv = stringConverter;
                        }
                        else
                        {
                            TypeConverter longConverter = typeMgr.getTypeConverterForType(memberType, Long.class);
                            if (longConverter != null)
                            {
                                type = "bigint";
                                typeConv = longConverter;
                            }
                            else
                            {
                                TypeConverter intConverter = typeMgr.getTypeConverterForType(memberType, Integer.class);
                                if (intConverter != null)
                                {
                                    type = "int";
                                    typeConv = intConverter;
                                }
                                else if (Serializable.class.isAssignableFrom(memberType))
                                {
                                    type = "blob";
                                    typeConv = typeMgr.getTypeConverterForType(Serializable.class, ByteBuffer.class);
                                }
                            }
                        }
                    }
                }
            }
        }
        else if (RelationType.isRelationSingleValued(relType))
        {
            // 1-1/N-1 relation stored as String (or serialised)
            type = mmd.isSerialized() ? "blob" : "varchar";
        }
        else if (RelationType.isRelationMultiValued(relType))
        {
            // 1-N/M-N relation stored as set/list<String> or set/list<blob> (or serialised whole field)
            if (mmd.hasCollection())
            {
                if (List.class.isAssignableFrom(mmd.getType()))
                {
                    type = mmd.getCollection().isSerializedElement() ? "list<blob>" : "list<varchar>";
                }
                else if (Set.class.isAssignableFrom(mmd.getType()))
                {
                    type = mmd.getCollection().isSerializedElement() ? "set<blob>" : "set<varchar>";
                }
                else
                {
                    if (relType == RelationType.MANY_TO_MANY_BI)
                    {
                        type = mmd.getCollection().isSerializedElement() ? "set<blob>" : "set<varchar>";
                    }
                    else if (mmd.getOrderMetaData() != null)
                    {
                        type = mmd.getCollection().isSerializedElement() ? "list<blob>" : "list<varchar>";
                    }
                    else
                    {
                        type = mmd.getCollection().isSerializedElement() ? "set<blob>" : "set<varchar>";
                    }
                }
            }
            else if (mmd.hasMap())
            {
                String keyType = null;
                String valType = null;
                if (mmd.getMap().keyIsPersistent())
                {
                    keyType = mmd.getMap().isSerializedKey() ? "blob" : "varchar";
                }
                else
                {
                    keyType = cassandraTypeByJavaType.get(mmd.getMap().getKeyType());
                }
                if (mmd.getMap().valueIsPersistent())
                {
                    valType = mmd.getMap().isSerializedValue() ? "blob" : "varchar";
                }
                else
                {
                    valType = cassandraTypeByJavaType.get(mmd.getMap().getValueType());
                }
                type = "map<" + keyType + "," + valType + ">";
            }
            else if (mmd.hasArray())
            {
                type = mmd.getArray().isSerializedElement() ? "list<blob>" : "list<varchar>";
            }
        }

    	if (type == null)
    	{
    	    NucleusLogger.DATASTORE_SCHEMA.warn("Member " + mmd.getFullFieldName() + " of type=" + mmd.getTypeName() + " could not be directly mapped for Cassandra. Using varchar column");
            // Fallback to varchar - maybe BLOB would be better???
    	    type = "varchar";
    	}

    	return new CassandraTypeDetails(type, typeConv);
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
    	// TODO Support TypeManager autoApply type converter
        if (value == null)
        {
            return value;
        }
        else if (serialised && value instanceof Serializable)
        {
            // Convert to ByteBuffer and use that
            TypeConverter serialConv = typeMgr.getTypeConverterForType(Serializable.class, ByteBuffer.class);
            return serialConv.toDatastoreType(value);
        }
        else if (value.getClass() == Character.class)
        {
            return "" + value;
        }
        else if (value.getClass() == Byte.class)
        {
        	return ((Byte)value).intValue();
        }
        else if (value.getClass() == Short.class)
        {
        	return ((Short)value).intValue();
        }
        else if (ClassUtils.isPrimitiveWrapperType(value.getClass().getName()))
        {
            return value;
        }
        else if (value.getClass() == BigInteger.class)
        {
        	// TODO There is a TypeConverter for this
        	return ((BigInteger)value).longValue();
        }
        else if (value.getClass() == BigDecimal.class)
        {
        	// TODO There is a TypeConverter for this
        	return ((BigDecimal)value).doubleValue();
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
        else if (value instanceof Calendar)
        {
        	if (datastoreType.equals("varchar"))
        	{
                TypeConverter stringConverter = typeMgr.getTypeConverterForType(Calendar.class, String.class);
                if (stringConverter != null)
                {
                    return stringConverter.toDatastoreType(value);
                }
        	}
        	// TODO There is a TypeConverter for this
        	return ((Calendar)value).getTime();
        }
        else if (value instanceof Date)
        {
            if (datastoreType.equals("varchar"))
            {
            	// TODO When we have a lookup map per table of column and TypeConverter, remove this
            	Class valueType = Date.class;
            	if (value instanceof Time)
            	{
            		valueType = Time.class;
            	}
            	else if (value instanceof java.sql.Date)
            	{
            		valueType = java.sql.Date.class;
            	}
            	else if (value instanceof Timestamp)
            	{
            		valueType = Timestamp.class;
            	}
                TypeConverter stringConverter = typeMgr.getTypeConverterForType(valueType, String.class);
                if (stringConverter != null)
                {
                    return stringConverter.toDatastoreType(value);
                }
            }
            // TODO There is a TypeConverter for this
            return value;
        }
        else if (value instanceof TimeZone)
        {
            TypeConverter stringConverter = typeMgr.getTypeConverterForType(TimeZone.class, String.class);
            if (stringConverter != null)
            {
                return stringConverter.toDatastoreType(value);
            }
        }
        else if (value instanceof Locale)
        {
            TypeConverter stringConverter = typeMgr.getTypeConverterForType(Locale.class, String.class);
            if (stringConverter != null)
            {
                return stringConverter.toDatastoreType(value);
            }
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
        Table table = (Table) ec.getStoreManager().getStoreDataForClass(cmd.getFullClassName()).getProperty("tableObject");
        final FetchFieldManager fm = new FetchFieldManager(ec, row, cmd, table);
        Object id = IdentityUtils.getApplicationIdentityForResultSetRow(ec, cmd, null, false, fm);

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
                version = row.getInt(table.getVersionColumn().getIdentifier());
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
        Table table = (Table) ec.getStoreManager().getStoreDataForClass(cmd.getFullClassName()).getProperty("tableObject");
        if (storeMgr.isStrategyDatastoreAttributed(cmd, -1))
        {
            // TODO Support this?
        }
        else
        {
            idKey = row.getLong(table.getDatastoreIdColumn().getIdentifier());
        }

        final FetchFieldManager fm = new FetchFieldManager(ec, row, cmd, table);
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
                version = row.getInt(table.getVersionColumn().getIdentifier());
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