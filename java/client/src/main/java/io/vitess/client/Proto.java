/*
 * Copyright 2019 The Vitess Authors.

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 *     http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.vitess.client;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.ByteString;

import io.vitess.client.cursor.Cursor;
import io.vitess.client.cursor.CursorWithError;
import io.vitess.client.cursor.SimpleCursor;
import io.vitess.proto.Query;
import io.vitess.proto.Query.BindVariable;
import io.vitess.proto.Query.BoundQuery;
import io.vitess.proto.Query.QueryResult;
import io.vitess.proto.Vtrpc.RPCError;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientException;
import java.sql.SQLRecoverableException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Proto contains methods for working with Vitess protobuf messages.
 */
public class Proto {

  public static final Function<byte[], ByteString> BYTE_ARRAY_TO_BYTE_STRING =
      new Function<byte[], ByteString>() {
        @Override
        public ByteString apply(byte[] from) {
          return ByteString.copyFrom(from);
        }
      };
  private static final int MAX_DECIMAL_UNIT = 30;

  /**
   * Throws the proper SQLException for an error returned by VTGate.
   *
   * <p>
   * Errors returned by Vitess are documented in the
   * <a href="https://github.com/vitessio/vitess/blob/main/proto/vtrpc.proto">vtrpc proto</a>.
   */
  public static void checkError(RPCError error) throws SQLException {
    if (error != null) {
      int errno = getErrno(error.getMessage());
      String sqlState = getSQLState(error.getMessage());

      switch (error.getCode()) {
        case OK:
          break;
        case INVALID_ARGUMENT:
          throw new SQLSyntaxErrorException(error.toString(), sqlState, errno);
        case DEADLINE_EXCEEDED:
          throw new SQLTimeoutException(error.toString(), sqlState, errno);
        case ALREADY_EXISTS:
          throw new SQLIntegrityConstraintViolationException(error.toString(), sqlState, errno);
        case UNAVAILABLE:
          throw new SQLTransientException(error.toString(), sqlState, errno);
        case UNAUTHENTICATED:
          throw new SQLInvalidAuthorizationSpecException(error.toString(), sqlState, errno);
        case ABORTED:
          throw new SQLRecoverableException(error.toString(), sqlState, errno);
        default:
          throw new SQLNonTransientException("Vitess RPC error: " + error.toString(), sqlState,
              errno);
      }
    }
  }

  /**
   * Extracts the MySQL errno from a Vitess error message, if any.
   *
   * <p>
   * If no errno information is found, it returns {@code 0}.
   */
  public static int getErrno(@Nullable String errorMessage) {
    if (errorMessage == null) {
      return 1;
    }
    int tagPos = errorMessage.indexOf("(errno ");
    if (tagPos == -1) {
      return 0;
    }
    int start = tagPos + "(errno ".length();
    if (start >= errorMessage.length()) {
      return 0;
    }
    int end = errorMessage.indexOf(')', start);
    if (end == -1) {
      return 0;
    }
    try {
      return Integer.parseInt(errorMessage.substring(start, end));
    } catch (NumberFormatException exc) {
      return 0;
    }
  }

  /**
   * Extracts the SQLSTATE from a Vitess error message, if any.
   *
   * <p>
   * If no SQLSTATE information is found, it returns {@code ""}.
   */
  public static String getSQLState(@Nullable String errorMessage) {
    if (errorMessage == null) {
      return "";
    }
    int tagPos = errorMessage.indexOf("(sqlstate ");
    if (tagPos == -1) {
      return "";
    }
    int start = tagPos + "(sqlstate ".length();
    if (start >= errorMessage.length()) {
      return "";
    }
    int end = errorMessage.indexOf(')', start);
    if (end == -1) {
      return "";
    }
    return errorMessage.substring(start, end);
  }

