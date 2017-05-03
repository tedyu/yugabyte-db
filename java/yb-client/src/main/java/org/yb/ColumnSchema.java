// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//
// The following only applies to changes made to this file as part of YugaByte development.
//
// Portions Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//
package org.yb;

import org.yb.Common.CompressionType;
import org.yb.Common.EncodingType;
import org.yb.annotations.InterfaceAudience;
import org.yb.annotations.InterfaceStability;

/**
 * Represents a YB Table column. Use {@link ColumnSchema.ColumnSchemaBuilder} in order to
 * create columns.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public class ColumnSchema {

  private final Integer id;
  private final String name;
  // TODO Having both type and yqlType is redundant. After YugaWare is updated and Kudu
  // dependencies cleaned up, type can be removed.
  private final Type type;
  private final YQLType yqlType;
  private final boolean key;
  private final boolean hashKey;
  private final boolean nullable;
  private final Object defaultValue;
  private final int desiredBlockSize;
  private final Encoding encoding;
  private final CompressionAlgorithm compressionAlgorithm;
  private final SortOrder sortOrder;

  /**
   * Specifies the encoding of data for a column on disk.
   * Not all encodings are available for all data types.
   * Refer to the YB documentation for more information on each encoding.
   */
  public enum Encoding {
    UNKNOWN(EncodingType.UNKNOWN_ENCODING),
    AUTO_ENCODING(EncodingType.AUTO_ENCODING),
    PLAIN_ENCODING(EncodingType.PLAIN_ENCODING),
    PREFIX_ENCODING(EncodingType.PREFIX_ENCODING),
    GROUP_VARINT(EncodingType.GROUP_VARINT),
    RLE(EncodingType.RLE),
    DICT_ENCODING(EncodingType.DICT_ENCODING),
    BIT_SHUFFLE(EncodingType.BIT_SHUFFLE);

    final EncodingType internalPbType;

    Encoding(EncodingType internalPbType) {
      this.internalPbType = internalPbType;
    }

    @InterfaceAudience.Private
    public EncodingType getInternalPbType() {
      return internalPbType;
    }
  };

  /**
   * Specifies the compression algorithm of data for a column on disk.
   */
  public enum CompressionAlgorithm {
    UNKNOWN(CompressionType.UNKNOWN_COMPRESSION),
    DEFAULT_COMPRESSION(CompressionType.DEFAULT_COMPRESSION),
    NO_COMPRESSION(CompressionType.NO_COMPRESSION),
    SNAPPY(CompressionType.SNAPPY),
    LZ4(CompressionType.LZ4),
    ZLIB(CompressionType.ZLIB);

    final CompressionType internalPbType;

    CompressionAlgorithm(CompressionType internalPbType) {
      this.internalPbType = internalPbType;
    }

    @InterfaceAudience.Private
    public CompressionType getInternalPbType() {
      return internalPbType;
    }
  };

  public enum SortOrder {
    NONE(0),
    ASC(1),
    DESC(2);

    private final int sortOrder;

    SortOrder(int sortOrder) {
      this.sortOrder = sortOrder;
    }

    public int getValue() {
      return sortOrder;
    }

    public static SortOrder findFromValue(int value) {
      for (SortOrder sortOrder : values()) {
        if (sortOrder.getValue() == value) {
          return sortOrder;
        }
      }
      return NONE;
    }
  };

  private ColumnSchema(Integer id, String name, YQLType yqlType, boolean key, boolean hashKey,
                       boolean nullable, Object defaultValue, int desiredBlockSize,
                       Encoding encoding, CompressionAlgorithm compressionAlgorithm,
                       SortOrder sortOrder) {
    this.id = id;
    this.name = name;
    this.type = yqlType.toType();
    this.yqlType = yqlType;
    this.key = key;
    this.hashKey = hashKey;
    this.nullable = nullable;
    this.defaultValue = defaultValue;
    this.desiredBlockSize = desiredBlockSize;
    this.encoding = encoding;
    this.compressionAlgorithm = compressionAlgorithm;
    this.sortOrder = sortOrder;
  }

  public Integer getId() {
    return id;
  }

  /**
   * Get the column's Type
   * @return the type
   */
  @Deprecated
  public Type getType() {
    return type;
  }


  /**
   * Get the column's YQLType
   * @return the type
   */
  public YQLType getYQLType() {
    return yqlType;
  }

  /**
   * Get the column's name
   * @return A string representation of the name
   */
  public String getName() {
    return name;
  }

  /**
   * Answers if the column part of the key
   * @return true if the column is part of the key, else false
   */
  public boolean isKey() {
    return key;
  }

  /**
   * Answers if the column is used in hashing part of the key
   * @return true if the column is part of the hash key, else false
   */
  public boolean isHashKey() {
    return hashKey;
  }

  /**
   * Returns the sort order. Valid only if the key is a range primary key.
   * @return the sort order.
   */
  public SortOrder getSortOrder() {
    return sortOrder;
  }

  /**
   * Answers if the column can be set to null
   * @return true if it can be set to null, else false
   */
  public boolean isNullable() {
    return nullable;
  }

  /**
   * The Java object representation of the default value that's read
   * @return the default read value
   */
  public Object getDefaultValue() {
    return defaultValue;
  }

  /**
   * Gets the desired block size for this column.
   * If no block size has been explicitly specified for this column,
   * returns 0 to indicate that the server-side default will be used.
   *
   * @return the block size, in bytes, or 0 if none has been configured.
   */
  public int getDesiredBlockSize() {
    return desiredBlockSize;
  }

  /**
   * Return the encoding of this column, or null if it is not known.
   */
  public Encoding getEncoding() {
    return encoding;
  }

  /**
   * Return the compression algorithm of this column, or null if it is not known.
   */
  public CompressionAlgorithm getCompressionAlgorithm() {
    return compressionAlgorithm;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    ColumnSchema that = (ColumnSchema) o;

    if (key != that.key) return false;
    if (hashKey != that.hashKey) return false;
    if (!name.equals(that.name)) return false;
    if (!type.equals(that.type)) return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = name.hashCode();
    result = 31 * result + type.hashCode();
    result = 31 * result + (key ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return "Column name: " + name + ", type: " + type.getName();
  }

  /**
   * Builder for ColumnSchema.
   */
  public static class ColumnSchemaBuilder {
    private Integer id = null;
    private final String name;
    private final YQLType yqlType;
    private boolean key = false;
    private boolean hashKey = false;
    private boolean nullable = false;
    private Object defaultValue = null;
    private int blockSize = 0;
    private Encoding encoding = null;
    private CompressionAlgorithm compressionAlgorithm = null;
    private SortOrder sortOrder = SortOrder.NONE;

    /**
     * Constructor for the required parameters.
     * @param name column's name
     * @param type column's type
     */
    @Deprecated
    public ColumnSchemaBuilder(String name, Type type) {
      this.name = name;
      this.yqlType = YQLType.fromType(type);
    }

    /**
     * Constructor for the required parameters.
     * @param name column's name
     * @param yqlType column's YQL Type
     */
    public ColumnSchemaBuilder(String name, YQLType yqlType) {
      this.name = name;
      this.yqlType = yqlType;
    }

    /**
     * Sets if the column id is known. null by default.
     * @param id an int that is the column's id
     * @return this instance
     */
    public ColumnSchemaBuilder id(Integer id) {
      this.id = id;
      return this;
    }

    /**
     * Sets if the column is part of the row key. False by default.
     * @param key a boolean that indicates if the column is part of the key
     * @return this instance
     */
    public ColumnSchemaBuilder key(boolean key) {
      return rangeKey(key, SortOrder.NONE);
    }

    /**
     * Sets if the column is part of the range row key, along with a sort order.
     * @param key a boolean that indicates if the column is part of the key. False by default.
     * @param sortOrder an enum that indicates if the column is sorted in ascending, descending, or
     *                  no order
     * @return this instance.
     */
    public ColumnSchemaBuilder rangeKey(boolean key, SortOrder sortOrder) {
      this.key = key;
      this.sortOrder = sortOrder;
      return this;
    }

    /**
     * Sets if the column is to be used in the hash part of the row key. False by default.
     * @param hashKey a boolean that indicates if the column is part of the hash key
     * @return this instance
     */
    public ColumnSchemaBuilder hashKey(boolean hashKey) {
      this.hashKey = hashKey;
      if (hashKey) {
        this.key = true;
      }
      return this;
    }

    /**
     * Marks the column as allowing null values. False by default.
     * @param nullable a boolean that indicates if the column allows null values
     * @return this instance
     */
    public ColumnSchemaBuilder nullable(boolean nullable) {
      this.nullable = nullable;
      return this;
    }

    /**
     * Sets the default value that will be read from the column. Null by default.
     * @param defaultValue a Java object representation of the default value that's read
     * @return this instance
     */
    public ColumnSchemaBuilder defaultValue(Object defaultValue) {
      this.defaultValue = defaultValue;
      return this;
    }

    /**
     * Set the desired block size for this column.
     *
     * This is the number of bytes of user data packed per block on disk, and
     * represents the unit of IO when reading this column. Larger values
     * may improve scan performance, particularly on spinning media. Smaller
     * values may improve random access performance, particularly for workloads
     * that have high cache hit rates or operate on fast storage such as SSD.
     *
     * Note that the block size specified here corresponds to uncompressed data.
     * The actual size of the unit read from disk may be smaller if
     * compression is enabled.
     *
     * It's recommended that this not be set any lower than 4096 (4KB) or higher
     * than 1048576 (1MB).
     * @param blockSize the desired block size, in bytes
     * @return this instance
     * <!-- TODO(KUDU-1107): move the above info to docs -->
     */
    public ColumnSchemaBuilder desiredBlockSize(int blockSize) {
      this.blockSize = blockSize;
      return this;
    }

    /**
     * Set the block encoding for this column. See the documentation for the list
     * of valid options.
     */
    public ColumnSchemaBuilder encoding(Encoding encoding) {
      this.encoding = encoding;
      return this;
    }

    /**
     * Set the compression algorithm for this column. See the documentation for the list
     * of valid options.
     */
    public ColumnSchemaBuilder compressionAlgorithm(CompressionAlgorithm compressionAlgorithm) {
      this.compressionAlgorithm = compressionAlgorithm;
      return this;
    }

    /**
     * Builds a {@link ColumnSchema} using the passed parameters.
     * @return a new {@link ColumnSchema}
     */
    public ColumnSchema build() {
      return new ColumnSchema(id, name, yqlType,
                              key, hashKey, nullable, defaultValue,
                              blockSize, encoding, compressionAlgorithm, sortOrder);
    }
  }
}
