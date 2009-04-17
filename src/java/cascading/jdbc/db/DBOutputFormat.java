/*
 * Copyright (c) 2009 Concurrent, Inc.
 *
 * This work has been released into the public domain
 * by the copyright holder. This applies worldwide.
 *
 * In case this is not legally possible:
 * The copyright holder grants any entity the right
 * to use this work for any purpose, without any
 * conditions, unless such conditions are required by law.
 */
/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cascading.jdbc.db;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputFormat;
import org.apache.hadoop.mapred.RecordWriter;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.StringUtils;

/**
 * A OutputFormat that sends the reduce output to a SQL table.
 * <p/>
 * {@link DBOutputFormat} accepts &lt;key,value&gt; pairs, where
 * key has a type extending DBWritable. Returned {@link RecordWriter}
 * writes <b>only the key</b> to the database with a batch SQL query.
 */
public class DBOutputFormat<K extends DBWritable, V> implements OutputFormat<K, V>
  {
  private static final Log LOG = LogFactory.getLog( DBOutputFormat.class );

  /** A RecordWriter that writes the reduce output to a SQL table */
  protected class DBRecordWriter implements RecordWriter<K, V>
    {

    private Connection connection;
    private PreparedStatement statement;
    private final int statementsBeforeExecute;

    private long statementsAdded = 0;

    protected DBRecordWriter( Connection connection, PreparedStatement statement, int statementsBeforeExecute )
      {
      this.connection = connection;
      this.statement = statement;
      this.statementsBeforeExecute = statementsBeforeExecute;
      }

    /** {@inheritDoc} */
    public void close( Reporter reporter ) throws IOException
      {

      executeBatch();

      try
        {
        statement.close();
        connection.commit();
        }
      catch( SQLException exception )
        {
        rollBack();

        createThrowMessage( "unable to commit batch", exception );
        }
      finally
        {
        try
          {
          connection.close();
          }
        catch( SQLException exception )
          {
          throw new IOException( "unable to close connection", exception );
          }
        }
      }

    private void executeBatch() throws IOException
      {
      try
        {
        statement.executeBatch();
        }
      catch( SQLException exception )
        {
        rollBack();

        createThrowMessage( "unable to execute batch", exception );
        }
      }

    private void rollBack()
      {
      try
        {
        connection.rollback();
        }
      catch( SQLException sqlException )
        {
        LOG.warn( StringUtils.stringifyException( sqlException ) );
        }
      }

    private void createThrowMessage( String stateMessage, SQLException exception ) throws IOException
      {
      String message = exception.getMessage();

      message = message.substring( 0, Math.min( 75, message.length() ) );

      String errorMessage = String.format( "%s [length: %d][stmts: %d]: %s", stateMessage, exception.getMessage().length(), statementsAdded, message );

      LOG.error( errorMessage, exception.getNextException() );

      throw new IOException( errorMessage, exception.getNextException() );
      }

    /** {@inheritDoc} */
    public void write( K key, V value ) throws IOException
      {
      try
        {
        key.write( statement );
        statement.addBatch();
        }
      catch( SQLException exception )
        {
        throw new IOException( "unable to add batch statement", exception );
        }

      statementsAdded++;

      if( statementsAdded % statementsBeforeExecute == 0 )
        executeBatch();
      }
    }

  /**
   * Constructs the query used as the prepared statement to insert data.
   *
   * @param table      the table to insert into
   * @param fieldNames the fields to insert into. If field names are unknown, supply an
   *                   array of nulls.
   */
  protected String constructQuery( String table, String[] fieldNames )
    {
    if( fieldNames == null )
      throw new IllegalArgumentException( "Field names may not be null" );

    StringBuilder query = new StringBuilder();

    query.append( "INSERT INTO " ).append( table );

    if( fieldNames.length > 0 && fieldNames[ 0 ] != null )
      {
      query.append( " (" );

      for( int i = 0; i < fieldNames.length; i++ )
        {
        query.append( fieldNames[ i ] );

        if( i != fieldNames.length - 1 )
          query.append( "," );
        }

      query.append( ")" );

      }

    query.append( " VALUES (" );

    for( int i = 0; i < fieldNames.length; i++ )
      {
      query.append( "?" );

      if( i != fieldNames.length - 1 )
        query.append( "," );
      }

    query.append( ");" );

    return query.toString();
    }

  /** {@inheritDoc} */
  public void checkOutputSpecs( FileSystem filesystem, JobConf job ) throws IOException
    {
    }


  /** {@inheritDoc} */
  public RecordWriter<K, V> getRecordWriter( FileSystem filesystem, JobConf job, String name, Progressable progress ) throws IOException
    {
    DBConfiguration dbConf = new DBConfiguration( job );

    String tableName = dbConf.getOutputTableName();
    String[] fieldNames = dbConf.getOutputFieldNames();
    int batchStatements = dbConf.getBatchStatementsNum();

    Connection connection = dbConf.getConnection();

    configureConnection( connection );

    String sqlQuery = constructQuery( tableName, fieldNames );

    try
      {
      PreparedStatement preparedStatement = connection.prepareStatement( sqlQuery );

      preparedStatement.setEscapeProcessing( true ); // should be on be default

      return new DBRecordWriter( connection, preparedStatement, batchStatements );
      }
    catch( SQLException exception )
      {
      throw new IOException( "unable to create statement for: " + sqlQuery, exception );
      }
    }

  protected void configureConnection( Connection connection )
    {
    setAutoCommit( connection );
    }

  protected void setAutoCommit( Connection connection )
    {
    try
      {
      connection.setAutoCommit( false );
      }
    catch( Exception exception )
      {
      throw new RuntimeException( "unable to set auto commit", exception );
      }
    }

  /**
   * Initializes the reduce-part of the job with the appropriate output settings
   *
   * @param job                 The job
   * @param dbOutputFormatClass
   * @param tableName           The table to insert data into
   * @param fieldNames          The field names in the table. If unknown, supply the appropriate
   */
  public static void setOutput( JobConf job, Class<? extends DBOutputFormat> dbOutputFormatClass, String tableName, String... fieldNames )
    {
    if( dbOutputFormatClass == null )
      job.setOutputFormat( DBOutputFormat.class );
    else
      job.setOutputFormat( dbOutputFormatClass );

    // writing doesn't always happen in reduce
    job.setReduceSpeculativeExecution( false );
    job.setMapSpeculativeExecution( false );

    DBConfiguration dbConf = new DBConfiguration( job );

    dbConf.setOutputTableName( tableName );
    dbConf.setOutputFieldNames( fieldNames );
    }
  }