  public static BindVariable buildBindVariable(Object value) {
    if (value instanceof BindVariable) {
      return (BindVariable) value;
    }

    BindVariable.Builder builder = BindVariable.newBuilder();

    if (value instanceof Iterable<?>) {
      // List Bind Vars
      Iterator<?> itr = ((Iterable<?>) value).iterator();

      if (!itr.hasNext()) {
        throw new IllegalArgumentException("Can't pass empty list as list bind variable.");
      }

      builder.setType(Query.Type.TUPLE);

      while (itr.hasNext()) {
        TypedValue tval = new TypedValue(itr.next());
        builder.addValues(Query.Value.newBuilder().setType(tval.type).setValue(tval.value).build());
      }
    } else {
      TypedValue tval = new TypedValue(value);
      builder.setType(tval.type);
      builder.setValue(tval.value);
    }

    return builder.build();
  }

  /**
   * bindQuery creates a BoundQuery from query and vars.
   */
  public static BoundQuery bindQuery(String query, Map<String, ?> vars) {
    BoundQuery.Builder boundQueryBuilder = BoundQuery.newBuilder().setSql(query);
    if (vars != null) {
      for (Map.Entry<String, ?> entry : vars.entrySet()) {
        boundQueryBuilder.putBindVariables(entry.getKey(), buildBindVariable(entry.getValue()));
      }
    }
    return boundQueryBuilder.build();
  }

  public static List<CursorWithError> fromQueryResponsesToCursorList(
      List<Query.ResultWithError> resultWithErrorList) {
    ImmutableList.Builder<CursorWithError> builder = new ImmutableList.Builder<CursorWithError>();
    for (Query.ResultWithError resultWithError : resultWithErrorList) {
      builder.add(new CursorWithError(resultWithError));
    }
    return builder.build();
  }

  /**
   * Represents a type and value in the type system used in query.proto.
   */
  protected static class TypedValue {

    Query.Type type;
    ByteString value;

    TypedValue(Object value) {
      if (value == null) {
        this.type = Query.Type.NULL_TYPE;
        this.value = ByteString.EMPTY;
      } else if (value instanceof String) {
        // String
        this.type = Query.Type.VARCHAR;
        this.value = ByteString.copyFromUtf8((String) value);
      } else if (value instanceof byte[]) {
        // Bytes
        this.type = Query.Type.VARBINARY;
        this.value = ByteString.copyFrom((byte[]) value);
      } else if (value instanceof Integer || value instanceof Long || value instanceof Short
          || value instanceof Byte) {
        // Int32, Int64, Short, Byte
        this.type = Query.Type.INT64;
        this.value = ByteString.copyFromUtf8(value.toString());
      } else if (value instanceof BigInteger) {
        this.type = Query.Type.VARCHAR;
        this.value = ByteString.copyFromUtf8(value.toString());
      } else if (value instanceof UnsignedLong) {
        // Uint64
        this.type = Query.Type.UINT64;
        this.value = ByteString.copyFromUtf8(value.toString());
      } else if (value instanceof Float || value instanceof Double) {
        // Float, Double
        this.type = Query.Type.FLOAT64;
        this.value = ByteString.copyFromUtf8(value.toString());
      } else if (value instanceof Boolean) {
        // Boolean
        this.type = Query.Type.INT64;
        this.value = ByteString.copyFromUtf8(((boolean) value) ? "1" : "0");
      } else if (value instanceof BigDecimal) {
        // BigDecimal
        BigDecimal bigDecimal = (BigDecimal) value;
        if (bigDecimal.scale() > MAX_DECIMAL_UNIT) {
          // MySQL only supports scale up to 30.
          bigDecimal = bigDecimal.setScale(MAX_DECIMAL_UNIT, BigDecimal.ROUND_HALF_UP);
        }
        this.type = Query.Type.DECIMAL;
        this.value = ByteString.copyFromUtf8(bigDecimal.toPlainString());
      } else {
        throw new IllegalArgumentException(
            "unsupported type for Query.Value proto: " + value.getClass());
      }
    }
  }
}
